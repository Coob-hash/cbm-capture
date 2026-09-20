-- What the facility manager and the technician decide, from the phone.
--
-- The workflows already own these decisions and their consequences; this file adds no second
-- decision path. It authenticates the person, checks the ticket belongs to their site, and calls
-- the workflows' own guarded functions:
--
--   FM, before the job is offered : public.cbm_wf3_begin_action(approve|reject_intervention)
--                                   -> public.cbm_authorize_dispatch(), WF1 dispatches
--   FM, after the technician's report : public.cbm_wf3_begin_action(approve_completion|request_rework)
--                                   -> the decision event WF2's review loop settles (IFC, closure, notices)
--   Technician, on an offer       : public.cbm_record_offer_response(), exactly as the email link
--
-- So a tap in the app and a click in the email are the same decision: whichever happens first wins,
-- and the second is refused by the workflows' own guards. The actor recorded in public is FM_APP;
-- which person tapped is recorded here, in cbm_app.fm_decisions.
--
-- Repeatable. Apply after 002_api_role.sql (which grants the entry points below).
BEGIN;

-- A ticket's site. The workflows' tickets carry no site: an app ticket is matched through the
-- report it came from, and tickets from the older Drive/email intake belong to the site flagged
-- below (one site during the pilot; see Q12 in the PRD).
ALTER TABLE cbm_app.sites ADD COLUMN IF NOT EXISTS receives_unassigned_tickets boolean NOT NULL DEFAULT false;
CREATE UNIQUE INDEX IF NOT EXISTS sites_one_unassigned_intake
 ON cbm_app.sites((receives_unassigned_tickets)) WHERE receives_unassigned_tickets;

-- Who decided what from the app. public.ticket_events records the decision itself (actor FM_APP);
-- this adds the account behind it, which the workflows' schema has no column for.
CREATE TABLE IF NOT EXISTS cbm_app.fm_decisions (
 id bigserial PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES cbm_app.users(id),
 membership_id uuid NOT NULL REFERENCES cbm_app.memberships(id),
 session_id uuid NOT NULL REFERENCES cbm_app.sessions(id),
 ticket_id integer NOT NULL,
 action text NOT NULL CHECK (action IN ('approve_intervention','reject_intervention',
   'approve_completion','request_rework')),
 reason text CHECK (length(reason) <= 2000),
 request_key text NOT NULL,
 outcome text NOT NULL,
 result jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS fm_decisions_by_ticket ON cbm_app.fm_decisions(ticket_id, created_at DESC);

-- The skills triage asks for. Dispatch matches required_skill = ANY(technicians.skills), so these
-- are the words the technician ticks in "What do you work on?" (Q17).
CREATE OR REPLACE FUNCTION cbm_app.skill_vocabulary() RETURNS text[]
LANGUAGE sql IMMUTABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT ARRAY['plumbing','electrical','hvac','carpentry','general']
$$;

-- Internal helpers (not granted to the API) -----------------------------------------------------

-- The tickets one site's FM may see and decide on.
CREATE OR REPLACE FUNCTION cbm_app.site_tickets(p_site text) RETURNS TABLE(ticket_id integer)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT t.id FROM public.tickets t
 LEFT JOIN reports r ON r.intake_report_id = t.intake_report_id
 WHERE r.site_id = p_site
    OR (r.id IS NULL AND EXISTS (SELECT 1 FROM sites s WHERE s.id = p_site AND s.receives_unassigned_tickets))
$$;

-- One ticket as the FM's dashboard shows it. 'action' is the workflows' own view of what may be
-- decided now (allowed actions, current approval id, expected revision); the app echoes the last
-- two back when it decides, so a ticket that changed meanwhile is refused instead of decided blind.
CREATE OR REPLACE FUNCTION cbm_app.fm_card(p_ticket integer) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT jsonb_build_object(
  'ticket_id',t.id,'status',t.status,'created_at',t.created_at,'updated_at',t.updated_at,
  'severity',t.severity,'category',t.category,'required_skill',t.required_skill,'description',t.description,
  'location',jsonb_build_object('asset',t.ifc_name,'storey',t.ifc_storey,'ifc_class',t.ifc_class,'map_code',t.map_code),
  'reporter',jsonb_build_object('name',u.display_name,'email',coalesce(u.email,t.reporter_email),
    'from_app',r.id IS NOT NULL),
  'photo',jsonb_build_object(
    'capture_id',(SELECT p.capture_id FROM report_photos p WHERE p.report_id=r.id AND p.storage_ref IS NOT NULL
                  ORDER BY p.received_at DESC LIMIT 1),
    'before_url',t.photo_before_url,'after_url',t.photo_after_url),
  'technician',CASE WHEN tech.id IS NOT NULL THEN jsonb_build_object('id',tech.id,'name',tech.full_name,
    'jobs_completed',tech.jobs_completed,'first_job',tech.jobs_completed=0,'rating',tech.rating) END,
  'work',jsonb_build_object('scheduled_date',t.scheduled_date,'scheduled_slot',t.scheduled_slot,
    'report_text',t.report_text,'verification',t.verification,'rework_reason',t.fm_reject_reason),
  'action',public.cbm_wf3_action_context(t.id))
 FROM public.tickets t
 LEFT JOIN reports r ON r.intake_report_id = t.intake_report_id
 LEFT JOIN users u ON u.id = r.user_id
 LEFT JOIN public.technicians tech ON tech.id = t.technician_id
 WHERE t.id = p_ticket
$$;

-- The same ticket as the technician sees it: the work, not who reported it.
CREATE OR REPLACE FUNCTION cbm_app.job_card(p_ticket integer) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT jsonb_build_object(
  'ticket_id',t.id,'status',t.status,'created_at',t.created_at,'updated_at',t.updated_at,
  'severity',t.severity,'category',t.category,'required_skill',t.required_skill,'description',t.description,
  'location',jsonb_build_object('asset',t.ifc_name,'storey',t.ifc_storey,'ifc_class',t.ifc_class,'map_code',t.map_code),
  'photo',jsonb_build_object(
    'capture_id',(SELECT p.capture_id FROM report_photos p WHERE p.report_id=r.id AND p.storage_ref IS NOT NULL
                  ORDER BY p.received_at DESC LIMIT 1),
    'before_url',t.photo_before_url),
  'work',jsonb_build_object('scheduled_date',t.scheduled_date,'scheduled_slot',t.scheduled_slot,
    'rework_reason',CASE WHEN t.status='REWORK' THEN t.fm_reject_reason END,
    'closed_at',t.closed_at))
 FROM public.tickets t
 LEFT JOIN reports r ON r.intake_report_id = t.intake_report_id
 WHERE t.id = p_ticket
$$;

-- The offers this technician may still answer: live, unexpired, unanswered.
CREATE OR REPLACE FUNCTION cbm_app.live_offers(p_tech integer)
RETURNS TABLE(ticket_id integer, offer jsonb)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT t.id, o.value
 FROM public.tickets t
 JOIN LATERAL (SELECT e.payload FROM public.ticket_events e
   WHERE e.ticket_id=t.id AND e.event='CBM_DISPATCH_STATE' ORDER BY e.id DESC LIMIT 1) s ON true
 CROSS JOIN LATERAL jsonb_array_elements(coalesce(s.payload->'offers','[]'::jsonb)) o(value)
 WHERE t.status='DISPATCHING' AND p_tech IS NOT NULL
   AND (o.value->>'technician_id')::integer = p_tech
   AND o.value->>'status' IN ('SENDING','LIVE','UNCERTAIN')
   AND (o.value->>'expires_at')::timestamptz > clock_timestamp()
   AND NOT EXISTS (SELECT 1 FROM public.ticket_events e2 WHERE e2.ticket_id=t.id
     AND e2.event='CBM_RESPONSE' AND e2.payload->>'offer_id' = o.value->>'id')
$$;

-- Entry functions: the facility manager ---------------------------------------------------------

-- The FM's dashboard: the two queues that wait for a decision, plus the site's counts.
CREATE OR REPLACE FUNCTION cbm_app.fm_queue(p_token text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; v_site text;
BEGIN
 a := authenticate(p_token, ARRAY['FM','ADMIN']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 v_site := a->>'site_id';
 RETURN jsonb_build_object('status','OK','site_id',v_site,
  'authorizations',coalesce((SELECT jsonb_agg(fm_card(t.id) ORDER BY t.created_at)
    FROM site_tickets(v_site) x JOIN public.tickets t ON t.id=x.ticket_id
    WHERE t.status='PENDING_AUTHORIZATION'),'[]'::jsonb),
  'completions',coalesce((SELECT jsonb_agg(fm_card(t.id) ORDER BY t.updated_at)
    FROM site_tickets(v_site) x JOIN public.tickets t ON t.id=x.ticket_id
    WHERE t.status='PENDING_APPROVAL'),'[]'::jsonb),
  'counts',(SELECT jsonb_build_object(
    'awaiting_authorization',count(*) FILTER (WHERE t.status='PENDING_AUTHORIZATION'),
    'awaiting_approval',count(*) FILTER (WHERE t.status='PENDING_APPROVAL'),
    'in_progress',count(*) FILTER (WHERE t.status IN ('LOCALIZED','DISPATCHING','ASSIGNED','WORK_DONE','REWORK','ESCALATED')),
    'open',count(*) FILTER (WHERE t.status NOT IN ('CLOSED','REJECTED','DUPLICATE')),
    'closed_7d',count(*) FILTER (WHERE t.closed_at > clock_timestamp()-interval '7 days'),
    'rejected_7d',count(*) FILTER (WHERE t.dispatch_rejected_at > clock_timestamp()-interval '7 days'))
   FROM site_tickets(v_site) x JOIN public.tickets t ON t.id=x.ticket_id));
END $$;

-- The FM's decision. p: {token, ticket_id, action, reason?, approval_id, expected_updated_at, request_id?}
-- action: approve_intervention | reject_intervention | approve_completion | request_rework.
-- The workflows validate the decision itself (stage, current approval cycle, revision, an opposite
-- decision already recorded, an expired email link) and carry out its consequences. A rejection or
-- rework needs a reason, as by email.
CREATE OR REPLACE FUNCTION cbm_app.fm_decide(p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; v_site text; v_ticket integer; v_action text := p->>'action'; v_reason text;
 v_request text; v_key text; r jsonb; v_outcome text;
BEGIN
 a := authenticate(p->>'token', ARRAY['FM','ADMIN']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 v_site := a->>'site_id';
 IF v_action NOT IN ('approve_intervention','reject_intervention','approve_completion','request_rework')
  OR coalesce(p->>'ticket_id','') !~ '^[1-9][0-9]{0,8}$'
  OR coalesce(p->>'approval_id','') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  OR coalesce(p->>'expected_updated_at','') = ''
  OR coalesce(p->>'request_id','') !~ '^[A-Za-z0-9_:.-]{1,80}$' THEN
  RETURN jsonb_build_object('status','INVALID'); END IF;
 v_reason := nullif(btrim(coalesce(p->>'reason','')),'');
 IF v_action IN ('reject_intervention','request_rework') AND length(coalesce(v_reason,'')) < 2 THEN
  RETURN jsonb_build_object('status','REASON_REQUIRED'); END IF;
 v_ticket := (p->>'ticket_id')::integer;
 IF NOT EXISTS (SELECT 1 FROM site_tickets(v_site) WHERE ticket_id = v_ticket) THEN
  RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 -- One request key per (person, phone request): repeating it repeats the answer, never the decision.
 v_request := left('app:'||(a->>'session_id')||':'||(p->>'request_id'), 150);
 r := public.cbm_wf3_begin_action(jsonb_build_object(
   'action',v_action,'ticketId',v_ticket,'approvalId',p->>'approval_id',
   'expectedUpdatedAt',p->>'expected_updated_at','reason',left(v_reason,2000),
   'requestId',v_request,'sessionId',a->>'session_id','actor','FM_APP','truncated',false,
   'question','App: '||v_action||' on ticket #'||v_ticket));
 v_outcome := coalesce(r->>'outcome','BLOCKED');
 v_key := coalesce(r->>'request_key', v_request);
 INSERT INTO fm_decisions(user_id,membership_id,session_id,ticket_id,action,reason,request_key,outcome,result)
 VALUES ((a->>'user_id')::uuid,(a->>'membership_id')::uuid,(a->>'session_id')::uuid,v_ticket,v_action,
   v_reason,v_key,v_outcome,r);
 -- APPLIED: the workflows already did it (authorization). READY: the decision is recorded and WF2's
 -- review loop settles it within about a minute (closure, IFC, notices).
 RETURN jsonb_build_object('status',CASE WHEN v_outcome IN ('APPLIED','READY','ALREADY_DONE')
    THEN 'OK' ELSE 'BLOCKED' END,
  'outcome',v_outcome,'ticket_id',v_ticket,'ticket_status',r->>'ticket_status',
  'settling',v_outcome='READY','reason',r->>'reason','card',fm_card(v_ticket));
END $$;

-- The image behind an FM card: the reporter's own photo, from the API's capture store.
CREATE OR REPLACE FUNCTION cbm_app.fm_photo(p_token text, p_capture uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; v_ref text;
BEGIN
 a := authenticate(p_token, ARRAY['FM','ADMIN','TECHNICIAN']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 SELECT ph.storage_ref INTO v_ref FROM report_photos ph JOIN reports r ON r.id = ph.report_id
 WHERE ph.capture_id = p_capture AND ph.storage_ref IS NOT NULL AND r.site_id = a->>'site_id';
 IF v_ref IS NULL THEN RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 -- A technician sees only the photo of a job that is theirs.
 IF a->>'role' = 'TECHNICIAN' AND NOT EXISTS (
   SELECT 1 FROM report_photos ph JOIN reports r ON r.id=ph.report_id
   JOIN public.tickets t ON t.intake_report_id = r.intake_report_id
   WHERE ph.capture_id = p_capture AND t.technician_id = (a->>'technician_id')::integer)
  AND NOT EXISTS (
   SELECT 1 FROM report_photos ph JOIN reports r ON r.id=ph.report_id
   JOIN public.tickets t ON t.intake_report_id = r.intake_report_id
   JOIN live_offers((a->>'technician_id')::integer) o ON o.ticket_id = t.id
   WHERE ph.capture_id = p_capture) THEN
  RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 RETURN jsonb_build_object('status','OK','storage_ref',v_ref);
END $$;

-- Entry functions: the technician ----------------------------------------------------------------

-- The technician's three lists: offers to answer, jobs in hand, work completed.
CREATE OR REPLACE FUNCTION cbm_app.technician_jobs(p_token text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; v_tech integer; t public.technicians%ROWTYPE;
BEGIN
 a := authenticate(p_token, ARRAY['TECHNICIAN']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 v_tech := (a->>'technician_id')::integer;
 SELECT * INTO t FROM public.technicians WHERE id = v_tech;
 RETURN jsonb_build_object('status','OK',
  'me',jsonb_build_object('technician_id',t.id,'name',t.full_name,'skills',to_jsonb(coalesce(t.skills,'{}'::text[])),
    'zone',t.zone,'rating',t.rating,'jobs_completed',t.jobs_completed,'available',t.active,
    'needs_skills',coalesce(array_length(t.skills,1),0)=0),
  'skill_catalog',to_jsonb(skill_vocabulary()),
  'offers',coalesce((SELECT jsonb_agg(job_card(o.ticket_id)||jsonb_build_object('offer',
      jsonb_build_object('id',o.offer->>'id','date',o.offer->>'date','slot',o.offer->>'slot',
        'expires_at',o.offer->>'expires_at'))
    ORDER BY (o.offer->>'expires_at')) FROM live_offers(v_tech) o),'[]'::jsonb),
  'current',coalesce((SELECT jsonb_agg(job_card(t2.id)||jsonb_build_object(
      'report_needed',t2.status IN ('ASSIGNED','REWORK'),
      'report_state',CASE t2.status WHEN 'ASSIGNED' THEN 'TO_DO' WHEN 'REWORK' THEN 'REWORK'
        WHEN 'PENDING_APPROVAL' THEN 'WITH_FM' ELSE 'SENT' END) ORDER BY t2.scheduled_date, t2.id)
    FROM public.tickets t2 WHERE t2.technician_id = v_tech
      AND t2.status IN ('ASSIGNED','WORK_DONE','PENDING_APPROVAL','REWORK')),'[]'::jsonb),
  'completed',coalesce((SELECT jsonb_agg(job_card(t3.id) ORDER BY t3.closed_at DESC)
    FROM (SELECT * FROM public.tickets WHERE technician_id = v_tech AND status='CLOSED'
          ORDER BY closed_at DESC LIMIT 20) t3),'[]'::jsonb));
END $$;

-- Accept or decline an offer. p: {token, ticket_id, offer_id, decision: accept|deny}
-- Records exactly what the offer email's link records; WF1 acts on it within a minute.
CREATE OR REPLACE FUNCTION cbm_app.technician_respond(p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; v_tech integer; v_ticket integer; o jsonb; r jsonb;
BEGIN
 a := authenticate(p->>'token', ARRAY['TECHNICIAN']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 v_tech := (a->>'technician_id')::integer;
 IF coalesce(p->>'decision','') NOT IN ('accept','deny')
  OR coalesce(p->>'ticket_id','') !~ '^[1-9][0-9]{0,8}$' THEN
  RETURN jsonb_build_object('status','INVALID'); END IF;
 v_ticket := (p->>'ticket_id')::integer;
 SELECT offer INTO o FROM live_offers(v_tech)
 WHERE ticket_id = v_ticket AND offer->>'id' = p->>'offer_id';
 IF o IS NULL THEN RETURN jsonb_build_object('status','OFFER_GONE'); END IF;
 SELECT response_result INTO r
 FROM public.cbm_record_offer_response(v_ticket, o->>'id', o->>'token', p->>'decision');
 RETURN jsonb_build_object('status',CASE WHEN coalesce((r->>'recorded')::boolean,false)
   THEN 'OK' ELSE 'OFFER_GONE' END,'ticket_id',v_ticket,'decision',p->>'decision');
END $$;

-- The link to the technician's report template for a job of theirs (the workflows' own form).
CREATE OR REPLACE FUNCTION cbm_app.technician_report_link(p_token text, p_ticket integer) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; v_tech integer; r jsonb;
BEGIN
 a := authenticate(p_token, ARRAY['TECHNICIAN']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 v_tech := (a->>'technician_id')::integer;
 IF NOT EXISTS (SELECT 1 FROM public.tickets WHERE id = p_ticket AND technician_id = v_tech
   AND status IN ('ASSIGNED','REWORK')) THEN RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 r := public.cbm_issue_technician_report_link(p_ticket);
 IF r IS NULL THEN RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 RETURN jsonb_build_object('status','OK','ticket_id',p_ticket,'token',r->>'token','expires_at',r->>'expires_at');
END $$;

-- "What do you work on?": the technician's own skills, nobody else's (Q17).
CREATE OR REPLACE FUNCTION cbm_app.set_technician_skills(p_token text, p_skills text[]) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; v_tech integer; v_skills text[];
BEGIN
 a := authenticate(p_token, ARRAY['TECHNICIAN']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 v_tech := (a->>'technician_id')::integer;
 SELECT array_agg(DISTINCT s ORDER BY s) INTO v_skills
 FROM unnest(coalesce(p_skills,'{}'::text[])) s WHERE s = ANY(skill_vocabulary());
 IF v_skills IS NULL OR array_length(v_skills,1) = 0
  OR array_length(coalesce(p_skills,'{}'::text[]),1) IS DISTINCT FROM array_length(v_skills,1) THEN
  RETURN jsonb_build_object('status','INVALID','allowed',to_jsonb(skill_vocabulary())); END IF;
 UPDATE public.technicians SET skills = v_skills WHERE id = v_tech;
 IF NOT FOUND THEN RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 RETURN jsonb_build_object('status','OK','skills',to_jsonb(v_skills));
END $$;

-- The API's login reaches these and nothing else (002_api_role.sql revokes everything first, and
-- runs before this file). The helpers above stay unreachable from the API.
REVOKE ALL ON FUNCTION cbm_app.skill_vocabulary(), cbm_app.site_tickets(text), cbm_app.fm_card(integer),
 cbm_app.job_card(integer), cbm_app.live_offers(integer) FROM PUBLIC, cbm_app_api;
GRANT EXECUTE ON FUNCTION
 cbm_app.fm_queue(text),
 cbm_app.fm_decide(jsonb),
 cbm_app.fm_photo(text, uuid),
 cbm_app.technician_jobs(text),
 cbm_app.technician_respond(jsonb),
 cbm_app.technician_report_link(text, integer),
 cbm_app.set_technician_skills(text, text[])
TO cbm_app_api;
COMMIT;
