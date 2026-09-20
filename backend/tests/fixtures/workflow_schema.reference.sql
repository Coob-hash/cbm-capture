-- Reference copy of the workflows' schema (public), structure only, dumped from the live cbm_demo
-- on 2026-09-19. The app tests load it before the app migrations. Refresh when the workflows change.
-- Real addresses hard-coded in workflow functions are replaced with example.invalid placeholders.
--
-- PostgreSQL database dump
--

\restrict k8RfSPeANS9ltupY7gny8hhseE0TtDGQWSOZaXoMJfe0xiTub6maJphfwtYvg1I

-- Dumped from database version 16.15 (Debian 16.15-1.pgdg13+2)
-- Dumped by pg_dump version 16.15 (Debian 16.15-1.pgdg13+2)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

--
-- Name: public; Type: SCHEMA; Schema: -; Owner: -
--



--
-- Name: SCHEMA public; Type: COMMENT; Schema: -; Owner: -
--

COMMENT ON SCHEMA public IS 'standard public schema';


--
-- Name: cbm_authorize_dispatch(integer, uuid, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_authorize_dispatch(p_ticket integer, p_id uuid, p_token text, p_decision text) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE t tickets;
BEGIN
 SELECT * INTO t FROM tickets WHERE id=p_ticket FOR UPDATE;
 IF t.id IS NULL OR NOT t.requires_dispatch_authorization OR t.dispatch_authorization_expires_at IS NULL
 OR p_id IS NULL OR p_token IS NULL OR t.status<>'PENDING_AUTHORIZATION' OR t.dispatch_authorization_id IS DISTINCT FROM p_id
 OR t.dispatch_authorization_token IS DISTINCT FROM p_token OR clock_timestamp()>=t.dispatch_authorization_expires_at
 OR p_decision IS NULL OR p_decision NOT IN ('approve','reject') THEN
  RETURN jsonb_build_object('applied',false,'ticketId',p_ticket,'reason','INVALID_EXPIRED_OR_ALREADY_DECIDED');
 END IF;
 UPDATE tickets SET status=CASE WHEN p_decision='approve' THEN 'LOCALIZED' ELSE 'REJECTED' END,
 dispatch_authorized_at=CASE WHEN p_decision='approve' THEN clock_timestamp() END,
 dispatch_rejected_at=CASE WHEN p_decision='reject' THEN clock_timestamp() END,
 updated_at=greatest(clock_timestamp(),updated_at+interval '1 microsecond') WHERE id=p_ticket;
 INSERT INTO ticket_events(ticket_id,event,payload) VALUES(p_ticket,'CBM_DISPATCH_AUTHORIZATION',
 jsonb_build_object('authorization_id',p_id,'decision',p_decision,'actor','FM_EMAIL_LINK'));
 IF p_decision='reject' THEN
  INSERT INTO cbm_intake_outbox(event_key,kind,reporter_email,payload)
  VALUES('rejected:'||p_id,'REJECTED',t.reporter_email,jsonb_build_object('ticket_id',p_ticket));
 END IF;
 RETURN jsonb_build_object('applied',true,'ticketId',p_ticket,'approved',p_decision='approve');
END $$;


--
-- Name: cbm_capture_begin(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_capture_begin(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $_$
DECLARE r cbm_intake_reports; a cbm_capture_attempts; rid uuid; fid text:=p->>'file_id'; email text:=lower(p->>'reporter_email');
BEGIN
 IF fid IS NULL OR length(fid)>200 OR fid !~ '^[A-Za-z0-9_-]+$' OR email IS NULL OR email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' THEN
  RAISE EXCEPTION 'Valid Drive file id and reporter email are required';
 END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cbm-capture:'||fid,0));
 SELECT * INTO a FROM cbm_capture_attempts WHERE file_id=fid;
 IF FOUND THEN
  SELECT * INTO r FROM cbm_intake_reports WHERE id=a.report_id FOR UPDATE;
  SELECT * INTO a FROM cbm_capture_attempts WHERE file_id=fid FOR UPDATE;
  IF r.reporter_email<>email OR (nullif(p->>'report_id','') IS NOT NULL AND (p->>'report_id')::uuid<>r.id) THEN
   RAISE EXCEPTION 'Report belongs to another reporter or report';
  END IF;
  IF a.status='CONFIGURATION_REQUIRED' AND r.state='CONFIGURATION_REQUIRED' THEN
   IF p->'registration_ready'='true'::jsonb AND a.attempt=r.attempts+1 THEN
    INSERT INTO cbm_capture_configuration_events(report_id,file_id,action,reason,execution_id)
    VALUES(r.id,fid,'RESUMED',a.reason,left(p->>'execution_id',100));
    UPDATE cbm_capture_attempts SET status='PROCESSING',reason=NULL,completed_at=NULL,started_at=clock_timestamp(),
     execution_id=left(p->>'execution_id',100) WHERE file_id=fid;
    UPDATE cbm_intake_reports SET state='PROCESSING',attempts=a.attempt,updated_at=clock_timestamp() WHERE id=r.id;
    -- A queued warning is obsolete once the same capture has safely resumed.
    UPDATE cbm_intake_outbox SET status='CANCELLED' WHERE kind='CONFIGURATION_IT' AND status='PENDING'
     AND payload->>'file_id'=fid;
    RETURN jsonb_build_object('process',true,'report_id',r.id,'file_id',fid,'attempt',a.attempt,
     'reporter_email',email,'resumed',true);
   END IF;
   RETURN jsonb_build_object('process',false,'report_id',r.id,'file_id',fid,'state',r.state,
    'reason',a.reason,'attempts',r.attempts,'requires_new_photo',false);
  END IF;
  RETURN jsonb_build_object('process',false,'reason','FILE_ALREADY_RECORDED','report_id',a.report_id);
 END IF;
 rid:=coalesce(nullif(p->>'report_id','')::uuid,gen_random_uuid());
 INSERT INTO cbm_intake_reports(id,reporter_email) VALUES(rid,email) ON CONFLICT DO NOTHING;
 SELECT * INTO r FROM cbm_intake_reports WHERE id=rid FOR UPDATE;
 IF r.reporter_email<>email THEN RAISE EXCEPTION 'Report belongs to another reporter'; END IF;
 IF r.state='CONFIGURATION_REQUIRED' THEN
  RETURN jsonb_build_object('process',false,'report_id',rid,'state',r.state,'reason','RESUME_RETAINED_FILE',
   'file_id',(SELECT file_id FROM cbm_capture_attempts WHERE report_id=rid AND status='CONFIGURATION_REQUIRED'),
   'attempts',r.attempts,'requires_new_photo',false);
 END IF;
 IF r.state='PROCESSING' THEN
  SELECT * INTO a FROM cbm_capture_attempts WHERE report_id=rid AND attempt=r.attempts;
  IF a.started_at < clock_timestamp()-interval '10 minutes' THEN
   PERFORM cbm_capture_failed(rid,a.file_id,'CAPTURE_TIMEOUT');
   SELECT * INTO r FROM cbm_intake_reports WHERE id=rid;
  END IF;
 END IF;
 IF r.state IN ('PROCESSING','IT_ISSUE','IDENTIFIED') OR r.attempts>=4 THEN
  INSERT INTO cbm_intake_outbox(event_key,kind,reporter_email,payload)
  VALUES('unprocessed:'||fid,CASE WHEN r.state='PROCESSING' THEN 'BUSY' ELSE 'FINISHED' END,email,
   jsonb_build_object('report_id',rid,'state',r.state,'ticket_id',r.ticket_id)) ON CONFLICT DO NOTHING;
  RETURN jsonb_build_object('process',false,'report_id',rid,'state',r.state);
 END IF;
 UPDATE cbm_intake_reports SET attempts=attempts+1,state='PROCESSING',updated_at=clock_timestamp() WHERE id=rid RETURNING * INTO r;
 INSERT INTO cbm_capture_attempts(file_id,report_id,attempt,status,photo_url,execution_id)
 VALUES(fid,rid,r.attempts,'PROCESSING',coalesce(p->>'photo_url',''),left(p->>'execution_id',100));
 IF p->'registration_ready'='false'::jsonb THEN
  RETURN cbm_capture_pause_configuration(rid,fid,coalesce(p->>'configuration_reason','REGISTRATION_REQUIRED'))
   ||jsonb_build_object('process',false);
 END IF;
 RETURN jsonb_build_object('process',true,'report_id',rid,'file_id',fid,'attempt',r.attempts,'reporter_email',email);
END $_$;


--
-- Name: cbm_capture_failed(uuid, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_capture_failed(p_report uuid, p_file text, p_reason text) RETURNS jsonb
    LANGUAGE plpgsql
    AS $_$
DECLARE r cbm_intake_reports; a cbm_capture_attempts; bug uuid; result jsonb;
BEGIN
 IF p_reason IN ('REGISTRATION_REQUIRED','REGISTRATION_INVALID','MAP_CODE_MISMATCH','IFC_SERVICE_UNAVAILABLE','MULTISET_SERVICE_UNAVAILABLE') THEN
  RETURN cbm_capture_pause_configuration(p_report,p_file,p_reason);
 END IF;
 SELECT * INTO r FROM cbm_intake_reports WHERE id=p_report FOR UPDATE;
 SELECT * INTO a FROM cbm_capture_attempts WHERE file_id=p_file AND report_id=p_report FOR UPDATE;
 IF r.id IS NULL OR a.file_id IS NULL OR r.state<>'PROCESSING' OR a.status<>'PROCESSING' OR a.attempt<>r.attempts THEN
  RETURN jsonb_build_object('changed',false);
 END IF;
 -- Only diagnostic codes are persisted, never provider responses/tokens/image bodies.
 IF p_reason IS NULL OR p_reason !~ '^[A-Z][A-Z0-9_]{0,79}$' THEN p_reason:='IDENTIFICATION_FAILED'; END IF;
 UPDATE cbm_capture_attempts SET status='FAILED',reason=p_reason,completed_at=clock_timestamp() WHERE file_id=p_file;
 UPDATE cbm_intake_reports SET state=CASE WHEN attempts<4 THEN 'AWAITING_PHOTO' ELSE 'IT_ISSUE' END,
 updated_at=clock_timestamp() WHERE id=p_report RETURNING * INTO r;
 result:=jsonb_build_object('changed',true,'report_id',r.id,'attempts',r.attempts,'retries_remaining',4-r.attempts,
 'state',r.state,'reason',p_reason);
 IF r.attempts<4 THEN
  INSERT INTO cbm_intake_outbox(event_key,kind,reporter_email,payload)
  VALUES('retry:'||p_file,'RETRY',r.reporter_email,result) ON CONFLICT DO NOTHING;
 ELSE
  INSERT INTO cbm_it_issues(report_id,summary,diagnostics)
  VALUES(r.id,'IFC identification failed after the initial capture and three replacement photos',
   jsonb_build_object('report_id',r.id,'attempts',(SELECT jsonb_agg(jsonb_build_object('attempt',attempt,'file_id',file_id,
    'reason',reason,'execution_id',execution_id,'started_at',started_at,'completed_at',completed_at) ORDER BY attempt)
    FROM cbm_capture_attempts WHERE report_id=r.id)))
  ON CONFLICT(report_id) DO NOTHING;
  SELECT id INTO bug FROM cbm_it_issues WHERE report_id=r.id;
  result:=result||jsonb_build_object('issue_id',bug);
  INSERT INTO cbm_intake_outbox(event_key,kind,reporter_email,payload) VALUES
   ('bug-receipt:'||r.id,'BUG_RECEIPT',r.reporter_email,result),
   ('bug-it:'||r.id,'IT_BUG',NULL,result||jsonb_build_object('diagnostics',(SELECT diagnostics FROM cbm_it_issues WHERE id=bug)))
  ON CONFLICT DO NOTHING;
 END IF;
 RETURN result;
END $_$;


--
-- Name: cbm_capture_identified(uuid, text, jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_capture_identified(p_report uuid, p_file text, p_request jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE r cbm_intake_reports; a cbm_capture_attempts; t tickets; tid integer; prior integer; req jsonb;
BEGIN
 SELECT * INTO r FROM cbm_intake_reports WHERE id=p_report FOR UPDATE;
 SELECT * INTO a FROM cbm_capture_attempts WHERE file_id=p_file AND report_id=p_report FOR UPDATE;
 IF r.id IS NULL OR a.file_id IS NULL OR r.state<>'PROCESSING' OR a.status<>'PROCESSING' OR a.attempt<>r.attempts THEN
  RETURN jsonb_build_object('changed',false,'ticket_id',r.ticket_id);
 END IF;
 IF NOT coalesce((p_request#>>'{triage,triageValid}')::boolean,false) OR p_request#>>'{triage,element,global_id}' IS NULL THEN
  RAISE EXCEPTION 'Validated automatic identification and triage are required';
 END IF;
 req:=p_request||jsonb_build_object('sourceKey',p_file,'ticketId',NULL,'triage',
  (p_request->'triage')||jsonb_build_object('reporterEmail',r.reporter_email,'photoUrl',a.photo_url));
 LOCK TABLE tickets IN SHARE ROW EXCLUSIVE MODE;
 SELECT id INTO prior FROM tickets WHERE ifc_global_id=req#>>'{triage,element,global_id}'
  AND status NOT IN ('CLOSED','DUPLICATE','REJECTED') ORDER BY id LIMIT 1;
 SELECT ticket_id INTO tid FROM public.cbm_create_ticket(req);
 IF tid IS NULL THEN RAISE EXCEPTION 'Ticket creation returned no record'; END IF;
 IF prior IS NULL THEN UPDATE tickets SET intake_report_id=r.id WHERE id=tid; END IF;
 SELECT * INTO t FROM tickets WHERE id=tid;
 UPDATE cbm_capture_attempts SET status='IDENTIFIED',completed_at=clock_timestamp() WHERE file_id=p_file;
 UPDATE cbm_intake_reports SET state='IDENTIFIED',ticket_id=tid,updated_at=clock_timestamp() WHERE id=r.id;
 INSERT INTO ticket_events(ticket_id,event,payload) VALUES(tid,'CBM_CAPTURE_IDENTIFIED',jsonb_build_object('report_id',r.id,'file_id',p_file,'attempt',r.attempts,'duplicate',prior IS NOT NULL));
 INSERT INTO cbm_intake_outbox(event_key,kind,reporter_email,payload)
 VALUES('received:'||r.id,CASE WHEN prior IS NULL THEN 'RECEIVED' ELSE 'DUPLICATE' END,r.reporter_email,
  jsonb_build_object('report_id',r.id,'ticket_id',tid,'status',t.status)) ON CONFLICT DO NOTHING;
 IF prior IS NULL AND t.status='PENDING_AUTHORIZATION' THEN
  INSERT INTO cbm_intake_outbox(event_key,kind,payload)
  VALUES('authorize:'||t.dispatch_authorization_id,'AUTHORIZATION',jsonb_build_object('ticket_id',tid,
   'authorization_id',t.dispatch_authorization_id,'token',t.dispatch_authorization_token,
   'ifc_global_id',t.ifc_global_id,'ifc_name',t.ifc_name,'description',t.description,'severity',t.severity,
   'photo_url',t.photo_before_url,'expires_at',t.dispatch_authorization_expires_at)) ON CONFLICT DO NOTHING;
 END IF;
 RETURN jsonb_build_object('changed',true,'ticket_id',tid,'status',t.status,'duplicate',prior IS NOT NULL);
END $$;


--
-- Name: cbm_capture_pause_configuration(uuid, text, text, boolean); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_capture_pause_configuration(p_report uuid, p_file text, p_reason text, p_reclassify boolean DEFAULT false) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE r cbm_intake_reports; a cbm_capture_attempts; result jsonb; event_id bigint;
BEGIN
 IF p_reason IS NULL OR p_reason NOT IN ('REGISTRATION_REQUIRED','REGISTRATION_INVALID','MAP_CODE_MISMATCH','IFC_SERVICE_UNAVAILABLE','MULTISET_SERVICE_UNAVAILABLE') THEN
  RAISE EXCEPTION 'Expected a configuration diagnostic code';
 END IF;
 SELECT * INTO r FROM cbm_intake_reports WHERE id=p_report FOR UPDATE;
 SELECT * INTO a FROM cbm_capture_attempts WHERE file_id=p_file AND report_id=p_report FOR UPDATE;
 IF r.id IS NULL OR a.file_id IS NULL OR a.attempt<>r.attempts OR NOT (
  (r.state='PROCESSING' AND a.status='PROCESSING') OR
  (p_reclassify AND r.state='AWAITING_PHOTO' AND a.status='FAILED' AND a.reason=p_reason)) THEN
  RETURN jsonb_build_object('changed',false);
 END IF;
 -- Retain the reserved slot and file row: only this same file can resume it.
 UPDATE cbm_capture_attempts SET status='CONFIGURATION_REQUIRED',reason=p_reason,completed_at=clock_timestamp() WHERE file_id=p_file;
 UPDATE cbm_intake_reports SET state='CONFIGURATION_REQUIRED',attempts=attempts-1,updated_at=clock_timestamp()
  WHERE id=p_report RETURNING * INTO r;
 INSERT INTO cbm_capture_configuration_events(report_id,file_id,action,reason,execution_id)
 VALUES(p_report,p_file,CASE WHEN a.status='FAILED' THEN 'RECLASSIFIED' ELSE 'PAUSED' END,p_reason,a.execution_id)
 RETURNING id INTO event_id;
 UPDATE cbm_intake_outbox SET status='CANCELLED' WHERE event_key='retry:'||p_file AND status='PENDING';
 UPDATE cbm_intake_outbox
 SET event_key=event_key||':superseded:'||id,
     payload=payload||jsonb_build_object('superseded_event_key',event_key,'resolution','Technical failure did not consume a photo attempt')
 WHERE event_key='retry:'||p_file AND status='CANCELLED';
 result:=jsonb_build_object('changed',true,'report_id',r.id,'file_id',p_file,'attempts',r.attempts,
  'retries_remaining',4-r.attempts,'state',r.state,'reason',p_reason,'configuration_event_id',event_id,
  'requires_new_photo',false);
 INSERT INTO cbm_intake_outbox(event_key,kind,reporter_email,payload)
 VALUES('configuration:'||event_id,'CONFIGURATION_IT',NULL,result);
 RETURN result;
END $$;


--
-- Name: cbm_claim_dispatch_batch(integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_claim_dispatch_batch(p_limit integer DEFAULT 5) RETURNS TABLE(dispatch_run boolean, "ticketId" text, "leaseToken" uuid, "batchId" uuid, "selectionReason" text, "batchPosition" integer)
    LANGUAGE plpgsql
    AS $$
DECLARE chosen integer[]:='{}'; tid integer; lim integer:=greatest(1,least(coalesce(p_limit,5),5));
 batch uuid:=gen_random_uuid(); token uuid; pos integer:=0; why text; fair_id integer;
BEGIN
 -- Serializes selection only. All selected tickets have persistent expiring claims.
 PERFORM pg_advisory_xact_lock(164620260916::bigint);
 SELECT coalesce(array_agg(ticket_id ORDER BY created_at DESC,ticket_id DESC),'{}') INTO chosen
 FROM (SELECT ticket_id,created_at FROM cbm_dispatch_queue_items
  WHERE actionable AND (lease_until IS NULL OR lease_until<=clock_timestamp())
  ORDER BY created_at DESC,ticket_id DESC LIMIT lim-1) newest;
 SELECT ticket_id INTO tid FROM cbm_dispatch_queue_items
 WHERE actionable AND (lease_until IS NULL OR lease_until<=clock_timestamp()) AND NOT(ticket_id=ANY(chosen))
 ORDER BY last_selected_at ASC NULLS FIRST,created_at ASC,ticket_id ASC LIMIT 1;
 fair_id:=tid;
 IF tid IS NOT NULL THEN chosen:=array_append(chosen,tid); END IF;
 FOREACH tid IN ARRAY chosen LOOP
  pos:=pos+1; token:=gen_random_uuid();
  why:=CASE WHEN tid=fair_id THEN 'OLDER_UNFINISHED_TURN'
            ELSE 'NEWEST_CREATED' END;
  INSERT INTO cbm_dispatch_queue_visits(ticket_id,last_selected_at,lease_token,batch_id,lease_until,worker_execution_id,selection_reason)
  VALUES(tid,clock_timestamp(),token,batch,clock_timestamp()+interval '65 minutes',NULL,why)
  ON CONFLICT(ticket_id) DO UPDATE SET last_selected_at=EXCLUDED.last_selected_at,lease_token=EXCLUDED.lease_token,
   batch_id=EXCLUDED.batch_id,lease_until=EXCLUDED.lease_until,worker_execution_id=NULL,selection_reason=EXCLUDED.selection_reason;
  RETURN QUERY SELECT true,tid::text,token,batch,why,pos;
 END LOOP;
END $$;


--
-- Name: cbm_claim_dispatch_ticket(integer, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_claim_dispatch_ticket(p_ticket integer, p_execution text) RETURNS TABLE("ticketId" text, "leaseToken" uuid, "selectionReason" text)
    LANGUAGE plpgsql
    AS $$
DECLARE tid integer; token uuid:=gen_random_uuid(); why text;
BEGIN
 IF p_execution IS NULL OR btrim(p_execution)='' THEN RAISE EXCEPTION 'Execution ID is required'; END IF;
 PERFORM pg_advisory_xact_lock(164620260916);
 SELECT i.ticket_id INTO tid FROM cbm_dispatch_queue_items i
 WHERE i.actionable AND (i.lease_until IS NULL OR i.lease_until<=clock_timestamp())
  AND (p_ticket IS NULL OR i.ticket_id=p_ticket)
 ORDER BY i.last_selected_at ASC NULLS FIRST,i.created_at ASC,i.ticket_id ASC LIMIT 1;
 IF tid IS NULL THEN RETURN; END IF;
 why:=CASE WHEN p_ticket IS NULL THEN 'RECOVERY_OLDEST_UNFINISHED' ELSE 'EVENT_TICKET' END;
 INSERT INTO cbm_dispatch_queue_visits(ticket_id,last_selected_at,lease_token,batch_id,lease_until,worker_execution_id,selection_reason)
 VALUES(tid,clock_timestamp(),token,NULL,clock_timestamp()+interval '65 minutes',p_execution,why)
 ON CONFLICT(ticket_id) DO UPDATE SET last_selected_at=EXCLUDED.last_selected_at,lease_token=EXCLUDED.lease_token,
  batch_id=NULL,lease_until=EXCLUDED.lease_until,worker_execution_id=EXCLUDED.worker_execution_id,selection_reason=EXCLUDED.selection_reason;
 RETURN QUERY SELECT tid::text,token,why;
END $$;


--
-- Name: cbm_claim_technician_report(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_claim_technician_report(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $_$
DECLARE access jsonb; sid uuid;
BEGIN
 IF coalesce(p->>'ticketId','') !~ '^[1-9][0-9]{0,9}$' THEN RETURN jsonb_build_object('status','UNAVAILABLE'); END IF;
 PERFORM 1 FROM tickets WHERE id=(p->>'ticketId')::int FOR UPDATE;
 access=cbm_technician_report_access(p);
 IF access->>'status'<>'OK' THEN RETURN access; END IF;
 IF coalesce(p->>'pdf_sha256','') !~ '^[0-9a-f]{64}$' OR jsonb_typeof(p->'report')<>'object' THEN
  RETURN jsonb_build_object('status','INVALID'); END IF;
 INSERT INTO cbm_technician_submissions(ticket_id,approval_cycle,technician_id,pdf_sha256,report)
 VALUES((access->>'ticketId')::int,access->>'approvalCycle',(access->>'technicianId')::int,p->>'pdf_sha256',p->'report')
 ON CONFLICT(ticket_id,approval_cycle) DO NOTHING RETURNING id INTO sid;
 IF sid IS NULL THEN RETURN jsonb_build_object('status','UNCONFIRMED'); END IF;
 RETURN jsonb_build_object('status','UPLOAD','ticketId',access->'ticketId','submissionId',sid);
END $_$;


--
-- Name: cbm_create_ticket(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_create_ticket(p_request jsonb) RETURNS TABLE(ticket_id integer)
    LANGUAGE plpgsql
    AS $_$
BEGIN
 LOCK TABLE tickets IN SHARE ROW EXCLUSIVE MODE;
 RETURN QUERY WITH prior AS MATERIALIZED (
 SELECT t.id FROM tickets t WHERE
 (NULLIF($1::jsonb->>'ticketId','') IS NOT NULL AND t.id=(NULLIF($1::jsonb->>'ticketId',''))::int)
 OR
 EXISTS (SELECT 1 FROM ticket_events e WHERE e.ticket_id=t.id AND e.event='CBM_SOURCE'
         AND e.payload->>'source_key'=$1::jsonb->>'sourceKey')
 OR (t.ifc_global_id=$1::jsonb#>>'{triage,element,global_id}' AND t.status NOT IN ('CLOSED','DUPLICATE','REJECTED'))
 ORDER BY t.id LIMIT 1
), inserted AS (
 INSERT INTO tickets(status,reporter_email,photo_before_url,map_code,pos_x,pos_y,pos_z,vps_confidence,
 ifc_global_id,ifc_class,ifc_name,ifc_storey,category,severity,description,required_skill)
 SELECT 'LOCALIZED',p#>>'{triage,reporterEmail}',p#>>'{triage,photoUrl}',p#>>'{triage,mapCode}',
 (p#>>'{triage,position,x}')::float8,(p#>>'{triage,position,y}')::float8,(p#>>'{triage,position,z}')::float8,
 (p#>>'{triage,confidence}')::float8,p#>>'{triage,element,global_id}',p#>>'{triage,element,ifc_class}',
 p#>>'{triage,element,name}',p#>>'{triage,element,storey}',p#>>'{triage,category}',(p#>>'{triage,severity}')::int,
 p#>>'{triage,description}',p#>>'{triage,required_skill}'
 FROM (SELECT $1::jsonb AS p) a WHERE NOT EXISTS (SELECT 1 FROM prior)
 AND p#>>'{triage,element,global_id}' IS NOT NULL AND p->>'sourceKey' IS NOT NULL
 RETURNING id
), selected AS (SELECT id FROM inserted UNION ALL SELECT id FROM prior), source AS (
 INSERT INTO ticket_events(ticket_id,event,payload)
 SELECT id,'CBM_SOURCE',jsonb_build_object('source_key',$1::jsonb->>'sourceKey') FROM selected
 WHERE $1::jsonb->>'sourceKey' IS NOT NULL
 AND NOT EXISTS(SELECT 1 FROM ticket_events WHERE event='CBM_SOURCE' AND payload->>'source_key'=$1::jsonb->>'sourceKey')
 RETURNING id
)
SELECT id AS ticket_id FROM selected;
END $_$;


--
-- Name: cbm_dispatch_context(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_dispatch_context(p_request jsonb) RETURNS jsonb
    LANGUAGE sql
    AS $$
SELECT context FROM (WITH loaded AS (WITH target AS (
 SELECT t.* FROM tickets t WHERE
 (NULLIF(p_request::jsonb->>'ticketId','') IS NOT NULL AND t.id=(NULLIF(p_request::jsonb->>'ticketId',''))::int)
 OR (NULLIF(p_request::jsonb->>'ticketId','') IS NULL AND EXISTS (
 SELECT 1 FROM ticket_events s WHERE s.ticket_id=t.id AND s.event='CBM_SOURCE' AND s.payload->>'source_key'=p_request::jsonb->>'sourceKey'))
 ORDER BY t.id LIMIT 1
)
SELECT jsonb_build_object('now',clock_timestamp(),'nonce',gen_random_uuid()::text,
 'token',replace(gen_random_uuid()::text || gen_random_uuid()::text,'-',''),
 'ticket',(SELECT to_jsonb(t) FROM target t),
 'revision',(SELECT updated_at::text FROM target),
 'init_failures',(SELECT count(*) FROM ticket_events WHERE ticket_id=(SELECT id FROM target) AND event='CBM_INIT_FAILURE'),
 'state',(SELECT payload FROM ticket_events WHERE ticket_id=(SELECT id FROM target)
          AND event='CBM_DISPATCH_STATE' ORDER BY id DESC LIMIT 1),
 'inbox',COALESCE((SELECT jsonb_agg(to_jsonb(e) ORDER BY e.id) FROM ticket_events e
          WHERE e.ticket_id=(SELECT id FROM target) AND e.event='CBM_RESPONSE'), '[]'::jsonb),
 'candidates',COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.open_jobs,c.last_assigned_at ASC NULLS FIRST,c.rating DESC,c.technician_id)
 FROM (SELECT x.id AS technician_id,x.full_name,x.email,x.rating,x.last_assigned_at,
 (SELECT count(*) FROM tickets j WHERE j.technician_id=x.id AND j.status IN ('ASSIGNED','WORK_DONE','PENDING_APPROVAL','REWORK')) AS open_jobs
 FROM technicians x WHERE x.active=TRUE AND x.zone='building-A'
 AND (SELECT required_skill FROM target)=ANY(x.skills)) c),'[]'::jsonb)
) AS context), base AS (
 SELECT context AS raw, context->'ticket' AS t, NULLIF(context->'state','null'::jsonb) AS s,
 (context->>'now')::timestamptz AS instant FROM loaded
), summary AS (
 SELECT *,
 COALESCE((t->>'severity')::int>=4,FALSE) AS urgent,
 (SELECT count(*) FROM jsonb_array_elements(COALESCE(s->'offers','[]')) o WHERE o->>'status' IN ('SENDING','LIVE','UNCERTAIN')) AS active_count,
 (SELECT count(*) FROM jsonb_array_elements(COALESCE(s->'offers','[]')) o WHERE o->>'status' IN ('SENDING','LIVE','UNCERTAIN') AND (o->>'expires_at')::timestamptz<=instant) AS expired_count,
 (SELECT count(*) FROM jsonb_array_elements(raw->'inbox') e WHERE (e->>'id')::bigint>COALESCE((s->>'response_cursor')::bigint,0)) AS response_count,
 COALESCE((s->>'halted')::boolean,FALSE) OR EXISTS (
 SELECT 1 FROM jsonb_each(COALESCE(s->'messages','{}')) m WHERE m.value->>'status'='UNCERTAIN'
 OR (m.value->>'status'='SENDING' AND (m.value->>'claimed_at')::timestamptz+interval '5 minutes'<instant)) AS needs_operator,
 COALESCE((SELECT jsonb_agg(id ORDER BY ord) FROM jsonb_array_elements(COALESCE(s->'shortlist','[]')) WITH ORDINALITY AS ids(id,ord)
 WHERE NOT EXISTS(SELECT 1 FROM jsonb_array_elements(COALESCE(s->'offers','[]')) o WHERE o->'technician_id'=id)
 AND EXISTS(SELECT 1 FROM jsonb_array_elements(raw->'candidates') c WHERE c->'technician_id'=id)),'[]') AS available,
 EXISTS(SELECT 1 FROM jsonb_each(COALESCE(s->'messages','{}')) m WHERE m.value->>'status'='PENDING'
 AND m.key NOT LIKE 'offer:%' AND (m.key<>'opening' OR t->>'status' IN ('LOCALIZED','DISPATCHING'))) AS pending_notices
 FROM base
), checked AS (
 SELECT *, response_count>0 OR expired_count>0 OR pending_notices OR (
 t->>'status' IN ('LOCALIZED','DISPATCHING') AND s#>>'{messages,opening,status}'='SENT' AND (
 (active_count<CASE WHEN urgent THEN 2 ELSE 1 END AND jsonb_array_length(available)>0 AND (NOT urgent OR instant<(s->>'urgent_start')::timestamptz))
 OR (active_count=0 AND (jsonb_array_length(available)=0 OR (urgent AND instant>=(s->>'urgent_start')::timestamptz))))) AS unfinished
 FROM summary
)
SELECT jsonb_build_object(
 'ticket',jsonb_build_object('id',t->'id','created_at',t->'created_at','updated_at',t->'updated_at',
 'description',left(t->>'description',1600),'description_truncated',length(t->>'description')>1600,
 'category',t->'category','severity',t->'severity','required_skill',t->'required_skill',
 'asset',jsonb_build_object('ifc_global_id',t->'ifc_global_id','name',t->'ifc_name','class',t->'ifc_class','storey',t->'ifc_storey')),
 'authorization',jsonb_build_object('required',coalesce((t->>'requires_dispatch_authorization')::boolean,false),
 'approved',t->>'dispatch_authorized_at' IS NOT NULL,'approved_at',t->'dispatch_authorized_at'),
 'portfolio',public.cbm_dispatch_portfolio(greatest(0,least(coalesce((p_request::jsonb->>'overviewPage')::integer,0),100000))),
 'ticket_id',t->'id','initialized',s IS NOT NULL,'status',t->>'status',
 'outcome',CASE WHEN t IS NULL OR t='null'::jsonb THEN 'NO_TICKET'
 WHEN t->>'status' IN ('CLOSED','REJECTED','DUPLICATE') THEN t->>'status'
 WHEN t->>'status'='PENDING_AUTHORIZATION' OR (coalesce((t->>'requires_dispatch_authorization')::boolean,false) AND t->>'dispatch_authorized_at' IS NULL) THEN 'AWAITING_FM_AUTHORIZATION'
 WHEN needs_operator OR (s IS NULL AND (raw->>'init_failures')::int>=3) THEN 'OPERATOR_ACTION_REQUIRED'
 WHEN s IS NULL AND t->>'status'='LOCALIZED' THEN 'UNINITIALIZED' WHEN s IS NULL THEN t->>'status' WHEN unfinished THEN 'ACTION_REQUIRED'
 WHEN t->>'status'='ASSIGNED' THEN 'ASSIGNED' WHEN t->>'status'='ESCALATED' THEN 'ESCALATED' ELSE 'WAITING' END,
 'now',instant,'urgent',urgent,'max_live_offers',CASE WHEN urgent THEN 2 ELSE 1 END,
 'active_offer_count',active_count,'expired_offer_count',expired_count,'pending_response_count',response_count,
 'urgent_start',s->'urgent_start','original_date',s->'original_date','opening_status',s#>>'{messages,opening,status}',
 'available_candidate_ids',available,'next_wake',s->'next_wake',
 'error',CASE WHEN needs_operator THEN COALESCE(s->>'error','Delivery receipt missing or uncertain. Operator reconciliation required.') ELSE NULL END,
 'offers',COALESCE((SELECT jsonb_agg(jsonb_build_object('id',o->'id','technician_id',o->'technician_id','full_name',o->'full_name',
 'date',o->'date','slot',o->'slot','status',o->'status','reserved_at',o->'reserved_at','sent_at',o->'sent_at','expires_at',o->'expires_at',
 'knowledge',jsonb_build_object('status',coalesce(o#>>'{technical_knowledge,status}','UNAVAILABLE'),
 'chunk_ids',coalesce((SELECT jsonb_agg(d#>'{metadata,chunk_id}') FROM jsonb_array_elements(coalesce(o#>'{technical_knowledge,chunks}','[]')) d),'[]'::jsonb)))) FROM jsonb_array_elements(COALESCE(s->'offers','[]')) o),'[]'),
 'notices',COALESCE((SELECT jsonb_agg(jsonb_build_object('key',m.key,'status',m.value->>'status','message_id',m.value->'message_id')) FROM jsonb_each(COALESCE(s->'messages','{}')) m),'[]'),
 'candidates',COALESCE((SELECT jsonb_agg(jsonb_build_object('technician_id',id,'eligible',c IS NOT NULL,
 'full_name',c->'full_name','open_jobs',c->'open_jobs','last_assigned_at',c->'last_assigned_at','rating',c->'rating') ORDER BY ord)
 FROM jsonb_array_elements(COALESCE(s->'shortlist','[]')) WITH ORDINALITY ids(id,ord)
 LEFT JOIN LATERAL (SELECT value AS c FROM jsonb_array_elements(raw->'candidates') WHERE value->'technician_id'=id) found ON TRUE),'[]')
) AS context FROM checked) facts;
$$;


--
-- Name: cbm_dispatch_portfolio(integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_dispatch_portfolio(p_page integer DEFAULT 0) RETURNS jsonb
    LANGUAGE sql STABLE
    AS $$
 WITH selected AS (
  SELECT ticket_id,created_at,status,severity,required_skill,ifc_name,issue_summary,
   dispatch_authorized_at,operator_required,attention_required,responsibility,actionable,last_selected_at
  FROM cbm_dispatch_queue_items ORDER BY created_at DESC,ticket_id DESC
  LIMIT 5 OFFSET greatest(0,least(coalesce(p_page,0),100000))*5
 ), totals AS (
  SELECT count(*) AS total,count(*) FILTER(WHERE actionable) AS actionable,
   count(*) FILTER(WHERE operator_required) AS operator_required,
   count(*) FILTER(WHERE attention_required) AS attention_required FROM cbm_dispatch_queue_items
 ) SELECT jsonb_build_object('scope','ALL_NON_CLOSED_TICKETS','excluded_statuses',jsonb_build_array('CLOSED'),
  'total',total,'actionable',actionable,'operator_required',operator_required,'attention_required',attention_required,
  'page',greatest(0,least(coalesce(p_page,0),100000)),'page_size',5,
  'has_more',total>(greatest(0,least(coalesce(p_page,0),100000))+1)*5,
  'status_counts',coalesce((SELECT jsonb_object_agg(status,n) FROM
    (SELECT status,count(*) n FROM cbm_dispatch_queue_items GROUP BY status) c),'{}'::jsonb),
  'tickets',coalesce((SELECT jsonb_agg(to_jsonb(s) ORDER BY created_at DESC,ticket_id DESC) FROM selected s),'[]'::jsonb))
 FROM totals;
$$;


--
-- Name: cbm_finish_dispatch_work(integer, uuid, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_finish_dispatch_work(p_ticket integer, p_token uuid, p_execution text, p_outcome text) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE changed integer;
BEGIN
 UPDATE cbm_dispatch_queue_visits SET lease_token=NULL,lease_until=NULL,last_finished_at=clock_timestamp(),
  last_outcome=left(p_outcome,100)
 WHERE ticket_id=p_ticket AND lease_token=p_token AND worker_execution_id=p_execution
 RETURNING ticket_id INTO changed;
 RETURN jsonb_build_object('ticket_id',p_ticket,'released',changed IS NOT NULL,'outcome',p_outcome);
END $$;


--
-- Name: cbm_guard_dispatch_authorization(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_guard_dispatch_authorization() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF TG_OP='INSERT' AND NEW.requires_dispatch_authorization AND NEW.status='LOCALIZED' THEN
  NEW.status:='PENDING_AUTHORIZATION';
 END IF;
 IF TG_OP='INSERT' AND NEW.requires_dispatch_authorization AND NEW.status='PENDING_AUTHORIZATION' THEN
  NEW.dispatch_authorization_id:=gen_random_uuid();
  NEW.dispatch_authorization_token:=replace(gen_random_uuid()::text||gen_random_uuid()::text,'-','');
  NEW.dispatch_authorization_expires_at:=clock_timestamp()+interval '72 hours';
  NEW.dispatch_authorized_at:=NULL;
 END IF;
 IF TG_OP='UPDATE' AND OLD.requires_dispatch_authorization AND NOT NEW.requires_dispatch_authorization THEN
  RAISE EXCEPTION 'Dispatch authorization cannot be disabled for a new ticket';
 END IF;
 IF NEW.requires_dispatch_authorization AND NEW.status IN
 ('LOCALIZED','DISPATCHING','ASSIGNED','ESCALATED','WORK_DONE','PENDING_APPROVAL','REWORK','CLOSED')
 AND NEW.dispatch_authorized_at IS NULL THEN
  RAISE EXCEPTION 'FM authorization is required before dispatch';
 END IF;
 RETURN NEW;
END $$;


SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: cbm_intake_outbox; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cbm_intake_outbox (
    id bigint NOT NULL,
    event_key text NOT NULL,
    kind text NOT NULL,
    reporter_email text,
    payload jsonb NOT NULL,
    status text DEFAULT 'PENDING'::text NOT NULL,
    claim uuid,
    claimed_at timestamp with time zone,
    message_id text,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    sent_at timestamp with time zone,
    CONSTRAINT cbm_intake_outbox_kind_check CHECK ((kind = ANY (ARRAY['RETRY'::text, 'BUG_RECEIPT'::text, 'IT_BUG'::text, 'AUTHORIZATION'::text, 'RECEIVED'::text, 'DUPLICATE'::text, 'REJECTED'::text, 'BUSY'::text, 'FINISHED'::text, 'CONFIGURATION_IT'::text]))),
    CONSTRAINT cbm_intake_outbox_status_check CHECK ((status = ANY (ARRAY['PENDING'::text, 'SENDING'::text, 'SENT'::text, 'UNCERTAIN'::text, 'CANCELLED'::text])))
);


--
-- Name: cbm_intake_claim_notice(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_intake_claim_notice() RETURNS SETOF public.cbm_intake_outbox
    LANGUAGE sql
    AS $$
 UPDATE cbm_intake_outbox SET status='SENDING',claim=gen_random_uuid(),claimed_at=clock_timestamp()
 WHERE id=(SELECT id FROM cbm_intake_outbox WHERE status='PENDING'
 ORDER BY CASE
  WHEN kind IN ('IT_BUG','CONFIGURATION_IT') THEN 0
  WHEN kind IN ('REJECTED','RECEIVED','DUPLICATE') THEN 1
  WHEN kind='AUTHORIZATION' THEN 2
  ELSE 3 END,id
 LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING *;
$$;


--
-- Name: cbm_intake_recover(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_intake_recover() RETURNS integer
    LANGUAGE plpgsql
    AS $$
DECLARE a record; t tickets; n integer:=0; added integer:=0;
BEGIN
 FOR a IN SELECT report_id,file_id FROM cbm_capture_attempts WHERE status='PROCESSING'
 AND started_at<clock_timestamp()-interval '10 minutes' ORDER BY started_at LIMIT 20 LOOP
  PERFORM cbm_capture_failed(a.report_id,a.file_id,'CAPTURE_TIMEOUT'); n:=n+1;
 END LOOP;
 -- Lost Gmail receipts are uncertain; never automatically send the same message twice.
 UPDATE cbm_intake_outbox SET status='UNCERTAIN' WHERE status='SENDING' AND claimed_at<clock_timestamp()-interval '5 minutes';
 -- If the request is still pending, send the FM one reminder with the same valid approval link.
 INSERT INTO cbm_intake_outbox(event_key,kind,payload)
 SELECT 'authorize-reminder:'||pending_ticket.dispatch_authorization_id,'AUTHORIZATION',
  jsonb_build_object('ticket_id',pending_ticket.id,'authorization_id',pending_ticket.dispatch_authorization_id,
   'token',pending_ticket.dispatch_authorization_token,'ifc_global_id',pending_ticket.ifc_global_id,'ifc_name',pending_ticket.ifc_name,
   'description',pending_ticket.description,'severity',pending_ticket.severity,'photo_url',pending_ticket.photo_before_url,
   'expires_at',pending_ticket.dispatch_authorization_expires_at,'reminder',true)
 FROM tickets pending_ticket
 JOIN cbm_intake_outbox original ON original.event_key='authorize:'||pending_ticket.dispatch_authorization_id
  AND original.status='SENT' AND original.sent_at<=clock_timestamp()-interval '24 hours'
 WHERE pending_ticket.status='PENDING_AUTHORIZATION' AND pending_ticket.requires_dispatch_authorization
  AND pending_ticket.dispatch_authorization_expires_at>clock_timestamp()
 ON CONFLICT (event_key) DO NOTHING;
 GET DIAGNOSTICS added = ROW_COUNT;
 n:=n+added;
 -- Renew only expired business-approval links. Timeout is neither approval nor rejection.
 FOR t IN SELECT * FROM tickets WHERE status='PENDING_AUTHORIZATION' AND requires_dispatch_authorization
 AND dispatch_authorization_expires_at<clock_timestamp()
 ORDER BY id LIMIT 20 FOR UPDATE SKIP LOCKED LOOP
  UPDATE cbm_intake_outbox SET status='CANCELLED' WHERE event_key='authorize:'||t.dispatch_authorization_id AND status='PENDING';
  UPDATE tickets SET dispatch_authorization_id=gen_random_uuid(),
   dispatch_authorization_token=replace(gen_random_uuid()::text||gen_random_uuid()::text,'-',''),
   dispatch_authorization_expires_at=clock_timestamp()+interval '72 hours',updated_at=clock_timestamp()
   WHERE id=t.id RETURNING * INTO t;
  INSERT INTO cbm_intake_outbox(event_key,kind,payload) VALUES('authorize:'||t.dispatch_authorization_id,'AUTHORIZATION',
   jsonb_build_object('ticket_id',t.id,'authorization_id',t.dispatch_authorization_id,'token',t.dispatch_authorization_token,
    'ifc_global_id',t.ifc_global_id,'ifc_name',t.ifc_name,'description',t.description,'severity',t.severity,
    'photo_url',t.photo_before_url,'expires_at',t.dispatch_authorization_expires_at));
 END LOOP;
 RETURN n;
END $$;


--
-- Name: cbm_issue_technician_report_link(integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_issue_technician_report_link(p_ticket integer) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE t tickets%ROWTYPE; s jsonb; o jsonb; i integer; token text;
BEGIN
 SELECT * INTO t FROM tickets WHERE id=p_ticket FOR UPDATE;
 IF NOT FOUND OR t.status NOT IN ('ASSIGNED','REWORK') THEN RETURN NULL; END IF;
 SELECT payload INTO s FROM ticket_events WHERE ticket_id=t.id AND event='CBM_DISPATCH_STATE' ORDER BY id DESC LIMIT 1;
 SELECT value,ordinality::int-1 INTO o,i FROM jsonb_array_elements(s->'offers') WITH ORDINALITY
 WHERE value->>'status'='ACCEPTED' AND (value->>'technician_id')::int=t.technician_id LIMIT 1;
 IF o IS NULL THEN RETURN NULL; END IF;
 token=o->>'report_token';
 IF token IS NULL OR coalesce((o->>'report_expires_at')::timestamptz,'epoch')<=clock_timestamp() THEN
  token=replace(gen_random_uuid()::text||gen_random_uuid()::text,'-','');
  o=o||jsonb_build_object('report_token',token,'report_expires_at',clock_timestamp()+interval '30 days');
  s=jsonb_set(s,ARRAY['offers',i::text],o);
  INSERT INTO ticket_events(ticket_id,event,payload) VALUES(t.id,'CBM_DISPATCH_STATE',s);
  UPDATE tickets SET updated_at=GREATEST(clock_timestamp(),updated_at+interval '1 microsecond') WHERE id=t.id;
 END IF;
 RETURN jsonb_build_object('ticketId',t.id,'token',token,'expires_at',o->>'report_expires_at');
END $$;


--
-- Name: cbm_log_status_change(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_log_status_change() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    INSERT INTO ticket_events (ticket_id, event, payload)
    VALUES (NEW.id, 'CBM_STATUS_CHANGED',
            jsonb_build_object('from', OLD.status,
                               'to', NEW.status,
                               'technician_id', NEW.technician_id));
  END IF;
  RETURN NULL;   -- AFTER trigger: the return value is ignored
END;
$$;


--
-- Name: cbm_record_offer_response(integer, text, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_record_offer_response(p_ticket integer, p_offer text, p_token text, p_decision text) RETURNS TABLE(response_result jsonb)
    LANGUAGE plpgsql
    AS $_$
BEGIN
 PERFORM id FROM tickets WHERE id=p_ticket FOR UPDATE;
 RETURN QUERY WITH valid AS MATERIALIZED (
 SELECT t.id FROM tickets t JOIN LATERAL (
 SELECT payload FROM ticket_events WHERE ticket_id=t.id AND event='CBM_DISPATCH_STATE' ORDER BY id DESC LIMIT 1
 ) s ON TRUE CROSS JOIN LATERAL jsonb_array_elements(s.payload->'offers') o
 WHERE t.id=$1::int AND t.status='DISPATCHING' AND o->>'id'=$2 AND o->>'token'=$3
 AND o->>'status' IN ('SENDING','LIVE','UNCERTAIN') AND clock_timestamp()<(o->>'expires_at')::timestamptz
 AND $4 IN ('accept','deny')
), recorded AS (
 INSERT INTO ticket_events(ticket_id,event,payload)
 SELECT id,'CBM_RESPONSE',jsonb_build_object('offer_id',$2::text,'decision',$4::text) FROM valid
 WHERE NOT EXISTS (SELECT 1 FROM ticket_events e WHERE e.ticket_id=valid.id AND e.event='CBM_RESPONSE' AND e.payload->>'offer_id'=$2)
 RETURNING id
), invalidate_snapshot AS (
 UPDATE tickets SET updated_at=GREATEST(clock_timestamp(),updated_at+interval '1 microsecond')
 WHERE id=$1::int AND EXISTS(SELECT 1 FROM recorded) RETURNING id
)
SELECT jsonb_build_object('recorded',EXISTS(SELECT 1 FROM recorded),'ticket_id',$1::int) AS response_result;
END $_$;


--
-- Name: cbm_record_technician_report(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_record_technician_report(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $_$
DECLARE r cbm_technician_submissions%ROWTYPE; fid text=nullif(p->>'fileId','');
BEGIN
 SELECT * INTO r FROM cbm_technician_submissions WHERE id=(p->>'submissionId')::uuid FOR UPDATE;
 IF NOT FOUND THEN RETURN jsonb_build_object('status','UNAVAILABLE'); END IF;
 IF r.status='SUBMITTED' THEN RETURN jsonb_build_object('status','SUBMITTED','ticketId',r.ticket_id,'submissionId',r.id); END IF;
 IF fid IS NOT NULL AND fid !~ '^[A-Za-z0-9_-]{1,200}$' THEN fid=NULL; END IF;
 UPDATE cbm_technician_submissions SET status=CASE WHEN fid IS NULL THEN 'UNCONFIRMED' ELSE 'SUBMITTED' END,
 drive_file_id=fid,submitted_at=CASE WHEN fid IS NOT NULL THEN clock_timestamp() END WHERE id=r.id;
 INSERT INTO ticket_events(ticket_id,event,payload) VALUES(r.ticket_id,'CBM_TECHNICIAN_REPORT',
  jsonb_build_object('submission_id',r.id,'drive_file_id',fid,'status',CASE WHEN fid IS NULL THEN 'UNCONFIRMED' ELSE 'SUBMITTED' END));
 RETURN jsonb_build_object('status',CASE WHEN fid IS NULL THEN 'UNCONFIRMED' ELSE 'SUBMITTED' END,'ticketId',r.ticket_id,'submissionId',r.id);
END $_$;


--
-- Name: cbm_start_dispatch_work(integer, uuid, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_start_dispatch_work(p_ticket integer, p_token uuid, p_execution text) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE changed integer;
BEGIN
 UPDATE cbm_dispatch_queue_visits SET worker_execution_id=p_execution,lease_until=clock_timestamp()+interval '65 minutes'
 WHERE ticket_id=p_ticket AND lease_token=p_token AND lease_until>clock_timestamp()
 AND worker_execution_id IS NULL RETURNING ticket_id INTO changed;
 RETURN jsonb_build_object('accepted',changed IS NOT NULL,'ticketId',changed);
END $$;


--
-- Name: cbm_technician_report_access(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_technician_report_access(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $_$
DECLARE t tickets%ROWTYPE; s jsonb; o jsonb; tech technicians%ROWTYPE; submission cbm_technician_submissions%ROWTYPE;
BEGIN
 IF coalesce(p->>'ticketId','') !~ '^[1-9][0-9]{0,9}$' OR coalesce(p->>'token','') !~ '^[0-9a-f]{64}$'
  OR (p->>'ticketId')::bigint>2147483647 THEN RETURN jsonb_build_object('status','UNAVAILABLE'); END IF;
 SELECT * INTO t FROM tickets WHERE id=(p->>'ticketId')::int;
 IF NOT FOUND THEN RETURN jsonb_build_object('status','UNAVAILABLE'); END IF;
 SELECT payload INTO s FROM ticket_events WHERE ticket_id=t.id AND event='CBM_DISPATCH_STATE' ORDER BY id DESC LIMIT 1;
 SELECT value INTO o FROM jsonb_array_elements(s->'offers') WHERE value->>'status'='ACCEPTED'
  AND (value->>'technician_id')::int=t.technician_id AND value->>'report_token'=p->>'token'
  AND (value->>'report_expires_at')::timestamptz>now() LIMIT 1;
 IF o IS NULL THEN RETURN jsonb_build_object('status','UNAVAILABLE'); END IF;
 SELECT * INTO submission FROM cbm_technician_submissions WHERE ticket_id=t.id AND approval_cycle=coalesce(t.approval_id::text,'initial');
 IF FOUND THEN RETURN jsonb_build_object('status',CASE WHEN submission.status='SUBMITTED' THEN 'SUBMITTED' ELSE 'UNCONFIRMED' END,'ticketId',t.id,'submissionId',submission.id); END IF;
 IF t.status NOT IN ('ASSIGNED','REWORK') THEN RETURN jsonb_build_object('status','NOT_OPEN','ticketId',t.id); END IF;
 SELECT * INTO tech FROM technicians WHERE id=t.technician_id;
 RETURN jsonb_build_object('status','OK','ticketId',t.id,'technicianId',tech.id,'approvalCycle',coalesce(t.approval_id::text,'initial'),
  'fields',jsonb_build_object('ticket_id',t.id,'technician_name',tech.full_name,'technician_email',tech.email,
  'asset_name',coalesce(nullif(t.ifc_name,''),t.ifc_global_id),'location',coalesce(t.ifc_storey,''),'reported_issue',t.description));
END $_$;


--
-- Name: cbm_touch_updated_at(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_touch_updated_at() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    NEW.updated_at := now();
  END IF;
  RETURN NEW;
END;
$$;


--
-- Name: cbm_wf2_claim_notice(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf2_claim_notice(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $_$
DECLARE t tickets%ROWTYPE; nkey text; recipient text; claim_id uuid; claim_row integer;
BEGIN
 SELECT * INTO t FROM tickets WHERE id=(p->>'ticketId')::int FOR UPDATE;
 IF NOT FOUND OR t.approval_id IS DISTINCT FROM (p->>'approvalId')::uuid THEN
  RETURN jsonb_build_object('send_status','BLOCKED','reason','Missing ticket or stale approval');
 END IF;
 IF NOT EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF2_APPROVAL'
  AND payload->>'approval_id'=t.approval_id::text AND payload->>'decision'=p->>'decision') THEN
  RETURN jsonb_build_object('send_status','BLOCKED','reason','No matching FM decision');
 END IF;
 IF p->>'decision'='APPROVED' THEN
  IF t.status<>'CLOSED' OR NOT cbm_wf2_ifc_succeeded(t.id,t.approval_id) THEN
   RETURN jsonb_build_object('send_status','BLOCKED','reason','Ticket must be closed with successful IFC update before a closure email');
  END IF;
  nkey=CASE p->>'noticeRecipient' WHEN 'fm' THEN 'fm:closed' WHEN 'technician' THEN 'technician:closed' END;
 ELSIF p->>'decision'='REJECTED' AND t.status='REWORK' AND p->>'noticeRecipient'='technician' THEN
  nkey='technician:rework';
 END IF;
 IF nkey IS NULL THEN RETURN jsonb_build_object('send_status','BLOCKED','reason','Invalid notification for current decision/state'); END IF;
 IF p->>'noticeRecipient'='fm' THEN recipient=p->>'fmEmail';
 ELSE SELECT email INTO recipient FROM technicians WHERE id=t.technician_id; END IF;
 IF recipient IS NULL OR recipient !~ '^[^[:space:]<>@]+@[^[:space:]<>@]+\.[^[:space:]<>@]+$' THEN
  RETURN jsonb_build_object('send_status','BLOCKED','reason','Recipient is missing or invalid');
 END IF;
 IF cbm_wf2_notice_sent(t.id,t.approval_id,nkey,recipient) THEN
  RETURN jsonb_build_object('send_status','SENT','notice_key',nkey,'already_sent',true);
 END IF;
 -- Old claims without verified receipts need reconciliation, never a blind resend.
 IF nkey='fm:closed' AND EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=t.id
  AND event='CBM_WF2_FM_NOTICE_CLAIM' AND payload->>'approval_id'=t.approval_id::text) THEN
  RETURN jsonb_build_object('send_status','UNCONFIRMED','reason','Legacy email claim requires reconciliation');
 END IF;
 claim_id=gen_random_uuid();
 INSERT INTO ticket_events(ticket_id,event,payload) VALUES(t.id,'CBM_WF2_NOTICE_CLAIM',
  jsonb_build_object('approval_id',t.approval_id,'notice_key',nkey,'claim_id',claim_id,'recipient',recipient))
 ON CONFLICT DO NOTHING RETURNING id INTO claim_row;
 IF claim_row IS NULL THEN RETURN jsonb_build_object('send_status','UNCONFIRMED','notice_key',nkey,'reason','Existing send claim has no verified receipt; inspect the Gmail execution before retrying'); END IF;
 RETURN jsonb_build_object('send_status','SEND','ticket_id',t.id,'approval_id',t.approval_id,
  'notice_key',nkey,'claim_id',claim_id,'recipient',recipient,'ifc_new_version',t.ifc_new_version,
  'reason',coalesce(t.fm_reject_reason,'The facility manager rejected this completion. Contact the FM for details.'));
END $_$;


--
-- Name: cbm_wf2_close_ticket(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf2_close_ticket(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE t tickets%ROWTYPE;
BEGIN
 SELECT * INTO t FROM tickets WHERE id=(p->>'ticketId')::int FOR UPDATE;
 IF NOT FOUND OR t.approval_id IS DISTINCT FROM (p->>'approvalId')::uuid THEN
  RETURN jsonb_build_object('status','BLOCKED','reason','Missing ticket or stale approval');
 END IF;
 IF NOT EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF2_APPROVAL'
  AND payload->>'approval_id'=t.approval_id::text AND payload->>'decision'='APPROVED')
  OR NOT cbm_wf2_ifc_succeeded(t.id,t.approval_id) THEN
  RETURN jsonb_build_object('status','BLOCKED','reason','Successful IFC update and current FM approval are required','ticket_status',t.status);
 END IF;
 IF t.status='CLOSED' THEN RETURN jsonb_build_object('status','CLOSED','changed',false,'version_file',t.ifc_new_version); END IF;
 IF t.status<>'PENDING_APPROVAL' THEN RETURN jsonb_build_object('status','BLOCKED','reason','Ticket is not pending closure'); END IF;
 UPDATE tickets SET status='CLOSED',closed_at=clock_timestamp(),updated_at=clock_timestamp() WHERE id=t.id;
 RETURN jsonb_build_object('status','CLOSED','changed',true,'version_file',t.ifc_new_version);
END $$;


--
-- Name: cbm_wf2_closure_outcome(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf2_closure_outcome(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE t tickets%ROWTYPE; missing text[]='{}'; decision text=p->>'decision'; tech_email text; nkey text;
BEGIN
 SELECT * INTO t FROM tickets WHERE id=(p->>'ticketId')::int;
 IF NOT FOUND THEN RETURN jsonb_build_object('settled',false,'missing_operations',ARRAY['ticket']); END IF;
 IF t.approval_id IS DISTINCT FROM (p->>'approvalId')::uuid THEN missing=array_append(missing,'current_approval'); END IF;
 IF NOT EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF2_APPROVAL'
  AND payload->>'approval_id'=p->>'approvalId' AND payload->>'decision'=decision) THEN missing=array_append(missing,'fm_decision'); END IF;
 SELECT email INTO tech_email FROM technicians WHERE id=t.technician_id;
 IF decision='APPROVED' THEN
  IF t.status<>'CLOSED' OR t.closed_at IS NULL THEN missing=array_append(missing,'close_ticket'); END IF;
  IF NOT cbm_wf2_ifc_succeeded(t.id,(p->>'approvalId')::uuid) THEN missing=array_append(missing,'log_ifc_maintenance'); END IF;
  IF NOT EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF2_STATS'
   AND payload->>'approval_id'=p->>'approvalId') THEN missing=array_append(missing,'update_technician_stats'); END IF;
  IF NOT cbm_wf2_notice_sent(t.id,(p->>'approvalId')::uuid,'fm:closed',p->>'fmEmail') THEN missing=array_append(missing,'notify_fm_closed'); END IF;
  nkey='technician:closed';
 ELSIF decision='REJECTED' THEN
  IF t.status<>'REWORK' OR t.closed_at IS NOT NULL THEN missing=array_append(missing,'reopen_for_rework'); END IF;
  nkey='technician:rework';
 ELSE missing=array_append(missing,'explicit_fm_decision');
 END IF;
 IF nkey IS NOT NULL AND NOT cbm_wf2_notice_sent(t.id,(p->>'approvalId')::uuid,nkey,tech_email) THEN missing=array_append(missing,replace(nkey,':','_')||'_notice'); END IF;
 RETURN jsonb_build_object('id',t.id,'status',t.status,'approval_id',t.approval_id,'closed_at',t.closed_at,
  'ifc_new_version',t.ifc_new_version,'settled',cardinality(missing)=0,'missing_operations',missing);
END $$;


--
-- Name: cbm_wf2_guard_closed(); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf2_guard_closed() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
 IF NEW.status='CLOSED' AND (TG_OP='INSERT' OR OLD.status IS DISTINCT FROM 'CLOSED') THEN
  IF NEW.approval_id IS NULL OR nullif(btrim(NEW.ifc_new_version),'') IS NULL
   OR NOT EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=NEW.id AND event='CBM_WF2_APPROVAL'
      AND payload->>'approval_id'=NEW.approval_id::text AND payload->>'decision'='APPROVED')
   OR NOT EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=NEW.id AND event='CBM_WF2_IFC_RESULT'
      AND payload->>'operation_key'='wf2:'||NEW.id||':'||NEW.approval_id
      AND payload->>'outcome'='SUCCEEDED' AND payload->>'version_file'=NEW.ifc_new_version) THEN
   RAISE EXCEPTION 'Closure requires current FM approval and a successful IFC result matching the recorded version';
  END IF;
 END IF;
 RETURN NEW;
END $$;


--
-- Name: cbm_wf2_ifc_succeeded(integer, uuid); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf2_ifc_succeeded(tid integer, aid uuid) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
 SELECT EXISTS(SELECT 1 FROM tickets t JOIN ticket_events e ON e.ticket_id=t.id
 WHERE t.id=tid AND t.approval_id=aid AND nullif(btrim(t.ifc_new_version),'') IS NOT NULL
 AND e.event='CBM_WF2_IFC_RESULT' AND e.payload->>'outcome'='SUCCEEDED'
 AND e.payload->>'operation_key'='wf2:'||tid||':'||aid
 AND e.payload->>'version_file'=t.ifc_new_version);
$$;


--
-- Name: cbm_wf2_notice_sent(integer, uuid, text, text); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf2_notice_sent(tid integer, aid uuid, nkey text, recipient text) RETURNS boolean
    LANGUAGE sql STABLE
    AS $$
 SELECT EXISTS(SELECT 1 FROM ticket_events e WHERE e.ticket_id=tid AND e.event='CBM_WF2_NOTICE'
 AND e.payload->>'approval_id'=aid::text AND e.payload->>'notice_key'=nkey
 AND e.payload->>'source'='GMAIL_API' AND e.payload->>'outcome'='SENT'
 AND nullif(btrim(e.payload->>'message_id'),'') IS NOT NULL
 AND lower(e.payload->>'recipient')=lower(recipient));
$$;


--
-- Name: cbm_wf2_prepare_review_email(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf2_prepare_review_email(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE t tickets%ROWTYPE; e cbm_wf3_approval_emails%ROWTYPE; prefix text; eid uuid;
BEGIN
 SELECT * INTO t FROM tickets WHERE id=(p->>'ticketId')::int FOR UPDATE;
 IF t.id IS NULL OR t.status<>'PENDING_APPROVAL' OR t.approval_id IS DISTINCT FROM (p->>'approvalId')::uuid
 OR nullif(t.report_file_id,'') IS NULL OR coalesce(p->>'source','')<>'WF2_REPORT' THEN
  RETURN jsonb_build_object('outcome','BLOCKED','reason','No matching completion report awaiting approval'); END IF;
 IF EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF2_APPROVAL' AND payload->>'approval_id'=t.approval_id::text) THEN
  RETURN jsonb_build_object('outcome','ALREADY_DECIDED','reason','Read the stored decision; no new approval email needed'); END IF;
 prefix='wf2-review:'||t.id||':'||t.approval_id||':';
 SELECT * INTO e FROM cbm_wf3_approval_emails WHERE ticket_id=t.id AND approval_id=t.approval_id
  AND left(request_key,length(prefix))=prefix ORDER BY created_at DESC LIMIT 1;
 IF e.id IS NOT NULL AND e.expires_at>clock_timestamp() THEN
  RETURN jsonb_build_object('outcome',CASE WHEN e.send_status='SENT' THEN 'SENT' ELSE 'UNCONFIRMED' END,
   'already_processed',true,'message_id',e.message_id,'expires_at',e.expires_at); END IF;
 IF e.id IS NOT NULL THEN
  INSERT INTO ticket_events(ticket_id,event,payload) VALUES(t.id,'CBM_WF2_APPROVAL_EXPIRED',
   jsonb_build_object('approval_id',t.approval_id,'email_id',e.id,'status',t.status)); END IF;
 eid=gen_random_uuid();
 INSERT INTO cbm_wf3_approval_emails(id,request_key,ticket_id,approval_id)
  VALUES(eid,prefix||eid,t.id,t.approval_id) RETURNING * INTO e;
 INSERT INTO ticket_events(ticket_id,event,payload) VALUES(t.id,'CBM_WF2_APPROVAL_REQUEST',
  jsonb_build_object('approval_id',t.approval_id,'email_id',e.id,'source','WF2_REPORT','execution_id',p->>'executionId'));
 RETURN jsonb_build_object('outcome','SEND','email_id',e.id,'ticketId',t.id,'approvalId',t.approval_id,
  'token',e.token,'expires_at',e.expires_at,'ifc_name',t.ifc_name,'description',left(t.description,1000),
  'report_text',left(t.report_text,5000),'report_file_id',t.report_file_id);
END $$;


--
-- Name: cbm_wf2_record_notice(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf2_record_notice(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE claim jsonb; aid uuid; tid integer; sent boolean;
BEGIN
 tid=(p->>'ticket_id')::int; aid=(p->>'approval_id')::uuid;
 PERFORM 1 FROM tickets WHERE id=tid FOR UPDATE;
 SELECT payload INTO claim FROM ticket_events WHERE ticket_id=tid AND event='CBM_WF2_NOTICE_CLAIM'
  AND payload->>'approval_id'=aid::text AND payload->>'notice_key'=p->>'notice_key'
  AND payload->>'claim_id'=p->>'claim_id';
 IF claim IS NULL THEN RETURN jsonb_build_object('send_status','BLOCKED','reason','Send claim not found'); END IF;
 sent=coalesce(p->>'send_status'='SENT' AND nullif(btrim(p->>'message_id'),'') IS NOT NULL,false);
 INSERT INTO ticket_events(ticket_id,event,payload) VALUES(tid,
  CASE WHEN sent THEN 'CBM_WF2_NOTICE' ELSE 'CBM_WF2_NOTICE_FAILED' END,
  jsonb_build_object('approval_id',aid,'notice_key',claim->>'notice_key','claim_id',claim->>'claim_id',
   'recipient',claim->>'recipient','message_id',CASE WHEN sent THEN p->>'message_id' END,
   'source','GMAIL_API','outcome',CASE WHEN sent THEN 'SENT' ELSE 'UNCONFIRMED' END,'error',p->>'error'))
 ON CONFLICT DO NOTHING;
 RETURN jsonb_build_object('send_status',CASE WHEN sent THEN 'SENT' ELSE 'UNCONFIRMED' END,'notice_key',claim->>'notice_key');
END $$;


--
-- Name: cbm_wf2_review_status(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf2_review_status(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql STABLE
    AS $$
DECLARE t tickets%ROWTYPE; ev ticket_events%ROWTYPE; r jsonb; route text; prefix text;
BEGIN
 SELECT * INTO t FROM tickets WHERE id=(p->>'ticketId')::int;
 IF t.id IS NULL OR t.approval_id IS DISTINCT FROM (p->>'approvalId')::uuid THEN
  RETURN jsonb_build_object('route','SUPERSEDED','reason','The ticket or approval cycle was replaced'); END IF;
 SELECT * INTO ev FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF2_APPROVAL'
  AND payload->>'approval_id'=t.approval_id::text ORDER BY id LIMIT 1;
 IF ev.id IS NOT NULL THEN
  r=cbm_wf2_closure_outcome(jsonb_build_object('ticketId',t.id,'approvalId',t.approval_id,
   'decision',ev.payload->>'decision','fmEmail','fm@example.invalid'));
  IF (r->>'settled')::boolean THEN route='SETTLED';
  ELSIF EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF3_ACTION_RESULT'
   AND payload->>'request_key'=ev.payload->>'request_key') THEN route='DECIDED';
  ELSIF ev.payload->>'request_key' IS NULL THEN route='DECIDED';
  -- A decision taken in the mobile app (cbm_app.fm_decide) has no WF3 execution behind it to wait
  -- for: the record is all there is, and this loop carries out the rest.
  ELSIF ev.payload->>'actor'='FM_APP' THEN route='DECIDED';
  ELSIF ev.created_at<statement_timestamp()-interval '10 minutes' THEN route='ATTENTION';
  ELSE route='PROCESSING'; END IF;
  RETURN r||jsonb_build_object('route',route,'decision',ev.payload->>'decision',
   'rejection_reason',ev.payload->>'reason','decision_source',ev.payload->>'source');
 END IF;
 IF t.status<>'PENDING_APPROVAL' THEN
  RETURN jsonb_build_object('route','SUPERSEDED','status',t.status,'reason','Ticket is no longer awaiting this review'); END IF;
 prefix='wf2-review:'||t.id||':'||t.approval_id||':';
 IF EXISTS(SELECT 1 FROM cbm_wf3_approval_emails WHERE ticket_id=t.id AND approval_id=t.approval_id
  AND left(request_key,length(prefix))=prefix AND expires_at>statement_timestamp()) THEN route='WAIT';
 ELSE route='NEEDS_EMAIL'; END IF;
 RETURN jsonb_build_object('route',route,'id',t.id,'status',t.status,'approval_id',t.approval_id);
END $$;


--
-- Name: cbm_wf3_action_context(integer); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf3_action_context(tid integer) RETURNS jsonb
    LANGUAGE sql STABLE
    AS $$
 SELECT jsonb_build_object('approval_id',CASE WHEN t.status IN ('PENDING_AUTHORIZATION','LOCALIZED','REJECTED')
  THEN t.dispatch_authorization_id ELSE t.approval_id END,
  'expected_updated_at',t.updated_at,'requires_dispatch_authorization',t.requires_dispatch_authorization,
  'completion_decision',(SELECT e.payload->>'decision' FROM ticket_events e WHERE e.ticket_id=t.id
   AND e.event='CBM_WF2_APPROVAL' AND e.payload->>'approval_id'=t.approval_id::text LIMIT 1),
  'ifc_update_succeeded',coalesce(cbm_wf2_ifc_succeeded(t.id,t.approval_id),false),
  'allowed_actions',CASE t.status WHEN 'PENDING_AUTHORIZATION' THEN
   jsonb_build_array('approve_intervention','reject_intervention','resend_approval_email')
   WHEN 'PENDING_APPROVAL' THEN CASE
    WHEN EXISTS(SELECT 1 FROM ticket_events e WHERE e.ticket_id=t.id AND e.event='CBM_WF2_APPROVAL'
     AND e.payload->>'approval_id'=t.approval_id::text AND e.payload->>'decision'='APPROVED') THEN jsonb_build_array('approve_completion')
    WHEN EXISTS(SELECT 1 FROM ticket_events e WHERE e.ticket_id=t.id AND e.event='CBM_WF2_APPROVAL'
     AND e.payload->>'approval_id'=t.approval_id::text AND e.payload->>'decision'='REJECTED') THEN jsonb_build_array('request_rework')
    ELSE jsonb_build_array('approve_completion','request_rework','resend_approval_email') END
   ELSE '[]'::jsonb END)
 FROM tickets t WHERE t.id=tid;
$$;


--
-- Name: cbm_wf3_begin_action(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf3_begin_action(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE t tickets%ROWTYPE; tech technicians%ROWTYPE; a text=p->>'action'; aid uuid;
 k text; previous jsonb; decision text; old_decision text; outbox_id bigint; r jsonb; c jsonb;
BEGIN
 IF a NOT IN ('approve_intervention','reject_intervention','resend_approval_email','approve_completion','request_rework')
 OR a IS NULL OR coalesce(p->>'requestId','')='' OR coalesce(p->>'sessionId','')=''
 -- FM_APP: the same decision taken by an authenticated facility manager in the mobile app
 -- (cbm_app.fm_decide). Identical guards; only the channel recorded on the event differs.
 OR coalesce(p->>'question','')='' OR p->>'actor' NOT IN ('FM_CHAT','FM_EMAIL_LINK','FM_APP')
 OR p->>'actor' IS NULL OR (p->>'truncated')::boolean IS DISTINCT FROM false THEN
  RETURN jsonb_build_object('outcome','BLOCKED','reason','Invalid or truncated FM request'); END IF;
 IF a IN ('reject_intervention','request_rework') AND length(btrim(coalesce(p->>'reason','')))<2 THEN
  RETURN jsonb_build_object('outcome','BLOCKED','reason','The FM must supply a rejection/rework reason'); END IF;
 aid=(p->>'approvalId')::uuid;
 SELECT * INTO t FROM tickets WHERE id=(p->>'ticketId')::int FOR UPDATE;
 IF t.id IS NULL THEN RETURN jsonb_build_object('outcome','BLOCKED','reason','Ticket not found'); END IF;
 k=left(p->>'requestId',150)||':'||a||':'||t.id||':'||coalesce(aid::text,'missing');
 SELECT payload INTO previous FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF3_ACTION_REQUEST' AND payload->>'request_key'=k;
 IF previous IS NOT NULL THEN
  IF previous->>'action' IN ('approve_intervention','reject_intervention') OR previous->>'route'='intake_resend' THEN
   RETURN previous->'result'||jsonb_build_object('already_processed',true,'ticket_status',t.status); END IF;
  -- Continuations still validate current approval and the persisted decision below.
 END IF;
 IF aid IS NULL OR aid IS DISTINCT FROM (CASE WHEN a IN ('approve_intervention','reject_intervention')
   OR (a='resend_approval_email' AND t.status='PENDING_AUTHORIZATION') THEN t.dispatch_authorization_id ELSE t.approval_id END) THEN
  RETURN jsonb_build_object('outcome','BLOCKED','reason','Stale approval: read ticket_lookup again','ticket_status',t.status); END IF;
 IF previous IS NULL AND t.updated_at IS DISTINCT FROM (p->>'expectedUpdatedAt')::timestamptz THEN
  RETURN jsonb_build_object('outcome','BLOCKED','reason','Ticket changed: read ticket_lookup again','ticket_status',t.status); END IF;
 IF a IN ('approve_intervention','reject_intervention') THEN
  IF (a='approve_intervention' AND t.dispatch_authorized_at IS NOT NULL)
    OR (a='reject_intervention' AND t.status='REJECTED' AND t.dispatch_rejected_at IS NOT NULL) THEN
   RETURN jsonb_build_object('outcome','ALREADY_DONE','ticketId',t.id,'ticket_status',t.status); END IF;
  IF t.status<>'PENDING_AUTHORIZATION' OR NOT t.requires_dispatch_authorization THEN
   RETURN jsonb_build_object('outcome','BLOCKED','reason','Ticket is not awaiting initial authorization','ticket_status',t.status); END IF;
  -- The authenticated FM may decide in chat after an email link expires. Renew
  -- that ticket's token before calling the existing authorization function.
  IF t.dispatch_authorization_expires_at IS NULL OR t.dispatch_authorization_expires_at<=clock_timestamp() THEN
   UPDATE tickets SET dispatch_authorization_token=replace(gen_random_uuid()::text||gen_random_uuid()::text,'-',''),
    dispatch_authorization_expires_at=clock_timestamp()+interval '72 hours' WHERE id=t.id RETURNING * INTO t;
  END IF;
  r=cbm_authorize_dispatch(t.id,t.dispatch_authorization_id,t.dispatch_authorization_token,
    CASE a WHEN 'approve_intervention' THEN 'approve' ELSE 'reject' END);
  IF coalesce((r->>'applied')::boolean,false) THEN
   UPDATE ticket_events SET payload=payload||jsonb_build_object('actor',p->>'actor','source','WF3',
    'reason',nullif(p->>'reason',''),'request_key',k) WHERE ticket_id=t.id AND event='CBM_DISPATCH_AUTHORIZATION'
    AND payload->>'authorization_id'=t.dispatch_authorization_id::text;
  END IF;
  SELECT status INTO decision FROM tickets WHERE id=t.id;
  r=jsonb_build_object('outcome',CASE WHEN (r->>'applied')::boolean THEN 'APPLIED' ELSE 'BLOCKED' END,
   'ticketId',t.id,'ticket_status',decision,'request_key',k,'reason',r->>'reason');
 ELSIF a='resend_approval_email' AND t.status='PENDING_AUTHORIZATION' THEN
  IF t.dispatch_authorization_expires_at IS NULL OR t.dispatch_authorization_expires_at<=clock_timestamp() THEN
   UPDATE tickets SET dispatch_authorization_token=replace(gen_random_uuid()::text||gen_random_uuid()::text,'-',''),
    dispatch_authorization_expires_at=clock_timestamp()+interval '72 hours' WHERE id=t.id RETURNING * INTO t;
  END IF;
  -- Reuse the WF1 outbox and its Gmail/receipt handling. Do not claim it was sent.
  INSERT INTO cbm_intake_outbox(event_key,kind,payload) VALUES('wf3-resend:'||k,'AUTHORIZATION',
   jsonb_build_object('ticket_id',t.id,'authorization_id',t.dispatch_authorization_id,'token',t.dispatch_authorization_token,
    'ifc_global_id',t.ifc_global_id,'ifc_name',t.ifc_name,'description',t.description,'severity',t.severity,
    'photo_url',t.photo_before_url,'expires_at',t.dispatch_authorization_expires_at,'reminder',true))
   ON CONFLICT(event_key) DO UPDATE SET event_key=excluded.event_key RETURNING id INTO outbox_id;
  r=jsonb_build_object('outcome','QUEUED','route','intake_resend','ticketId',t.id,'ticket_status',t.status,
   'request_key',k,'outbox_id',outbox_id,'reason','WF1 notification recovery will send this request');
 ELSE
  decision=CASE a WHEN 'approve_completion' THEN 'APPROVED' WHEN 'request_rework' THEN 'REJECTED' END;
  SELECT payload->>'decision' INTO old_decision FROM ticket_events WHERE ticket_id=t.id
   AND event='CBM_WF2_APPROVAL' AND payload->>'approval_id'=aid::text;
  IF a='resend_approval_email' THEN
   IF t.status<>'PENDING_APPROVAL' OR old_decision IS NOT NULL THEN
    RETURN jsonb_build_object('outcome','BLOCKED','reason','There is no undecided completion awaiting approval','ticket_status',t.status); END IF;
  ELSE
   IF t.status<>'PENDING_APPROVAL' AND NOT (a='approve_completion' AND t.status='CLOSED')
     AND NOT(a='request_rework' AND t.status='REWORK') THEN
    RETURN jsonb_build_object('outcome','BLOCKED','reason','Ticket is not awaiting completion approval','ticket_status',t.status); END IF;
   IF old_decision IS NOT NULL AND old_decision<>decision THEN
    RETURN jsonb_build_object('outcome','BLOCKED','reason','An opposite FM decision is already recorded','ticket_status',t.status); END IF;
   IF t.status<>'PENDING_APPROVAL' AND old_decision IS NULL THEN
    RETURN jsonb_build_object('outcome','BLOCKED','reason','Current FM decision is missing'); END IF;
   INSERT INTO ticket_events(ticket_id,event,payload) VALUES(t.id,'CBM_WF2_APPROVAL',
    jsonb_build_object('approval_id',aid,'decision',decision,'actor',p->>'actor','source','WF3',
     'request_key',k,'reason',nullif(p->>'reason',''))) ON CONFLICT DO NOTHING;
   SELECT payload->>'decision' INTO old_decision FROM ticket_events WHERE ticket_id=t.id
    AND event='CBM_WF2_APPROVAL' AND payload->>'approval_id'=aid::text;
   IF old_decision IS DISTINCT FROM decision THEN
    RETURN jsonb_build_object('outcome','BLOCKED','reason','Another approval decision won the race'); END IF;
  END IF;
  SELECT * INTO tech FROM technicians WHERE id=t.technician_id;
  c=jsonb_build_object('ticketId',t.id,'approvalId',aid,'operationKey','wf2:'||t.id||':'||aid,
   'decision',decision,'technicianId',tech.id,'technicianName',tech.full_name,'technicianEmail',tech.email,
   'ifcGlobalId',t.ifc_global_id,'rejectionReason',coalesce((SELECT payload->>'reason' FROM ticket_events
      WHERE ticket_id=t.id AND event='CBM_WF2_APPROVAL' AND payload->>'approval_id'=aid::text),p->>'reason'),
   'request_key',k,'action',a);
  r=jsonb_build_object('outcome','READY','route',CASE a WHEN 'resend_approval_email' THEN 'completion_resend'
    WHEN 'approve_completion' THEN 'completion_approve' ELSE 'completion_rework' END,
   'ticketId',t.id,'ticket_status',t.status,'request_key',k,'context',c);
 END IF;
 INSERT INTO ticket_events(ticket_id,event,payload) VALUES(t.id,'CBM_WF3_ACTION_REQUEST',
  jsonb_build_object('request_key',k,'action',a,'approval_id',aid,'actor',p->>'actor','session',p->>'sessionId',
   'request_id',p->>'requestId','question',left(p->>'question',1500),'reason',left(p->>'reason',2000),
   'route',r->>'route','result',r)) ON CONFLICT DO NOTHING;
 RETURN r;
END $$;


--
-- Name: cbm_wf3_email_access(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf3_email_access(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $_$
DECLARE e cbm_wf3_approval_emails%ROWTYPE; t tickets%ROWTYPE; decision text;
BEGIN
 IF coalesce(p->>'emailId','') !~* '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$'
 OR coalesce(p->>'token','') !~ '^[0-9a-f]{64}$' THEN
  RETURN jsonb_build_object('valid',false,'reason','Invalid approval link'); END IF;
 IF p->>'decision'='reject' AND length(btrim(coalesce(p->>'reason','')))<2 THEN
  RETURN jsonb_build_object('valid',false,'reason','Enter a reason for rework, then submit again'); END IF;
 SELECT * INTO e FROM cbm_wf3_approval_emails WHERE id=(p->>'emailId')::uuid AND token=p->>'token';
 IF e.id IS NULL OR e.expires_at<=clock_timestamp() THEN
  RETURN jsonb_build_object('valid',false,'reason','Invalid or expired approval link'); END IF;
 SELECT * INTO t FROM tickets WHERE id=e.ticket_id;
 IF t.approval_id IS DISTINCT FROM e.approval_id OR t.status<>'PENDING_APPROVAL'
 OR EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF2_APPROVAL' AND payload->>'approval_id'=e.approval_id::text) THEN
  RETURN jsonb_build_object('valid',false,'reason','This approval was already decided or replaced'); END IF;
 IF p->>'decision' IS NOT NULL AND p->>'decision' NOT IN ('approve','reject') THEN
  RETURN jsonb_build_object('valid',false,'reason','Invalid decision'); END IF;
 RETURN jsonb_build_object('valid',true,'ticketId',t.id,'approvalId',t.approval_id,'expectedUpdatedAt',t.updated_at,
  'ifc_name',t.ifc_name,'description',left(t.description,1000),'emailId',e.id,'token',e.token,
  'context',jsonb_build_object('ticketId',t.id,'approvalId',t.approval_id,'expectedUpdatedAt',t.updated_at,
   'action',CASE p->>'decision' WHEN 'approve' THEN 'approve_completion' ELSE 'request_rework' END,
   'reason',left(p->>'reason',2000),'actor','FM_EMAIL_LINK','truncated',false,
   'sessionId','wf3-email:'||e.id,'requestId','wf3-email:'||e.id,
   'question','FM submitted completion decision '||coalesce(p->>'decision','view')||' for ticket #'||t.id));
END $_$;


--
-- Name: cbm_wf3_finish_action(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf3_finish_action(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE t tickets%ROWTYPE; r jsonb; request jsonb;
BEGIN
 SELECT * INTO t FROM tickets WHERE id=(p->>'ticketId')::int FOR UPDATE;
 SELECT payload INTO request FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF3_ACTION_REQUEST'
  AND payload->>'request_key'=p->>'request_key';
 IF request IS NULL THEN RETURN jsonb_build_object('outcome','BLOCKED','reason','Action request was not recorded'); END IF;
 IF p->>'decision' IN ('APPROVED','REJECTED') THEN
  r=cbm_wf2_closure_outcome(p);
  r=r||jsonb_build_object('outcome',CASE WHEN (r->>'settled')::boolean THEN 'APPLIED' ELSE 'INCOMPLETE' END);
 ELSE
  r=coalesce(p->'emailResult','{}'::jsonb)||jsonb_build_object('ticket_status',t.status);
 END IF;
 r=r||jsonb_build_object('ticketId',t.id,'action',request->>'action','request_key',p->>'request_key');
 INSERT INTO ticket_events(ticket_id,event,payload) VALUES(t.id,'CBM_WF3_ACTION_RESULT',r);
 RETURN r;
END $$;


--
-- Name: cbm_wf3_prepare_email(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf3_prepare_email(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE t tickets%ROWTYPE; e cbm_wf3_approval_emails%ROWTYPE;
BEGIN
 SELECT * INTO t FROM tickets WHERE id=(p->>'ticketId')::int FOR UPDATE;
 IF t.id IS NULL OR t.status<>'PENDING_APPROVAL' OR t.approval_id IS DISTINCT FROM (p->>'approvalId')::uuid
 OR EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF2_APPROVAL' AND payload->>'approval_id'=t.approval_id::text)
 OR NOT EXISTS(SELECT 1 FROM ticket_events WHERE ticket_id=t.id AND event='CBM_WF3_ACTION_REQUEST'
  AND payload->>'request_key'=p->>'request_key' AND payload->>'action'='resend_approval_email') THEN
  RETURN jsonb_build_object('outcome','BLOCKED','reason','No current undecided completion approval'); END IF;
 SELECT * INTO e FROM cbm_wf3_approval_emails WHERE request_key=p->>'request_key';
 IF e.id IS NOT NULL THEN RETURN jsonb_build_object('outcome',CASE WHEN e.send_status='SENT' THEN 'SENT' ELSE 'UNCONFIRMED' END,
  'already_processed',true,'message_id',e.message_id,'reason','Existing email attempt is not sent again'); END IF;
 INSERT INTO cbm_wf3_approval_emails(request_key,ticket_id,approval_id) VALUES(p->>'request_key',t.id,t.approval_id) RETURNING * INTO e;
 RETURN jsonb_build_object('outcome','SEND','email_id',e.id,'ticketId',t.id,'approvalId',t.approval_id,
  'token',e.token,'expires_at',e.expires_at,'ifc_name',t.ifc_name,'description',left(t.description,1000),
  'report_text',left(t.report_text,5000),'report_file_id',t.report_file_id);
END $$;


--
-- Name: cbm_wf3_record_email(jsonb); Type: FUNCTION; Schema: public; Owner: -
--

CREATE FUNCTION public.cbm_wf3_record_email(p jsonb) RETURNS jsonb
    LANGUAGE plpgsql
    AS $$
DECLARE e cbm_wf3_approval_emails%ROWTYPE;
BEGIN
 UPDATE cbm_wf3_approval_emails SET send_status=CASE WHEN p->>'send_status'='SENT' AND nullif(p->>'message_id','') IS NOT NULL
  THEN 'SENT' ELSE 'UNCONFIRMED' END,message_id=nullif(p->>'message_id',''),error=left(p->>'error',1000)
 WHERE id=(p->>'email_id')::uuid AND send_status='SENDING' RETURNING * INTO e;
 IF e.id IS NULL THEN RETURN jsonb_build_object('outcome','UNCONFIRMED','reason','No pending email send claim'); END IF;
 INSERT INTO ticket_events(ticket_id,event,payload) VALUES(e.ticket_id,'CBM_WF3_APPROVAL_EMAIL',
  jsonb_build_object('request_key',e.request_key,'approval_id',e.approval_id,'outcome',e.send_status,'message_id',e.message_id));
 RETURN jsonb_build_object('outcome',e.send_status,'message_id',e.message_id,'ticketId',e.ticket_id,'approvalId',e.approval_id);
END $$;


--
-- Name: cbm_capture_attempts; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cbm_capture_attempts (
    file_id text NOT NULL,
    report_id uuid NOT NULL,
    attempt integer NOT NULL,
    status text NOT NULL,
    photo_url text DEFAULT ''::text NOT NULL,
    execution_id text,
    reason text,
    started_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    completed_at timestamp with time zone,
    CONSTRAINT cbm_capture_attempts_attempt_check CHECK (((attempt >= 1) AND (attempt <= 4))),
    CONSTRAINT cbm_capture_attempts_status_check CHECK ((status = ANY (ARRAY['PROCESSING'::text, 'FAILED'::text, 'IDENTIFIED'::text, 'CONFIGURATION_REQUIRED'::text])))
);


--
-- Name: cbm_capture_configuration_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cbm_capture_configuration_events (
    id bigint NOT NULL,
    report_id uuid NOT NULL,
    file_id text NOT NULL,
    action text NOT NULL,
    reason text NOT NULL,
    execution_id text,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT cbm_capture_configuration_events_action_check CHECK ((action = ANY (ARRAY['PAUSED'::text, 'RECLASSIFIED'::text, 'RESUMED'::text])))
);


--
-- Name: cbm_capture_configuration_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.cbm_capture_configuration_events_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: cbm_capture_configuration_events_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.cbm_capture_configuration_events_id_seq OWNED BY public.cbm_capture_configuration_events.id;


--
-- Name: cbm_dispatch_queue_visits; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cbm_dispatch_queue_visits (
    ticket_id integer NOT NULL,
    last_selected_at timestamp with time zone,
    last_finished_at timestamp with time zone,
    lease_token uuid,
    batch_id uuid,
    lease_until timestamp with time zone,
    worker_execution_id text,
    selection_reason text,
    last_outcome text
);


--
-- Name: ticket_events; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.ticket_events (
    id integer NOT NULL,
    ticket_id integer,
    event text NOT NULL,
    payload jsonb DEFAULT '{}'::jsonb,
    created_at timestamp with time zone DEFAULT now()
);


--
-- Name: tickets; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.tickets (
    id integer NOT NULL,
    status text DEFAULT 'RECEIVED'::text NOT NULL,
    reporter_email text,
    photo_before_url text,
    photo_after_url text,
    map_code text,
    pos_x double precision,
    pos_y double precision,
    pos_z double precision,
    vps_confidence double precision,
    ifc_global_id text,
    ifc_class text,
    ifc_name text,
    ifc_storey text,
    ifc_new_version text,
    category text,
    severity integer,
    description text,
    required_skill text,
    technician_id integer,
    scheduled_date date,
    scheduled_slot text,
    fm_reject_reason text,
    created_at timestamp with time zone DEFAULT now(),
    updated_at timestamp with time zone DEFAULT now(),
    report_text text,
    report_file_id text,
    after_file_id text,
    verification jsonb,
    closed_at timestamp with time zone,
    approval_id uuid,
    requires_dispatch_authorization boolean DEFAULT true NOT NULL,
    intake_report_id uuid,
    dispatch_authorization_id uuid,
    dispatch_authorization_token text,
    dispatch_authorization_expires_at timestamp with time zone,
    dispatch_authorized_at timestamp with time zone,
    dispatch_rejected_at timestamp with time zone,
    CONSTRAINT tickets_closed_at_consistent CHECK (((status = 'CLOSED'::text) = (closed_at IS NOT NULL)))
);


--
-- Name: cbm_dispatch_queue_items; Type: VIEW; Schema: public; Owner: -
--

CREATE VIEW public.cbm_dispatch_queue_items AS
 WITH base AS (
         SELECT t.id,
            t.status,
            t.reporter_email,
            t.photo_before_url,
            t.photo_after_url,
            t.map_code,
            t.pos_x,
            t.pos_y,
            t.pos_z,
            t.vps_confidence,
            t.ifc_global_id,
            t.ifc_class,
            t.ifc_name,
            t.ifc_storey,
            t.ifc_new_version,
            t.category,
            t.severity,
            t.description,
            t.required_skill,
            t.technician_id,
            t.scheduled_date,
            t.scheduled_slot,
            t.fm_reject_reason,
            t.created_at,
            t.updated_at,
            t.report_text,
            t.report_file_id,
            t.after_file_id,
            t.verification,
            t.closed_at,
            t.approval_id,
            t.requires_dispatch_authorization,
            t.intake_report_id,
            t.dispatch_authorization_id,
            t.dispatch_authorization_token,
            t.dispatch_authorization_expires_at,
            t.dispatch_authorized_at,
            t.dispatch_rejected_at,
            s.payload AS dispatch_state,
            (COALESCE(((s.payload ->> 'halted'::text))::boolean, false) OR (EXISTS ( SELECT 1
                   FROM jsonb_each(COALESCE((s.payload -> 'messages'::text), '{}'::jsonb)) m(key, value)
                  WHERE (((m.value ->> 'status'::text) = 'UNCERTAIN'::text) OR (((m.value ->> 'status'::text) = 'SENDING'::text) AND ((((m.value ->> 'claimed_at'::text))::timestamp with time zone + '00:05:00'::interval) < statement_timestamp()))))) OR ((s.payload IS NULL) AND (( SELECT count(*) AS count
                   FROM public.ticket_events e
                  WHERE ((e.ticket_id = t.id) AND (e.event = 'CBM_INIT_FAILURE'::text))) >= 3))) AS operator_required,
            ( SELECT count(*) AS count
                   FROM public.ticket_events e
                  WHERE ((e.ticket_id = t.id) AND (e.event = 'CBM_RESPONSE'::text) AND (e.id > COALESCE(((s.payload ->> 'response_cursor'::text))::bigint, (0)::bigint)))) AS pending_responses,
            (EXISTS ( SELECT 1
                   FROM jsonb_array_elements(COALESCE((s.payload -> 'offers'::text), '[]'::jsonb)) o(value)
                  WHERE (((o.value ->> 'status'::text) = ANY (ARRAY['SENDING'::text, 'LIVE'::text, 'UNCERTAIN'::text])) AND (((o.value ->> 'expires_at'::text))::timestamp with time zone <= statement_timestamp())))) AS expired_offers,
            (EXISTS ( SELECT 1
                   FROM jsonb_each(COALESCE((s.payload -> 'messages'::text), '{}'::jsonb)) m(key, value)
                  WHERE (((m.value ->> 'status'::text) = 'PENDING'::text) AND (m.key !~~ 'offer:%'::text) AND ((m.key <> 'opening'::text) OR (t.status = ANY (ARRAY['LOCALIZED'::text, 'DISPATCHING'::text])))))) AS pending_notices,
            ( SELECT max(e.created_at) AS max
                   FROM public.ticket_events e
                  WHERE ((e.ticket_id = t.id) AND (e.event = 'CBM_INIT_FAILURE'::text))) AS last_init_failure
           FROM (public.tickets t
             LEFT JOIN LATERAL ( SELECT ticket_events.payload
                   FROM public.ticket_events
                  WHERE ((ticket_events.ticket_id = t.id) AND (ticket_events.event = 'CBM_DISPATCH_STATE'::text))
                  ORDER BY ticket_events.id DESC
                 LIMIT 1) s ON (true))
          WHERE (t.status <> 'CLOSED'::text)
        )
 SELECT b.id AS ticket_id,
    b.created_at,
    b.status,
    b.severity,
    b.required_skill,
    b.ifc_name,
    "left"(b.description, 240) AS issue_summary,
    b.dispatch_authorized_at,
    b.operator_required,
    (b.operator_required OR (b.status = ANY (ARRAY['ESCALATED'::text, 'REWORK'::text, 'NEEDS_TRIAGE'::text]))) AS attention_required,
        CASE
            WHEN (b.status = 'PENDING_AUTHORIZATION'::text) THEN 'AWAITING_FM_AUTHORIZATION'::text
            WHEN b.operator_required THEN 'OPERATOR_ACTION_REQUIRED'::text
            WHEN (b.status = ANY (ARRAY['ASSIGNED'::text, 'WORK_DONE'::text, 'PENDING_APPROVAL'::text, 'REWORK'::text])) THEN 'WF2_COMPLETION'::text
            WHEN (b.status = ANY (ARRAY['REJECTED'::text, 'DUPLICATE'::text])) THEN 'NO_DISPATCH'::text
            WHEN (b.status = 'ESCALATED'::text) THEN 'MANUAL_DISPATCH'::text
            ELSE 'DISPATCH'::text
        END AS responsibility,
    ((NOT b.operator_required) AND ((NOT b.requires_dispatch_authorization) OR (b.dispatch_authorized_at IS NOT NULL)) AND (((b.dispatch_state IS NULL) AND (b.status = 'LOCALIZED'::text) AND ((b.last_init_failure IS NULL) OR ((b.last_init_failure + '00:01:00'::interval) <= statement_timestamp()))) OR ((b.dispatch_state IS NOT NULL) AND ((b.pending_responses > 0) OR b.expired_offers OR b.pending_notices OR ((b.status = ANY (ARRAY['LOCALIZED'::text, 'DISPATCHING'::text])) AND (((b.dispatch_state ->> 'next_wake'::text))::timestamp with time zone <= statement_timestamp())))))) AS actionable,
    q.last_selected_at,
    q.last_finished_at,
    q.lease_until,
    q.last_outcome
   FROM (base b
     LEFT JOIN public.cbm_dispatch_queue_visits q ON ((q.ticket_id = b.id)));


--
-- Name: cbm_intake_outbox_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.cbm_intake_outbox_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: cbm_intake_outbox_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.cbm_intake_outbox_id_seq OWNED BY public.cbm_intake_outbox.id;


--
-- Name: cbm_intake_reports; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cbm_intake_reports (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    reporter_email text NOT NULL,
    state text DEFAULT 'NEW'::text NOT NULL,
    attempts integer DEFAULT 0 NOT NULL,
    ticket_id integer,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    updated_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT cbm_intake_reports_attempts_check CHECK (((attempts >= 0) AND (attempts <= 4))),
    CONSTRAINT cbm_intake_reports_state_check CHECK ((state = ANY (ARRAY['NEW'::text, 'PROCESSING'::text, 'AWAITING_PHOTO'::text, 'IT_ISSUE'::text, 'IDENTIFIED'::text, 'CONFIGURATION_REQUIRED'::text])))
);


--
-- Name: cbm_it_issues; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cbm_it_issues (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    report_id uuid NOT NULL,
    status text DEFAULT 'OPEN'::text NOT NULL,
    summary text NOT NULL,
    diagnostics jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL
);


--
-- Name: cbm_technician_submissions; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cbm_technician_submissions (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    ticket_id integer NOT NULL,
    approval_cycle text NOT NULL,
    technician_id integer NOT NULL,
    pdf_sha256 text NOT NULL,
    report jsonb NOT NULL,
    status text DEFAULT 'CLAIMED'::text NOT NULL,
    drive_file_id text,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    submitted_at timestamp with time zone,
    CONSTRAINT cbm_technician_submissions_pdf_sha256_check CHECK ((pdf_sha256 ~ '^[0-9a-f]{64}$'::text)),
    CONSTRAINT cbm_technician_submissions_status_check CHECK ((status = ANY (ARRAY['CLAIMED'::text, 'SUBMITTED'::text, 'UNCONFIRMED'::text])))
);


--
-- Name: cbm_wf3_approval_emails; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.cbm_wf3_approval_emails (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    request_key text NOT NULL,
    ticket_id integer NOT NULL,
    approval_id uuid NOT NULL,
    token text DEFAULT replace(((gen_random_uuid())::text || (gen_random_uuid())::text), '-'::text, ''::text) NOT NULL,
    expires_at timestamp with time zone DEFAULT (clock_timestamp() + '72:00:00'::interval) NOT NULL,
    send_status text DEFAULT 'SENDING'::text NOT NULL,
    message_id text,
    error text,
    created_at timestamp with time zone DEFAULT clock_timestamp() NOT NULL,
    CONSTRAINT cbm_wf3_approval_emails_send_status_check CHECK ((send_status = ANY (ARRAY['SENDING'::text, 'SENT'::text, 'UNCONFIRMED'::text])))
);


--
-- Name: technicians; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.technicians (
    id integer NOT NULL,
    full_name text NOT NULL,
    email text NOT NULL,
    skills text[] NOT NULL,
    zone text DEFAULT 'building-A'::text NOT NULL,
    rating numeric(2,1) DEFAULT 4.0,
    active boolean DEFAULT true,
    profile_text text,
    last_assigned_at timestamp with time zone,
    created_at timestamp with time zone DEFAULT now(),
    jobs_completed integer DEFAULT 0 NOT NULL
);


--
-- Name: technicians_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.technicians_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: technicians_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.technicians_id_seq OWNED BY public.technicians.id;


--
-- Name: ticket_events_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.ticket_events_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: ticket_events_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.ticket_events_id_seq OWNED BY public.ticket_events.id;


--
-- Name: tickets_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

CREATE SEQUENCE public.tickets_id_seq
    AS integer
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;


--
-- Name: tickets_id_seq; Type: SEQUENCE OWNED BY; Schema: public; Owner: -
--

ALTER SEQUENCE public.tickets_id_seq OWNED BY public.tickets.id;


--
-- Name: cbm_capture_configuration_events id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_capture_configuration_events ALTER COLUMN id SET DEFAULT nextval('public.cbm_capture_configuration_events_id_seq'::regclass);


--
-- Name: cbm_intake_outbox id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_intake_outbox ALTER COLUMN id SET DEFAULT nextval('public.cbm_intake_outbox_id_seq'::regclass);


--
-- Name: technicians id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.technicians ALTER COLUMN id SET DEFAULT nextval('public.technicians_id_seq'::regclass);


--
-- Name: ticket_events id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ticket_events ALTER COLUMN id SET DEFAULT nextval('public.ticket_events_id_seq'::regclass);


--
-- Name: tickets id; Type: DEFAULT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tickets ALTER COLUMN id SET DEFAULT nextval('public.tickets_id_seq'::regclass);


--
-- Name: cbm_capture_attempts cbm_capture_attempts_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_capture_attempts
    ADD CONSTRAINT cbm_capture_attempts_pkey PRIMARY KEY (file_id);


--
-- Name: cbm_capture_attempts cbm_capture_attempts_report_id_attempt_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_capture_attempts
    ADD CONSTRAINT cbm_capture_attempts_report_id_attempt_key UNIQUE (report_id, attempt);


--
-- Name: cbm_capture_configuration_events cbm_capture_configuration_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_capture_configuration_events
    ADD CONSTRAINT cbm_capture_configuration_events_pkey PRIMARY KEY (id);


--
-- Name: cbm_dispatch_queue_visits cbm_dispatch_queue_visits_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_dispatch_queue_visits
    ADD CONSTRAINT cbm_dispatch_queue_visits_pkey PRIMARY KEY (ticket_id);


--
-- Name: cbm_intake_outbox cbm_intake_outbox_event_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_intake_outbox
    ADD CONSTRAINT cbm_intake_outbox_event_key_key UNIQUE (event_key);


--
-- Name: cbm_intake_outbox cbm_intake_outbox_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_intake_outbox
    ADD CONSTRAINT cbm_intake_outbox_pkey PRIMARY KEY (id);


--
-- Name: cbm_intake_reports cbm_intake_reports_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_intake_reports
    ADD CONSTRAINT cbm_intake_reports_pkey PRIMARY KEY (id);


--
-- Name: cbm_it_issues cbm_it_issues_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_it_issues
    ADD CONSTRAINT cbm_it_issues_pkey PRIMARY KEY (id);


--
-- Name: cbm_it_issues cbm_it_issues_report_id_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_it_issues
    ADD CONSTRAINT cbm_it_issues_report_id_key UNIQUE (report_id);


--
-- Name: cbm_technician_submissions cbm_technician_submissions_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_technician_submissions
    ADD CONSTRAINT cbm_technician_submissions_pkey PRIMARY KEY (id);


--
-- Name: cbm_technician_submissions cbm_technician_submissions_ticket_id_approval_cycle_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_technician_submissions
    ADD CONSTRAINT cbm_technician_submissions_ticket_id_approval_cycle_key UNIQUE (ticket_id, approval_cycle);


--
-- Name: cbm_wf3_approval_emails cbm_wf3_approval_emails_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_wf3_approval_emails
    ADD CONSTRAINT cbm_wf3_approval_emails_pkey PRIMARY KEY (id);


--
-- Name: cbm_wf3_approval_emails cbm_wf3_approval_emails_request_key_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_wf3_approval_emails
    ADD CONSTRAINT cbm_wf3_approval_emails_request_key_key UNIQUE (request_key);


--
-- Name: technicians technicians_email_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.technicians
    ADD CONSTRAINT technicians_email_key UNIQUE (email);


--
-- Name: technicians technicians_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.technicians
    ADD CONSTRAINT technicians_pkey PRIMARY KEY (id);


--
-- Name: ticket_events ticket_events_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ticket_events
    ADD CONSTRAINT ticket_events_pkey PRIMARY KEY (id);


--
-- Name: tickets tickets_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tickets
    ADD CONSTRAINT tickets_pkey PRIMARY KEY (id);


--
-- Name: tickets tickets_verification_object; Type: CHECK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE public.tickets
    ADD CONSTRAINT tickets_verification_object CHECK (((verification IS NULL) OR (jsonb_typeof(verification) = 'object'::text))) NOT VALID;


--
-- Name: cbm_capture_configuration_events_report; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX cbm_capture_configuration_events_report ON public.cbm_capture_configuration_events USING btree (report_id, id);


--
-- Name: cbm_intake_outbox_pending; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX cbm_intake_outbox_pending ON public.cbm_intake_outbox USING btree (id) WHERE (status = 'PENDING'::text);


--
-- Name: idx_ticket_events_status_change; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ticket_events_status_change ON public.ticket_events USING btree (created_at, ticket_id) WHERE (event = 'CBM_STATUS_CHANGED'::text);


--
-- Name: idx_ticket_events_wf2; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ticket_events_wf2 ON public.ticket_events USING btree (ticket_id, event) WHERE (event = ANY (ARRAY['CBM_WF2_ATTEMPT'::text, 'CBM_WF2_NOTICE'::text]));


--
-- Name: idx_ticket_events_wf3; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_ticket_events_wf3 ON public.ticket_events USING btree (event, created_at) WHERE (event = ANY (ARRAY['CBM_WF3_QUERY'::text, 'CBM_WF3_REPORT'::text]));


--
-- Name: idx_tickets_closed_at; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_closed_at ON public.tickets USING btree (closed_at) WHERE (closed_at IS NOT NULL);


--
-- Name: idx_tickets_open_age; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_open_age ON public.tickets USING btree (created_at) WHERE (status <> ALL (ARRAY['CLOSED'::text, 'DUPLICATE'::text]));


--
-- Name: idx_tickets_open_element; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_open_element ON public.tickets USING btree (ifc_global_id) WHERE (status <> ALL (ARRAY['CLOSED'::text, 'DUPLICATE'::text]));


--
-- Name: idx_tickets_status_created; Type: INDEX; Schema: public; Owner: -
--

CREATE INDEX idx_tickets_status_created ON public.tickets USING btree (status, created_at DESC);


--
-- Name: uq_tickets_open_element; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_tickets_open_element ON public.tickets USING btree (ifc_global_id) WHERE (status <> ALL (ARRAY['CLOSED'::text, 'DUPLICATE'::text, 'REJECTED'::text]));


--
-- Name: uq_wf2_approval_decision; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_wf2_approval_decision ON public.ticket_events USING btree (ticket_id, ((payload ->> 'approval_id'::text))) WHERE (event = 'CBM_WF2_APPROVAL'::text);


--
-- Name: uq_wf2_fm_notice_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_wf2_fm_notice_claim ON public.ticket_events USING btree (ticket_id, ((payload ->> 'approval_id'::text))) WHERE (event = 'CBM_WF2_FM_NOTICE_CLAIM'::text);


--
-- Name: uq_wf2_notice_claim; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_wf2_notice_claim ON public.ticket_events USING btree (ticket_id, ((payload ->> 'approval_id'::text)), ((payload ->> 'notice_key'::text))) WHERE (event = 'CBM_WF2_NOTICE_CLAIM'::text);


--
-- Name: uq_wf2_stats_receipt; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_wf2_stats_receipt ON public.ticket_events USING btree (ticket_id) WHERE (event = 'CBM_WF2_STATS'::text);


--
-- Name: uq_wf2_verified_notice; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_wf2_verified_notice ON public.ticket_events USING btree (ticket_id, ((payload ->> 'approval_id'::text)), ((payload ->> 'notice_key'::text))) WHERE ((event = 'CBM_WF2_NOTICE'::text) AND ((payload ->> 'source'::text) = 'GMAIL_API'::text));


--
-- Name: uq_wf3_action_request; Type: INDEX; Schema: public; Owner: -
--

CREATE UNIQUE INDEX uq_wf3_action_request ON public.ticket_events USING btree (ticket_id, ((payload ->> 'request_key'::text))) WHERE (event = 'CBM_WF3_ACTION_REQUEST'::text);


--
-- Name: tickets cbm_dispatch_authorization_guard; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER cbm_dispatch_authorization_guard BEFORE INSERT OR UPDATE ON public.tickets FOR EACH ROW EXECUTE FUNCTION public.cbm_guard_dispatch_authorization();


--
-- Name: tickets trg_tickets_status_change; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_tickets_status_change AFTER UPDATE OF status ON public.tickets FOR EACH ROW EXECUTE FUNCTION public.cbm_log_status_change();


--
-- Name: tickets trg_tickets_status_touch; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER trg_tickets_status_touch BEFORE UPDATE OF status ON public.tickets FOR EACH ROW EXECUTE FUNCTION public.cbm_touch_updated_at();


--
-- Name: tickets wf2_require_successful_ifc; Type: TRIGGER; Schema: public; Owner: -
--

CREATE TRIGGER wf2_require_successful_ifc BEFORE INSERT OR UPDATE OF status ON public.tickets FOR EACH ROW EXECUTE FUNCTION public.cbm_wf2_guard_closed();


--
-- Name: cbm_capture_attempts cbm_capture_attempts_report_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_capture_attempts
    ADD CONSTRAINT cbm_capture_attempts_report_id_fkey FOREIGN KEY (report_id) REFERENCES public.cbm_intake_reports(id);


--
-- Name: cbm_capture_configuration_events cbm_capture_configuration_events_file_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_capture_configuration_events
    ADD CONSTRAINT cbm_capture_configuration_events_file_id_fkey FOREIGN KEY (file_id) REFERENCES public.cbm_capture_attempts(file_id);


--
-- Name: cbm_capture_configuration_events cbm_capture_configuration_events_report_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_capture_configuration_events
    ADD CONSTRAINT cbm_capture_configuration_events_report_id_fkey FOREIGN KEY (report_id) REFERENCES public.cbm_intake_reports(id);


--
-- Name: cbm_dispatch_queue_visits cbm_dispatch_queue_visits_ticket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_dispatch_queue_visits
    ADD CONSTRAINT cbm_dispatch_queue_visits_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES public.tickets(id);


--
-- Name: cbm_intake_reports cbm_intake_reports_ticket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_intake_reports
    ADD CONSTRAINT cbm_intake_reports_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES public.tickets(id);


--
-- Name: cbm_it_issues cbm_it_issues_report_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_it_issues
    ADD CONSTRAINT cbm_it_issues_report_id_fkey FOREIGN KEY (report_id) REFERENCES public.cbm_intake_reports(id);


--
-- Name: cbm_technician_submissions cbm_technician_submissions_technician_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_technician_submissions
    ADD CONSTRAINT cbm_technician_submissions_technician_id_fkey FOREIGN KEY (technician_id) REFERENCES public.technicians(id);


--
-- Name: cbm_technician_submissions cbm_technician_submissions_ticket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_technician_submissions
    ADD CONSTRAINT cbm_technician_submissions_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES public.tickets(id);


--
-- Name: cbm_wf3_approval_emails cbm_wf3_approval_emails_ticket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.cbm_wf3_approval_emails
    ADD CONSTRAINT cbm_wf3_approval_emails_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES public.tickets(id);


--
-- Name: ticket_events ticket_events_ticket_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.ticket_events
    ADD CONSTRAINT ticket_events_ticket_id_fkey FOREIGN KEY (ticket_id) REFERENCES public.tickets(id);


--
-- Name: tickets tickets_intake_report_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tickets
    ADD CONSTRAINT tickets_intake_report_id_fkey FOREIGN KEY (intake_report_id) REFERENCES public.cbm_intake_reports(id);


--
-- Name: tickets tickets_technician_id_fkey; Type: FK CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.tickets
    ADD CONSTRAINT tickets_technician_id_fkey FOREIGN KEY (technician_id) REFERENCES public.technicians(id);


--
-- PostgreSQL database dump complete
--

\unrestrict k8RfSPeANS9ltupY7gny8hhseE0TtDGQWSOZaXoMJfe0xiTub6maJphfwtYvg1I

