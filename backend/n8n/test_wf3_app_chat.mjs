// Tests for WF3's app entry, run against the JavaScript stored in the exports.
//
//   node test_wf3_app_chat.mjs <wf3-before.json> <wf3-after.json>
//
// Graph: the entry is wired as designed, n8n's own chat is untouched, nothing else changed.
// Behaviour: 'App Chat Turn' turns the App API's request into what 'FM Chat Context' reads from
// n8n's chat, refuses a session that is not an app account's, and the answer comes out of
// 'Chat Response' as {output}, which is what the App API reads.

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
check('three nodes added (entry, turn, note), none removed',
  after.nodes.length === before.nodes.length + 3 && before.nodes.every((n) => names.includes(n.name)));
const changed = before.nodes.filter((n) => JSON.stringify(n) !== JSON.stringify(node(after, n.name))).map((n) => n.name);
check('no existing node changed', changed.length === 0, changed);
const oldConnections = Object.fromEntries(Object.entries(after.connections).filter(([k]) => !['App Chat', 'App Chat Turn'].includes(k)));
check('no existing connection changed', JSON.stringify(oldConnections) === JSON.stringify(before.connections));
check("n8n's chat still leads to FM Chat Context", JSON.stringify(targets(after, 'FM Chat')) === '["FM Chat Context"]');
check('App Chat -> App Chat Turn -> FM Chat Context',
  JSON.stringify(targets(after, 'App Chat')) === '["App Chat Turn"]' &&
  JSON.stringify(targets(after, 'App Chat Turn')) === '["FM Chat Context"]');
const hook = node(after, 'App Chat');
check('App Chat is a POST webhook at /webhook/cbm-app-fm-chat',
  hook.type === 'n8n-nodes-base.webhook' && hook.parameters.httpMethod === 'POST' && hook.parameters.path === 'cbm-app-fm-chat');
check('App Chat answers only with the key (header auth, CBM App Assistant Key)',
  hook.parameters.authentication === 'headerAuth' && hook.credentials?.httpHeaderAuth?.id === 'cbmAppAssistKey1');
check('App Chat answers with its last node, as one JSON object',
  hook.parameters.responseMode === 'lastNode' && hook.parameters.responseData === 'firstEntryJson');
check('App Chat has a stable webhook id', /^[0-9a-f-]{36}$/.test(hook.webhookId || ''));
const refs = new Set();
for (const n of after.nodes) {
  for (const m of JSON.stringify(n.parameters).matchAll(/\$\((?:\\?["'])([^"'\\]+)(?:\\?["'])\)/g)) refs.add(m[1]);
}
const dangling = [...refs].filter((r) => !names.includes(r));
check("every $('Node') reference resolves", dangling.length === 0, dangling);
check("nothing reads $('FM Chat'), which the app entry does not run", !refs.has('FM Chat'));

// ---- Code nodes --------------------------------------------------------------------------------
console.log('behaviour');
function run(code, { input, nodes = {} }) {
  const $input = { first: () => ({ json: input }), all: () => [{ json: input }] };
  const $ = (name) => {
    if (!(name in nodes)) throw new Error(`No node named "${name}" in this run`);
    return { first: () => ({ json: nodes[name] }) };
  };
  return new Function('$input', '$', code)($input, $);
}
const turn = node(after, 'App Chat Turn').parameters.jsCode;
const context = node(after, 'FM Chat Context').parameters.jsCode;
const response = node(after, 'Chat Response').parameters.jsCode;
const session = 'app-fm-0b6f7c1e-2a34-4d5e-8f90-123456789abc';
const webhookItem = (body) => ({ headers: { 'x-cbm-app-key': 'k' }, params: {}, query: {}, body });

const [t] = run(turn, { input: webhookItem({ question: '  What is the status of ticket 42? ', sessionId: session }) });
check('the question and the session pass through', t.json.chatInput === '  What is the status of ticket 42? ' && t.json.sessionId === session, t.json);
const [c] = run(context, { input: t.json });
check('FM Chat Context reads them as from its own chat',
  c.json.question === 'What is the status of ticket 42?' && c.json.sessionId === session && c.json.asked === true, c.json);

const [empty] = run(turn, { input: webhookItem({ sessionId: session }) });
check('no question: an empty turn, which the chat answers with examples, not a model call',
  run(context, { input: empty.json })[0].json.asked === false);

for (const bad of ['fm-default', 'app-fm-', `app-fm-${'x'.repeat(36)}`, '', undefined, 'app-fm-0b6f7c1e-2a34-4d5e-8f90-123456789abc\n']) {
  let threw = false;
  try { run(turn, { input: webhookItem({ question: 'hi', sessionId: bad }) }); } catch { threw = true; }
  check(`a session that is not an app account's is refused: ${JSON.stringify(bad)}`, threw);
}
let threw = false;
try { run(turn, { input: { headers: {} } }); } catch { threw = true; }
check('a request with no body is refused', threw);

const [answer] = run(response, {
  input: {},
  nodes: { 'FM Chat Context': c.json, 'FM Dashboard Agent': { output: 'Ticket 42 is ASSIGNED.' } },
});
check('the answer comes out as {output}, which the App API reads', answer.json.output === 'Ticket 42 is ASSIGNED.', answer.json);

console.log(failed ? `\n${failed} FAILED` : '\nall passed');
process.exit(failed ? 1 : 0);
