"""Adds the app's entry to WF3's chat: the facility manager asks the same agent from the phone.

    python wf3_app_chat.py <wf3-export.json> <output.json>

Input is an export of the running WF3 (n8n export:workflow). n8n's own chat ('FM Chat') needs an
n8n login, which a phone does not have, so the App API asks through a second entry instead:

  FM Chat (n8n's chat, n8n login) ──────────────────────────────┐
  App Chat (webhook, X-CBM-App-Key) ─► App Chat Turn ───────────┤
                                                                ▼
                         FM Chat Context ─► Question Asked? ─► FM Dashboard Agent ─► … ─► Chat Response

Everything after 'FM Chat Context' is shared: the same agent, the same tools and guarded action
tools, the same memory (keyed by the session id), the same logging. Nothing reads $('FM Chat') by
name, and 'FM Chat Context' reads only chatInput and sessionId, which 'App Chat Turn' supplies. The
webhook answers with its last node, 'Chat Response' (or 'Empty Question Reply'): {"output": ...}.

The webhook is reachable through the public edge like every n8n webhook; it answers only a request
carrying the key in the 'CBM App Assistant Key' credential, which the App API holds as
CBM_APP_ASSISTANT_KEY and sends only for an active facility manager's session. 'App Chat Turn'
also refuses a session id that is not an app account's, so the app entry can never continue a
conversation begun in n8n's own chat.
"""

import copy
import json
import sys
import uuid

PATH = "cbm-app-fm-chat"
CREDENTIAL = {"httpHeaderAuth": {"id": "cbmAppAssistKey1", "name": "CBM App Assistant Key"}}
WEBHOOK_ID = str(uuid.uuid5(uuid.NAMESPACE_URL, "cbm-app/wf3/app-chat"))
NEW_NODES = ("App Chat", "App Chat Turn", "App Chat Note")

APP_CHAT_TURN_JS = r"""// The app's question, in the shape FM Chat Context reads from n8n's own chat.
// The App API sends it only for an active facility manager's session (backend/cbm_api/assistant.py);
// the key on 'App Chat' keeps everyone else out. The session is the app account's own conversation:
// an app request can never continue one begun in n8n's chat.
const body = $input.first().json.body || {};
const sessionId = String(body.sessionId || '');
if (!/^app-fm-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(sessionId)) {
  throw new Error('Not an app conversation');
}
const question = typeof body.question === 'string' ? body.question : '';
return [{ json: { chatInput: question, sessionId } }];"""


def fail(msg):
    sys.exit(f"wf3_app_chat: {msg}")


def main(src, dst):
    with open(src, encoding="utf-8") as f:
        data = json.load(f)
    wf = copy.deepcopy(data[0] if isinstance(data, list) else data)
    nodes = {n["name"]: n for n in wf["nodes"]}

    # What the entry relies on, checked before anything is changed.
    for name in NEW_NODES:
        if name in nodes:
            fail(f"'{name}' already exists: the app entry is already there.")
    for name in ("FM Chat", "FM Chat Context", "Question Asked?", "FM Dashboard Agent", "Chat Response"):
        if name not in nodes:
            fail(f"expected node '{name}' is missing; is this WF3?")
    context = nodes["FM Chat Context"]
    code = context.get("parameters", {}).get("jsCode", "")
    if context["type"] != "n8n-nodes-base.code" or "t.chatInput" not in code or "t.sessionId" not in code:
        fail("'FM Chat Context' no longer reads chatInput and sessionId from its input.")
    if "output" not in nodes["Chat Response"].get("parameters", {}).get("jsCode", ""):
        fail("'Chat Response' no longer answers with 'output'.")
    if wf["connections"].get("FM Chat", {}).get("main", [[]])[0][:1] != [{"node": "FM Chat Context", "type": "main", "index": 0}]:
        fail("'FM Chat' no longer leads to 'FM Chat Context'.")
    if any(f"$('FM Chat')" in json.dumps(n.get("parameters", {})) or '$(\\"FM Chat\\")' in json.dumps(n.get("parameters", {}))
           for n in wf["nodes"]):
        fail("a node reads $('FM Chat') by name; the app entry would not supply it.")

    x, y = nodes["FM Chat"]["position"]
    wf["nodes"] += [
        {
            "parameters": {
                "httpMethod": "POST",
                "path": PATH,
                "authentication": "headerAuth",
                "responseMode": "lastNode",
                "responseData": "firstEntryJson",
                "options": {},
            },
            "id": str(uuid.uuid5(uuid.NAMESPACE_URL, "cbm-app/wf3/node/App Chat")),
            "name": "App Chat",
            "type": "n8n-nodes-base.webhook",
            "typeVersion": 2.1,
            "position": [x - 220, y - 220],
            "webhookId": WEBHOOK_ID,
            "credentials": copy.deepcopy(CREDENTIAL),
        },
        {
            "parameters": {"jsCode": APP_CHAT_TURN_JS},
            "id": str(uuid.uuid5(uuid.NAMESPACE_URL, "cbm-app/wf3/node/App Chat Turn")),
            "name": "App Chat Turn",
            "type": "n8n-nodes-base.code",
            "typeVersion": 2,
            "position": [x, y - 220],
        },
        {
            "parameters": {
                "content": ("## App entry\nThe CBM App's facility managers ask here. The App API sends "
                            "a question only for an active FM session, with the key in **CBM App "
                            "Assistant Key**; everything after FM Chat Context is shared with the chat "
                            "below. Session ids are `app-fm-<account>`."),
                "height": 170, "width": 440, "color": 6,
            },
            "id": str(uuid.uuid5(uuid.NAMESPACE_URL, "cbm-app/wf3/node/App Chat Note")),
            "name": "App Chat Note",
            "type": "n8n-nodes-base.stickyNote",
            "typeVersion": 1,
            "position": [x - 260, y - 420],
        },
    ]
    wf["connections"]["App Chat"] = {"main": [[{"node": "App Chat Turn", "type": "main", "index": 0}]]}
    wf["connections"]["App Chat Turn"] = {"main": [[{"node": "FM Chat Context", "type": "main", "index": 0}]]}

    with open(dst, "w", encoding="utf-8") as f:
        json.dump([wf] if isinstance(data, list) else wf, f, indent=2, ensure_ascii=False)
    print(f"wf3_app_chat: added {', '.join(NEW_NODES)}; webhook POST /webhook/{PATH}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
