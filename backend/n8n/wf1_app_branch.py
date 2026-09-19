"""Adds the CBM App branch to WF1, next to the Google Drive branch.

    python wf1_app_branch.py <wf1-export.json> <output.json>

Input is an export of the running WF1 (n8n export:workflow). The Drive branch is kept unchanged; the
app branch ends in the same node, 'Capture Input', so every downstream node - seven of which read
$('Capture Input') by name - works unchanged for both sources:

  Drive Trigger - New Snapshot ─────────────────────────────┐
  App Capture Notification (LISTEN cbm_app_capture) ─► Read App Capture ─┤
  Phase B Recovery Tick ─► Sweep App Captures ──────────────┘
                                                            ▼
                                                      Capture Input (shared) ─► Check IFC Registration
                                                            ─► Claim Capture Attempt ─► Record App Intake
                                                            ─► Capture Accepted? ─► App Capture?
                                                                  ├ yes ─► Fetch App Snapshot ─┐
                                                                  └ no  ─► Download Snapshot ──┴► Prepare Image & Metadata

Changed nodes: 'Capture Input' (accepts an app capture), 'Prepare Image & Metadata' (uses the
phone's factory calibration for an app capture instead of re-estimating K from EXIF). The Drive
path through both is byte-for-byte the previous code. Deleting the Drive branch later means
removing 'Drive Trigger - New Snapshot' and the Drive half of 'Capture Input'.
"""

import copy
import json
import sys
import uuid

PG_CREDENTIAL = {"postgres": {"id": "cbmLocalPg20260917", "name": "CBM Postgres - Local Demo"}}
INTERNAL_IMAGE_URL = "http://cbm-app-internal:8081/internal/captures/"
NEW_NODES = ("App Capture Notification", "Read App Capture", "Sweep App Captures", "Record App Intake",
             "App Capture?", "Fetch App Snapshot")

CAPTURE_INPUT_APP = r"""const src=$input.item.json;
// App branch: a capture recorded by the CBM App (cbm_app.captures_for_intake). It carries the same
// fields as a Drive file (id, name, report_id, reporter_email, webViewLink) plus the phone's own
// calibration, so every node after this one works unchanged for both sources.
const app=src.capture&&src.capture.source==='APP'?src.capture:null;
if(app){
 if(!/^app-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(app.id||''))throw new Error('Invalid app capture reference');
 if(!/^[^\s<>"@]+@[^\s<>"@]+\.[^\s<>"@]+$/.test(app.reporter_email||''))throw new Error('App capture without a reporter email');
 return {json:{...app,reporter_email:String(app.reporter_email).toLowerCase()}};
}
// Drive branch (unchanged).
"""

PREPARE_APP_FRAME = r"""let frame;
if (trg.source === 'APP') {
  // App capture: the phone already sends an upright JPEG of at most 1280 px together with the
  // calibration of exactly these pixels (ARCore/ARKit/Camera2, or an EXIF estimate it labels as
  // such). Use it as it is: re-estimating K from EXIF would discard the one measurement the app
  // exists to provide. Trusted only if the phone trusted it AND the decoded frame is the K frame.
  const cam = trg.camera || {};
  const size = imageSize(buf);
  const sameFrame = !!size && size.width === cam.width && size.height === cam.height;
  const factory = ['ARKIT', 'ARCORE', 'ANDROID_CAMERA2'].includes(cam.source);
  frame = {
    width: cam.width, height: cam.height,
    fx: cam.fx, fy: cam.fy, px: cam.cx, py: cam.cy,
    imageB64: buf.toString('base64'),
    intrinsicsSource: cam.source || 'UNKNOWN',
    intrinsicsCalibrated: factory,
    intrinsicsQuality: factory ? 'FACTORY' : 'ESTIMATE',
    sourceSha256: (trg.image && trg.image.sha256) || null,
    intrinsicsTrusted: cam.trusted === true && sameFrame,
    orientationApplied: 1,
    sourceWidth: size ? size.width : cam.width,
    sourceHeight: size ? size.height : cam.height,
    lens: null,
    gateRejections: sameFrame ? [] : ['FRAME_MISMATCH'],
    normalizeError: null,
    // The reporter's tap: an observed target pixel, in the same frame as K.
    targetPixel: trg.target_pixel || null,
    captureSource: 'APP',
  };
} else try {"""


def fail(msg):
    raise SystemExit(f"wf1_app_branch: {msg}")


def patch(wf: dict) -> dict:
    wf = copy.deepcopy(wf)
    nodes = {n["name"]: n for n in wf["nodes"]}
    conns = wf["connections"]
    if any(name in nodes for name in NEW_NODES):
        fail("the app branch is already present")
    for required in ("Drive Trigger - New Snapshot", "Capture Input", "Claim Capture Attempt", "Capture Accepted?",
                     "Download Snapshot", "Prepare Image & Metadata", "Classify Capture Failure", "Phase B Recovery Tick"):
        if required not in nodes:
            fail(f"expected node missing: {required}")

    def main_targets(name, output):
        outs = conns.get(name, {}).get("main", [])
        return [t["node"] for t in (outs[output] if output < len(outs) and outs[output] else [])]

    # The wiring this patch assumes; refuse to guess if WF1 has changed shape.
    if main_targets("Drive Trigger - New Snapshot", 0) != ["Capture Input"]:
        fail("Drive Trigger no longer feeds Capture Input")
    if main_targets("Claim Capture Attempt", 0) != ["Capture Accepted?"]:
        fail("Claim Capture Attempt no longer feeds Capture Accepted?")
    if main_targets("Download Snapshot", 0) != ["Prepare Image & Metadata"] or main_targets("Download Snapshot", 1) != ["Classify Capture Failure"]:
        fail("Download Snapshot outputs changed")

    def pos(name, dx, dy):
        x, y = nodes[name]["position"]
        return [x + dx, y + dy]

    def node(name, type_, version, position, parameters, **extra):
        n = {"parameters": parameters, "id": str(uuid.uuid4()), "name": name, "type": type_,
             "typeVersion": version, "position": position, **extra}
        wf["nodes"].append(n)
        nodes[name] = n
        return n

    def link(src, dst, output=0):
        outs = conns.setdefault(src, {}).setdefault("main", [])
        while len(outs) <= output:
            outs.append([])
        outs[output].append({"node": dst, "type": "main", "index": 0})

    # 1. The app branch: NOTIFY for immediacy, the existing one-minute tick as the safety net.
    node("App Capture Notification", "n8n-nodes-base.postgresTrigger", 1, pos("Drive Trigger - New Snapshot", 0, 240),
         {"triggerMode": "listenTrigger", "channelName": "cbm_app_capture", "options": {}},
         credentials=PG_CREDENTIAL,
         notes="cbm_app.store_capture() sends NOTIFY cbm_app_capture with the capture id when the App API has stored a photo.")
    node("Read App Capture", "n8n-nodes-base.postgres", 2.6, pos("Drive Trigger - New Snapshot", 0, 440),
         {"operation": "executeQuery",
          "query": "SELECT x AS capture FROM cbm_app.captures_for_intake($1::uuid) x;",
          "options": {"queryBatching": "independently",
                      "queryReplacement": "={{ [String($json.payload || '')] }}"}},
         credentials=PG_CREDENTIAL,
         notes="Returns the capture only while it still waits for the intake, so a late or repeated notification does nothing.")
    node("Sweep App Captures", "n8n-nodes-base.postgres", 2.6, pos("Drive Trigger - New Snapshot", 0, 640),
         {"operation": "executeQuery",
          "query": "SELECT x AS capture FROM cbm_app.captures_for_intake() x;",
          "options": {}},
         credentials=PG_CREDENTIAL,
         notes="One-minute safety net: app captures the notification missed (stored over two minutes ago) and paused ones due for a retry. At most one per tick.")
    link("App Capture Notification", "Read App Capture")
    link("Read App Capture", "Capture Input")
    link("Sweep App Captures", "Capture Input")
    link("Phase B Recovery Tick", "Sweep App Captures")

    # 2. Capture Input: the shared node.
    ci = nodes["Capture Input"]["parameters"]
    if not ci.get("jsCode", "").startswith("const src=$input.item.json;\n"):
        fail("Capture Input code changed; review before patching")
    ci["jsCode"] = CAPTURE_INPUT_APP + ci["jsCode"][len("const src=$input.item.json;\n"):]

    # 3. Record the intake outcome on the app photo; returns the claim result unchanged.
    node("Record App Intake", "n8n-nodes-base.postgres", 2.6, pos("Claim Capture Attempt", 96, 208),
         {"operation": "executeQuery",
          "query": "SELECT cbm_app.record_intake($1::jsonb) AS capture;",
          "options": {"queryBatching": "independently",
                      "queryReplacement": "={{ [JSON.stringify({source: $('Capture Input').item.json.source || 'DRIVE', "
                                          "capture_id: $('Capture Input').item.json.capture_id || null, capture: $json.capture})] }}"}},
         credentials=PG_CREDENTIAL,
         notes="App captures: stores the intake's answer on cbm_app.report_photos (SUBMITTED, PAUSED or NOT_PROCESSED). Drive files pass through unchanged.")
    conns["Claim Capture Attempt"]["main"][0] = []
    link("Claim Capture Attempt", "Record App Intake")
    link("Record App Intake", "Capture Accepted?")

    # 4. Where the image comes from: Drive, or the App API's internal image service.
    node("App Capture?", "n8n-nodes-base.if", 2, pos("Capture Accepted?", 128, -208),
         {"conditions": {"options": {"caseSensitive": True, "leftValue": "", "typeValidation": "strict", "version": 1},
                         "combinator": "and",
                         "conditions": [{"id": str(uuid.uuid4()), "leftValue": "={{ $('Capture Input').item.json.source === 'APP' }}",
                                         "rightValue": True,
                                         "operator": {"type": "boolean", "operation": "true", "singleValue": True}}]},
          "options": {}})
    for output, targets in enumerate(conns["Capture Accepted?"]["main"]):
        for t in targets or []:
            if t["node"] == "Download Snapshot":
                t["node"] = "App Capture?"
    node("Fetch App Snapshot", "n8n-nodes-base.httpRequest", 4.2, pos("Download Snapshot", 0, -208),
         {"url": "=" + INTERNAL_IMAGE_URL + "{{ $('Capture Input').item.json.capture_id }}/image",
          "options": {"response": {"response": {"responseFormat": "file", "outputPropertyName": "data"}},
                      "timeout": 30000}},
         onError="continueErrorOutput",
         notes="The App API's image service. It has no published port: reachable only on the Docker network.")
    link("App Capture?", "Fetch App Snapshot", 0)
    link("App Capture?", "Download Snapshot", 1)
    link("Fetch App Snapshot", "Prepare Image & Metadata", 0)
    link("Fetch App Snapshot", "Classify Capture Failure", 1)

    # 5. Prepare Image & Metadata: factory K for app captures; the Drive path is untouched.
    pim = nodes["Prepare Image & Metadata"]["parameters"]
    code = pim["jsCode"]
    old_email = "const reporterEmail = (parts[1] && parts[1].includes('@')) ? parts[1] : 'unknown@reporter';"
    if code.count("let frame;\ntry {") != 1 or code.count(old_email) != 1:
        fail("Prepare Image & Metadata code changed; review before patching")
    code = code.replace("let frame;\ntry {", PREPARE_APP_FRAME, 1)
    code = code.replace(old_email, "const reporterEmail = trg.source === 'APP' ? trg.reporter_email\n"
                                   "  : ((parts[1] && parts[1].includes('@')) ? parts[1] : 'unknown@reporter');", 1)
    pim["jsCode"] = code
    return wf


def main():
    src, dst = sys.argv[1], sys.argv[2]
    data = json.load(open(src, encoding="utf-8"))
    wf = data[0] if isinstance(data, list) else data
    out = patch(wf)
    json.dump([out] if isinstance(data, list) else out, open(dst, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
    print(f"{dst}: {len(wf['nodes'])} -> {len(out['nodes'])} nodes")


if __name__ == "__main__":
    main()
