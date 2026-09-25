-- The technician's report, written in the app.
--
-- The app sends the fields of the workflows' own template (submission.schema.json) and, if the
-- technician took one, an AFTER photo. It does not send a PDF: WF2 renders it with the template's
-- own renderer, so a report written in the app and one written in the browser are the same
-- document (Q18). WF2 then claims it through the workflows' existing
-- public.cbm_claim_technician_report(), so one approval cycle still takes exactly one report.
--
-- The path mirrors the reporter's photo: the app stores, NOTIFY wakes the workflow, a sweep
-- catches whatever the notification missed, and the workflow writes the outcome back (Q19).
--
-- Repeatable. Apply after 003_decisions.sql.
BEGIN;

-- RECEIVED: the fields are stored, the photo (if any) is not yet on disk.
-- STORED:   everything is on disk; the workflows have been notified.
-- SUBMITTED / NOT_PROCESSED: what WF2 made of it.
CREATE TABLE IF NOT EXISTS cbm_app.technician_reports (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 ticket_id integer NOT NULL,
 user_id uuid NOT NULL REFERENCES cbm_app.users(id),
 membership_id uuid NOT NULL REFERENCES cbm_app.memberships(id),
 technician_id integer NOT NULL REFERENCES public.technicians(id),
 site_id text NOT NULL REFERENCES cbm_app.sites(id),
 status text NOT NULL DEFAULT 'RECEIVED'
  CHECK (status IN ('RECEIVED','STORED','SUBMITTED','NOT_PROCESSED')),
 report jsonb NOT NULL,
 photo_caption text CHECK (photo_caption IS NULL OR length(photo_caption) BETWEEN 1 AND 500),
 photo_sha256 text CHECK (photo_sha256 IS NULL OR photo_sha256 ~ '^[0-9a-f]{64}$'),
 photo_bytes integer CHECK (photo_bytes IS NULL OR photo_bytes > 0),
 storage_ref text,                       -- file name in the API's store, 'report-<id>.jpg'
 submission_id uuid,                     -- public.cbm_technician_submissions, once WF2 claims it
 intake_result jsonb,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 stored_at timestamptz,
 intake_checked_at timestamptz,
 CONSTRAINT technician_reports_stage CHECK ((status = 'RECEIVED') = (stored_at IS NULL)),
 -- A file name belongs to a photo; a caption belongs to a photo; and once the report is stored,
 -- a photo it declared must have its file.
 CONSTRAINT technician_reports_photo CHECK (
   (storage_ref IS NULL OR storage_ref = 'report-' || id::text || '.jpg')
   AND (storage_ref IS NULL OR photo_sha256 IS NOT NULL)
   AND (photo_caption IS NULL OR photo_sha256 IS NOT NULL)
   AND (status = 'RECEIVED' OR photo_sha256 IS NULL OR storage_ref IS NOT NULL))
);
-- Repeatable: bring a table created by an earlier version of this file up to date.
DO $$ BEGIN
 ALTER TABLE cbm_app.technician_reports DROP CONSTRAINT IF EXISTS technician_reports_photo;
 ALTER TABLE cbm_app.technician_reports ADD CONSTRAINT technician_reports_photo CHECK (
   (storage_ref IS NULL OR storage_ref = 'report-' || id::text || '.jpg')
   AND (storage_ref IS NULL OR photo_sha256 IS NOT NULL)
   AND (photo_caption IS NULL OR photo_sha256 IS NOT NULL)
   AND (status = 'RECEIVED' OR photo_sha256 IS NULL OR storage_ref IS NOT NULL));
END $$;
-- The approval cycle the report was written in, counted as the workflows count it: one report per
-- cycle, the cycle being the ticket's approval_id, or 'initial' before its first review
-- (public.cbm_technician_report_access). WF2 gives the ticket a new approval_id when it takes a
-- report, so a job sent back for rework is a new cycle. NULL on reports written before this column.
ALTER TABLE cbm_app.technician_reports ADD COLUMN IF NOT EXISTS approval_cycle text;
-- One report in flight per ticket: the workflows' own table enforces one per approval cycle, this
-- stops a second one being written while the first has not been taken up.
CREATE UNIQUE INDEX IF NOT EXISTS technician_reports_one_open_per_ticket
 ON cbm_app.technician_reports(ticket_id) WHERE status IN ('RECEIVED','STORED');
CREATE INDEX IF NOT EXISTS technician_reports_awaiting_intake
 ON cbm_app.technician_reports(stored_at) WHERE status = 'STORED';

-- A real day written YYYY-MM-DD. The shape alone let 2026-99-99 through; the cast refuses a month
-- or a day that does not exist, and the round trip refuses anything the cast would have reinterpreted.
CREATE OR REPLACE FUNCTION cbm_app.is_calendar_date(p text) RETURNS boolean
LANGUAGE plpgsql IMMUTABLE SET search_path = cbm_app, public, pg_temp AS $$
BEGIN
 IF coalesce(p,'') !~ '^\d{4}-\d{2}-\d{2}$' THEN RETURN false; END IF;
 RETURN to_char(p::date,'YYYY-MM-DD') = p;
EXCEPTION WHEN datetime_field_overflow OR invalid_datetime_format THEN
 RETURN false;
END $$;

-- What the template requires of the technician's own answers (submission.schema.json). The locked
-- half — ticket, asset, location, who they are — is never taken from the client: it is read here.
CREATE OR REPLACE FUNCTION cbm_app.report_fields_valid(p jsonb) RETURNS boolean
LANGUAGE sql IMMUTABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT is_calendar_date(p->>'work_date')
    AND length(btrim(coalesce(p->>'findings',''))) BETWEEN 1 AND 3000
    AND length(btrim(coalesce(p->>'work_performed',''))) BETWEEN 20 AND 6000
    AND length(coalesce(p->>'materials','')) <= 2000
    AND length(btrim(coalesce(p->>'checks',''))) BETWEEN 1 AND 4000
    AND p->>'check_result' IN ('PASSED','FAILED','NOT_PERFORMED')
    AND p->>'outcome' IN ('COMPLETED','PARTIAL','NOT_COMPLETED')
    AND length(btrim(coalesce(p->>'remaining_issues',''))) BETWEEN 1 AND 4000
    AND (p->>'declaration')::boolean IS TRUE
$$;

-- This cycle's report of a ticket, if one has been written - what a repeat is answered with, and
-- what the job list and the form check before offering to write one:
--  * the report in flight (RECEIVED or STORED; at most one per ticket), whatever its cycle;
--  * else, while the job is the technician's to report (ASSIGNED, REWORK), the one the workflows
--    took up (SUBMITTED) in the cycle the ticket is in now;
--  * else, once the job has moved on (with the FM, closed), the last one taken up: the one that
--    moved it.
-- A report the workflows refused (NOT_PROCESSED) does not count: the job can be reported again.
CREATE OR REPLACE FUNCTION cbm_app.cycle_report(p_ticket integer) RETURNS cbm_app.technician_reports
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT r.* FROM technician_reports r JOIN public.tickets t ON t.id = r.ticket_id
 WHERE r.ticket_id = p_ticket
   AND (r.status IN ('RECEIVED','STORED')
        OR r.status = 'SUBMITTED' AND (t.status NOT IN ('ASSIGNED','REWORK')
                                       OR r.approval_cycle = coalesce(t.approval_id::text,'initial')))
 ORDER BY r.status IN ('RECEIVED','STORED') DESC, r.created_at DESC
 LIMIT 1
$$;

-- What a report says, to tell a repeat from a different report: the technician's answers, the
-- photo's hash and its caption. The locked half, read from the ticket and the account, is left out.
CREATE OR REPLACE FUNCTION cbm_app.report_content(p_report jsonb, p_sha256 text, p_caption text) RETURNS jsonb
LANGUAGE sql IMMUTABLE SET search_path = cbm_app, public, pg_temp AS $$
 SELECT jsonb_build_object(
   'report', p_report - ARRAY['schema_version','ticket_id','technician_name','technician_email',
                              'asset_name','location','reported_issue'],
   'photo_sha256', lower(p_sha256), 'photo_caption', left(nullif(btrim(p_caption),''),500))
$$;

-- The technician writes the report. p: {token, ticket_id, report:{…}, photo:{caption, sha256, bytes}?}
-- Returns UPLOAD when a photo is expected next, STORED when there is none; STORED with already_sent
-- for a repeat of this cycle's report; ALREADY_SENT for a different report in a cycle that has one.
CREATE OR REPLACE FUNCTION cbm_app.submit_technician_report(p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; t public.tickets%ROWTYPE; tech public.technicians%ROWTYPE; r technician_reports;
 v_ticket integer; v_fields jsonb; v_photo jsonb := p->'photo'; v_id uuid;
BEGIN
 a := authenticate(p->>'token', ARRAY['TECHNICIAN']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 IF coalesce(p->>'ticket_id','') !~ '^[1-9][0-9]{0,8}$' THEN RETURN jsonb_build_object('status','INVALID'); END IF;
 v_ticket := (p->>'ticket_id')::integer;
 SELECT * INTO t FROM public.tickets WHERE id=v_ticket FOR UPDATE;
 IF t.id IS NULL OR t.technician_id IS DISTINCT FROM (a->>'technician_id')::integer THEN
  RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 v_fields := p->'report';
 IF jsonb_typeof(v_fields) <> 'object' OR NOT report_fields_valid(v_fields) THEN
  RETURN jsonb_build_object('status','INVALID_REPORT'); END IF;
 IF jsonb_typeof(v_photo) = 'object' AND coalesce(v_photo->>'sha256','') !~ '^[0-9a-f]{64}$' THEN
  RETURN jsonb_build_object('status','INVALID_REPORT'); END IF;
 IF jsonb_typeof(v_photo) IS DISTINCT FROM 'object' THEN v_photo := NULL; END IF;
 -- This cycle's report, if it has been sent. The same report again - a double tap, or a retry after
 -- the first answer was lost on the way back, even once WF2 has taken it and moved the job on - is
 -- answered with itself, so the phone learns it was sent. A different report is refused and said to
 -- be refused: answering "sent" would drop its answers without a word. The ownership check above
 -- comes first, so a receipt only ever goes to the job's own technician.
 r := cycle_report(v_ticket);
 IF r.status IN ('STORED','SUBMITTED') THEN
  IF r.technician_id = t.technician_id THEN
   IF report_content(r.report, r.photo_sha256, r.photo_caption)
      = report_content(v_fields, v_photo->>'sha256', v_photo->>'caption') THEN
    RETURN jsonb_build_object('status','STORED','report_id',r.id,'ticket_id',v_ticket,'already_sent',true);
   END IF;
   RETURN jsonb_build_object('status','ALREADY_SENT','report_id',r.id,'ticket_id',v_ticket);
  END IF;
  RETURN jsonb_build_object('status','ALREADY_SENT','ticket_id',v_ticket);
 END IF;
 -- Anything else is a new report, or the retry of one whose photo never arrived (RECEIVED): only
 -- while the job is the technician's to report.
 IF t.status NOT IN ('ASSIGNED','REWORK') THEN RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 SELECT * INTO tech FROM public.technicians WHERE id=t.technician_id;
 -- The locked half of the template, from the ticket and the account — never from the client.
 v_fields := v_fields || jsonb_build_object(
   'schema_version','1.0.0','ticket_id',v_ticket,
   'technician_name',coalesce(tech.full_name,''),'technician_email',coalesce(tech.email,''),
   'asset_name',coalesce(nullif(t.ifc_name,''),'Not identified'),
   'location',concat_ws(' · ',nullif(t.ifc_storey,''),nullif(t.map_code,'')),
   'reported_issue',coalesce(nullif(t.description,''),'Not described'));
 IF r.id IS NOT NULL THEN
  -- An earlier attempt stopped before its photo was on disk (RECEIVED). This attempt replaces it
  -- whole: the fields, the caption and the photo's hash and size move together, so the bytes the
  -- API stores next are the bytes this row describes, and store_technician_report checks it. The
  -- report keeps its id, which is the one-open-report-per-ticket rule.
  UPDATE technician_reports SET user_id=(a->>'user_id')::uuid, membership_id=(a->>'membership_id')::uuid,
    technician_id=t.technician_id, site_id=a->>'site_id', report=v_fields,
    approval_cycle=coalesce(t.approval_id::text,'initial'),
    photo_caption=CASE WHEN jsonb_typeof(v_photo)='object' THEN left(nullif(btrim(v_photo->>'caption'),''),500) END,
    photo_sha256=CASE WHEN jsonb_typeof(v_photo)='object' THEN v_photo->>'sha256' END,
    photo_bytes=CASE WHEN jsonb_typeof(v_photo)='object' THEN (v_photo->>'bytes')::integer END
  WHERE id=r.id;
  v_id := r.id;
 ELSE
  INSERT INTO technician_reports(ticket_id,user_id,membership_id,technician_id,site_id,report,
    photo_caption,photo_sha256,photo_bytes,approval_cycle)
  VALUES (v_ticket,(a->>'user_id')::uuid,(a->>'membership_id')::uuid,t.technician_id,a->>'site_id',
    v_fields,
    CASE WHEN jsonb_typeof(v_photo)='object' THEN left(nullif(btrim(v_photo->>'caption'),''),500) END,
    CASE WHEN jsonb_typeof(v_photo)='object' THEN v_photo->>'sha256' END,
    CASE WHEN jsonb_typeof(v_photo)='object' THEN (v_photo->>'bytes')::integer END,
    coalesce(t.approval_id::text,'initial'))
  RETURNING id INTO v_id;
 END IF;
 IF jsonb_typeof(v_photo) = 'object' THEN
  RETURN jsonb_build_object('status','UPLOAD','report_id',v_id);
 END IF;
 RETURN store_technician_report(p->>'token', jsonb_build_object('report_id',v_id));
END $$;

-- The API calls this once the photo (if any) is on disk: the report becomes visible to WF2.
-- p: {report_id, photo_sha256?}. With a photo, photo_sha256 is the hash of the bytes the API has just
-- written; it must be the hash this report declares, or the report is not stored (CONFLICT): a
-- report is never marked complete over a photo it does not describe.
CREATE OR REPLACE FUNCTION cbm_app.store_technician_report(p_token text, p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; r technician_reports;
BEGIN
 a := authenticate(p_token, ARRAY['TECHNICIAN']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 SELECT * INTO r FROM technician_reports WHERE id=(p->>'report_id')::uuid FOR UPDATE;
 IF r.id IS NULL OR r.technician_id IS DISTINCT FROM (a->>'technician_id')::integer THEN
  RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 IF r.status <> 'RECEIVED' THEN
  RETURN jsonb_build_object('status','STORED','report_id',r.id,'ticket_id',r.ticket_id); END IF;
 IF r.photo_sha256 IS NOT NULL AND r.photo_sha256 IS DISTINCT FROM lower(p->>'photo_sha256') THEN
  RETURN jsonb_build_object('status','CONFLICT','report_id',r.id); END IF;
 UPDATE technician_reports SET status='STORED', stored_at=clock_timestamp(),
   storage_ref=CASE WHEN r.photo_sha256 IS NOT NULL THEN 'report-'||r.id::text||'.jpg' END
 WHERE id=r.id;
 PERFORM pg_notify('cbm_app_report', r.id::text);
 RETURN jsonb_build_object('status','STORED','report_id',r.id,'ticket_id',r.ticket_id);
END $$;

-- A report WF2 has claimed (record_app_report_submission) whose run stopped before the approval cycle
-- took it: the workflows hold its submission for the cycle the ticket is still in, and the ticket is
-- still the technician's to report. The claim stands; the rest of the run is what is missing.
CREATE OR REPLACE FUNCTION cbm_app.claim_awaits_approval(r cbm_app.technician_reports) RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT r.status = 'SUBMITTED' AND EXISTS (
   SELECT 1 FROM public.cbm_technician_submissions s JOIN public.tickets t ON t.id = s.ticket_id
   WHERE s.id = r.submission_id AND s.ticket_id = r.ticket_id AND t.status IN ('ASSIGNED','REWORK')
     AND s.approval_cycle = coalesce(t.approval_id::text,'initial'))
$$;

-- For WF2 (not granted to the API). Without an id: the sweep — reports the notification missed,
-- older than two minutes, and reports WF2 left unfinished, retried after ten: stored ones it never
-- recorded an outcome for, and claimed ones whose ticket never reached the approval cycle (the
-- assessment failed, say). WF2 claims a report before assessing it, so a run that stops after the
-- claim would otherwise leave the job with the office's claim and no review, for good.
CREATE OR REPLACE FUNCTION cbm_app.reports_for_wf2(p_report uuid DEFAULT NULL, p_limit integer DEFAULT 1)
RETURNS SETOF jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT jsonb_build_object(
   'source','APP','report_id',r.id,'ticket_id',r.ticket_id,'submission_id',r.submission_id,
   'technician_id',r.technician_id,'report',r.report,
   'photo',CASE WHEN r.storage_ref IS NOT NULL THEN jsonb_build_object(
     'storage_ref',r.storage_ref,'caption',r.photo_caption,'sha256',r.photo_sha256) END,
   'stored_at',r.stored_at)
 FROM technician_reports r
 WHERE (r.status='STORED'
        AND (p_report IS NOT NULL AND r.id = p_report
             OR p_report IS NULL AND (r.intake_checked_at IS NULL AND r.stored_at < clock_timestamp()-interval '2 minutes'
                                      OR r.intake_checked_at < clock_timestamp()-interval '10 minutes')))
    OR (p_report IS NULL AND r.status='SUBMITTED' AND r.intake_checked_at < clock_timestamp()-interval '10 minutes'
        AND claim_awaits_approval(r))
 ORDER BY r.stored_at
 LIMIT greatest(1, least(coalesce(p_limit,1), 10))
$$;

-- WF2 writes back what it made of the report. p: {report_id, submission_id?, outcome}
-- outcome: SUBMITTED (claimed, the approval cycle has it) | NOT_PROCESSED (refused) | RETRY.
CREATE OR REPLACE FUNCTION cbm_app.record_report_intake(p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE r technician_reports; v_outcome text := upper(coalesce(p->>'outcome',''));
BEGIN
 SELECT * INTO r FROM technician_reports WHERE id=(p->>'report_id')::uuid FOR UPDATE;
 IF r.id IS NULL THEN RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 UPDATE technician_reports SET
   status = CASE v_outcome WHEN 'SUBMITTED' THEN 'SUBMITTED'
                           WHEN 'NOT_PROCESSED' THEN 'NOT_PROCESSED' ELSE r.status END,
   submission_id = coalesce((p->>'submission_id')::uuid, r.submission_id),
   intake_result = p - 'report_id',
   intake_checked_at = clock_timestamp()
 WHERE id=r.id;
 RETURN jsonb_build_object('status','OK','report_id',r.id);
END $$;

-- WF2 calls this once it has rendered the PDF, before it assesses the report: the report is claimed
-- through the workflows' own submission function, so one approval cycle still takes exactly one
-- report, and the outcome is written back here. p: {report_id, pdf_sha256}
--
-- The claim is made while the ticket is still the technician's to report (ASSIGNED, REWORK): the
-- report belongs to that cycle, and only then does the workflows' report link exist. WF2's 'Set
-- Pending Approval' moves the ticket on and opens the next cycle, so it comes after this node. It
-- used to come before: the claim then found the job closed, and every app report was dropped.
--
-- 'proceed' tells WF2 whether to assess the report: true when this cycle's report is claimed, now or
-- by an earlier run that stopped before the approval cycle took it; false otherwise, with the reason
-- recorded on the report (the app then shows the job as still to report).
CREATE OR REPLACE FUNCTION cbm_app.record_app_report_submission(p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE r technician_reports; t public.tickets%ROWTYPE; link jsonb; claim jsonb;
BEGIN
 SELECT * INTO r FROM technician_reports WHERE id=(p->>'report_id')::uuid FOR UPDATE;
 IF r.id IS NULL THEN RETURN jsonb_build_object('status','NOT_FOUND','proceed',false); END IF;
 IF r.status <> 'STORED' THEN
  -- Claimed by a run that stopped before the approval cycle took it: the claim stands, and this run
  -- finishes what that one started.
  IF claim_awaits_approval(r) THEN
   UPDATE technician_reports SET intake_checked_at=clock_timestamp() WHERE id=r.id;
   RETURN jsonb_build_object('status','SUBMITTED','proceed',true,'resumed',true,'report_id',r.id,
     'submission_id',r.submission_id,'ticket_id',r.ticket_id);
  END IF;
  RETURN jsonb_build_object('status',r.status,'proceed',false,'report_id',r.id,'submission_id',r.submission_id);
 END IF;
 IF coalesce(p->>'pdf_sha256','') !~ '^[0-9a-f]{64}$' THEN RETURN jsonb_build_object('status','INVALID','proceed',false); END IF;
 SELECT * INTO t FROM public.tickets WHERE id=r.ticket_id FOR UPDATE;
 -- Written in an earlier round of this job (it was reported another way meanwhile, and sent back):
 -- it is not this round's report, and claiming it would give the new round an old report.
 IF r.approval_cycle IS NOT NULL AND r.approval_cycle <> coalesce(t.approval_id::text,'initial') THEN
  PERFORM record_report_intake(jsonb_build_object('report_id',r.id,'outcome','NOT_PROCESSED',
    'reason','The report was written for an earlier round of this job'));
  RETURN jsonb_build_object('status','NOT_PROCESSED','proceed',false,'report_id',r.id);
 END IF;
 -- The workflows' own access token for this ticket; minting it is what the portal link does too.
 link := public.cbm_issue_technician_report_link(r.ticket_id);
 IF link IS NULL THEN
  PERFORM record_report_intake(jsonb_build_object('report_id',r.id,'outcome','NOT_PROCESSED',
    'reason','The job is no longer open for a report'));
  RETURN jsonb_build_object('status','NOT_PROCESSED','proceed',false,'report_id',r.id);
 END IF;
 claim := public.cbm_claim_technician_report(jsonb_build_object(
   'ticketId',r.ticket_id::text,'token',link->>'token','pdf_sha256',p->>'pdf_sha256','report',r.report));
 IF claim->>'status' <> 'UPLOAD' THEN
  PERFORM record_report_intake(jsonb_build_object('report_id',r.id,'outcome','NOT_PROCESSED','claim',claim));
  RETURN jsonb_build_object('status','NOT_PROCESSED','proceed',false,'report_id',r.id,'claim',claim);
 END IF;
 PERFORM record_report_intake(jsonb_build_object('report_id',r.id,'outcome','SUBMITTED',
   'submission_id',claim->>'submissionId','claim',claim));
 RETURN jsonb_build_object('status','SUBMITTED','proceed',true,'report_id',r.id,
   'submission_id',claim->>'submissionId','ticket_id',r.ticket_id);
END $$;

-- Whether this job's report has been sent in its current cycle (the job list reads the same
-- cycle_report). A report from before a rework is not this cycle's; one whose photo never arrived
-- (RECEIVED) has not been sent.
CREATE OR REPLACE FUNCTION cbm_app.my_report_state(p_token text, p_ticket integer) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; r technician_reports;
BEGIN
 a := authenticate(p_token, ARRAY['TECHNICIAN']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 r := cycle_report(p_ticket);
 IF r.id IS NULL OR r.technician_id IS DISTINCT FROM (a->>'technician_id')::integer OR r.status = 'RECEIVED' THEN
  RETURN jsonb_build_object('status','OK','sent',false); END IF;
 RETURN jsonb_build_object('status','OK','sent',true,'report_id',r.id,'state',r.status,'at',r.created_at);
END $$;

REVOKE ALL ON FUNCTION cbm_app.is_calendar_date(text), cbm_app.report_fields_valid(jsonb), cbm_app.reports_for_wf2(uuid, integer),
 cbm_app.cycle_report(integer), cbm_app.report_content(jsonb, text, text),
 cbm_app.claim_awaits_approval(cbm_app.technician_reports),
 cbm_app.record_report_intake(jsonb), cbm_app.record_app_report_submission(jsonb) FROM PUBLIC, cbm_app_api;
GRANT EXECUTE ON FUNCTION
 cbm_app.submit_technician_report(jsonb),
 cbm_app.store_technician_report(text, jsonb),
 cbm_app.my_report_state(text, integer)
TO cbm_app_api;
COMMIT;
