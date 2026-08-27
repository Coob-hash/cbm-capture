/**
 * Executes the JavaScript inside the n8n Code nodes against fixtures.
 *
 * The validation that decides whether a capture is trustworthy lives in a Code node, which
 * means it is a string inside a JSON file and normally only ever runs inside n8n. Extracting
 * that exact string and running it under Node gives the same kind of check the mobile apps get
 * from their unit tests, without standing up an n8n instance.
 *
 *   node test_code_nodes.mjs
 */

import { readFileSync } from "node:fs";
import { createHash } from "node:crypto";

const WF1 = JSON.parse(readFileSync(new URL("./WF1_Capture_Intake.json", import.meta.url)));
const WF2 = JSON.parse(readFileSync(new URL("./WF2_EOD_AI_Agent_and_FM_Approval.json", import.meta.url)));

function codeOf(workflow, nodeName) {
  const node = workflow.nodes.find((n) => n.name === nodeName);
  if (!node) throw new Error(`no node named ${nodeName}`);
  if (typeof node.parameters.jsCode !== "string") throw new Error(`${nodeName} has no jsCode`);
  return node.parameters.jsCode;
}

/** Run a Code node body with n8n's globals mocked. */
function runNode(code, { input, env = {}, refs = {}, json = null }) {
  const $input = { first: () => input, all: () => [input], last: () => input };
  const $ = (name) => {
    if (!(name in refs)) throw new Error(`unexpected $('${name}')`);
    return { first: () => refs[name], all: () => [refs[name]] };
  };
  const fn = new Function("$input", "$env", "$", "$json", "Buffer", code);
  return fn($input, env, $, json ?? input.json, Buffer);
}

let passed = 0, failed = 0;
function check(label, actual, expected) {
  const ok = JSON.stringify(actual) === JSON.stringify(expected);
  if (ok) { passed++; console.log(`  [PASS] ${label}`); }
  else { failed++; console.log(`  [FAIL] ${label}\n         got      ${JSON.stringify(actual)}\n         expected ${JSON.stringify(expected)}`); }
}

// ---------------------------------------------------------------------------
// Fixtures
// ---------------------------------------------------------------------------

const IMAGE_BYTES = Buffer.from("not-really-a-jpeg-but-hashes-the-same-way");
const IMAGE_SHA = createHash("sha256").update(IMAGE_BYTES).digest("hex");

function metadata(overrides = {}) {
  const base = {
    schema_version: "1.0.0",
    capture_id: "46ccbf7f-2f0a-4a1e-9d3c-0b6b8f5a1c22",
    building_id: "ROOM-POC",
    reporter_email: "worker@example.com",
    description: "Door handle detached.",
    captured_at: "2026-08-27T18:32:14.482Z",
    client: { platform: "IOS", app_version: "1.0.0 (1)", os_version: "18.5", device_model: "iPhone15,2" },
    image: { width: 960, height: 1280, mime_type: "image/jpeg", sha256: IMAGE_SHA, byte_length: IMAGE_BYTES.length, orientation_applied: 1 },
    camera: { source: "ARKIT", trusted: true, fx: 954.63, fy: 955.07, cx: 480.19, cy: 639.65, width: 960, height: 1280, skew: 0 },
    target: { pixel: { x: 512.0, y: 706.5 }, source: "USER_TAP", centrality: 0.104 },
    pose: null,
  };
  return { ...base, ...overrides };
}

function webhookItem(meta, { withImage = true, rawMetadata = null } = {}) {
  return {
    json: {
      body: { metadata: rawMetadata ?? JSON.stringify(meta) },
      computed_sha256: IMAGE_SHA,
    },
    binary: withImage ? { image: { data: IMAGE_BYTES.toString("base64"), mimeType: "image/jpeg" } } : {},
  };
}

const ENV = { CBM_BUILDING_ID: "ROOM-POC" };

// ---------------------------------------------------------------------------
// WF1 - Validate Capture Package
// ---------------------------------------------------------------------------

const validate = codeOf(WF1, "Validate Capture Package");

console.log("\nWF1 - Validate Capture Package");

{
  const out = runNode(validate, { input: webhookItem(metadata()), env: ENV })[0].json;
  check("a well-formed trusted package is accepted", out.validation.outcome, "ACCEPT");
  check("  ... maps cx/cy through unchanged", [out.camera_intrinsics.cx, out.camera_intrinsics.cy], [480.19, 639.65]);
  check("  ... stages as RECEIVED", out.request_status, "RECEIVED");
  check("  ... carries the tap", [out.target_pixel.x, out.target_pixel.y], [512.0, 706.5]);
}

{
  const item = webhookItem(metadata());
  item.json.computed_sha256 = "0".repeat(64);
  const out = runNode(validate, { input: item, env: ENV })[0].json;
  check("a corrupted image is rejected", [out.validation.error, out.validation.http], ["CHECKSUM_MISMATCH", 400]);
}

{
  // The live defect: K for one frame, pixels for another.
  const meta = metadata();
  meta.camera = { ...meta.camera, width: 1920, height: 1440 };
  const out = runNode(validate, { input: webhookItem(meta), env: ENV })[0].json;
  check("K describing a different frame is rejected", out.validation.error, "FRAME_MISMATCH");
}

{
  const meta = metadata();
  meta.target = { ...meta.target, pixel: { x: 5000, y: 10 } };
  const out = runNode(validate, { input: webhookItem(meta), env: ENV })[0].json;
  check("a target outside the frame is rejected", out.validation.error, "FRAME_MISMATCH");
}

{
  const meta = metadata();
  meta.camera = { ...meta.camera, fx: 0 };
  const out = runNode(validate, { input: webhookItem(meta), env: ENV })[0].json;
  check("a non-positive focal length is rejected", out.validation.error, "FRAME_MISMATCH");
}

{
  const meta = metadata();
  meta.camera = { ...meta.camera, trusted: false, source: "EXIF" };
  const out = runNode(validate, { input: webhookItem(meta), env: ENV })[0].json;
  check("untrusted intrinsics are recorded, not discarded", out.validation.ok, true);
  check("  ... routed to NEEDS_LOCALIZATION", out.validation.outcome, "NEEDS_LOCALIZATION");
  check("  ... staged with that status", out.request_status, "NEEDS_LOCALIZATION");
  check("  ... answered 422 so the client stops retrying", out.validation.http, 422);
}

{
  const out = runNode(validate, { input: webhookItem(metadata({ schema_version: "0.9.0" })), env: ENV })[0].json;
  check("an unknown schema version is rejected", out.validation.error, "UNSUPPORTED_SCHEMA_VERSION");
}

{
  const out = runNode(validate, { input: webhookItem(metadata(), { withImage: false }), env: ENV })[0].json;
  check("a missing image part is rejected", out.validation.error, "MISSING_IMAGE");
}

{
  const out = runNode(validate, { input: webhookItem(null, { rawMetadata: "{not json" }), env: ENV })[0].json;
  check("unparseable metadata is rejected", [out.validation.error, out.validation.http], ["MALFORMED_METADATA", 400]);
}

{
  const out = runNode(validate, { input: webhookItem(metadata({ building_id: "OTHER-BUILDING" })), env: ENV })[0].json;
  check("a package for another building is rejected", out.validation.error, "FRAME_MISMATCH");
}

// ---------------------------------------------------------------------------
// WF2 - Prepare Image and Intrinsics
// ---------------------------------------------------------------------------

const prepare = codeOf(WF2, "Prepare Image and Intrinsics");

console.log("\nWF2 - Prepare Image and Intrinsics");

function claimedRow(overrides = {}) {
  return {
    json: {
      request_id: "r-1",
      building_id: "ROOM-POC",
      camera_intrinsics: { source: "ARKIT", trusted: true, fx: 954.63, fy: 955.07, cx: 480.19, cy: 639.65, width: 960, height: 1280 },
      target_pixel: { x: 512, y: 706.5 },
      ...overrides,
    },
    binary: { data: { data: IMAGE_BYTES.toString("base64"), mimeType: "image/jpeg" } },
  };
}

{
  const out = runNode(prepare, { input: claimedRow(), env: {} })[0].json;
  check("cx becomes px and cy becomes py", [out.px, out.py], [480.19, 639.65]);
  check("fx and fy pass through", [out.fx, out.fy], [954.63, 955.07]);
  check("the frame travels with them", [out.width, out.height], [960, 1280]);
  check("provenance is recorded", out.intrinsics_source, "ARKIT");
  check("trusted", out.intrinsics_trusted, true);
}

{
  // A legacy Drive row has no intrinsics. It must degrade, not throw: throwing would fail the
  // whole batch item rather than routing this one request for manual placement.
  const out = runNode(prepare, { input: claimedRow({ camera_intrinsics: null }), env: {} })[0].json;
  check("a legacy row without intrinsics does not throw", out.intrinsics_trusted, false);
  check("  ... and invents no numbers", [out.fx, out.fy, out.px, out.py], [null, null, null, null]);
  check("  ... with a reason for the FM", typeof out.intrinsics_failure_reason, "string");
}

{
  const row = claimedRow();
  row.json.camera_intrinsics = { ...row.json.camera_intrinsics, trusted: false };
  const out = runNode(prepare, { input: row, env: {} })[0].json;
  check("untrusted intrinsics yield no usable K", [out.intrinsics_trusted, out.fx], [false, null]);
}

// ---------------------------------------------------------------------------
// WF2 - Parse Visual Evidence
// ---------------------------------------------------------------------------

const parseEvidence = codeOf(WF2, "Parse Visual Evidence");

console.log("\nWF2 - Parse Visual Evidence");

const VISION_RESPONSE = {
  output_text: JSON.stringify({
    object_type: "door", damage_description: "handle detached", damage_cues: [], safety_signals: [],
    target_bbox: { x_min: 100, y_min: 100, x_max: 300, y_max: 300 },
    visual_confidence: 0.8, manual_review_reason: "",
  }),
};

function preparedRef(overrides = {}) {
  return {
    json: { request_id: "r-1", width: 960, height: 1280, target_pixel: { x: 512, y: 706.5 }, ...overrides },
    binary: { data: { data: IMAGE_BYTES.toString("base64"), mimeType: "image/jpeg" } },
  };
}

{
  const out = runNode(parseEvidence, {
    input: { json: VISION_RESPONSE, binary: {} },
    json: VISION_RESPONSE,
    refs: { "Prepare Image and Intrinsics": preparedRef() },
  })[0].json;
  check("the worker's tap wins over the model's box", out.visual_evidence.target_pixel, { x: 512, y: 706.5 });
  check("  ... and says so", out.visual_evidence.target_pixel_source, "USER_TAP");
}

{
  const out = runNode(parseEvidence, {
    input: { json: VISION_RESPONSE, binary: {} },
    json: VISION_RESPONSE,
    refs: { "Prepare Image and Intrinsics": preparedRef({ target_pixel: null }) },
  })[0].json;
  check("no tap falls back to the box centre", out.visual_evidence.target_pixel, { x: 200, y: 200 });
  check("  ... and says so", out.visual_evidence.target_pixel_source, "VISION_BBOX");
}

{
  const out = runNode(parseEvidence, {
    input: { json: VISION_RESPONSE, binary: {} },
    json: VISION_RESPONSE,
    refs: { "Prepare Image and Intrinsics": preparedRef({ target_pixel: { x: 9999, y: 5 } }) },
  })[0].json;
  check("a tap outside the frame is not trusted either", out.visual_evidence.target_pixel_source, "VISION_BBOX");
}

console.log(`\n${"=".repeat(56)}\n${passed} passed, ${failed} failed\n${"=".repeat(56)}`);
process.exit(failed === 0 ? 0 : 1);
