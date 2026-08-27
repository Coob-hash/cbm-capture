"""
Generates the two n8n workflows for the capture intake.

Written as a generator rather than hand-authored JSON for two reasons: the Code nodes contain
JavaScript that has to be escaped into JSON string literals, which is where hand-editing goes
wrong; and WF2 is a 54 KB file of which only four nodes change, so a script makes the diff
reviewable instead of burying it.

    python build_workflows.py --wf2-source ../../../2026_07_13/GPT_Output/n8n/WF2_EOD_AI_Agent_and_FM_Approval.json

Outputs WF1_Capture_Intake.json and WF2_EOD_AI_Agent_and_FM_Approval.json beside this file.
"""

from __future__ import annotations

import argparse
import json
import sys
import uuid
from pathlib import Path

HERE = Path(__file__).parent


def node(name, type_, type_version, position, parameters, **extra):
    n = {
        "parameters": parameters,
        "type": type_,
        "typeVersion": type_version,
        "position": position,
        "id": str(uuid.uuid5(uuid.NAMESPACE_URL, f"cbm-capture/{name}")),
        "name": name,
    }
    n.update(extra)
    return n


def chain(*pairs):
    """Build an n8n `connections` object from (source, [targets]) pairs."""
    connections = {}
    for source, targets in pairs:
        outputs = []
        for group in targets:
            outputs.append([{"node": t, "type": "main", "index": 0} for t in group])
        connections[source] = {"main": outputs}
    return connections


# ---------------------------------------------------------------------------
# Code node: package validation
# ---------------------------------------------------------------------------
#
# The seven checks. Six are cheap; the seventh is the reason the endpoint exists.

VALIDATE_JS = r"""
const item = $input.first();
const body = item.json.body ?? item.json ?? {};
const binary = item.binary ?? {};

const SCHEMA_VERSION = '1.0.0';

// n8n puts multipart text fields on body and files on binary, keyed by the form field name.
const imageBinary = binary.image ?? binary.data ?? null;

function fail(error, detail, http) {
  return [{
    json: {
      validation: { ok: false, outcome: 'REJECT', http: http || 422, error, detail: detail || null },
      capture_id: (typeof captureId !== 'undefined') ? captureId : null
    },
    binary: item.binary
  }];
}

let captureId = null;

// 1. metadata parses
let meta;
try {
  const raw = body.metadata;
  meta = typeof raw === 'string' ? JSON.parse(raw) : raw;
} catch (e) {
  return fail('MALFORMED_METADATA', e.message, 400);
}
if (!meta || typeof meta !== 'object') {
  return fail('MALFORMED_METADATA', 'metadata part is missing', 400);
}

captureId = meta.capture_id || null;
if (!captureId) return fail('MALFORMED_METADATA', 'capture_id is required', 400);

// 2. the app and this workflow agree on the contract
if (meta.schema_version !== SCHEMA_VERSION) {
  return fail('UNSUPPORTED_SCHEMA_VERSION', 'server speaks ' + SCHEMA_VERSION, 422);
}

// 3. an image actually arrived
if (!imageBinary) return fail('MISSING_IMAGE', 'no image part in the request', 400);

// 4. the bytes are the bytes the app hashed
const declared = (meta.image || {}).sha256;
const computed = item.json.computed_sha256;
if (!declared) return fail('MALFORMED_METADATA', 'image.sha256 is required', 400);
if (computed && String(computed).toLowerCase() !== String(declared).toLowerCase()) {
  return fail('CHECKSUM_MISMATCH', 'declared ' + declared + ', computed ' + computed, 400);
}

const camera = meta.camera || {};
const image = meta.image || {};
const target = (meta.target || {}).pixel || {};

// 5. K, the pixels and the tap describe ONE frame.
//
// This is the whole point of the endpoint. A package that passes everything else and fails
// here would unproject a pixel from one coordinate system using a principal point from
// another - the defect this change exists to remove.
const problems = [];
if (Number(camera.width) !== Number(image.width) || Number(camera.height) !== Number(image.height)) {
  problems.push('K describes ' + camera.width + 'x' + camera.height +
                ' but the image is ' + image.width + 'x' + image.height);
}
const tx = Number(target.x), ty = Number(target.y);
if (!Number.isFinite(tx) || !Number.isFinite(ty) ||
    tx < 0 || ty < 0 || tx >= Number(image.width) || ty >= Number(image.height)) {
  problems.push('target pixel (' + target.x + ', ' + target.y + ') is outside the image');
}
for (const key of ['fx', 'fy', 'cx', 'cy']) {
  if (!Number.isFinite(Number(camera[key])) || Number(camera[key]) <= 0) {
    problems.push('camera.' + key + ' is not a positive number');
  }
}
if (problems.length) return fail('FRAME_MISMATCH', problems.join('; '), 422);

// 6. the building this endpoint serves
const expectedBuilding = $env.CBM_BUILDING_ID || 'ROOM-POC';
if (meta.building_id && meta.building_id !== expectedBuilding) {
  return fail('FRAME_MISMATCH', 'package is for building ' + meta.building_id +
                                ' but this endpoint serves ' + expectedBuilding, 422);
}

// 7. trust. An untrusted package is still a real defect report and is still recorded - it is
// routed for manual placement instead of being unprojected with a K nobody believes. This is
// the fail-closed behaviour that replaces the old silent $env.CAMERA_FX fallback.
const trusted = camera.trusted === true;

return [{
  json: {
    validation: {
      ok: true,
      outcome: trusted ? 'ACCEPT' : 'NEEDS_LOCALIZATION',
      http: trusted ? 200 : 422,
      error: trusted ? null : 'UNTRUSTED_INTRINSICS',
      detail: trusted ? null : 'intrinsics were not trusted; routed for manual localization'
    },
    capture_id: captureId,
    building_id: expectedBuilding,
    reporter_email: meta.reporter_email || null,
    reporter_description: meta.description || null,
    captured_at: meta.captured_at || null,
    image_sha256: declared,
    image_mime_type: image.mime_type || 'image/jpeg',
    request_status: trusted ? 'RECEIVED' : 'NEEDS_LOCALIZATION',
    camera_intrinsics: camera,
    target_pixel: Object.assign({}, target, { centrality: (meta.target || {}).centrality ?? null }),
    capture_pose: meta.pose || null,
    client: meta.client || null
  },
  binary: item.binary
}];
""".strip()


RESPOND_ACCEPTED_JS = r"""
const staged = $input.first().json;
const v = $('Validate Capture Package').first().json;
return [{ json: {
  ok: true,
  capture_id: v.capture_id,
  request_id: staged.request_id || null,
  status: staged.status || v.request_status,
  duplicate: Boolean(staged.duplicate)
} }];
""".strip()


# ---------------------------------------------------------------------------
# WF1
# ---------------------------------------------------------------------------

def build_wf1() -> dict:
    nodes = [
        node("Capture Webhook", "n8n-nodes-base.webhook", 2.1, [-1080, -220], {
            "httpMethod": "POST",
            "path": "cbm/capture",
            "responseMode": "responseNode",
            # Create an n8n "Header Auth" credential with name Authorization and
            # value "Bearer <token>", matching the token provisioned on each handset.
            "authentication": "headerAuth",
            "options": {"binaryPropertyName": "image", "rawBody": False},
        }, webhookId="8f1c3a10-2b44-4f0e-9a55-6a0f2d7c1e01"),

        # A built-in node rather than require('crypto'): the Code node blocks builtin modules
        # unless NODE_FUNCTION_ALLOW_BUILTIN is set, and depending on that would make the
        # workflow fail on a stock n8n.
        node("Hash Uploaded Image", "n8n-nodes-base.crypto", 1, [-860, -220], {
            "action": "hash",
            "type": "SHA256",
            "binaryData": True,
            "binaryPropertyName": "image",
            "dataPropertyName": "computed_sha256",
            "encoding": "hex",
        }),

        node("Validate Capture Package", "n8n-nodes-base.code", 2, [-640, -220],
             {"jsCode": VALIDATE_JS}),

        node("Package Acceptable?", "n8n-nodes-base.if", 2.2, [-420, -220], {
            "conditions": {
                "options": {"caseSensitive": True, "leftValue": "", "typeValidation": "strict"},
                "conditions": [{
                    "id": "e1a1c1f0-0001-4a00-9000-000000000001",
                    "leftValue": "={{ $json.validation.ok }}",
                    "rightValue": True,
                    "operator": {"type": "boolean", "operation": "true", "singleValue": True},
                }],
                "combinator": "and",
            },
            "options": {},
        }),

        # Drive is now storage, not intake. The image is uploaded here so the request row can
        # carry a stable file id, exactly as the Drive-trigger flow used to provide.
        node("Store Image in Drive", "n8n-nodes-base.googleDrive", 3, [-180, -360], {
            "operation": "upload",
            "inputDataFieldName": "image",
            "name": "={{ $json.capture_id }}.jpg",
            "driveId": {"__rl": True, "value": "My Drive", "mode": "list",
                        "cachedResultName": "My Drive"},
            "folderId": {"__rl": True, "value": "={{ $env.INCOMING_DRIVE_FOLDER_ID }}", "mode": "id"},
            "options": {},
        }),

        node("Stage Capture Idempotently", "n8n-nodes-base.postgres", 2.6, [60, -360], {
            "operation": "executeQuery",
            "query": (
                "WITH inserted AS (\n"
                "  INSERT INTO maintenance_requests(\n"
                "    source_system, source_file_id, source_file_name, source_mime_type, source_web_url,\n"
                "    reporter_email, building_id, status,\n"
                "    camera_intrinsics, target_pixel, capture_pose, captured_at,\n"
                "    reporter_description, image_sha256\n"
                "  ) VALUES (\n"
                "    'mobile_app', $1, $2, $3, $4, $5, $6, $7::request_status,\n"
                "    $8::jsonb, $9::jsonb, $10::jsonb, $11::timestamptz, $12, $13\n"
                "  )\n"
                "  ON CONFLICT (source_system, source_file_id) DO NOTHING\n"
                "  RETURNING id, status\n"
                "), logged AS (\n"
                "  INSERT INTO ticket_events(request_id, event_type, actor, payload)\n"
                "  SELECT id, 'CAPTURE_RECEIVED', 'mobile_app',\n"
                "         jsonb_build_object('capture_id', $1,\n"
                "                            'intrinsics_source', ($8::jsonb)->>'source',\n"
                "                            'intrinsics_trusted', ($8::jsonb)->'trusted')\n"
                "  FROM inserted\n"
                ")\n"
                "SELECT i.id AS request_id, i.status::text AS status, false AS duplicate FROM inserted i\n"
                "UNION ALL\n"
                "SELECT r.id, r.status::text, true\n"
                "  FROM maintenance_requests r\n"
                " WHERE r.source_system = 'mobile_app' AND r.source_file_id = $1\n"
                "   AND NOT EXISTS (SELECT 1 FROM inserted);"
            ),
            "options": {
                "queryBatching": "single",
                "queryParameters": (
                    "={{ [ $('Validate Capture Package').first().json.capture_id,"
                    " $('Validate Capture Package').first().json.capture_id + '.jpg',"
                    " $('Validate Capture Package').first().json.image_mime_type,"
                    " ($json.webViewLink || null),"
                    " $('Validate Capture Package').first().json.reporter_email,"
                    " $('Validate Capture Package').first().json.building_id,"
                    " $('Validate Capture Package').first().json.request_status,"
                    " JSON.stringify($('Validate Capture Package').first().json.camera_intrinsics),"
                    " JSON.stringify($('Validate Capture Package').first().json.target_pixel),"
                    " $('Validate Capture Package').first().json.capture_pose"
                    " ? JSON.stringify($('Validate Capture Package').first().json.capture_pose) : null,"
                    " $('Validate Capture Package').first().json.captured_at,"
                    " $('Validate Capture Package').first().json.reporter_description,"
                    " $('Validate Capture Package').first().json.image_sha256 ] }}"
                ),
            },
        }, alwaysOutputData=True),

        node("Intrinsics Trusted?", "n8n-nodes-base.if", 2.2, [280, -360], {
            "conditions": {
                "options": {"caseSensitive": True, "leftValue": "", "typeValidation": "strict"},
                "conditions": [{
                    "id": "e1a1c1f0-0002-4a00-9000-000000000002",
                    "leftValue": "={{ $('Validate Capture Package').first().json.validation.outcome === 'ACCEPT' }}",
                    "rightValue": True,
                    "operator": {"type": "boolean", "operation": "true", "singleValue": True},
                }],
                "combinator": "and",
            },
            "options": {},
        }),

        node("Build Accepted Response", "n8n-nodes-base.code", 2, [520, -460],
             {"jsCode": RESPOND_ACCEPTED_JS}),

        node("Respond Accepted", "n8n-nodes-base.respondToWebhook", 1.4, [740, -460], {
            "respondWith": "json",
            "responseBody": "={{ $json }}",
            "options": {"responseCode": 200},
        }),

        # 422 rather than 200: the app's outbox treats this as a permanent outcome and tells the
        # worker the office will place the report by hand. The request IS recorded.
        node("Respond Needs Localization", "n8n-nodes-base.respondToWebhook", 1.4, [520, -260], {
            "respondWith": "json",
            "responseBody": (
                "={{ { ok:false, error:'UNTRUSTED_INTRINSICS',"
                " detail: $('Validate Capture Package').first().json.validation.detail,"
                " capture_id: $('Validate Capture Package').first().json.capture_id } }}"
            ),
            "options": {"responseCode": 422},
        }),

        node("Record Rejected Capture", "n8n-nodes-base.postgres", 2.6, [-180, -60], {
            "operation": "executeQuery",
            "query": (
                "INSERT INTO maintenance_requests(\n"
                "  source_system, source_file_id, source_file_name, building_id, status, failure_reason\n"
                ") VALUES ('mobile_app', $1, $2, $3, 'REJECTED_INPUT', $4)\n"
                "ON CONFLICT (source_system, source_file_id) DO NOTHING\n"
                "RETURNING id, status;"
            ),
            "options": {
                "queryBatching": "single",
                "queryParameters": (
                    "={{ [ ($json.capture_id || 'unknown-' + $execution.id),"
                    " ($json.capture_id || 'unknown') + '.jpg',"
                    " ($env.CBM_BUILDING_ID || 'ROOM-POC'),"
                    " ($json.validation.error + ': ' + ($json.validation.detail || '')) ] }}"
                ),
            },
        }, alwaysOutputData=True),

        node("Respond Rejected", "n8n-nodes-base.respondToWebhook", 1.4, [60, -60], {
            "respondWith": "json",
            "responseBody": (
                "={{ { ok:false,"
                " error: $('Validate Capture Package').first().json.validation.error,"
                " detail: $('Validate Capture Package').first().json.validation.detail,"
                " capture_id: $('Validate Capture Package').first().json.capture_id } }}"
            ),
            "options": {
                "responseCode": "={{ $('Validate Capture Package').first().json.validation.http }}"
            },
        }),

        # Used by the app's Settings screen to prove the endpoint and token are right before a
        # worker walks into a basement with a misconfigured handset.
        node("Health Webhook", "n8n-nodes-base.webhook", 2.1, [-1080, 160], {
            "httpMethod": "GET",
            "path": "cbm/capture/health",
            "responseMode": "responseNode",
            "authentication": "headerAuth",
            "options": {},
        }, webhookId="8f1c3a10-2b44-4f0e-9a55-6a0f2d7c1e02"),

        node("Respond Health", "n8n-nodes-base.respondToWebhook", 1.4, [-820, 160], {
            "respondWith": "json",
            "responseBody": (
                "={{ { ok:true, building_id: ($env.CBM_BUILDING_ID || 'ROOM-POC'),"
                " schema_version: '1.0.0' } }}"
            ),
            "options": {"responseCode": 200},
        }),
    ]

    # The manual-localization branch is carried over from the release 2026_07_13 WF1 unchanged:
    # an FM still needs a way to place a request the pipeline could not ground.
    nodes += [
        node("Manual Localization Webhook", "n8n-nodes-base.webhook", 2.1, [-1080, 420], {
            "httpMethod": "POST",
            "path": "cbm/manual-localization",
            "responseMode": "responseNode",
            "options": {},
        }, webhookId="d586585b-81a3-4300-96aa-195df25918fa"),

        node("Validate IFC GlobalId", "n8n-nodes-base.httpRequest", 4.2, [-830, 420], {
            "method": "GET",
            "url": "={{ ($env.IFC_SERVICE_URL || 'http://ifc-service:8000') + '/elements/' + encodeURIComponent($json.body.ifc_global_id) }}",
            "options": {"response": {"response": {"fullResponse": True, "neverError": True,
                                                  "responseFormat": "json"}}},
        }),

        node("GlobalId Valid?", "n8n-nodes-base.if", 2.2, [-600, 420], {
            "conditions": {
                "options": {"caseSensitive": True, "leftValue": "", "typeValidation": "strict"},
                "conditions": [{
                    "id": "1cc70629-9065-43ce-88ad-dd09c53639f7",
                    "leftValue": "={{ $json.statusCode }}",
                    "rightValue": 200,
                    "operator": {"type": "number", "operation": "equals"},
                }],
                "combinator": "and",
            },
            "options": {},
        }),

        node("Requeue Manually Localized Request", "n8n-nodes-base.postgres", 2.6, [-360, 320], {
            "operation": "executeQuery",
            "query": ("UPDATE maintenance_requests\n"
                      "SET manual_ifc_global_id=$2, status='RECEIVED', failure_reason=NULL, updated_at=now()\n"
                      "WHERE id=$1::uuid AND status='NEEDS_LOCALIZATION'\n"
                      "RETURNING id,status,manual_ifc_global_id;"),
            "options": {
                "queryBatching": "single",
                "queryParameters": "={{ [$('Manual Localization Webhook').first().json.body.request_id,$('Manual Localization Webhook').first().json.body.ifc_global_id] }}",
            },
        }),

        node("Respond Manual Success", "n8n-nodes-base.respondToWebhook", 1.4, [-120, 320], {
            "respondWith": "json",
            "responseBody": "={{ {ok:true, request:$json} }}",
            "options": {"responseCode": 200},
        }),

        node("Respond Manual Failure", "n8n-nodes-base.respondToWebhook", 1.4, [-360, 520], {
            "respondWith": "json",
            "responseBody": "={{ {ok:false, error:'Invalid IFC GlobalId'} }}",
            "options": {"responseCode": 422},
        }),
    ]

    connections = chain(
        ("Capture Webhook", [["Hash Uploaded Image"]]),
        ("Hash Uploaded Image", [["Validate Capture Package"]]),
        ("Validate Capture Package", [["Package Acceptable?"]]),
        ("Package Acceptable?", [["Store Image in Drive"], ["Record Rejected Capture"]]),
        ("Store Image in Drive", [["Stage Capture Idempotently"]]),
        ("Stage Capture Idempotently", [["Intrinsics Trusted?"]]),
        ("Intrinsics Trusted?", [["Build Accepted Response"], ["Respond Needs Localization"]]),
        ("Build Accepted Response", [["Respond Accepted"]]),
        ("Record Rejected Capture", [["Respond Rejected"]]),
        ("Health Webhook", [["Respond Health"]]),
        ("Manual Localization Webhook", [["Validate IFC GlobalId"]]),
        ("Validate IFC GlobalId", [["GlobalId Valid?"]]),
        ("GlobalId Valid?", [["Requeue Manually Localized Request"], ["Respond Manual Failure"]]),
        ("Requeue Manually Localized Request", [["Respond Manual Success"]]),
    )

    return {
        "name": "CBM WF1 - Capture Intake and Staging",
        "nodes": nodes,
        "connections": connections,
        "pinData": {},
        "active": False,
        "settings": {
            "executionOrder": "v1",
            "timezone": "Europe/Rome",
            "saveManualExecutions": True,
            "callerPolicy": "workflowsFromSameOwner",
        },
        "versionId": str(uuid.uuid5(uuid.NAMESPACE_URL, "cbm-capture/wf1/v1")),
        "meta": {"templateCredsSetupCompleted": False},
        "tags": [],
    }


# ---------------------------------------------------------------------------
# WF2 patch
# ---------------------------------------------------------------------------

PREPARE_JS = r"""
const item = $input.first();
if (!item.binary?.data?.data) throw new Error('Image binary data is missing');
const buffer = Buffer.from(item.binary.data.data, 'base64');
const mime = item.binary.data.mimeType || 'image/jpeg';

// Intrinsics come from the capture package, recorded by WF1. There is deliberately no
// $env.CAMERA_* fallback: a fabricated K produces a confidently wrong GlobalId, which is worse
// than no answer, so a request without trustworthy intrinsics is routed to NEEDS_LOCALIZATION
// by the gate immediately after this node.
const K = item.json.camera_intrinsics || null;
const trusted = Boolean(K && K.trusted === true);

// Conventional CV notation on the wire (cx, cy); the MultiSet form and /elements/resolve-ray
// both call the principal point (px, py). This node is where the two names meet.
const out = {
  ...item.json,
  image_base64: buffer.toString('base64'),
  mime_type: mime,
  intrinsics_source: K ? K.source : null,
  intrinsics_trusted: trusted,
  fx: trusted ? Number(K.fx) : null,
  fy: trusted ? Number(K.fy) : null,
  px: trusted ? Number(K.cx) : null,
  py: trusted ? Number(K.cy) : null,
  width: trusted ? Number(K.width) : null,
  height: trusted ? Number(K.height) : null,
  intrinsics_failure_reason: trusted
    ? null
    : (K ? 'Intrinsics reported as untrusted by the capturing device'
         : 'Request has no camera intrinsics (legacy Drive upload or pre-app capture)')
};

return [{ json: out, binary: item.binary }];
""".strip()


PARSE_EVIDENCE_JS = r"""
const response = $json.body ?? $json;
let text = response.output_text;
if (!text && Array.isArray(response.output)) {
  for (const out of response.output) for (const c of (out.content || [])) {
    if (c.type === 'output_text' && c.text) text = c.text;
  }
}
let evidence;
try { evidence = typeof text === 'string' ? JSON.parse(text) : text; }
catch (e) { evidence = {object_type:'unknown',damage_description:'Vision output could not be parsed',damage_cues:[],safety_signals:[],target_bbox:{x_min:0,y_min:0,x_max:0,y_max:0},visual_confidence:0,manual_review_reason:e.message}; }

const p = $('Prepare Image and Intrinsics').first().json;

// The worker's tap wins over the model's bounding box.
//
// A tap is an observation; a bounding box is a generation, and a hallucinated one silently
// moves the ray onto a different element. The bbox centre remains only as a fallback for
// requests captured before the mobile app existed.
const tap = p.target_pixel || null;
const tapX = tap ? Number(tap.x) : NaN;
const tapY = tap ? Number(tap.y) : NaN;
const tapUsable = Number.isFinite(tapX) && Number.isFinite(tapY) &&
                  tapX >= 0 && tapY >= 0 &&
                  tapX < Number(p.width) && tapY < Number(p.height);

if (tapUsable) {
  evidence.target_pixel = { x: tapX, y: tapY };
  evidence.target_pixel_source = 'USER_TAP';
} else {
  const b = evidence.target_bbox || {};
  evidence.target_pixel = {
    x: (Number(b.x_min) + Number(b.x_max)) / 2,
    y: (Number(b.y_min) + Number(b.y_max)) / 2
  };
  evidence.target_pixel_source = 'VISION_BBOX';
}

return [{json:{...p, visual_evidence:evidence}, binary:$('Prepare Image and Intrinsics').first().binary}];
""".strip()


def patch_wf2(wf2: dict) -> tuple[dict, list[str]]:
    changes = []
    by_name = {n["name"]: n for n in wf2["nodes"]}

    # 1. Carry the intrinsics and the tap out of the database with the claimed request.
    claim = by_name["Claim Requests and Create Batch"]
    q = claim["parameters"]["query"]
    assert "c.manual_ifc_global_id, false AS no_requests" in q, "claim query shape changed"
    q = q.replace(
        "c.reporter_email, c.building_id, c.manual_ifc_global_id, false AS no_requests",
        "c.reporter_email, c.building_id, c.manual_ifc_global_id,\n"
        "       c.camera_intrinsics, c.target_pixel, false AS no_requests",
    )
    q = q.replace(
        "SELECT (SELECT id FROM selected_batch), NULL::uuid, NULL,NULL,NULL,NULL,NULL,NULL,true",
        "SELECT (SELECT id FROM selected_batch), NULL::uuid, NULL,NULL,NULL,NULL,NULL,NULL,\n"
        "       NULL::jsonb,NULL::jsonb,true",
    )
    claim["parameters"]["query"] = q
    changes.append("Claim Requests and Create Batch: select camera_intrinsics and target_pixel")

    # 2. Read K from the request instead of inventing it from the environment.
    by_name["Prepare Image and Intrinsics"]["parameters"]["jsCode"] = PREPARE_JS
    changes.append("Prepare Image and Intrinsics: read camera_intrinsics, drop the $env fallback")

    # 3. Prefer the worker's tap over the model's bounding box.
    by_name["Parse Visual Evidence"]["parameters"]["jsCode"] = PARSE_EVIDENCE_JS
    changes.append("Parse Visual Evidence: use target_pixel from the capture, bbox as fallback")

    # 4. Tell the vision model the real image dimensions.
    #
    # The prompt asked for a bounding box "using the supplied image dimensions" and never
    # supplied them - the live defect in section 3 of the calibration note.
    vision = by_name["Vision Evidence Extraction"]
    body = vision["parameters"]["jsonBody"]
    old_text = "return its bounding box in absolute image pixels using the supplied image dimensions."
    # The prompt is a single-quoted JS string inside an n8n expression, so interpolating means
    # closing the quote, concatenating, and reopening it.
    new_text = ("return its bounding box in absolute image pixels for an image that is "
                "' + $json.width + ' pixels wide and ' + $json.height + ' pixels tall.")
    if old_text not in body:
        raise AssertionError("vision prompt text changed; refusing to patch blindly")
    patched = body.replace(old_text, new_text, 1)
    # str.replace is a silent no-op when it does not match. Asserting the text was *present*
    # proves nothing about whether it was *replaced*; assert the result actually differs.
    if patched == body:
        raise AssertionError("vision prompt replacement did not change anything")
    vision["parameters"]["jsonBody"] = patched
    changes.append("Vision Evidence Extraction: interpolate the real width and height into the prompt")

    # 5. The gate: no trusted intrinsics, no unprojection.
    gate = {
        "parameters": {
            "conditions": {
                "options": {"caseSensitive": True, "leftValue": "", "typeValidation": "strict"},
                "conditions": [{
                    "id": "a7c11f22-0003-4a00-9000-000000000003",
                    "leftValue": "={{ $json.intrinsics_trusted === true || Boolean($json.manual_ifc_global_id) }}",
                    "rightValue": True,
                    "operator": {"type": "boolean", "operation": "true", "singleValue": True},
                }],
                "combinator": "and",
            },
            "options": {},
        },
        "type": "n8n-nodes-base.if",
        "typeVersion": 2.2,
        "position": [by_name["Prepare Image and Intrinsics"]["position"][0] + 110,
                     by_name["Prepare Image and Intrinsics"]["position"][1] + 140],
        "id": str(uuid.uuid5(uuid.NAMESPACE_URL, "cbm-capture/wf2/intrinsics-gate")),
        "name": "Intrinsics Trusted?",
    }
    wf2["nodes"].append(gate)

    # Rewire: Prepare -> Intrinsics Trusted? -> (true) Vision..., (false) Mark Needs Localization
    conns = wf2["connections"]
    prepare_targets = conns["Prepare Image and Intrinsics"]["main"][0]
    conns["Prepare Image and Intrinsics"]["main"][0] = [
        {"node": "Intrinsics Trusted?", "type": "main", "index": 0}
    ]
    conns["Intrinsics Trusted?"] = {
        "main": [
            prepare_targets,
            [{"node": "Mark Needs Localization", "type": "main", "index": 0}],
        ]
    }
    changes.append("Intrinsics Trusted? gate inserted before the vision and MultiSet calls")

    # 6. Belt and braces on the existing localization gate.
    loc = by_name["Localization Trusted?"]
    loc["parameters"]["conditions"]["conditions"][0]["leftValue"] = (
        "={{ ($json.localization_ok && $json.intrinsics_trusted !== false)"
        " || Boolean($json.manual_ifc_global_id) }}"
    )
    changes.append("Localization Trusted?: also require trusted intrinsics")

    wf2["name"] = "CBM WF2 - EOD AI Agent and FM Approval (device intrinsics)"
    return wf2, changes


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--wf2-source", type=Path, required=True)
    args = ap.parse_args()

    wf1 = build_wf1()
    (HERE / "WF1_Capture_Intake.json").write_text(
        json.dumps(wf1, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"WF1_Capture_Intake.json           {len(wf1['nodes'])} nodes")

    wf2 = json.loads(args.wf2_source.read_text(encoding="utf-8"))
    wf2, changes = patch_wf2(wf2)
    (HERE / "WF2_EOD_AI_Agent_and_FM_Approval.json").write_text(
        json.dumps(wf2, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"WF2_EOD_AI_Agent_and_FM_Approval.json  {len(wf2['nodes'])} nodes")
    for c in changes:
        print(f"  - {c}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
