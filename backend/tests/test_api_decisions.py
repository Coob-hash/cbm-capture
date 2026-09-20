"""The FM's decisions and the technician's offers over HTTP.

The rules themselves are the database's, and the SQL suite proves them; these tests prove the wire:
which role reaches which endpoint, what a card looks like on the phone, and that a refusal from the
workflows arrives as a 409 the app can act on.
"""

import json
import os
import uuid

from conftest import CODE, SITE, auth, device, new_email, sign_up
from test_api_captures import jpeg, metadata, post


def approve(owner, membership_id):
    owner.execute("SELECT cbm_app.decide_membership(jsonb_build_object('membership_id',%s::uuid,"
                  "'decision','APPROVE','operator',true))", [membership_id])


def fm(client, owner, dev=21):
    """An approved facility manager of the test site, and their session."""
    _, r = sign_up(client, role="FM", dev=dev)
    approve(owner, r.json()["membership_id"])
    return r.json()["token"]


def technician(client, owner, dev=22, skills=("carpentry",)):
    _, r = sign_up(client, role="TECHNICIAN", dev=dev)
    token = r.json()["token"]
    if skills:
        assert client.post("/v1/technician/skills", headers=auth(token), json={"skills": list(skills)}).status_code == 200
    tech_id = owner.execute("SELECT technician_id FROM cbm_app.memberships WHERE id=%s",
                            [r.json()["membership_id"]]).fetchone()[0]
    return token, tech_id


def ticket(owner, **columns):
    """The ticket WF1 would create. The site's own tickets carry no site column; API-TEST is the
    site that takes tickets from the older Drive/email intake, which is how the FM sees them."""
    owner.execute("UPDATE cbm_app.sites SET receives_unassigned_tickets=true WHERE id=%s", [SITE])
    cols = {"status": "PENDING_AUTHORIZATION", "description": "Door handle detached", "category": "doors",
            "severity": 3, "required_skill": "carpentry", "ifc_name": "Door D-12", "ifc_storey": "Level 1",
            "reporter_email": "reporter@example.invalid"} | columns
    names = ",".join(cols)
    holders = ",".join(["%s"] * len(cols))
    return owner.execute(f"INSERT INTO public.tickets({names}) VALUES ({holders}) RETURNING id",
                         list(cols.values())).fetchone()[0]


def card(client, token, queue="authorizations", ticket_id=None):
    body = client.get("/v1/fm/queue", headers=auth(token))
    assert body.status_code == 200, body.text
    cards = body.json()[queue]
    return next(c for c in cards if ticket_id is None or c["ticket_id"] == ticket_id)


def decision(c, action, **extra):
    return {"ticket_id": c["ticket_id"], "action": action, "approval_id": c["action"]["approval_id"],
            "expected_updated_at": c["action"]["expected_updated_at"],
            "request_id": uuid.uuid4().hex} | extra


def test_only_an_approved_fm_sees_the_queue(client, owner):
    _, reporter_up = sign_up(client)
    assert client.get("/v1/fm/queue", headers=auth(reporter_up.json()["token"])).status_code == 401
    _, pending = sign_up(client, role="FM", dev=23)
    assert client.get("/v1/fm/queue", headers=auth(pending.json()["token"])).status_code == 401
    approve(owner, pending.json()["membership_id"])
    r = client.get("/v1/fm/queue", headers=auth(pending.json()["token"]))
    assert r.status_code == 200 and r.json()["site_id"] == SITE
    assert set(r.json()) == {"site_id", "authorizations", "completions", "counts"}


def test_the_card_carries_what_the_fm_must_judge(client, owner):
    token = fm(client, owner)
    tid = ticket(owner)
    c = card(client, token, ticket_id=tid)
    assert c["description"] == "Door handle detached" and c["severity"] == 3
    assert c["location"]["asset"] == "Door D-12" and c["required_skill"] == "carpentry"
    assert "approve_intervention" in c["action"]["allowed_actions"]
    assert client.get("/v1/fm/queue", headers=auth(token)).json()["counts"]["awaiting_authorization"] >= 1


def test_authorizing_an_intervention_applies_at_once_and_repeats_harmlessly(client, owner):
    token = fm(client, owner)
    tid = ticket(owner)
    body = decision(card(client, token, ticket_id=tid), "approve_intervention")
    r = client.post("/v1/fm/decisions", headers=auth(token), json=body)
    assert r.status_code == 200, r.text
    assert r.json()["outcome"] == "APPLIED" and r.json()["ticket_status"] == "LOCALIZED"
    assert r.json()["settling"] is False and r.json()["card"]["status"] == "LOCALIZED"
    again = client.post("/v1/fm/decisions", headers=auth(token), json=body)
    assert again.status_code == 200 and again.json()["ticket_status"] == "LOCALIZED"
    events = owner.execute("SELECT count(*) FROM public.ticket_events WHERE ticket_id=%s "
                           "AND event='CBM_DISPATCH_AUTHORIZATION'", [tid]).fetchone()[0]
    assert events == 1
    assert owner.execute("SELECT count(*) FROM cbm_app.fm_decisions WHERE ticket_id=%s", [tid]).fetchone()[0] == 2


def test_a_rejection_needs_a_reason_and_a_moved_ticket_is_refused(client, owner):
    token = fm(client, owner)
    tid = ticket(owner)
    c = card(client, token, ticket_id=tid)
    no_reason = client.post("/v1/fm/decisions", headers=auth(token), json=decision(c, "reject_intervention"))
    assert no_reason.status_code == 422 and no_reason.json()["error"] == "REASON_REQUIRED"
    stale = decision(c, "approve_intervention", expected_updated_at="2000-01-01T00:00:00Z")
    r = client.post("/v1/fm/decisions", headers=auth(token), json=stale)
    assert r.status_code == 409 and r.json()["error"] == "BLOCKED"
    done = client.post("/v1/fm/decisions", headers=auth(token),
                       json=decision(c, "reject_intervention", reason="Not our building."))
    assert done.status_code == 200 and done.json()["ticket_status"] == "REJECTED"


def test_a_technician_cannot_decide_and_an_fm_cannot_answer_offers(client, owner):
    tech_token, _ = technician(client, owner, dev=24)
    fm_token = fm(client, owner, dev=25)
    tid = ticket(owner)
    c = card(client, fm_token, ticket_id=tid)
    assert client.post("/v1/fm/decisions", headers=auth(tech_token),
                       json=decision(c, "approve_intervention")).status_code == 401
    assert client.get("/v1/technician/jobs", headers=auth(fm_token)).status_code == 401
    assert client.post("/v1/technician/offers", headers=auth(fm_token),
                       json={"ticket_id": tid, "offer_id": "x", "decision": "accept"}).status_code == 401


def test_a_new_technician_is_asked_what_they_work_on(client, owner):
    _, r = sign_up(client, role="TECHNICIAN", dev=26)
    token = r.json()["token"]
    jobs = client.get("/v1/technician/jobs", headers=auth(token)).json()
    assert jobs["me"]["needs_skills"] is True and jobs["me"]["skills"] == []
    assert "plumbing" in jobs["skill_catalog"] and jobs["offers"] == []
    bad = client.post("/v1/technician/skills", headers=auth(token), json={"skills": ["sorcery"]})
    assert bad.status_code == 422
    ok = client.post("/v1/technician/skills", headers=auth(token), json={"skills": ["hvac", "plumbing"]})
    assert ok.status_code == 200 and ok.json()["skills"] == ["hvac", "plumbing"]
    assert client.get("/v1/technician/jobs", headers=auth(token)).json()["me"]["needs_skills"] is False


def test_an_offer_is_answered_once(client, owner):
    token, tech_id = technician(client, owner, dev=27)
    tid = ticket(owner, status="DISPATCHING", requires_dispatch_authorization=False)
    offer = {"id": f"offer-{tid}", "token": "c" * 64, "technician_id": tech_id, "status": "LIVE",
             "full_name": "Tina Tech", "email": "tech@example.invalid", "date": "2026-10-01",
             "slot": "14:00-16:00", "expires_at": "2099-01-01T00:00:00Z"}
    owner.execute("INSERT INTO public.ticket_events(ticket_id,event,payload) VALUES (%s,'CBM_DISPATCH_STATE',%s)",
                  [tid, json.dumps({"status": "DISPATCHING", "offers": [offer]})])
    jobs = client.get("/v1/technician/jobs", headers=auth(token)).json()
    mine = next(o for o in jobs["offers"] if o["ticket_id"] == tid)
    assert mine["offer"]["slot"] == "14:00-16:00" and "token" not in mine["offer"] and "reporter" not in mine
    answer = {"ticket_id": tid, "offer_id": offer["id"], "decision": "accept"}
    r = client.post("/v1/technician/offers", headers=auth(token), json=answer)
    assert r.status_code == 200 and r.json() == {"ticket_id": tid, "decision": "accept"}
    assert client.post("/v1/technician/offers", headers=auth(token), json=answer).status_code == 409
    recorded = owner.execute("SELECT payload->>'decision' FROM public.ticket_events WHERE ticket_id=%s "
                             "AND event='CBM_RESPONSE'", [tid]).fetchall()
    assert recorded == [("accept",)]


def test_the_report_template_link_is_for_the_technician_whose_job_it_is(client, owner):
    token, tech_id = technician(client, owner, dev=28)
    other, _ = technician(client, owner, dev=29)
    tid = ticket(owner, status="ASSIGNED", technician_id=tech_id, requires_dispatch_authorization=False)
    owner.execute("INSERT INTO public.ticket_events(ticket_id,event,payload) VALUES (%s,'CBM_DISPATCH_STATE',%s)",
                  [tid, json.dumps({"status": "ASSIGNED", "offers": [
                      {"id": f"a-{tid}", "token": "c" * 64, "technician_id": tech_id, "status": "ACCEPTED",
                       "expires_at": "2099-01-01T00:00:00Z"}]})])
    assert client.get(f"/v1/technician/jobs/{tid}/report-link", headers=auth(other)).status_code == 404
    r = client.get(f"/v1/technician/jobs/{tid}/report-link", headers=auth(token))
    assert r.status_code == 200 and r.json()["url"].startswith("https://portal.example.invalid/cbm-technician-report?")
    assert len(r.json()["url"].split("token=")[1]) == 64
    jobs = client.get("/v1/technician/jobs", headers=auth(token)).json()
    assert next(j for j in jobs["current"] if j["ticket_id"] == tid)["report_state"] == "TO_DO"


def test_the_completion_card_flags_a_first_job_and_rework_needs_a_reason(client, owner):
    fm_token = fm(client, owner, dev=30)
    _, tech_id = technician(client, owner, dev=31)
    tid = ticket(owner, status="PENDING_APPROVAL", technician_id=tech_id,
                 requires_dispatch_authorization=False, report_text="Handle replaced.")
    owner.execute("UPDATE public.tickets SET approval_id=gen_random_uuid() WHERE id=%s", [tid])
    c = card(client, fm_token, queue="completions", ticket_id=tid)
    assert c["technician"]["first_job"] is True and c["technician"]["jobs_completed"] == 0
    assert c["work"]["report_text"] == "Handle replaced."
    assert client.post("/v1/fm/decisions", headers=auth(fm_token),
                       json=decision(c, "request_rework")).status_code == 422
    r = client.post("/v1/fm/decisions", headers=auth(fm_token),
                    json=decision(c, "request_rework", reason="The handle is still loose."))
    assert r.status_code == 200 and r.json()["outcome"] == "READY" and r.json()["settling"] is True
    # The app records the decision; WF2's review loop carries it out, so the ticket has not moved yet.
    assert r.json()["ticket_status"] == "PENDING_APPROVAL"
    opposite = client.post("/v1/fm/decisions", headers=auth(fm_token),
                           json=decision(card(client, fm_token, "completions", tid), "approve_completion"))
    assert opposite.status_code == 409


def test_the_reporter_photo_is_served_to_the_site_fm_only(client, owner):
    fm_token = fm(client, owner, dev=32)
    _, reporter_up = sign_up(client, dev=33)
    reporter_token = reporter_up.json()["token"]
    image = jpeg(960, 1280, os.urandom(1500))
    meta = metadata(image)
    assert post(client, reporter_token, meta, image).status_code == 202
    r = client.get(f"/v1/photos/{meta['capture_id']}", headers=auth(fm_token))
    assert r.status_code == 200 and r.headers["content-type"] == "image/jpeg" and r.content == image
    assert client.get(f"/v1/photos/{meta['capture_id']}", headers=auth(reporter_token)).status_code == 401
    assert client.get(f"/v1/photos/{uuid.uuid4()}", headers=auth(fm_token)).status_code == 404
    assert client.get(f"/v1/photos/{meta['capture_id']}").status_code == 401
