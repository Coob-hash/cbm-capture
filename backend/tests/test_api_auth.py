import os
import dataclasses

import psycopg
import pytest

from cbm_api import google_id, main
from conftest import CODE, auth, device, new_email, sign_up


def test_health(client):
    assert client.get("/healthz").json() == {"ok": True}


def test_user_sign_up_is_active_and_logged_in(client):
    email, r = sign_up(client)
    assert r.status_code == 201, r.text
    body = r.json()
    assert len(body["token"]) == 64 and body["membership_id"]
    me = client.get("/v1/me", headers=auth(body["token"])).json()
    assert me["user"]["email"] == email
    assert me["membership"]["role"] == "USER" and me["membership"]["status"] == "ACTIVE"
    assert me["membership"]["site_id"] == "API-TEST"


def test_sign_up_errors_have_one_shape(client):
    email, _ = sign_up(client)
    _, dup = sign_up(client, email=email)
    assert dup.status_code == 409 and dup.json()["error"] == "ACCOUNT_EXISTS"
    bad_site = client.post("/v1/auth/signup", json={"site_code": "no-such-site-code", "role": "USER",
                                                    "email": new_email(), "password": "long-enough-1", "device": device()})
    assert bad_site.status_code == 422 and bad_site.json()["error"] == "INVALID_SITE_CODE"
    _, weak = sign_up(client, password="short")
    assert weak.status_code == 422 and weak.json()["error"] == "WEAK_PASSWORD"


def test_admin_cannot_be_requested_and_input_is_never_echoed(client):
    secret = "my-secret-password-123"
    r = client.post("/v1/auth/signup", json={"site_code": CODE, "role": "ADMIN", "email": new_email(),
                                             "password": secret, "device": device()})
    assert r.status_code == 422 and r.json()["error"] == "INVALID_REQUEST" and r.json()["fields"] == ["role"]
    assert secret not in r.text
    r = client.post("/v1/auth/login", json={"email": "x@example.com", "password": secret, "unexpected": 1, "device": device()})
    assert r.status_code == 422 and secret not in r.text


def test_login_lockout_and_generic_failure(client):
    email, _ = sign_up(client)
    unknown = client.post("/v1/auth/login", json={"email": new_email(), "password": "whatever-123", "device": device()})
    assert unknown.status_code == 401 and unknown.json()["error"] == "INVALID_CREDENTIALS"
    for _ in range(5):
        r = client.post("/v1/auth/login", json={"email": email, "password": "wrong-password", "device": device()})
        assert r.status_code == 401
    r = client.post("/v1/auth/login", json={"email": email, "password": "long-enough-1", "device": device()})
    assert r.status_code == 429 and r.json()["error"] == "LOCKED"
    assert 800 <= int(r.headers["Retry-After"]) <= 900


def test_first_login_on_a_new_device_is_flagged(client):
    email, r = sign_up(client, dev=11)
    assert r.json()["new_device"] is True
    again = client.post("/v1/auth/login", json={"email": email, "password": "long-enough-1", "device": device(11)})
    assert again.status_code == 200 and again.json()["new_device"] is False
    other = client.post("/v1/auth/login", json={"email": email, "password": "long-enough-1", "device": device(12)})
    assert other.json()["new_device"] is True


def test_session_lasts_one_hour(client):
    _, r = sign_up(client)
    me = client.get("/v1/me", headers=auth(r.json()["token"])).json()
    assert me["expires_at"] == r.json()["expires_at"]
    from datetime import datetime, timezone
    remaining = datetime.fromisoformat(me["expires_at"]) - datetime.now(timezone.utc)
    assert 3500 < remaining.total_seconds() <= 3600


def test_technician_joins_freely_and_only_fm_waits(client):
    _, r = sign_up(client, role="TECHNICIAN")
    assert r.status_code == 201
    me = client.get("/v1/me", headers=auth(r.json()["token"])).json()
    assert me["membership"]["role"] == "TECHNICIAN" and me["membership"]["status"] == "ACTIVE"
    assert isinstance(me["membership"]["technician_id"], int)
    _, r = sign_up(client, role="FM")
    me = client.get("/v1/me", headers=auth(r.json()["token"])).json()
    assert me["membership"]["role"] == "FM" and me["membership"]["status"] == "PENDING"


def test_an_existing_technician_is_not_taken_over_by_a_password_sign_up(client, owner):
    """Audit 2026-09-24, finding 1: knowing a technician's email is not being that technician."""
    from test_api_decisions import assigned_ticket
    email = new_email()
    tech = owner.execute("INSERT INTO public.technicians(full_name,email,skills) "
                         "VALUES ('Existing technician',%s,ARRAY['carpentry']) RETURNING id", [email]).fetchone()[0]
    tid = assigned_ticket(owner, tech)

    _, r = sign_up(client, role="TECHNICIAN", email=email, dev=41)
    assert r.status_code == 201, r.text
    membership = r.json()["memberships"][0]
    assert membership["status"] == "PENDING" and membership["technician_id"] is None
    token = r.json()["token"]
    assert client.get("/v1/me", headers=auth(token)).json()["membership"]["status"] == "PENDING"
    assert client.get("/v1/technician/jobs", headers=auth(token)).status_code == 401  # their jobs stay theirs

    # The operator checks it is them, and links the row: the jobs are theirs from then on.
    decided = owner.execute("SELECT cbm_app.decide_membership(jsonb_build_object('membership_id',%s::uuid,"
                            "'decision','APPROVE','operator',true))", [membership["id"]]).fetchone()[0]
    assert decided["status"] == "APPROVED" and decided["technician_id"] == tech
    jobs = client.get("/v1/technician/jobs", headers=auth(token))
    assert jobs.status_code == 200 and tid in [j["ticket_id"] for j in jobs.json()["current"]]

    # A new address is still a new technician, active at once.
    _, fresh = sign_up(client, role="TECHNICIAN", dev=42)
    assert fresh.json()["memberships"][0]["status"] == "ACTIVE"
    assert fresh.json()["memberships"][0]["technician_id"] not in (None, tech)


def test_a_verified_google_address_links_its_technician(client, owner, monkeypatch):
    email = new_email()
    tech = owner.execute("INSERT INTO public.technicians(full_name,email,skills) "
                         "VALUES ('Google technician',%s,ARRAY['hvac']) RETURNING id", [email]).fetchone()[0]
    monkeypatch.setattr(google_id, "verify", lambda token, ids: {"subject": "g-" + email, "email": email,
                                                                 "email_verified": True, "name": "Gus"})
    r = client.post("/v1/auth/signup/google", json={"site_code": CODE, "role": "TECHNICIAN", "id_token": "t",
                                                    "device": device(43)})
    assert r.status_code == 201, r.text
    assert r.json()["memberships"][0]["status"] == "ACTIVE" and r.json()["memberships"][0]["technician_id"] == tech


def test_every_character_of_a_long_password_counts(client):
    """Audit 2026-09-24, finding 8: bcrypt reads 72 bytes; the API accepts 128 characters."""
    head = "x" * 72
    email, r = sign_up(client, password=head + "A", dev=44)
    assert r.status_code == 201
    wrong = client.post("/v1/auth/login", json={"email": email, "password": head + "B", "device": device(44)})
    assert wrong.status_code == 401 and wrong.json()["error"] == "INVALID_CREDENTIALS"
    right = client.post("/v1/auth/login", json={"email": email, "password": head + "A", "device": device(44)})
    assert right.status_code == 200
    # The same with characters of two bytes each: 36 of them fill bcrypt's 72.
    head = "é" * 36
    email, r = sign_up(client, password=head + "A", dev=45)
    assert r.status_code == 201
    assert client.post("/v1/auth/login", json={"email": email, "password": head + "B", "device": device(45)}).status_code == 401
    assert client.post("/v1/auth/login", json={"email": email, "password": head + "A", "device": device(45)}).status_code == 200


def test_a_password_hash_from_before_is_replaced_at_login(client, owner):
    email, r = sign_up(client, dev=46)
    uid = r.json()["user"]["id"]
    owner.execute("UPDATE cbm_app.password_credentials SET password_hash=crypt('long-enough-1',gen_salt('bf',4)), "
                  "scheme='bcrypt' WHERE user_id=%s", [uid])
    login = client.post("/v1/auth/login", json={"email": email, "password": "long-enough-1", "device": device(46)})
    assert login.status_code == 200
    assert owner.execute("SELECT scheme FROM cbm_app.password_credentials WHERE user_id=%s", [uid]).fetchone()[0] == "bcrypt-sha256"
    again = client.post("/v1/auth/login", json={"email": email, "password": "long-enough-1", "device": device(46)})
    assert again.status_code == 200


def test_role_choice_when_holding_two_roles(client, owner):
    email, r = sign_up(client)
    owner.execute("INSERT INTO cbm_app.memberships(user_id,site_id,role,status) "
                  "SELECT id,'API-TEST','TECHNICIAN','PENDING' FROM cbm_app.users WHERE email=%s", [email])
    login = client.post("/v1/auth/login", json={"email": email, "password": "long-enough-1", "device": device()}).json()
    assert login["membership_id"] is None and len(login["memberships"]) == 2
    chosen = login["memberships"][0]["id"]
    ok = client.post("/v1/auth/role", json={"membership_id": chosen}, headers=auth(login["token"]))
    assert ok.status_code == 200 and ok.json()["membership_id"] == chosen
    again = client.post("/v1/auth/role", json={"membership_id": login["memberships"][1]["id"]}, headers=auth(login["token"]))
    assert again.status_code == 409 and again.json()["error"] == "ALREADY_BOUND"


def test_logout_and_bearer_handling(client):
    _, r = sign_up(client)
    token = r.json()["token"]
    assert client.post("/v1/auth/logout", headers=auth(token)).status_code == 204
    gone = client.get("/v1/me", headers=auth(token))
    assert gone.status_code == 401 and gone.json()["error"] == "UNAUTHENTICATED"
    assert client.get("/v1/me").status_code == 401
    assert client.get("/v1/me", headers={"Authorization": "Basic abc"}).status_code == 401
    assert client.get("/v1/me", headers=auth("0" * 64)).status_code == 401


def test_request_size_limit(client):
    r = client.post("/v1/auth/login", content=b"{" + b" " * (70 * 1024) + b"}", headers={"Content-Type": "application/json"})
    assert r.status_code == 413 and r.json()["error"] == "TOO_LARGE"


def test_per_address_rate_limit(client, monkeypatch):
    monkeypatch.setattr(main.limiter, "per_minute", 3)
    monkeypatch.setattr(main.limiter, "_hits", {})
    codes = [client.post("/v1/auth/login", json={"email": new_email(), "password": "x-123456", "device": device()}).status_code
             for _ in range(4)]
    assert codes == [401, 401, 401, 429]


def test_google_sign_up_and_login(client, monkeypatch):
    email = new_email()
    claims = {"subject": "g-" + email, "email": email, "email_verified": True, "name": "Gina"}
    monkeypatch.setattr(google_id, "verify", lambda token, ids: claims)
    r = client.post("/v1/auth/signup/google", json={"site_code": CODE, "role": "USER", "id_token": "t", "device": device()})
    assert r.status_code == 201, r.text
    assert r.json()["user"]["display_name"] == "Gina"
    r = client.post("/v1/auth/login/google", json={"id_token": "t", "device": device()})
    assert r.status_code == 200
    monkeypatch.setattr(google_id, "verify", lambda token, ids: {**claims, "subject": "someone-else", "email": new_email()})
    r = client.post("/v1/auth/login/google", json={"id_token": "t", "device": device()})
    assert r.status_code == 404 and r.json()["error"] == "NO_ACCOUNT"


def test_google_rejections(client, monkeypatch):
    def bad(token, ids):
        raise google_id.GoogleTokenError("expired")
    monkeypatch.setattr(google_id, "verify", bad)
    r = client.post("/v1/auth/login/google", json={"id_token": "t", "device": device()})
    assert r.status_code == 401 and r.json()["error"] == "INVALID_GOOGLE_IDENTITY"
    monkeypatch.setattr(main, "settings", dataclasses.replace(main.settings, google_client_ids=frozenset()))
    r = client.post("/v1/auth/login/google", json={"id_token": "t", "device": device()})
    assert r.status_code == 503 and r.json()["error"] == "GOOGLE_NOT_CONFIGURED"


def test_the_api_login_cannot_read_tables():
    with psycopg.connect(os.environ["CBM_APP_DATABASE_URL"]) as conn:
        for table in ("cbm_app.users", "cbm_app.password_credentials", "cbm_app.sessions", "public.tickets"):
            with pytest.raises(psycopg.errors.InsufficientPrivilege):
                conn.execute(f"SELECT 1 FROM {table} LIMIT 1")
            conn.rollback()


def test_forwarded_client_is_the_entry_ngrok_added(monkeypatch):
    from starlette.requests import Request
    monkeypatch.setattr(main, "settings", dataclasses.replace(main.settings, trust_proxy=True))
    req = Request({"type": "http", "headers": [(b"x-forwarded-for", b"6.6.6.6, 203.0.113.9")], "client": ("172.18.0.5", 1)})
    assert main.client_address(req) == "203.0.113.9"
    monkeypatch.setattr(main, "settings", dataclasses.replace(main.settings, trust_proxy=False))
    assert main.client_address(req) == "172.18.0.5"
