"""The facility manager's questions to the building assistant: WF3's agent, asked from the app.

WF3 answers the FM in n8n's own chat, which needs an n8n login. The app reaches the same agent
through a second entry in WF3, an n8n webhook: same context node, same tools, same guarded action
tools, same memory keyed by session. The webhook answers only a request that carries the shared
key, and this API sends it only after the database has confirmed an active FM session.

Standard library only: one POST, one JSON answer.
"""

import json
import urllib.error
import urllib.request

KEY_HEADER = "X-CBM-App-Key"
_MAX_ANSWER_BYTES = 256 * 1024


class AssistantUnavailable(Exception):
    """The agent did not answer usably: n8n down, the workflow off, a timeout, an empty answer."""


def ask(url: str, key: str, session_id: str, question: str, timeout: int) -> str:
    """The agent's answer to one question, in the conversation named session_id."""
    body = json.dumps({"question": question, "sessionId": session_id}).encode()
    request = urllib.request.Request(url, data=body, method="POST",
                                     headers={"Content-Type": "application/json", KEY_HEADER: key})
    try:
        # No proxy: the webhook is on the Docker network, never out through one.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(request, timeout=timeout) as response:
            raw = response.read(_MAX_ANSWER_BYTES + 1)
    except (urllib.error.URLError, OSError) as exc:  # HTTPError is a URLError; timeouts are OSErrors
        raise AssistantUnavailable(type(exc).__name__) from exc
    if len(raw) > _MAX_ANSWER_BYTES:
        raise AssistantUnavailable("answer too long")
    try:
        data = json.loads(raw)
    except ValueError as exc:
        raise AssistantUnavailable("not JSON") from exc
    # A webhook answering with its last node gives that node's JSON; a list if set to all entries.
    if isinstance(data, list) and data:
        data = data[0]
    answer = data.get("output") if isinstance(data, dict) else None
    if not isinstance(answer, str) or not answer.strip():
        raise AssistantUnavailable("no answer")
    return answer.strip()
