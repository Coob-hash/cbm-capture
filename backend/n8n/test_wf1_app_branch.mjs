// Tests for the WF1 app branch, run against the JavaScript stored in the exports.
//
//   node test_wf1_app_branch.mjs <wf1-before.json> <wf1-after.json>
//
// Graph: every $('Node') reference resolves, the new wiring is as designed, and the Drive path is
// unchanged. Behaviour: the two modified Code nodes return, for a Drive file, exactly what the
// original code returns; for an app capture, the app's fields and the phone's calibration.

import fs from 'node:fs';
import crypto from 'node:crypto';

const load = (p) => { const d = JSON.parse(fs.readFileSync(p, 'utf8')); return Array.isArray(d) ? d[0] : d; };
const before = load(process.argv[2]);
const after = load(process.argv[3]);

let failed = 0;
const check = (label, cond, detail) => {
  console.log(`  ${cond ? 'PASS' : 'FAIL'}  ${label}`);
  if (!cond) { failed++; if (detail !== undefined) console.log('          got:', JSON.stringify(detail)); }
};
const node = (wf, name) => wf.nodes.find((n) => n.name === name);
const targets = (wf, name, output = 0) => ((wf.connections[name]?.main || [])[output] || []).map((t) => t.node);

// ---- Graph -------------------------------------------------------------------------------------
console.log('graph');
const names = after.nodes.map((n) => n.name);
check('node names are unique', new Set(names).size === names.length);
check('six nodes added, none removed', after.nodes.length === before.nodes.length + 6 &&
  before.nodes.every((n) => names.includes(n.name)));
const refs = new Set();
for (const n of after.nodes) {
  for (const m of JSON.stringify(n.parameters).matchAll(/\$\((?:\\?["'])([^"'\\]+)(?:\\?["'])\)/g)) refs.add(m[1]);
}
const dangling = [...refs].filter((r) => !names.includes(r));
check('every $(\'Node\') reference resolves', dangling.length === 0, dangling);
const badTargets = Object.entries(after.connections).flatMap(([src, c]) =>
  (c.main || []).flat().filter(Boolean).map((t) => t.node).filter((t) => !names.includes(t)).map((t) => `${src}->${t}`));
check('every connection targets an existing node', badTargets.length === 0, badTargets);
check('Drive branch unchanged: trigger -> Capture Input', JSON.stringify(targets(after, 'Drive Trigger - New Snapshot')) === '["Capture Input"]');
check('app notification -> Read App Capture -> Capture Input',
  targets(after, 'App Capture Notification')[0] === 'Read App Capture' && targets(after, 'Read App Capture')[0] === 'Capture Input');
check('tick keeps its dispatch/intake work and adds the sweep',
  JSON.stringify(targets(after, 'Phase B Recovery Tick').sort()) ===
  JSON.stringify([...targets(before, 'Phase B Recovery Tick'), 'Sweep App Captures'].sort()));
check('sweep -> Capture Input', targets(after, 'Sweep App Captures')[0] === 'Capture Input');
check('claim -> Record App Intake -> Capture Accepted?',
  JSON.stringify(targets(after, 'Claim Capture Attempt')) === '["Record App Intake"]' &&
  JSON.stringify(targets(after, 'Record App Intake')) === '["Capture Accepted?"]');
check('both Capture Accepted? outputs keep their previous meaning (now via App Capture?)',
  [0, 1].every((o) => JSON.stringify(targets(after, 'Capture Accepted?', o)) ===
    JSON.stringify(targets(before, 'Capture Accepted?', o).map((t) => (t === 'Download Snapshot' ? 'App Capture?' : t)))));
check('App Capture? yes -> Fetch App Snapshot, no -> Download Snapshot',
  targets(after, 'App Capture?', 0)[0] === 'Fetch App Snapshot' && targets(after, 'App Capture?', 1)[0] === 'Download Snapshot');
check('Fetch App Snapshot mirrors Download Snapshot outputs',
  JSON.stringify([0, 1].map((o) => targets(after, 'Fetch App Snapshot', o))) ===
  JSON.stringify([0, 1].map((o) => targets(after, 'Download Snapshot', o))));
check('Fetch App Snapshot keeps errors on its error output', node(after, 'Fetch App Snapshot').onError === 'continueErrorOutput');
const unchanged = before.nodes.filter((n) => !['Capture Input', 'Prepare Image & Metadata'].includes(n.name))
  .filter((n) => JSON.stringify(n.parameters) !== JSON.stringify(node(after, n.name).parameters)).map((n) => n.name);
check('no other existing node changed', unchanged.length === 0, unchanged);
check('new Postgres nodes use the local demo database',
  ['App Capture Notification', 'Read App Capture', 'Sweep App Captures', 'Record App Intake']
    .every((n) => node(after, n).credentials?.postgres?.id === 'cbmLocalPg20260917'));

// ---- Code nodes --------------------------------------------------------------------------------
async function run(code, { item = {}, nodes = {}, buf = null, http = null } = {}) {
  const $input = { item: { json: item, binary: buf ? { data: {} } : undefined } };
  const $ = (name) => {
    if (!(name in nodes)) throw new Error(`No node named "${name}" in this run`);
    return { item: { json: nodes[name] } };
  };
  const helpers = {
    getBinaryDataBuffer: async () => buf,
    httpRequest: async (req) => { if (!http) throw new Error('no network in tests'); return http(req); },
  };
  const fn = new Function('$input', '$', '$env', '$itemIndex', `return (async function () { ${code} }).call(this);`);
  return fn.call({ helpers }, $input, $, { IFC_SERVICE_URL: 'http://ifc-service.test:8000' }, 0);
}

function jpeg(width, height) {
  const sof = Buffer.concat([Buffer.from([0xff, 0xc0, 0x00, 0x11, 0x08]), Buffer.from([height >> 8, height & 255, width >> 8, width & 255]),
    Buffer.from([0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00])]);
  return Buffer.concat([Buffer.from([0xff, 0xd8]), sof, Buffer.from([0xff, 0xd9])]);
}

const ciBefore = node(before, 'Capture Input').parameters.jsCode;
const ciAfter = node(after, 'Capture Input').parameters.jsCode;
const pimBefore = node(before, 'Prepare Image & Metadata').parameters.jsCode;
const pimAfter = node(after, 'Prepare Image & Metadata').parameters.jsCode;

console.log('Capture Input');
const driveFile = { id: 'DriveFile_123', name: 'report_Worker@Example.com_aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa_IMG_7911.jpg',
  webViewLink: 'https://drive.google.com/file/d/DriveFile_123/view' };
check('Drive file: identical to the original code',
  JSON.stringify(await run(ciAfter, { item: driveFile })) === JSON.stringify(await run(ciBefore, { item: driveFile })));
let err = null;
try { await run(ciAfter, { item: { id: 'x', name: 'holiday.jpg' } }); } catch (e) { err = e.message; }
check('Drive file with a bad name: still rejected', /report_<reporter-email>/.test(err || ''), err);

const cid = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const img = jpeg(960, 1280);
const appCapture = { source: 'APP', capture_id: cid, id: `app-${cid}`, name: `app-${cid}.jpg`,
  report_id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc', reporter_email: 'Reporter@Example.com', site_id: 'ROOM-POC',
  webViewLink: `cbm-app://captures/${cid}`, description: 'Door handle detached',
  image: { width: 960, height: 1280, sha256: crypto.createHash('sha256').update(img).digest('hex') },
  camera: { source: 'ARCORE', trusted: true, fx: 954.6, fy: 955.1, cx: 480.2, cy: 639.6, width: 960, height: 1280 },
  target_pixel: { x: 512, y: 700 } };
const ci = await run(ciAfter, { item: { capture: appCapture } });
check('app capture: passes the fields downstream nodes read', ci.json.id === `app-${cid}` && ci.json.report_id === appCapture.report_id
  && ci.json.webViewLink === appCapture.webViewLink && ci.json.source === 'APP', ci.json);
check('app capture: reporter email lower-cased', ci.json.reporter_email === 'reporter@example.com');
err = null;
try { await run(ciAfter, { item: { capture: { ...appCapture, id: 'app-../../etc' } } }); } catch (e) { err = e.message; }
check('app capture with a bad reference: rejected', /Invalid app capture/.test(err || ''), err);

console.log('Prepare Image & Metadata');
const normalizeResponse = { camera: { width: 1280, height: 960, fx: 1000, fy: 1000, px: 640, py: 480, source: 'EXIF_35MM', calibrated: false,
  quality: 'ESTIMATE', trusted: true }, imageB64: 'AAAA', provenance: { original_sha256: 'f'.repeat(64) },
  image: { orientation_applied: 6, source_width: 4032, source_height: 3024 }, gate_rejections: [] };
const driveRun = async (code) => run(code, { item: {}, nodes: { 'Capture Input': { ...driveFile, report_id: null, reporter_email: 'worker@example.com' } },
  buf: jpeg(4032, 3024), http: async () => normalizeResponse });
check('Drive file: identical to the original code',
  JSON.stringify(await driveRun(pimAfter)) === JSON.stringify(await driveRun(pimBefore)));

const called = [];
const app = await run(pimAfter, { nodes: { 'Capture Input': ci.json }, buf: img, http: async (r) => { called.push(r); return {}; } });
const f = app.json;
check('app capture: no EXIF re-estimation (normalize service not called)', called.length === 0);
check('app capture: K is the phone\'s, cx/cy mapped to px/py',
  f.fx === 954.6 && f.fy === 955.1 && f.px === 480.2 && f.py === 639.6 && f.width === 960 && f.height === 1280, f);
check('app capture: factory calibration, trusted', f.intrinsicsSource === 'ARCORE' && f.intrinsicsCalibrated === true && f.intrinsicsTrusted === true);
check('app capture: image passed through unchanged', f.imageB64 === img.toString('base64') && f.dataUri.endsWith(img.toString('base64')));
check('app capture: reporter and tap carried', f.reporterEmail === 'reporter@example.com' && f.targetPixel.x === 512 && f.photoUrl === appCapture.webViewLink);
const mismatch = (await run(pimAfter, { nodes: { 'Capture Input': ci.json }, buf: jpeg(1280, 960) })).json;
check('app capture whose bytes are not the K frame: untrusted', mismatch.intrinsicsTrusted === false && mismatch.gateRejections.includes('FRAME_MISMATCH'), mismatch.gateRejections);
const exifOnly = (await run(pimAfter, { nodes: { 'Capture Input': { ...ci.json, camera: { ...appCapture.camera, source: 'EXIF', trusted: false } } }, buf: img })).json;
check('app capture with an EXIF estimate: not calibrated, not trusted', exifOnly.intrinsicsCalibrated === false && exifOnly.intrinsicsTrusted === false);

console.log(failed ? `\n${failed} FAILED` : '\nall passed');
process.exit(failed ? 1 : 0);
