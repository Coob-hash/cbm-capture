"""The FM's decisions and the technician's offers over HTTP.

The rules themselves are the database's, and the SQL suite proves them; these tests prove the wire:
which role reaches which endpoint, what a card looks like on the phone, and that a refusal from the
workflows arrives as a 409 the app can act on.
"""

import hashlib
import json
import os
import uuid

from fastapi.testclient import TestClient

from cbm_api import captures, internal
from conftest import CODE, SITE, auth, device, new_email, sign_up
from test_api_captures import header_only_jpeg, jpeg, metadata, post


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


# ---- The technician's report, written in the app ------------------------------------------------

def report_fields(**overrides) -> dict:
    """The technician's half of the template. The locked half is read from the ticket."""
    return {
        "work_date": "2026-09-22",
        "findings": "The seal was perished along the lower edge.",
        "work_performed": "Replaced the seal, refitted the frame and checked that the sash closes flush.",
        "materials": "1 x seal, 4 m",
        "checks": "Poured water along the sill and watched for ten minutes.",
        "check_result": "PASSED",
        "outcome": "COMPLETED",
        "remaining_issues": "None.",
        "declaration": True,
    } | overrides


def assigned_ticket(owner, tech_id: int) -> int:
    return ticket(owner, status="ASSIGNED", technician_id=tech_id, requires_dispatch_authorization=False,
                  description="Window frame lets water in.", ifc_name="Window W-7", ifc_storey="Level 2")


def send_report(client, token, ticket_id, fields, photo: bytes | None = None):
    files = {"photo": ("after.jpg", photo, "image/jpeg")} if photo else None
    return client.post(f"/v1/technician/jobs/{ticket_id}/report", headers=auth(token),
                       data={"report": json.dumps(fields)}, files=files)


def test_a_report_written_in_the_app_reaches_the_workflows(client, owner):
    token, tech_id = technician(client, owner, dev=34)
    tid = assigned_ticket(owner, tech_id)

    r = send_report(client, token, tid, report_fields())
    assert r.status_code == 202, r.text
    assert r.json()["status"] == "SENT"

    row = owner.execute("SELECT status, report, photo_sha256 FROM cbm_app.technician_reports WHERE ticket_id=%s",
                        [tid]).fetchone()
    assert row[0] == "STORED" and row[2] is None
    # The locked half is the ticket's and the account's, never the phone's.
    assert row[1]["asset_name"] == "Window W-7" and row[1]["reported_issue"] == "Window frame lets water in."
    assert row[1]["ticket_id"] == tid

    # WF2 sees it, with everything it needs to render the document.
    waiting = owner.execute("SELECT x FROM cbm_app.reports_for_wf2() x", []).fetchall()
    offered = owner.execute("SELECT x FROM cbm_app.reports_for_wf2((SELECT id FROM cbm_app.technician_reports "
                            "WHERE ticket_id=%s)) x", [tid]).fetchone()[0]
    assert offered["source"] == "APP" and offered["ticket_id"] == tid
    assert offered["report"]["work_performed"].startswith("Replaced the seal")
    assert offered["photo"] is None
    assert isinstance(waiting, list)

    state = client.get(f"/v1/technician/jobs/{tid}/report", headers=auth(token)).json()
    assert state["sent"] is True and state["state"] == "STORED"


def test_a_report_may_carry_one_after_photo(client, owner):
    token, tech_id = technician(client, owner, dev=35)
    tid = assigned_ticket(owner, tech_id)
    image = jpeg(1280, 960, os.urandom(1200))

    r = send_report(client, token, tid, report_fields(photo_caption="The new seal in place"), photo=image)
    assert r.status_code == 202, r.text
    report_id = r.json()["report_id"]

    row = owner.execute("SELECT status, storage_ref, photo_caption FROM cbm_app.technician_reports WHERE id=%s",
                        [report_id]).fetchone()
    assert row[0] == "STORED" and row[1] == f"report-{report_id}.jpg" and row[2] == "The new seal in place"

    # WF2 fetches the photo from the internal service, as it fetches a reporter's capture.
    with TestClient(internal.app) as svc:
        got = svc.get(f"/internal/reports/{report_id}/photo")
        assert got.status_code == 200 and got.content == image
        assert svc.get(f"/internal/reports/{uuid.uuid4()}/photo").status_code == 404
    assert client.get(f"/internal/reports/{report_id}/photo").status_code == 404


def test_a_report_is_refused_unless_it_is_complete_and_the_job_is_theirs(client, owner):
    token, tech_id = technician(client, owner, dev=36)
    other, _ = technician(client, owner, dev=37)
    tid = assigned_ticket(owner, tech_id)

    assert send_report(client, other, tid, report_fields()).status_code == 404
    assert send_report(client, token, tid, report_fields(work_performed="too short")).status_code == 422
    assert send_report(client, token, tid, report_fields(declaration=False)).status_code == 422
    assert send_report(client, token, tid, report_fields(check_result="MAYBE")).status_code == 422
    assert send_report(client, token, tid, report_fields(work_date="22-09-2026")).status_code == 422
    # A caption with no photo is a mistake, not a report.
    assert send_report(client, token, tid, report_fields(photo_caption="the seal")).status_code == 422
    assert owner.execute("SELECT count(*) FROM cbm_app.technician_reports WHERE ticket_id=%s", [tid]).fetchone()[0] == 0

    assert send_report(client, token, tid, report_fields()).status_code == 202
    # Sending the same report twice leaves one report, not two.
    assert send_report(client, token, tid, report_fields()).status_code == 202
    assert owner.execute("SELECT count(*) FROM cbm_app.technician_reports WHERE ticket_id=%s", [tid]).fetchone()[0] == 1


def test_a_report_with_a_photo_can_be_sent_again_after_it_went_through(client, owner):
    """Audit 2026-09-24, finding 4: the answer to the first send was lost; the app sends again."""
    token, tech_id = technician(client, owner, dev=51)
    tid = assigned_ticket(owner, tech_id)
    photo = jpeg(1280, 960, b"sent once")
    fields = report_fields(photo_caption="The new seal in place")
    first = send_report(client, token, tid, fields, photo)
    again = send_report(client, token, tid, fields, photo)
    assert first.status_code == 202, first.text
    assert again.status_code == 202, again.text
    assert again.json() == {"report_id": first.json()["report_id"], "status": "SENT"}
    assert owner.execute("SELECT count(*) FROM cbm_app.technician_reports WHERE ticket_id=%s", [tid]).fetchone()[0] == 1


def test_an_interrupted_report_is_replaced_whole_by_its_retry(client, owner, monkeypatch):
    """Audit 2026-09-24, finding 5: the photo on disk is always the photo the report describes."""
    token, tech_id = technician(client, owner, dev=52)
    tid = assigned_ticket(owner, tech_id)
    first_image = jpeg(1280, 960, b"first")
    other_image = jpeg(1280, 960, b"changed after the failure")

    real_store = captures.store

    def disk_full(*args, **kwargs):
        raise OSError("simulated disk failure after the claim")

    monkeypatch.setattr(captures, "store", disk_full)
    try:
        send_report(client, token, tid, report_fields(photo_caption="first"), first_image)
    except OSError:
        pass
    monkeypatch.setattr(captures, "store", real_store)
    row = owner.execute("SELECT status FROM cbm_app.technician_reports WHERE ticket_id=%s", [tid]).fetchone()
    assert row[0] == "RECEIVED"  # claimed, photo never stored

    second = send_report(client, token, tid, report_fields(photo_caption="second"), other_image)
    assert second.status_code == 202, second.text
    report_id = second.json()["report_id"]
    sha, size, caption, status = owner.execute(
        "SELECT photo_sha256, photo_bytes, photo_caption, status FROM cbm_app.technician_reports WHERE id=%s",
        [report_id]).fetchone()
    on_disk = captures.report_path_for(os.environ["CBM_APP_CAPTURE_DIR"], report_id).read_bytes()
    assert on_disk == other_image and sha == hashlib.sha256(other_image).hexdigest() and size == len(other_image)
    assert caption == "second" and status == "STORED"
    assert owner.execute("SELECT count(*) FROM cbm_app.technician_reports WHERE ticket_id=%s", [tid]).fetchone()[0] == 1


def test_the_work_date_must_be_a_day_that_exists(client, owner):
    """Audit 2026-09-24, finding 9."""
    token, tech_id = technician(client, owner, dev=53)
    tid = assigned_ticket(owner, tech_id)
    for impossible in ("2026-99-99", "2026-02-29", "2026-04-31", "0000-01-01"):
        r = send_report(client, token, tid, report_fields(work_date=impossible))
        assert r.status_code == 422, (impossible, r.text)
        assert "work date" in r.json()["message"], r.text
    assert owner.execute("SELECT count(*) FROM cbm_app.technician_reports WHERE ticket_id=%s", [tid]).fetchone()[0] == 0
    assert send_report(client, token, tid, report_fields(work_date="2028-02-29")).status_code == 202


def test_a_report_photo_must_be_an_image(client, owner):
    token, tech_id = technician(client, owner, dev=54)
    tid = assigned_ticket(owner, tech_id)
    r = send_report(client, token, tid, report_fields(photo_caption="none"), header_only_jpeg(1280, 960))
    assert r.status_code == 422 and r.json()["error"] == "NOT_A_JPEG"
    assert owner.execute("SELECT count(*) FROM cbm_app.technician_reports WHERE ticket_id=%s", [tid]).fetchone()[0] == 0


def job_card(client, token, ticket_id: int) -> dict:
    jobs = client.get("/v1/technician/jobs", headers=auth(token)).json()["current"]
    return next(j for j in jobs if j["ticket_id"] == ticket_id)


def wf2_takes(owner, report_id: str, ticket_id: int) -> None:
    """What WF2 does with a stored report: claims it, and puts the job before the FM in a new
    approval cycle ('Set Pending Approval' gives the ticket a new approval_id)."""
    owner.execute("UPDATE cbm_app.technician_reports SET status='SUBMITTED' WHERE id=%s", [report_id])
    owner.execute("UPDATE public.tickets SET status='PENDING_APPROVAL', approval_id=gen_random_uuid() WHERE id=%s",
                  [ticket_id])


def test_a_sent_report_is_not_offered_to_be_written_again(client, owner):
    """Second audit 2026-09-24, finding 3: the ticket stays ASSIGNED until WF2 takes the report."""
    token, tech_id = technician(client, owner, dev=55)
    tid = assigned_ticket(owner, tech_id)
    card = job_card(client, token, tid)
    assert card["report_state"] == "TO_DO" and card["report_needed"] is True
    first = send_report(client, token, tid, report_fields())
    assert first.status_code == 202, first.text
    card = job_card(client, token, tid)
    assert card["report_state"] == "PROCESSING" and card["report_needed"] is False
    # Other answers are refused, and said to be refused; the report that was sent stays as it was.
    other = send_report(client, token, tid, report_fields(findings="A newly noticed crack needs attention."))
    assert other.status_code == 409 and other.json()["error"] == "REPORT_ALREADY_SENT", other.text
    assert other.json()["report_id"] == first.json()["report_id"]
    assert owner.execute("SELECT report->>'findings' FROM cbm_app.technician_reports WHERE ticket_id=%s",
                         [tid]).fetchone()[0] == report_fields()["findings"]
    # The same answers again are a repeat, answered with the receipt.
    again = send_report(client, token, tid, report_fields())
    assert again.status_code == 202 and again.json() == {"report_id": first.json()["report_id"], "status": "SENT"}


def test_a_retry_after_the_job_moved_on_gets_its_receipt(client, owner):
    """Second audit 2026-09-24, finding 4: the first answer was lost, and WF2 took the report meanwhile."""
    token, tech_id = technician(client, owner, dev=56)
    tid = assigned_ticket(owner, tech_id)
    photo = jpeg(1280, 960, b"after")
    fields = report_fields(photo_caption="The new seal in place")
    first = send_report(client, token, tid, fields, photo)
    assert first.status_code == 202, first.text
    wf2_takes(owner, first.json()["report_id"], tid)
    again = send_report(client, token, tid, fields, photo)
    assert again.status_code == 202, again.text
    assert again.json() == {"report_id": first.json()["report_id"], "status": "SENT"}
    assert job_card(client, token, tid)["report_state"] == "WITH_FM"
    # It is not a way into a job under review: a different report is refused.
    assert send_report(client, token, tid, report_fields(findings="Something else")).status_code == 409
    # Nor a receipt for someone else: another technician gets nothing.
    other, _ = technician(client, owner, dev=58)
    assert send_report(client, other, tid, fields, photo).status_code == 404


def test_after_a_rework_a_new_report_is_written(client, owner):
    token, tech_id = technician(client, owner, dev=57)
    tid = assigned_ticket(owner, tech_id)
    first = send_report(client, token, tid, report_fields())
    wf2_takes(owner, first.json()["report_id"], tid)
    owner.execute("UPDATE public.tickets SET status='REWORK', fm_reject_reason='The corner still leaks.' WHERE id=%s",
                  [tid])
    card = job_card(client, token, tid)
    assert card["report_state"] == "REWORK" and card["report_needed"] is True
    assert client.get(f"/v1/technician/jobs/{tid}/report", headers=auth(token)).json()["sent"] is False
    # The new round's report is a new report, even word for word the same as the first.
    second = send_report(client, token, tid, report_fields())
    assert second.status_code == 202, second.text
    assert second.json()["report_id"] != first.json()["report_id"]
    assert job_card(client, token, tid)["report_state"] == "PROCESSING"


# ---- WF2 takes a report written in the app (third audit 2026-09-25, finding 1) -------------------
# The branch (backend/n8n/wf2_app_branch.py) runs Render Report PDF -> Record App Submission ->
# the assessment -> Set Pending Approval -> Approval Cycle. These tests run its database half in
# that order: the claim while the job is still the technician's to report, then the approval cycle.

# WF2's 'Set Pending Approval', as in the workflow release (parameters reordered for psycopg: the
# ticket id last). Its RETURNING row is what 'Approval Cycle' requires: an integer id and an approval_id.
SET_PENDING_APPROVAL = (
    "UPDATE tickets SET status='PENDING_APPROVAL',report_text=%s,report_file_id=%s,after_file_id=%s,"
    " verification=%s::jsonb,approval_id=gen_random_uuid(),ifc_new_version=NULL,updated_at=clock_timestamp()"
    " WHERE id=%s AND status IN ('ASSIGNED','WORK_DONE','REWORK') AND technician_id IS NOT NULL"
    " RETURNING id,status,approval_id")


def dispatched_ticket(owner, tech_id: int) -> int:
    """An assigned ticket as dispatch leaves it: the technician's accepted offer is in its dispatch
    state, which the workflows' report link - and so WF2's claim - is issued from."""
    tid = assigned_ticket(owner, tech_id)
    owner.execute("INSERT INTO public.ticket_events(ticket_id,event,payload) VALUES (%s,'CBM_DISPATCH_STATE',%s)",
                  [tid, json.dumps({"status": "ASSIGNED", "offers": [
                      {"id": f"a-{tid}", "token": "c" * 64, "technician_id": tech_id, "status": "ACCEPTED",
                       "expires_at": "2099-01-01T00:00:00Z"}]})])
    return tid


def wf2_claims(owner, report_id: str) -> dict:
    """'Record App Submission', with the hash of the PDF 'Render Report PDF' made."""
    return owner.execute("SELECT cbm_app.record_app_report_submission(%s::jsonb)",
                         [json.dumps({"report_id": report_id, "pdf_sha256": "e" * 64})]).fetchone()[0]


def wf2_sets_pending_approval(owner, ticket_id: int):
    return owner.execute(SET_PENDING_APPROVAL, ["Report text", None, None, json.dumps({"source": "REPORT"}),
                                                ticket_id]).fetchone()


def swept(owner) -> list[str]:
    return [r[0] for r in owner.execute("SELECT x->>'report_id' FROM cbm_app.reports_for_wf2(NULL, 10) x").fetchall()]


def test_wf2_claims_an_app_report_before_it_moves_the_job_on(client, owner):
    token, tech_id = technician(client, owner, dev=64)
    tid = dispatched_ticket(owner, tech_id)
    fields = report_fields()
    sent = send_report(client, token, tid, fields)
    assert sent.status_code == 202, sent.text
    report_id = sent.json()["report_id"]
    item = owner.execute("SELECT x FROM cbm_app.reports_for_wf2(%s) x", [report_id]).fetchone()[0]
    assert item["ticket_id"] == tid
    # The claim, while the ticket is still ASSIGNED: this cycle's report, in the workflows' own table.
    claim = wf2_claims(owner, report_id)
    assert claim["status"] == "SUBMITTED" and claim["proceed"] is True and claim["ticket_id"] == tid, claim
    assert owner.execute("SELECT approval_cycle FROM public.cbm_technician_submissions WHERE id=%s",
                         [claim["submission_id"]]).fetchone()[0] == "initial"
    # Then the approval cycle, whose input is the ticket with its new approval_id.
    row = wf2_sets_pending_approval(owner, tid)
    assert row is not None and row[0] == tid and row[1] == "PENDING_APPROVAL" and row[2] is not None
    # The technician's side: sent, with the facility manager; a lost answer still gets its receipt.
    assert client.get(f"/v1/technician/jobs/{tid}/report", headers=auth(token)).json()["sent"] is True
    assert job_card(client, token, tid)["report_state"] == "WITH_FM"
    retry = send_report(client, token, tid, fields)
    assert retry.status_code == 202 and retry.json()["report_id"] == report_id, retry.text
    # WF2 does not take it again.
    assert owner.execute("SELECT count(*) FROM cbm_app.reports_for_wf2(%s) x", [report_id]).fetchone()[0] == 0
    assert wf2_claims(owner, report_id)["proceed"] is False


def test_a_claim_the_workflows_refuse_stops_wf2_with_its_reason(client, owner):
    token, tech_id = technician(client, owner, dev=65)
    tid = dispatched_ticket(owner, tech_id)
    report_id = send_report(client, token, tid, report_fields()).json()["report_id"]
    # This cycle already has a report, from the browser portal.
    owner.execute("INSERT INTO public.cbm_technician_submissions(ticket_id,approval_cycle,technician_id,pdf_sha256,report) "
                  "VALUES (%s,'initial',%s,%s,'{}')", [tid, tech_id, "d" * 64])
    claim = wf2_claims(owner, report_id)
    assert claim["status"] == "NOT_PROCESSED" and claim["proceed"] is False, claim
    status, outcome = owner.execute("SELECT status, intake_result FROM cbm_app.technician_reports WHERE id=%s",
                                    [report_id]).fetchone()
    assert status == "NOT_PROCESSED" and outcome["claim"]["status"] == "UNCONFIRMED"
    assert report_id not in swept(owner)


def test_a_run_that_stops_after_the_claim_is_taken_up_again(client, owner):
    token, tech_id = technician(client, owner, dev=66)
    tid = dispatched_ticket(owner, tech_id)
    report_id = send_report(client, token, tid, report_fields()).json()["report_id"]
    # First in the sweep's order, whatever else the suite has left waiting.
    owner.execute("UPDATE cbm_app.technician_reports SET stored_at=stored_at-interval '1 day' WHERE id=%s", [report_id])
    first = wf2_claims(owner, report_id)
    assert first["proceed"] is True
    # The assessment fails: the ticket never reaches 'Set Pending Approval'. The sweep leaves the run
    # ten minutes, then offers the report again, and the claim that run made stands.
    assert report_id not in swept(owner)
    age = "UPDATE cbm_app.technician_reports SET intake_checked_at=intake_checked_at-interval '11 minutes' WHERE id=%s"
    owner.execute(age, [report_id])
    assert report_id in swept(owner)
    again = wf2_claims(owner, report_id)
    assert again["proceed"] is True and again["resumed"] is True and again["submission_id"] == first["submission_id"]
    assert report_id not in swept(owner), "not offered again straight away"
    assert client.get(f"/v1/technician/jobs/{tid}/report", headers=auth(token)).json()["sent"] is True
    # Once the approval cycle has it, it is done.
    assert wf2_sets_pending_approval(owner, tid) is not None
    owner.execute(age, [report_id])
    assert report_id not in swept(owner)
    assert wf2_claims(owner, report_id)["proceed"] is False


def test_a_report_from_an_earlier_round_is_not_claimed_for_the_next(client, owner):
    token, tech_id = technician(client, owner, dev=67)
    tid = dispatched_ticket(owner, tech_id)
    report_id = send_report(client, token, tid, report_fields()).json()["report_id"]
    # Before WF2 took it, the job went before the FM another way and was sent back: a new round.
    owner.execute("UPDATE public.tickets SET status='PENDING_APPROVAL', approval_id=gen_random_uuid() WHERE id=%s", [tid])
    owner.execute("UPDATE public.tickets SET status='REWORK' WHERE id=%s", [tid])
    claim = wf2_claims(owner, report_id)
    assert claim["status"] == "NOT_PROCESSED" and claim["proceed"] is False, claim
    assert owner.execute("SELECT intake_result->>'reason' FROM cbm_app.technician_reports WHERE id=%s",
                         [report_id]).fetchone()[0] == "The report was written for an earlier round of this job"
    assert owner.execute("SELECT count(*) FROM public.cbm_technician_submissions WHERE ticket_id=%s",
                         [tid]).fetchone()[0] == 0
    assert job_card(client, token, tid)["report_needed"] is True
