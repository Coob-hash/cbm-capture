"""The building assistant: the FM's questions to WF3's agent, from the app (28 Sep 2026).

A stub stands in for WF3's app webhook, so these tests see exactly what the API sends it and can
make it answer, fail, stall or say nothing.
"""

import dataclasses
import json
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

import pytest

from cbm_api import main
from conftest import auth, sign_up
from test_api_decisions import fm

KEY = "k" * 43


class Agent:
    """WF3's app webhook as the API sees it: checks the key, records the question, answers."""

    def __init__(self):
        self.requests: list[dict] = []
        self.reply: tuple[int, object] = (200, {"output": "Ticket 42 is ASSIGNED to Mario.", "sessionId": "x"})
        handler = self._handler()
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.url = f"http://127.0.0.1:{self.server.server_port}/webhook/cbm-app-fm-chat"

    def _handler(self):
        agent = self

        class Handler(BaseHTTPRequestHandler):
            def do_POST(self):
                body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
                agent.requests.append({"key": self.headers.get("X-CBM-App-Key"), "body": body})
                if self.headers.get("X-CBM-App-Key") != KEY:
                    status, reply = 403, {"message": "Authorization data is wrong!"}
                else:
                    status, reply = agent.reply
                data = reply if isinstance(reply, bytes) else json.dumps(reply).encode()
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(data)))
                self.end_headers()
                self.wfile.write(data)

            def log_message(self, *_):
                pass

        return Handler

    def close(self):
        self.server.shutdown()


@pytest.fixture
def agent(monkeypatch):
    a = Agent()
    monkeypatch.setattr(main, "settings", dataclasses.replace(
        main.settings, assistant_url=a.url, assistant_key=KEY, assistant_timeout_seconds=5))
    yield a
    a.close()


def ask(client, token, message="What is the status of ticket 42?"):
    return client.post("/v1/fm/assistant", json={"message": message}, headers=auth(token))


def test_an_fm_question_reaches_the_agent_with_the_key_and_the_answer_comes_back(client, owner, agent):
    token = fm(client, owner, dev=301)
    r = ask(client, token, "  What is the status of ticket 42?  ")
    assert r.status_code == 200, r.text
    assert r.json() == {"answer": "Ticket 42 is ASSIGNED to Mario."}
    sent = agent.requests[-1]
    assert sent["key"] == KEY
    assert sent["body"]["question"] == "What is the status of ticket 42?"
    # One conversation per account, named so the agent's actions show they came from the app.
    assert sent["body"]["sessionId"].startswith("app-fm-")


def test_the_same_account_keeps_one_conversation_and_another_fm_has_their_own(client, owner, agent):
    first, second = fm(client, owner, dev=302), fm(client, owner, dev=303)
    ask(client, first), ask(client, first), ask(client, second)
    sessions = [r["body"]["sessionId"] for r in agent.requests[-3:]]
    assert sessions[0] == sessions[1] != sessions[2]


@pytest.mark.parametrize("role", ["USER", "TECHNICIAN"])
def test_only_a_facility_manager_can_ask(client, owner, agent, role):
    _, r = sign_up(client, role=role, dev=304)
    before = len(agent.requests)
    assert ask(client, r.json()["token"]).status_code == 401
    assert len(agent.requests) == before   # refused before anything reached the agent


def test_an_fm_still_waiting_for_approval_cannot_ask(client, owner, agent):
    _, r = sign_up(client, role="FM", dev=305)   # not approved
    assert ask(client, r.json()["token"]).status_code == 401
    assert agent.requests == []


def test_no_session_no_question(client, agent):
    assert client.post("/v1/fm/assistant", json={"message": "hello"}).status_code == 401
    assert ask(client, "0" * 64).status_code == 401
    assert agent.requests == []


@pytest.mark.parametrize("message", ["", "   ", "x" * 1501])
def test_an_empty_or_overlong_question_is_refused(client, owner, agent, message):
    token = fm(client, owner, dev=306)
    r = ask(client, token, message)
    assert r.status_code == 422
    assert r.json()["error"] == "INVALID_REQUEST"
    assert agent.requests == []


@pytest.mark.parametrize("reply", [
    (500, {"message": "Workflow could not be started!"}),
    (404, {"message": "The requested webhook is not registered."}),
    (200, {"output": ""}),
    (200, {"sessionId": "no output at all"}),
    (200, b"<html>not json</html>"),
])
def test_an_agent_that_fails_or_says_nothing_is_a_plain_503(client, owner, agent, reply):
    token = fm(client, owner, dev=307)
    agent.reply = reply
    r = ask(client, token)
    assert r.status_code == 503
    assert r.json()["error"] == "ASSISTANT_UNAVAILABLE"
    assert "check the ticket" in r.json()["message"]


def test_a_wrong_key_is_refused_by_the_agent_and_reported_as_unavailable(client, owner, agent, monkeypatch):
    monkeypatch.setattr(main, "settings", dataclasses.replace(main.settings, assistant_key="wrong"))
    r = ask(client, fm(client, owner, dev=308))
    assert r.status_code == 503
    assert agent.requests[-1]["key"] == "wrong"


def test_an_agent_that_does_not_answer_in_time_is_a_503(client, owner, agent, monkeypatch):
    stalled = threading.Event()

    class Silent(BaseHTTPRequestHandler):
        def do_POST(self):
            stalled.wait(5)

        def log_message(self, *_):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", 0), Silent)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        monkeypatch.setattr(main, "settings", dataclasses.replace(
            main.settings, assistant_url=f"http://127.0.0.1:{server.server_port}/", assistant_timeout_seconds=1))
        r = ask(client, fm(client, owner, dev=309))
        assert r.status_code == 503
        assert r.json()["error"] == "ASSISTANT_UNAVAILABLE"
    finally:
        stalled.set()
        server.shutdown()


def test_not_set_up_is_said_as_such(client, owner, monkeypatch):
    monkeypatch.setattr(main, "settings", dataclasses.replace(main.settings, assistant_url="", assistant_key=""))
    r = ask(client, fm(client, owner, dev=310))
    assert r.status_code == 503
    assert r.json()["error"] == "ASSISTANT_NOT_CONFIGURED"


def test_one_account_cannot_run_up_the_bill(client, owner, agent, monkeypatch):
    monkeypatch.setattr(main, "assistant_limiter", main._RateLimiter(3))
    token = fm(client, owner, dev=311)
    codes = [ask(client, token).status_code for _ in range(4)]
    assert codes == [200, 200, 200, 429]
    assert len(agent.requests) == 3
    # Another FM is not held back by the first one's questions.
    assert ask(client, fm(client, owner, dev=312)).status_code == 200
