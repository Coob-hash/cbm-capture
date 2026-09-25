// Tests for the WF2 app branch, run against the JavaScript stored in the exports.
//
//   node test_wf2_app_branch.mjs <wf2-before.json> <wf2-after.json>
//
// Graph: every $('Node') reference resolves, the new wiring is as designed, and the Drive path is
// untouched. Behaviour: 'Extract Ticket ID' returns, for a Drive file, exactly what the original
// code returns, and for a report from the app, the ticket it carries; the rendering node asks the
// template's own renderer for the document and hashes what it produced.

import fs from 'node:fs';

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
check('eight nodes added, none removed', after.nodes.length === before.nodes.length + 8 &&
  before.nodes.every((n) => names.includes(n.name)));

const refs = new Set();
for (const n of after.nodes) {
  for (const m of JSON.stringify(n.parameters).matchAll(/\$\((?:\\?["'])([^"'\\]+)(?:\\?["'])\)/g)) refs.add(m[1]);
}
const dangling = [...refs].filter((r) => !names.includes(r));
check("every $('Node') reference resolves", dangling.length === 0, dangling);
const badTargets = Object.entries(after.connections).flatMap(([src, c]) =>
  (c.main || []).flat().filter(Boolean).map((t) => t.node).filter((t) => !names.includes(t)).map((t) => `${src}->${t}`));
check('every connection targets an existing node', badTargets.length === 0, badTargets);

check('the Drive trigger still feeds Extract Ticket ID',
  JSON.stringify(targets(after, 'Completed Upload (Drive Trigger)')) === '["Extract Ticket ID"]');
check('notification -> Read App Report -> Extract Ticket ID',
  JSON.stringify(targets(after, 'App Report Submitted')) === '["Read App Report"]' &&
  JSON.stringify(targets(after, 'Read App Report')) === '["Extract Ticket ID"]');
check('tick -> Sweep App Reports -> Extract Ticket ID',
  JSON.stringify(targets(after, 'App Report Sweep Tick')) === '["Sweep App Reports"]' &&
  JSON.stringify(targets(after, 'Sweep App Reports')) === '["Extract Ticket ID"]');
check('an accepted ticket asks where the PDF comes from',
  JSON.stringify(targets(after, 'Ticket Open and Assigned?')) === '["App Report?"]');
check('app -> render -> claim -> claimed? -> the extraction; Drive -> download -> the extraction',
  JSON.stringify(targets(after, 'App Report?', 0)) === '["Render Report PDF"]' &&
  JSON.stringify(targets(after, 'App Report?', 1)) === '["Download Report PDF"]' &&
  JSON.stringify(targets(after, 'Render Report PDF')) === '["Record App Submission"]' &&
  JSON.stringify(targets(after, 'Record App Submission')) === '["App Report Claimed?"]' &&
  JSON.stringify(targets(after, 'App Report Claimed?')) === '["Extract Report Text and Photo"]' &&
  JSON.stringify(targets(after, 'Download Report PDF')) === '["Extract Report Text and Photo"]');
// Third audit 2026-09-25, finding 1: the claim ran after 'Set Pending Approval', and the node that
// recorded it stood between that and 'Approval Cycle'.
check("'Set Pending Approval' still feeds 'Approval Cycle' directly",
  JSON.stringify(targets(after, 'Set Pending Approval')) === '["Approval Cycle"]');
const reachable = (wf, from, without = null) => {
  const seen = new Set([from]); const todo = [from];
  while (todo.length) {
    for (const t of (wf.connections[todo.pop()]?.main || []).flat().filter(Boolean).map((x) => x.node)) {
      if (t !== without && !seen.has(t)) { seen.add(t); todo.push(t); }
    }
  }
  return seen;
};
check('on the app path the claim comes before the ticket is moved on',
  reachable(after, 'Render Report PDF').has('Set Pending Approval') &&
  !reachable(after, 'Render Report PDF', 'Record App Submission').has('Set Pending Approval') &&
  !reachable(after, 'Set Pending Approval').has('Record App Submission'));
check('the Drive path never meets the claim',
  !reachable(after, 'Download Report PDF').has('Record App Submission') &&
  !reachable(after, 'Completed Upload (Drive Trigger)', 'App Report?').has('Record App Submission'));

const changed = before.nodes.filter((b) => {
  const a = node(after, b.name);
  return JSON.stringify(a.parameters) !== JSON.stringify(b.parameters);
}).map((n) => n.name);
check('one existing node changed: Extract Ticket ID', JSON.stringify(changed) === '["Extract Ticket ID"]', changed);

console.log('the new nodes');
check('the notification listens on the app channel',
  node(after, 'App Report Submitted').parameters.channelName === 'cbm_app_report' &&
  node(after, 'App Report Submitted').parameters.triggerMode === 'listenTrigger');
check('the sweep runs every minute',
  node(after, 'App Report Sweep Tick').parameters.rule.interval[0].minutesInterval === 1);
for (const [name, fragment] of [['Read App Report', 'cbm_app.reports_for_wf2($1::uuid)'],
                                ['Sweep App Reports', 'cbm_app.reports_for_wf2()'],
                                ['Record App Submission', 'cbm_app.record_app_report_submission($1::jsonb)']]) {
  check(`${name} calls ${fragment}`, node(after, name).parameters.query.includes(fragment));
}
for (const name of ['Read App Report', 'Sweep App Reports', 'Record App Submission']) {
  check(`${name} uses the deployment's Postgres credential`,
    node(after, name).credentials?.postgres?.id === 'cbmLocalPg20260917');
}

// ---- Extract Ticket ID, run for real -------------------------------------------------------------
console.log('Extract Ticket ID');
const extract = (wf, item) => {
  const body = node(wf, 'Extract Ticket ID').parameters.jsCode;
  const $input = {first: () => ({json: item})};
  return new Function('$input', body)($input);
};
const driveFile = {name: 'TICKET-42.pdf', id: 'drive-file-1', webViewLink: 'https://drive/x'};
check('a Drive file gives exactly what it gave before',
  JSON.stringify(extract(after, driveFile)) === JSON.stringify(extract(before, driveFile)));
check('a file that is not a report is still ignored', extract(after, {name: 'photo.jpg'}).length === 0);
check('a file with no ticket in its name is still unmatched',
  extract(after, {name: 'report.pdf', id: 'x'})[0].json.matched === false);

const appItem = {report: {source: 'APP', report_id: 'r-1', ticket_id: 42,
  report: {work_performed: 'Replaced the seal.'}, photo: {caption: 'after', storage_ref: 'report-r-1.jpg'}}};
const appOut = extract(after, appItem)[0].json;
check('a report from the app carries its own ticket',
  appOut.matched === true && appOut.ticket_id === 42 && appOut.source === 'APP');
check('and its fields travel with it for the renderer',
  appOut.app_report.report.work_performed === 'Replaced the seal.' && appOut.app_report.photo.caption === 'after');
check('the file name is the one the rest of WF2 expects', appOut.report_file_name === 'TICKET-42.pdf');
let refused = null;
try { extract(after, {report: {source: 'APP', report_id: 'r-2'}}); } catch (e) { refused = e.message; }
check('a report without a ticket is refused', /without a ticket/.test(refused || ''), refused);

// ---- Render Report PDF ---------------------------------------------------------------------------
console.log('Render Report PDF');
const render = node(after, 'Render Report PDF').parameters.jsCode;
// n8n wraps a Code node's body in an async function, so top-level await is valid there.
const AsyncFunction = Object.getPrototypeOf(async function () {}).constructor;
check('it is valid JavaScript', (() => { try { new AsyncFunction(render); return true; } catch { return false; } })());
check('it uses the template\'s own renderer', render.includes('globalThis.CBMReport.createReport') &&
  render.includes('root.CBMReport={createReport}'));
check('pdf-lib is the only module it adds', render.includes("require('pdf-lib')") &&
  (render.match(/require\('(?!pdf-lib|crypto)[^']+'\)/g) || []).length === 0);
check('the photo comes from the internal service, not the open internet',
  render.includes("http://cbm-app-internal:8081/internal/reports/") && !/https?:\/\/(?!cbm-app-internal)/.test(render.replace(/\/\/[^\n]*/g, '')));
check('it checks that what came back is a PDF and hashes it',
  render.includes("'%PDF-'") && render.includes("createHash('sha256')"));
check('it hands the document on as binary the extraction can read',
  render.includes('prepareBinaryData') && render.includes('binary: {data:'));

// ---- The hand-off to the approval cycle, run with the nodes' own code ---------------------------
// The database half (record_app_report_submission, then the release's 'Set Pending Approval' query)
// runs in backend/tests/test_api_decisions.py; here each node's code gets what the node before it
// returns: the renderer's item, the claim's row, the ticket row 'Set Pending Approval' returns.
console.log('the hand-off to the approval cycle');
const rendered = {json: {app_report_id: 'r-1', ticket_id: 42, pdf_sha256: 'e'.repeat(64), pdf_bytes: 1234},
  binary: {data: {mimeType: 'application/pdf', fileName: 'TICKET-42.pdf', id: 'filesystem-v2:rendered'}}};
const expression = (value, $json) => new Function('$json', 'return ' + value.replace(/^=\{\{/, '').replace(/\}\}$/, ''))($json);
const claimArgs = expression(node(after, 'Record App Submission').parameters.options.queryReplacement, rendered.json);
check('the claim is asked for the rendered report and its hash',
  JSON.stringify(claimArgs) === JSON.stringify([JSON.stringify({report_id: 'r-1', pdf_sha256: 'e'.repeat(64)})]), claimArgs);
const claimed = (result) => new Function('$input', '$', node(after, 'App Report Claimed?').parameters.jsCode)(
  {first: () => ({json: {result}})}, (name) => ({first: () => (name === 'Render Report PDF' ? rendered : null)}));
const taken = claimed({status: 'SUBMITTED', proceed: true, report_id: 'r-1', submission_id: 's-1', ticket_id: 42});
check('a claimed report goes on to the extraction, with the rendered PDF',
  taken.length === 1 && taken[0].binary.data.id === 'filesystem-v2:rendered' && taken[0].json.ticket_id === 42, taken);
check('a resumed claim goes on too', claimed({status: 'SUBMITTED', proceed: true, resumed: true}).length === 1);
check('a report the workflows did not take stops there',
  claimed({status: 'NOT_PROCESSED', proceed: false, report_id: 'r-1'}).length === 0 &&
  claimed({status: 'SUBMITTED', proceed: false}).length === 0 && claimed(undefined).length === 0);
const approvalCycle = new Function('$json', node(after, 'Approval Cycle').parameters.jsCode);
const cycle = approvalCycle({id: 42, status: 'PENDING_APPROVAL', approval_id: '00000000-0000-4000-8000-000000000001'});
check("'Approval Cycle' starts from the row 'Set Pending Approval' returns",
  cycle.length === 1 && cycle[0].json.ticketId === 42 && cycle[0].json.approvalId === '00000000-0000-4000-8000-000000000001', cycle);
check("and would start nothing from the claim's row, which is why nothing stands between them",
  approvalCycle({result: {status: 'SUBMITTED', report_id: 'r-1', ticket_id: 42}}).length === 0);

console.log(failed === 0 ? '\nall checks passed' : `\n${failed} check(s) failed`);
process.exit(failed === 0 ? 0 : 1);
