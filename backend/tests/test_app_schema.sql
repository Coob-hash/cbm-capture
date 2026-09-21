-- Self-asserting tests for migrations/001_app_schema.sql + 002_api_role.sql. Run on a DISPOSABLE database that already
-- holds the live schema (tickets, technicians, intake). Every block raises if its rule is broken.
\set ON_ERROR_STOP 1
SET client_min_messages = warning;
CREATE TEMP TABLE k(name text PRIMARY KEY, v text);
CREATE OR REPLACE FUNCTION pg_temp.put(n text, v text) RETURNS void LANGUAGE sql AS $$
 INSERT INTO k VALUES (n,v) ON CONFLICT (name) DO UPDATE SET v=EXCLUDED.v $$;
CREATE OR REPLACE FUNCTION pg_temp.get(n text) RETURNS text LANGUAGE sql AS $$ SELECT v FROM k WHERE name=n $$;
CREATE OR REPLACE FUNCTION pg_temp.dev(n int) RETURNS jsonb LANGUAGE sql AS $$
 SELECT jsonb_build_object('id',format('00000000-0000-4000-8000-%s',lpad(n::text,12,'0')),'platform','ANDROID','model','Pixel 8') $$;
CREATE OR REPLACE FUNCTION pg_temp.capture(cid text, rid text, w int, h int, x float8) RETURNS jsonb LANGUAGE sql AS $$
 SELECT jsonb_build_object('capture_id',cid,'report_id',rid,'building_id','TEST-SITE','description','Door handle detached',
  'captured_at','2026-09-19T10:00:00Z','image',jsonb_build_object('sha256',repeat('a',64),'width',w,'height',h),
  'camera',jsonb_build_object('source','ARCORE','trusted',true,'fx',950,'fy',950,'cx',480,'cy',640,'width',w,'height',h),
  'target',jsonb_build_object('pixel',jsonb_build_object('x',x,'y',600),'source','USER_TAP')) $$;

INSERT INTO cbm_app.sites(id,name) VALUES ('TEST-SITE','Test building');
INSERT INTO cbm_app.site_access_codes(code,site_id) VALUES ('site-code-001','TEST-SITE');

-- 1. Sign-up validation --------------------------------------------------------------------------
DO $$ BEGIN
 ASSERT cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','ADMIN','email','x@example.com','password','long-enough-1','device',pg_temp.dev(1)))->>'status'='INVALID_ROLE', 'ADMIN must not be self-selectable';
 ASSERT cbm_app.sign_up(jsonb_build_object('site_code','nope-nope-nope','role','USER','email','x@example.com','password','long-enough-1','device',pg_temp.dev(1)))->>'status'='INVALID_SITE_CODE', 'unknown site code';
 ASSERT cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','USER','email','x@example.com','password','short','device',pg_temp.dev(1)))->>'status'='WEAK_PASSWORD', 'short password';
 ASSERT cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','USER','email','not-an-email','password','long-enough-1','device',pg_temp.dev(1)))->>'status'='INVALID_EMAIL', 'bad email';
END $$;

-- 2. USER sign-up is active immediately and logs in ---------------------------------------------
DO $$ DECLARE r jsonb; BEGIN
 r := cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','user','email','Reporter@Example.com','password','long-enough-1','device',pg_temp.dev(1)));
 ASSERT r->>'status'='OK' AND r->>'token' ~ '^[0-9a-f]{64}$', 'sign-up returns a session: '||r;
 ASSERT r->>'membership_id' IS NOT NULL AND r#>>'{memberships,0,status}'='ACTIVE', 'USER membership active and bound';
 ASSERT (SELECT email FROM cbm_app.users WHERE id=(r#>>'{user,id}')::uuid)='reporter@example.com', 'email stored lower-case';
 ASSERT (SELECT password_hash FROM cbm_app.password_credentials WHERE user_id=(r#>>'{user,id}')::uuid) LIKE '$2%', 'bcrypt hash stored';
 ASSERT (r->>'expires_at')::timestamptz BETWEEN clock_timestamp()+interval '59 minutes' AND clock_timestamp()+interval '61 minutes', 'one-hour session';
 PERFORM pg_temp.put('reporter',r->>'token');
 ASSERT cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','USER','email','reporter@example.com','password','long-enough-1','device',pg_temp.dev(1)))->>'status'='ACCOUNT_EXISTS', 'duplicate email';
 ASSERT cbm_app.authenticate(r->>'token',ARRAY['USER'])->>'role'='USER', 'USER token passes the USER gate';
 ASSERT cbm_app.authenticate(r->>'token',ARRAY['FM','TECHNICIAN']) IS NULL, 'USER token fails other gates';
 ASSERT cbm_app.authenticate(repeat('0',64),ARRAY['USER']) IS NULL AND cbm_app.authenticate('garbage',ARRAY['USER']) IS NULL, 'unknown token';
END $$;

-- 3. The one-hour rule is enforced by the table, and expiry is honoured -------------------------
DO $$ BEGIN
 BEGIN
  INSERT INTO cbm_app.sessions(token_sha256,user_id,device_id,method,expires_at)
  SELECT repeat('b',64),id,'00000000-0000-4000-8000-000000000001','PASSWORD',clock_timestamp()+interval '2 hours'
  FROM cbm_app.users WHERE email='reporter@example.com';
  RAISE EXCEPTION 'a two-hour session was accepted';
 EXCEPTION WHEN check_violation THEN NULL; END;
END $$;
DO $$ DECLARE r jsonb; BEGIN
 r := cbm_app.login('{"email":"reporter@example.com","password":"long-enough-1"}'::jsonb||jsonb_build_object('device',pg_temp.dev(1)));
 ASSERT r->>'status'='OK' AND (r->>'new_device')::boolean = false, 'login on a known device: '||r;
 UPDATE cbm_app.sessions SET created_at=clock_timestamp()-interval '2 hours', expires_at=clock_timestamp()-interval '61 minutes'
 WHERE token_sha256=cbm_app.token_hash(r->>'token');
 ASSERT cbm_app.authenticate(r->>'token',ARRAY['USER']) IS NULL AND cbm_app.me(r->>'token') IS NULL, 'expired session rejected';
 r := cbm_app.login('{"email":"reporter@example.com","password":"long-enough-1"}'::jsonb||jsonb_build_object('device',pg_temp.dev(2)));
 ASSERT (r->>'new_device')::boolean, 'first login on another device is flagged';
 PERFORM cbm_app.logout(r->>'token');
 ASSERT cbm_app.authenticate(r->>'token',ARRAY['USER']) IS NULL, 'logged-out session rejected';
END $$;

-- 4. Wrong passwords: generic answer, lock after five ----------------------------------------------
DO $$ DECLARE r jsonb; i int; BEGIN
 ASSERT cbm_app.login(jsonb_build_object('email','nobody@example.com','password','whatever-123','device',pg_temp.dev(1)))->>'status'='INVALID_CREDENTIALS', 'unknown email looks like a wrong password';
 FOR i IN 1..5 LOOP
  r := cbm_app.login(jsonb_build_object('email','reporter@example.com','password','wrong-password','device',pg_temp.dev(1)));
  ASSERT r->>'status'='INVALID_CREDENTIALS', 'wrong password '||i;
 END LOOP;
 r := cbm_app.login(jsonb_build_object('email','reporter@example.com','password','long-enough-1','device',pg_temp.dev(1)));
 ASSERT r->>'status'='LOCKED', 'locked after five failures, even with the right password: '||r;
 UPDATE cbm_app.users SET locked_until=NULL WHERE email='reporter@example.com';
 ASSERT cbm_app.login(jsonb_build_object('email','reporter@example.com','password','long-enough-1','device',pg_temp.dev(1)))->>'status'='OK', 'unlocked';
 ASSERT cbm_app.authenticate(pg_temp.get('reporter'),ARRAY['USER']) IS NOT NULL, 'other sessions unaffected';
END $$;

-- 5. USER and TECHNICIAN are open roles; only FM waits for the operator --------------------------------
INSERT INTO public.technicians(full_name,email,skills) VALUES ('Existing Tech','Existing.Tech@example.com','{doors,locks}');
DO $$ DECLARE r jsonb; fm jsonb; tech jsonb; tid int; BEGIN
 tech := cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','TECHNICIAN','email','tech@example.com','password','long-enough-1','display_name','Tina Tech','device',pg_temp.dev(4)));
 ASSERT tech#>>'{memberships,0,status}'='ACTIVE', 'technician is active at sign-up: '||tech;
 tid := (tech#>>'{memberships,0,technician_id}')::int;
 ASSERT (cbm_app.authenticate(tech->>'token',ARRAY['TECHNICIAN'])->>'technician_id')::int=tid, 'technician gate carries technician_id';
 ASSERT (SELECT full_name='Tina Tech' AND email='tech@example.com' AND skills='{}' AND active FROM public.technicians WHERE id=tid),
  'a new technician row with no skills (dispatch never selects it until skills are set)';
 r := cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','TECHNICIAN','email','existing.tech@example.com','password','long-enough-1','device',pg_temp.dev(8)));
 tid := (r#>>'{memberships,0,technician_id}')::int;
 ASSERT (SELECT email='Existing.Tech@example.com' AND skills='{doors,locks}' FROM public.technicians WHERE id=tid), 'existing technician linked by email, skills kept';
 ASSERT (SELECT count(*) FROM public.technicians WHERE lower(email)='existing.tech@example.com')=1, 'no duplicate technician row';
 fm := cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','FM','email','fm@example.com','password','long-enough-1','device',pg_temp.dev(3)));
 ASSERT fm#>>'{memberships,0,status}'='PENDING', 'FM is a request';
 ASSERT cbm_app.authenticate(fm->>'token',ARRAY['FM']) IS NULL, 'a pending FM grants nothing';
 ASSERT cbm_app.me(fm->>'token')#>>'{membership,status}'='PENDING', 'me() shows the pending state';
 ASSERT cbm_app.decide_membership(jsonb_build_object('membership_id',fm->>'membership_id','decision','APPROVE','decider_token',tech->>'token'))->>'status'='FORBIDDEN', 'a technician cannot approve an FM';
 ASSERT cbm_app.decide_membership(jsonb_build_object('membership_id',fm->>'membership_id','decision','APPROVE','operator',true))->>'status'='APPROVED', 'operator approves FM';
 ASSERT cbm_app.authenticate(fm->>'token',ARRAY['FM'])->>'role'='FM', 'approval takes effect in the open session';
 r := cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','FM','email','fm2@example.com','password','long-enough-1','device',pg_temp.dev(5)));
 ASSERT cbm_app.decide_membership(jsonb_build_object('membership_id',r->>'membership_id','decision','APPROVE','decider_token',fm->>'token'))->>'status'='FORBIDDEN', 'FM cannot approve FM';
 ASSERT cbm_app.decide_membership(jsonb_build_object('membership_id',r->>'membership_id','decision','REJECT','operator',true))->>'status'='REASON_REQUIRED', 'rejection needs a reason';
 ASSERT cbm_app.decide_membership(jsonb_build_object('membership_id',fm->>'membership_id','decision','REJECT','reason','x','operator',true))->>'status'='ALREADY_DECIDED', 'decisions are final';
 PERFORM pg_temp.put('tech',tech->>'token'); PERFORM pg_temp.put('fm',fm->>'token');
END $$;

-- 6. Two roles, one person: the session binds exactly one -------------------------------------------
DO $$ DECLARE r jsonb; mids jsonb; BEGIN
 INSERT INTO cbm_app.memberships(user_id,site_id,role,status,decided_at)
 SELECT id,'TEST-SITE','USER','ACTIVE',clock_timestamp() FROM cbm_app.users WHERE email='fm@example.com';
 r := cbm_app.login(jsonb_build_object('email','fm@example.com','password','long-enough-1','device',pg_temp.dev(3)));
 ASSERT r->>'membership_id' IS NULL AND jsonb_array_length(r->'memberships')=2, 'two roles: none bound yet';
 ASSERT cbm_app.authenticate(r->>'token',ARRAY['FM','USER']) IS NULL, 'unbound session grants nothing';
 ASSERT cbm_app.select_membership(r->>'token',(r#>>'{memberships,0,id}')::uuid)->>'status'='OK', 'bind role';
 ASSERT cbm_app.select_membership(r->>'token',(r#>>'{memberships,1,id}')::uuid)->>'status'='ALREADY_BOUND', 'no role switch within a session';
END $$;

-- 7. Google: sign-up, login, linking, unverified email --------------------------------------------
DO $$ DECLARE r jsonb; BEGIN
 ASSERT cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','USER','device',pg_temp.dev(6),
   'google',jsonb_build_object('subject','g-1','email','g@example.com','email_verified',false)))->>'status'='INVALID_GOOGLE_IDENTITY', 'unverified Google email refused';
 r := cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','USER','device',pg_temp.dev(6),
   'google',jsonb_build_object('subject','g-1','email','G@example.com','email_verified',true,'name','Gina')));
 ASSERT r->>'status'='OK' AND NOT EXISTS (SELECT 1 FROM cbm_app.password_credentials WHERE user_id=(r#>>'{user,id}')::uuid), 'Google account has no password';
 ASSERT cbm_app.login(jsonb_build_object('device',pg_temp.dev(6),'google',jsonb_build_object('subject','g-1','email','g@example.com','email_verified',true)))->>'status'='OK', 'Google login';
 ASSERT cbm_app.login(jsonb_build_object('email','g@example.com','password','anything-123','device',pg_temp.dev(6)))->>'status'='INVALID_CREDENTIALS', 'no password login for a Google-only account';
 ASSERT cbm_app.login(jsonb_build_object('device',pg_temp.dev(6),'google',jsonb_build_object('subject','g-2','email','new@example.com','email_verified',true)))->>'status'='NO_ACCOUNT', 'unknown Google user must sign up';
 r := cbm_app.login(jsonb_build_object('device',pg_temp.dev(6),'google',jsonb_build_object('subject','g-3','email','reporter@example.com','email_verified',true)));
 ASSERT r->>'status'='OK' AND (SELECT user_id FROM cbm_app.external_identities WHERE subject='g-3')=(r#>>'{user,id}')::uuid, 'verified Google email links to the password account';
 ASSERT cbm_app.login(jsonb_build_object('device',pg_temp.dev(6),'google',jsonb_build_object('subject','g-4','email','reporter@example.com','email_verified',true)))->>'status'='ACCOUNT_EXISTS', 'second Google identity refused';
END $$;

-- 8. Reporter capture, as WF1 will run it: claim -> store -> NOTIFY/sweep -> intake claim -> outcome ----
-- pg_temp.wf1_claim does what WF1's 'Claim Capture Attempt' + 'Record App Intake' nodes do.
CREATE OR REPLACE FUNCTION pg_temp.wf1_claim(item jsonb, ready boolean DEFAULT true) RETURNS jsonb LANGUAGE sql AS $f$
 SELECT cbm_app.record_intake(jsonb_build_object('source',item->>'source','capture_id',item->>'capture_id',
  'capture',public.cbm_capture_begin(jsonb_build_object('file_id',item->>'id','report_id',item->>'report_id',
   'reporter_email',item->>'reporter_email','photo_url',item->>'webViewLink','execution_id','test',
   'registration_ready',ready,'configuration_reason',CASE WHEN ready THEN NULL ELSE 'REGISTRATION_REQUIRED' END))))
$f$;
DO $$ DECLARE r jsonb; item jsonb; c1 text:='11111111-1111-4111-8111-111111111111'; c2 text:='22222222-2222-4222-8222-222222222222';
 c3 text:='44444444-4444-4444-8444-444444444444'; rep text:='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
 rep2 text:='bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'; tok text:=pg_temp.get('reporter'); BEGIN
 ASSERT cbm_app.claim_capture(pg_temp.get('tech'),pg_temp.capture(c1,rep,960,1280,500))->>'status'='UNAUTHENTICATED', 'technician cannot report';
 ASSERT cbm_app.claim_capture(tok,pg_temp.capture(c1,rep,960,1280,500)||'{"building_id":"OTHER"}')->>'status'='SITE_MISMATCH', 'building must match the session site';
 BEGIN
  PERFORM cbm_app.claim_capture(tok,pg_temp.capture(c1,rep,960,1280,5000));
  RAISE EXCEPTION 'out-of-frame target was accepted';
 EXCEPTION WHEN invalid_parameter_value THEN NULL; END;
 ASSERT NOT EXISTS (SELECT 1 FROM cbm_app.reports WHERE id=rep::uuid), 'rejected capture leaves no report behind';
 r := cbm_app.claim_capture(tok,pg_temp.capture(c1,rep,960,1280,500));
 ASSERT r->>'status'='UPLOAD' AND r->>'reporter_email'='reporter@example.com', 'claim: '||r;
 ASSERT cbm_app.claim_capture(tok,pg_temp.capture(c1,rep,960,1280,500))->>'status'='UPLOAD', 'replay before storing asks to upload again';
 ASSERT (SELECT description FROM cbm_app.reports WHERE id=rep::uuid)='Door handle detached', 'description stored on the report';
 ASSERT NOT EXISTS (SELECT 1 FROM cbm_app.captures_for_intake(c1::uuid)), 'not offered to WF1 before the image is stored';
 ASSERT cbm_app.store_capture(pg_temp.get('tech'),jsonb_build_object('capture_id',c1))->>'status'='UNAUTHENTICATED', 'store needs the reporter';
 r := cbm_app.store_capture(tok,jsonb_build_object('capture_id',c1,'image_bytes',250000));
 ASSERT r->>'status'='STORED', 'store: '||r;
 ASSERT cbm_app.store_capture(tok,jsonb_build_object('capture_id',c1))->>'status'='STORED', 'store is idempotent';
 ASSERT NOT EXISTS (SELECT 1 FROM public.cbm_intake_reports WHERE id=rep::uuid), 'the app does not start the intake itself';
 ASSERT cbm_app.reporter_reports(tok)#>>'{reports,0,status}'='RECEIVED', 'status RECEIVED while waiting for WF1';
 -- NOTIFY path: WF1 looks the capture up by id.
 SELECT x INTO item FROM cbm_app.captures_for_intake(c1::uuid) x;
 ASSERT item->>'source'='APP' AND item->>'id'='app-'||c1 AND item->>'reporter_email'='reporter@example.com'
  AND (item#>>'{camera,width}')::int=960 AND item->>'report_id'=rep, 'Capture Input shape: '||item;
 -- Sweep path: a fresh capture is left to NOTIFY; after two minutes the sweep offers it.
 ASSERT NOT EXISTS (SELECT 1 FROM cbm_app.captures_for_intake()), 'sweep leaves fresh captures to NOTIFY';
 UPDATE cbm_app.report_photos SET stored_at=stored_at-interval '3 minutes' WHERE capture_id=c1::uuid;
 ASSERT (SELECT x->>'capture_id' FROM cbm_app.captures_for_intake() x)=c1, 'sweep picks up a missed capture';
 -- WF1 claims it through the workflows' own intake.
 r := pg_temp.wf1_claim(item);
 ASSERT (r->>'process')::boolean AND (r->>'attempt')::int=1, 'intake attempt 1: '||r;
 ASSERT (SELECT status FROM cbm_app.report_photos WHERE capture_id=c1::uuid)='SUBMITTED', 'outcome recorded';
 ASSERT (SELECT reporter_email FROM public.cbm_intake_reports WHERE id=rep::uuid)='reporter@example.com', 'intake email comes from the account';
 ASSERT NOT EXISTS (SELECT 1 FROM cbm_app.captures_for_intake()), 'a submitted capture is not offered again';
 ASSERT cbm_app.reporter_reports(tok)#>>'{reports,0,status}'='ANALYSING', 'status ANALYSING';
 -- Duplicate delivery (NOTIFY and sweep both fired): the second claim changes nothing.
 r := pg_temp.wf1_claim(item);
 ASSERT r->>'reason'='FILE_ALREADY_RECORDED' AND (SELECT status FROM cbm_app.report_photos WHERE capture_id=c1::uuid)='SUBMITTED', 'duplicate: '||r;
 ASSERT cbm_app.claim_capture(tok,pg_temp.capture(c1,rep,960,1280,500))->>'status'='DUPLICATE', 'replay after storing';
 -- Identification fails: another photo is requested, on the same report.
 PERFORM public.cbm_capture_failed(rep::uuid,'app-'||c1,'TEST_UNRESOLVED');
 r := cbm_app.reporter_reports(tok);
 ASSERT r#>>'{reports,0,status}'='PHOTO_NEEDED' AND (r#>>'{reports,0,attempts_left}')::int=3, 'photo needed, 3 left: '||r;
 ASSERT cbm_app.claim_capture(tok,pg_temp.capture(c2,rep,960,1280,400)||'{"description":"From the other side"}')->>'status'='UPLOAD', 'replacement claim';
 PERFORM cbm_app.store_capture(tok,jsonb_build_object('capture_id',c2));
 SELECT x INTO item FROM cbm_app.captures_for_intake(c2::uuid) x;
 ASSERT item->>'description'='From the other side', 'replacement text reaches WF1';
 r := pg_temp.wf1_claim(item);
 ASSERT (r->>'attempt')::int=2, 'replacement is attempt 2: '||r;
 -- Configuration pause: the photo is kept and retried by the sweep, not failed.
 ASSERT cbm_app.claim_capture(tok,pg_temp.capture(c3,rep2,960,1280,300))->>'status'='UPLOAD', 'second report';
 PERFORM cbm_app.store_capture(tok,jsonb_build_object('capture_id',c3));
 SELECT x INTO item FROM cbm_app.captures_for_intake(c3::uuid) x;
 r := pg_temp.wf1_claim(item, false);
 ASSERT NOT coalesce((r->>'process')::boolean,false), 'paused: '||r;
 ASSERT (SELECT status FROM cbm_app.report_photos WHERE capture_id=c3::uuid)='PAUSED', 'photo PAUSED';
 ASSERT NOT EXISTS (SELECT 1 FROM cbm_app.captures_for_intake() x WHERE x->>'capture_id'=c3), 'not retried within ten minutes';
 UPDATE cbm_app.report_photos SET intake_checked_at=intake_checked_at-interval '11 minutes', stored_at=stored_at-interval '11 minutes' WHERE capture_id=c3::uuid;
 SELECT x INTO item FROM cbm_app.captures_for_intake() x WHERE x->>'capture_id'=c3;
 ASSERT item IS NOT NULL, 'paused capture retried by the sweep';
 r := pg_temp.wf1_claim(item, true);
 ASSERT (r->>'process')::boolean AND (SELECT status FROM cbm_app.report_photos WHERE capture_id=c3::uuid)='SUBMITTED', 'resumed after repair: '||r;
 -- A Drive item passes straight through record_intake.
 ASSERT cbm_app.record_intake('{"source":"DRIVE","capture":{"process":true,"x":1}}')='{"process":true,"x":1}'::jsonb, 'Drive passthrough';
 -- Another reporter cannot write into, or read, this report.
 r := cbm_app.sign_up(jsonb_build_object('site_code','site-code-001','role','USER','email','other@example.com','password','long-enough-1','device',pg_temp.dev(7)));
 ASSERT cbm_app.claim_capture(r->>'token',pg_temp.capture('33333333-3333-4333-8333-333333333333',rep,960,1280,500))->>'status'='NOT_FOUND', 'foreign report id';
 ASSERT cbm_app.store_capture(r->>'token',jsonb_build_object('capture_id',c1))->>'status'='NOT_FOUND', 'foreign capture';
 ASSERT jsonb_array_length(cbm_app.reporter_reports(r->>'token')->'reports')=0, 'other reporter sees nothing';
END $$;

-- 10. The FM's decisions from the app: the workflows' own guards, one channel more ----------------
-- The ticket WF1 would create from the reporter's photo (section 8), waiting for authorization.
DO $$ DECLARE tid int; BEGIN
 INSERT INTO public.tickets(status,reporter_email,intake_report_id,description,category,severity,
   required_skill,ifc_global_id,ifc_name,ifc_storey,photo_before_url)
 VALUES ('PENDING_AUTHORIZATION','reporter@example.com','aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa','Door handle detached',
   'doors',3,'carpentry','GID-1','Door D-12','Level 1','https://drive.example/photo') RETURNING id INTO tid;
 UPDATE public.cbm_intake_reports SET ticket_id=tid WHERE id='aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa';
 PERFORM pg_temp.put('ticket',tid::text);
END $$;

DO $$ DECLARE q jsonb; c jsonb; r jsonb; tid int:=pg_temp.get('ticket')::int; BEGIN
 q := cbm_app.fm_queue(pg_temp.get('fm'));
 ASSERT q->>'status'='OK' AND jsonb_array_length(q->'authorizations')=1, 'the ticket waits in the FM queue: '||q;
 c := q#>'{authorizations,0}';
 ASSERT (c->>'ticket_id')::int=tid AND c->>'description'='Door handle detached'
  AND c#>>'{location,asset}'='Door D-12' AND c#>>'{reporter,email}'='reporter@example.com'
  AND (c#>>'{reporter,from_app}')::boolean, 'the card carries what the FM must judge: '||c;
 ASSERT c#>>'{photo,capture_id}' IS NOT NULL, 'the card points at the reporter photo';
 ASSERT c#>'{action,allowed_actions}' ? 'approve_intervention'
  AND c#>>'{action,approval_id}' IS NOT NULL AND c#>>'{action,expected_updated_at}' IS NOT NULL, 'action context: '||c;
 ASSERT (q#>>'{counts,awaiting_authorization}')::int=1 AND (q#>>'{counts,open}')::int=1, 'counts: '||q;
 ASSERT cbm_app.fm_queue(pg_temp.get('reporter'))->>'status'='UNAUTHENTICATED', 'a reporter has no queue';
 ASSERT cbm_app.fm_queue(pg_temp.get('tech'))->>'status'='UNAUTHENTICATED', 'a technician has no queue';
 -- A rejection needs a reason, as by email.
 r := cbm_app.fm_decide(jsonb_build_object('token',pg_temp.get('fm'),'ticket_id',tid,'action','reject_intervention',
   'approval_id',c#>>'{action,approval_id}','expected_updated_at',c#>>'{action,expected_updated_at}','request_id','r1'));
 ASSERT r->>'status'='REASON_REQUIRED', 'rejection without a reason: '||r;
 -- A ticket seen in an older state is refused, not decided blind.
 r := cbm_app.fm_decide(jsonb_build_object('token',pg_temp.get('fm'),'ticket_id',tid,'action','approve_intervention',
   'approval_id',c#>>'{action,approval_id}','expected_updated_at','2000-01-01T00:00:00Z','request_id','r2'));
 ASSERT r->>'status'='BLOCKED' AND r->>'reason' LIKE 'Ticket changed%', 'stale revision: '||r;
 r := cbm_app.fm_decide(jsonb_build_object('token',pg_temp.get('fm'),'ticket_id',tid,'action','approve_intervention',
   'approval_id',gen_random_uuid(),'expected_updated_at',c#>>'{action,expected_updated_at}','request_id','r3'));
 ASSERT r->>'status'='BLOCKED' AND r->>'reason' LIKE 'Stale approval%', 'stale approval: '||r;
 ASSERT (SELECT status FROM public.tickets WHERE id=tid)='PENDING_AUTHORIZATION', 'nothing was decided';
END $$;

-- Another site's FM sees neither the ticket nor a way to decide it.
INSERT INTO cbm_app.sites(id,name) VALUES ('OTHER-SITE','Another building');
INSERT INTO cbm_app.site_access_codes(code,site_id) VALUES ('site-code-002','OTHER-SITE');
DO $$ DECLARE r jsonb; c jsonb; tid int:=pg_temp.get('ticket')::int; BEGIN
 r := cbm_app.sign_up(jsonb_build_object('site_code','site-code-002','role','FM','email','fm3@example.com','password','long-enough-1','device',pg_temp.dev(9)));
 PERFORM cbm_app.decide_membership(jsonb_build_object('membership_id',r->>'membership_id','decision','APPROVE','operator',true));
 c := cbm_app.fm_queue(pg_temp.get('fm'))#>'{authorizations,0}';
 ASSERT jsonb_array_length(cbm_app.fm_queue(r->>'token')->'authorizations')=0, 'another site sees nothing';
 ASSERT cbm_app.fm_decide(jsonb_build_object('token',r->>'token','ticket_id',tid,'action','approve_intervention',
   'approval_id',c#>>'{action,approval_id}','expected_updated_at',c#>>'{action,expected_updated_at}','request_id','r4'))->>'status'='NOT_FOUND',
  'another site cannot decide this ticket';
 PERFORM pg_temp.put('fm_other',r->>'token');
END $$;

-- The authorization itself: applied at once, recorded as FM_APP, and repeating it is harmless.
DO $$ DECLARE c jsonb; r jsonb; tid int:=pg_temp.get('ticket')::int; BEGIN
 c := cbm_app.fm_queue(pg_temp.get('fm'))#>'{authorizations,0}';
 r := cbm_app.fm_decide(jsonb_build_object('token',pg_temp.get('fm'),'ticket_id',tid,'action','approve_intervention',
   'approval_id',c#>>'{action,approval_id}','expected_updated_at',c#>>'{action,expected_updated_at}','request_id','r5'));
 ASSERT r->>'status'='OK' AND r->>'outcome'='APPLIED' AND r->>'ticket_status'='LOCALIZED', 'authorized: '||r;
 ASSERT (SELECT dispatch_authorized_at IS NOT NULL FROM public.tickets WHERE id=tid), 'dispatch may start';
 ASSERT (SELECT payload->>'actor'='FM_APP' FROM public.ticket_events WHERE ticket_id=tid AND event='CBM_DISPATCH_AUTHORIZATION'),
  'the decision is recorded as taken in the app';
 ASSERT (SELECT count(*) FROM cbm_app.fm_decisions WHERE ticket_id=tid AND outcome='APPLIED')=1, 'who decided it is recorded here';
 ASSERT (SELECT u.email FROM cbm_app.fm_decisions d JOIN cbm_app.users u ON u.id=d.user_id WHERE d.ticket_id=tid AND d.outcome='APPLIED')='fm@example.com',
  'the account behind the decision';
 -- The phone repeats the same tap (flaky network): the same answer, not a second decision.
 r := cbm_app.fm_decide(jsonb_build_object('token',pg_temp.get('fm'),'ticket_id',tid,'action','approve_intervention',
   'approval_id',c#>>'{action,approval_id}','expected_updated_at',c#>>'{action,expected_updated_at}','request_id','r5'));
 ASSERT r->>'status'='OK', 'repeat is harmless: '||r;
 ASSERT (SELECT count(*) FROM public.ticket_events WHERE ticket_id=tid AND event='CBM_DISPATCH_AUTHORIZATION')=1, 'decided once';
 ASSERT jsonb_array_length(cbm_app.fm_queue(pg_temp.get('fm'))->'authorizations')=0, 'and the queue is empty again';
END $$;

-- 11. The technician: skills, offers, the job in hand ---------------------------------------------
DO $$ DECLARE r jsonb; j jsonb; tech int; BEGIN
 SELECT (cbm_app.authenticate(pg_temp.get('tech'),ARRAY['TECHNICIAN'])->>'technician_id')::int INTO tech;
 PERFORM pg_temp.put('tech_id',tech::text);
 j := cbm_app.technician_jobs(pg_temp.get('tech'));
 ASSERT j->>'status'='OK' AND (j#>>'{me,needs_skills}')::boolean, 'a new technician is asked what they work on: '||j;
 ASSERT j->'skill_catalog' ? 'plumbing' AND jsonb_array_length(j->'offers')=0, 'the catalog and no offers yet';
 ASSERT cbm_app.set_technician_skills(pg_temp.get('tech'),ARRAY['plumbing','sorcery'])->>'status'='INVALID', 'only the listed skills';
 ASSERT cbm_app.set_technician_skills(pg_temp.get('tech'),ARRAY[]::text[])->>'status'='INVALID', 'at least one skill';
 ASSERT cbm_app.set_technician_skills(pg_temp.get('fm'),ARRAY['plumbing'])->>'status'='UNAUTHENTICATED', 'only a technician sets their own skills';
 r := cbm_app.set_technician_skills(pg_temp.get('tech'),ARRAY['carpentry','plumbing']);
 ASSERT r->>'status'='OK' AND (SELECT skills FROM public.technicians WHERE id=tech)='{carpentry,plumbing}', 'skills set: '||r;
 ASSERT NOT (cbm_app.technician_jobs(pg_temp.get('tech'))#>>'{me,needs_skills}')::boolean, 'and dispatch can now select them';
END $$;

-- The offer WF1 sends, answered on the phone instead of in the email.
CREATE OR REPLACE FUNCTION pg_temp.offer(tid int, tech int, oid text, expires interval, status text DEFAULT 'LIVE') RETURNS void
LANGUAGE sql AS $f$
 INSERT INTO public.ticket_events(ticket_id,event,payload) VALUES (tid,'CBM_DISPATCH_STATE',
  jsonb_build_object('status','DISPATCHING','offers',jsonb_build_array(jsonb_build_object(
   'id',oid,'token',repeat('c',64),'technician_id',tech,'full_name','Tina Tech','email','tech@example.com',
   'date','2026-10-01','slot','14:00-16:00','status',status,'reserved_at',clock_timestamp(),
   'expires_at',clock_timestamp()+expires))))
$f$;
DO $$ DECLARE j jsonb; r jsonb; tid int:=pg_temp.get('ticket')::int; tech int:=pg_temp.get('tech_id')::int; BEGIN
 UPDATE public.tickets SET status='DISPATCHING' WHERE id=tid;
 PERFORM pg_temp.offer(tid,tech,'offer-1',interval '48 hours');
 j := cbm_app.technician_jobs(pg_temp.get('tech'));
 ASSERT jsonb_array_length(j->'offers')=1 AND (j#>>'{offers,0,ticket_id}')::int=tid
  AND j#>>'{offers,0,offer,id}'='offer-1' AND j#>>'{offers,0,offer,slot}'='14:00-16:00', 'the offer reaches the app: '||j;
 ASSERT NOT (j#>'{offers,0}' ? 'reporter') AND NOT (j#>'{offers,0,offer}' ? 'token'),
  'the technician sees the work, not who reported it or the email link token';
 ASSERT cbm_app.technician_respond(jsonb_build_object('token',pg_temp.get('tech'),'ticket_id',tid,'offer_id','other','decision','accept'))->>'status'='OFFER_GONE',
  'an offer that is not theirs';
 ASSERT cbm_app.technician_respond(jsonb_build_object('token',pg_temp.get('fm'),'ticket_id',tid,'offer_id','offer-1','decision','accept'))->>'status'='UNAUTHENTICATED',
  'an FM cannot answer an offer';
 r := cbm_app.technician_respond(jsonb_build_object('token',pg_temp.get('tech'),'ticket_id',tid,'offer_id','offer-1','decision','accept'));
 ASSERT r->>'status'='OK', 'accepted: '||r;
 ASSERT (SELECT payload->>'decision' FROM public.ticket_events WHERE ticket_id=tid AND event='CBM_RESPONSE')='accept',
  'recorded exactly as the offer email records it';
 ASSERT cbm_app.technician_respond(jsonb_build_object('token',pg_temp.get('tech'),'ticket_id',tid,'offer_id','offer-1','decision','deny'))->>'status'='OFFER_GONE',
  'no second answer to one offer';
 ASSERT jsonb_array_length(cbm_app.technician_jobs(pg_temp.get('tech'))->'offers')=0, 'an answered offer leaves the list';
 -- An expired offer is not shown at all.
 PERFORM pg_temp.offer(tid,tech,'offer-2',interval '-1 minute');
 ASSERT jsonb_array_length(cbm_app.technician_jobs(pg_temp.get('tech'))->'offers')=0, 'an expired offer is gone';
END $$;

-- The job in hand, its photo, and the report template link.
DO $$ DECLARE j jsonb; r jsonb; cap uuid; tid int:=pg_temp.get('ticket')::int; tech int:=pg_temp.get('tech_id')::int; BEGIN
 UPDATE public.tickets SET status='ASSIGNED', technician_id=tech, scheduled_date='2026-10-01', scheduled_slot='14:00-16:00' WHERE id=tid;
 j := cbm_app.technician_jobs(pg_temp.get('tech'));
 ASSERT jsonb_array_length(j->'current')=1 AND (j#>>'{current,0,report_needed}')::boolean
  AND j#>>'{current,0,report_state}'='TO_DO', 'the accepted job waits for its report: '||j;
 cap := (j#>>'{current,0,photo,capture_id}')::uuid;
 ASSERT cap IS NOT NULL, 'the technician sees the reporter photo of their own job';
 ASSERT cbm_app.fm_photo(pg_temp.get('tech'),cap)->>'status'='OK', 'and may fetch it';
 ASSERT cbm_app.fm_photo(pg_temp.get('fm'),cap)->>'status'='OK', 'so may the site FM';
 ASSERT cbm_app.fm_photo(pg_temp.get('fm_other'),cap)->>'status'='NOT_FOUND', 'another site may not';
 ASSERT cbm_app.fm_photo(pg_temp.get('reporter'),cap)->>'status'='UNAUTHENTICATED', 'a reporter uses their own list';
 -- With the accepted offer recorded by WF1, the workflows issue the template link.
 INSERT INTO public.ticket_events(ticket_id,event,payload) VALUES (tid,'CBM_DISPATCH_STATE',
  jsonb_build_object('status','ASSIGNED','offers',jsonb_build_array(jsonb_build_object(
   'id','offer-1','token',repeat('c',64),'technician_id',tech,'status','ACCEPTED','full_name','Tina Tech',
   'email','tech@example.com','date','2026-10-01','slot','14:00-16:00','expires_at',clock_timestamp()+interval '48 hours'))));
 r := cbm_app.technician_report_link(pg_temp.get('tech'),tid);
 ASSERT r->>'status'='OK' AND r->>'token' ~ '^[0-9a-f]{64}$', 'the report template link: '||r;
 ASSERT cbm_app.technician_report_link(pg_temp.get('fm'),tid)->>'status'='UNAUTHENTICATED', 'only a technician';
END $$;

-- 12. The completion review: send it back with a reason, or approve and let WF2 close -------------
DO $$ DECLARE q jsonb; c jsonb; r jsonb; tid int:=pg_temp.get('ticket')::int; BEGIN
 UPDATE public.tickets SET status='PENDING_APPROVAL', approval_id=gen_random_uuid(),
  report_text='Handle replaced, hinges adjusted.' WHERE id=tid;
 q := cbm_app.fm_queue(pg_temp.get('fm'));
 ASSERT jsonb_array_length(q->'completions')=1, 'the completed work waits for the FM: '||q;
 c := q#>'{completions,0}';
 ASSERT c#>>'{technician,name}'='Tina Tech' AND (c#>>'{technician,first_job}')::boolean
  AND (c#>>'{technician,jobs_completed}')::int=0, 'a newcomer is flagged as a first job: '||c;
 ASSERT c#>>'{work,report_text}'='Handle replaced, hinges adjusted.', 'the report the FM judges';
 ASSERT c#>'{action,allowed_actions}' ? 'approve_completion' AND c#>'{action,allowed_actions}' ? 'request_rework', 'both ways out';
 r := cbm_app.fm_decide(jsonb_build_object('token',pg_temp.get('fm'),'ticket_id',tid,'action','request_rework',
   'approval_id',c#>>'{action,approval_id}','expected_updated_at',c#>>'{action,expected_updated_at}','request_id','r6'));
 ASSERT r->>'status'='REASON_REQUIRED', 'rework needs a reason: '||r;
 r := cbm_app.fm_decide(jsonb_build_object('token',pg_temp.get('fm'),'ticket_id',tid,'action','request_rework','reason','The handle is still loose.',
   'approval_id',c#>>'{action,approval_id}','expected_updated_at',c#>>'{action,expected_updated_at}','request_id','r7'));
 ASSERT r->>'status'='OK' AND r->>'outcome'='READY' AND (r->>'settling')::boolean, 'the decision is recorded for WF2 to settle: '||r;
 ASSERT (SELECT payload->>'decision'='REJECTED' AND payload->>'actor'='FM_APP' AND payload->>'reason'='The handle is still loose.'
   FROM public.ticket_events WHERE ticket_id=tid AND event='CBM_WF2_APPROVAL'), 'rework recorded with its reason';
 ASSERT (SELECT status FROM public.tickets WHERE id=tid)='PENDING_APPROVAL', 'the workflows, not the app, move the ticket';
 ASSERT (public.cbm_wf2_review_status(jsonb_build_object('ticketId',tid,
   'approvalId',(SELECT approval_id FROM public.tickets WHERE id=tid))))->>'route'='DECIDED', 'WF2 sees a decision to settle';
 -- And the opposite decision can no longer be slipped in from the app.
 c := cbm_app.fm_queue(pg_temp.get('fm'))#>'{completions,0}';
 r := cbm_app.fm_decide(jsonb_build_object('token',pg_temp.get('fm'),'ticket_id',tid,'action','approve_completion',
   'approval_id',c#>>'{action,approval_id}','expected_updated_at',c#>>'{action,expected_updated_at}','request_id','r8'));
 ASSERT r->>'status'='BLOCKED', 'an opposite decision is refused: '||r;
END $$;

-- 13. The technician's report, written in the app ------------------------------------------------
-- The fields of the workflows' template go to the database; WF2 renders the PDF from them.
CREATE OR REPLACE FUNCTION pg_temp.report_fields() RETURNS jsonb LANGUAGE sql AS $f$
 SELECT jsonb_build_object(
  'work_date','2026-09-22','findings','The seal was perished along the lower edge.',
  'work_performed','Replaced the seal, refitted the frame and checked that the sash closes flush.',
  'materials','1 x seal, 4 m','checks','Poured water along the sill and watched for ten minutes.',
  'check_result','PASSED','outcome','COMPLETED','remaining_issues','None.','declaration',true)
$f$;

-- WF2 sent the job back in section 12, so it is the technician's again.
DO $$ BEGIN UPDATE public.tickets SET status='ASSIGNED' WHERE id=pg_temp.get('ticket')::int; END $$;
DO $$ DECLARE r jsonb; tid int:=pg_temp.get('ticket')::int; tech int:=pg_temp.get('tech_id')::int; BEGIN
 ASSERT cbm_app.submit_technician_report(jsonb_build_object('token',pg_temp.get('fm'),'ticket_id',tid,
   'report',pg_temp.report_fields()))->>'status'='UNAUTHENTICATED', 'only a technician writes a report';
 ASSERT cbm_app.submit_technician_report(jsonb_build_object('token',pg_temp.get('tech'),'ticket_id',999999,
   'report',pg_temp.report_fields()))->>'status'='NOT_FOUND', 'not their ticket';

 -- What the template requires.
 ASSERT cbm_app.submit_technician_report(jsonb_build_object('token',pg_temp.get('tech'),'ticket_id',tid,
   'report',pg_temp.report_fields()||'{"work_performed":"too short"}'))->>'status'='INVALID_REPORT', 'work performed has a minimum';
 ASSERT cbm_app.submit_technician_report(jsonb_build_object('token',pg_temp.get('tech'),'ticket_id',tid,
   'report',pg_temp.report_fields()||'{"declaration":false}'))->>'status'='INVALID_REPORT', 'the declaration is required';
 ASSERT cbm_app.submit_technician_report(jsonb_build_object('token',pg_temp.get('tech'),'ticket_id',tid,
   'report',pg_temp.report_fields()||'{"check_result":"MAYBE"}'))->>'status'='INVALID_REPORT', 'the check result is one of three';
 ASSERT NOT EXISTS (SELECT 1 FROM cbm_app.technician_reports), 'nothing was written by a refused report';

 -- With a photo the API is told to upload it; the report is not visible to WF2 until it has.
 r := cbm_app.submit_technician_report(jsonb_build_object('token',pg_temp.get('tech'),'ticket_id',tid,
   'report',pg_temp.report_fields(),
   'photo',jsonb_build_object('caption','The new seal in place','sha256',repeat('d',64),'bytes',120000)));
 ASSERT r->>'status'='UPLOAD' AND r->>'report_id' IS NOT NULL, 'a photo is uploaded next: '||r;
 PERFORM pg_temp.put('report',r->>'report_id');
 ASSERT NOT EXISTS (SELECT 1 FROM cbm_app.reports_for_wf2()), 'not offered to WF2 before the photo is stored';
 ASSERT (SELECT status FROM cbm_app.technician_reports WHERE id=(r->>'report_id')::uuid)='RECEIVED', 'waiting for the photo';

 -- The locked half comes from the ticket and the account, never from the phone.
 ASSERT (SELECT report->>'technician_name' FROM cbm_app.technician_reports WHERE id=(r->>'report_id')::uuid)='Tina Tech',
  'the technician is read from the account';
 ASSERT (SELECT report->>'ticket_id' FROM cbm_app.technician_reports WHERE id=(r->>'report_id')::uuid)=tid::text,
  'the ticket is the one being reported on';
 ASSERT (SELECT report->>'reported_issue' FROM cbm_app.technician_reports WHERE id=(r->>'report_id')::uuid)='Door handle detached',
  'the reported issue comes from the ticket';

 -- Repeating the same submission answers with the same report instead of writing a second one.
 r := cbm_app.submit_technician_report(jsonb_build_object('token',pg_temp.get('tech'),'ticket_id',tid,
   'report',pg_temp.report_fields(),
   'photo',jsonb_build_object('caption','again','sha256',repeat('d',64),'bytes',120000)));
 ASSERT r->>'report_id'=pg_temp.get('report'), 'the same report comes back';
 ASSERT (SELECT count(*) FROM cbm_app.technician_reports)=1, 'one report, not two';
END $$;

DO $$ DECLARE r jsonb; item jsonb; rid uuid:=pg_temp.get('report')::uuid; tid int:=pg_temp.get('ticket')::int; BEGIN
 -- Once the photo is on disk the report is WF2's to take.
 r := cbm_app.store_technician_report(pg_temp.get('tech'),jsonb_build_object('report_id',rid));
 ASSERT r->>'status'='STORED' AND (r->>'ticket_id')::int=tid, 'stored: '||r;
 ASSERT (SELECT storage_ref FROM cbm_app.technician_reports WHERE id=rid)='report-'||rid::text||'.jpg', 'the photo has a place in the store';
 ASSERT cbm_app.store_technician_report(pg_temp.get('tech'),jsonb_build_object('report_id',rid))->>'status'='STORED', 'storing twice is harmless';

 -- WF2 by notification: it reads the report by id.
 SELECT x INTO item FROM cbm_app.reports_for_wf2(rid) x;
 ASSERT item->>'source'='APP' AND (item->>'ticket_id')::int=tid, 'the shape WF2 reads: '||item;
 ASSERT item#>>'{report,work_performed}' LIKE 'Replaced the seal%', 'the fields travel with it';
 ASSERT item#>>'{photo,caption}'='The new seal in place' AND item#>>'{photo,storage_ref}'='report-'||rid::text||'.jpg',
  'the AFTER photo travels with it';
 -- The sweep leaves a fresh report to the notification, and picks up a missed one.
 ASSERT NOT EXISTS (SELECT 1 FROM cbm_app.reports_for_wf2()), 'fresh reports are left to the notification';
 UPDATE cbm_app.technician_reports SET stored_at=stored_at-interval '3 minutes' WHERE id=rid;
 ASSERT (SELECT x->>'report_id' FROM cbm_app.reports_for_wf2() x)=rid::text, 'the sweep picks up a missed report';

 -- WF2 writes back what it made of it.
 PERFORM cbm_app.record_report_intake(jsonb_build_object('report_id',rid,'submission_id',gen_random_uuid(),'outcome','SUBMITTED'));
 ASSERT (SELECT status FROM cbm_app.technician_reports WHERE id=rid)='SUBMITTED', 'the report is with the workflows';
 ASSERT NOT EXISTS (SELECT 1 FROM cbm_app.reports_for_wf2()), 'a claimed report is not offered again';
 ASSERT (SELECT submission_id FROM cbm_app.technician_reports WHERE id=rid) IS NOT NULL, 'the workflows own submission is recorded';

 -- The technician sees that it has been sent, and may write again after a rework.
 r := cbm_app.my_report_state(pg_temp.get('tech'),tid);
 ASSERT (r->>'sent')::boolean AND r->>'state'='SUBMITTED', 'the app knows it was sent: '||r;
 ASSERT NOT (cbm_app.my_report_state(pg_temp.get('tech'),999999)->>'sent')::boolean, 'another ticket has no report';
 r := cbm_app.submit_technician_report(jsonb_build_object('token',pg_temp.get('tech'),'ticket_id',tid,
   'report',pg_temp.report_fields()||'{"findings":"Sent back: the corner still lets water in."}'));
 ASSERT r->>'status'='STORED', 'a second report may be written once the first is with the workflows: '||r;
 ASSERT (SELECT count(*) FROM cbm_app.technician_reports)=2, 'two reports, one per round';
END $$;


-- WF2 renders the PDF, then claims the report through the workflows' own submission function.
DO $$ DECLARE r jsonb; rid uuid; tid int:=pg_temp.get('ticket')::int; BEGIN
 SELECT id INTO rid FROM cbm_app.technician_reports WHERE status='STORED' ORDER BY created_at DESC LIMIT 1;
 ASSERT rid IS NOT NULL, 'fixture: a report is waiting for WF2';
 ASSERT cbm_app.record_app_report_submission(jsonb_build_object('report_id',rid,'pdf_sha256','nope'))->>'status'='INVALID',
  'the PDF hash is checked';
 r := cbm_app.record_app_report_submission(jsonb_build_object('report_id',rid,'pdf_sha256',repeat('e',64)));
 ASSERT r->>'status'='SUBMITTED' AND r->>'submission_id' IS NOT NULL, 'claimed through the workflows: '||r;
 ASSERT (SELECT status FROM cbm_app.technician_reports WHERE id=rid)='SUBMITTED', 'and recorded here';
 ASSERT (SELECT pdf_sha256 FROM public.cbm_technician_submissions WHERE id=(r->>'submission_id')::uuid)=repeat('e',64),
  'the workflows hold the report and its hash';
 ASSERT (SELECT report->>'work_performed' FROM public.cbm_technician_submissions WHERE id=(r->>'submission_id')::uuid) IS NOT NULL,
  'with the fields the technician wrote';
 -- One approval cycle takes one report: a second claim is refused, and said to be refused.
 ASSERT cbm_app.record_app_report_submission(jsonb_build_object('report_id',rid,'pdf_sha256',repeat('e',64)))->>'status'='SUBMITTED',
  'claiming twice answers with the first claim';
END $$;

-- 14. The API's login reaches data only through its entry functions --------------------------------
DO $$ BEGIN
 ASSERT cbm_app.store_capture(pg_temp.get('tech'),jsonb_build_object('capture_id','11111111-1111-4111-8111-111111111111'))->>'status'='UNAUTHENTICATED', 'store needs a reporter session';
END $$;
ALTER ROLE cbm_app_api LOGIN;
SET SESSION AUTHORIZATION cbm_app_api;
DO $$ DECLARE r jsonb; BEGIN
 ASSERT cbm_app.login(jsonb_build_object('email','reporter@example.com','password','long-enough-1','device',jsonb_build_object('id','00000000-0000-4000-8000-000000000001','platform','ANDROID')))->>'status'='OK', 'entry functions work for the API login';
 BEGIN PERFORM 1 FROM cbm_app.users; RAISE EXCEPTION 'API login read cbm_app.users';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM 1 FROM cbm_app.password_credentials; RAISE EXCEPTION 'API login read password hashes';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM 1 FROM public.tickets; RAISE EXCEPTION 'API login read workflow tickets';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM cbm_app.open_session(gen_random_uuid(),gen_random_uuid(),'PASSWORD','OK'); RAISE EXCEPTION 'API login called an internal helper';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM public.cbm_intake_recover(); RAISE EXCEPTION 'API login ran a workflow function';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM cbm_app.captures_for_intake(); RAISE EXCEPTION 'API login read the WF1 intake queue';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM cbm_app.record_intake('{}'); RAISE EXCEPTION 'API login recorded an intake outcome';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM cbm_app.fm_card(1); RAISE EXCEPTION 'API login built a card without a session';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM 1 FROM cbm_app.site_tickets('TEST-SITE'); RAISE EXCEPTION 'API login listed a site''s tickets';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 BEGIN PERFORM 1 FROM cbm_app.live_offers(1); RAISE EXCEPTION 'API login read live offers';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 -- The workflows' own functions run with the caller's rights, so the API login cannot reach a
 -- ticket through them either: only cbm_app.fm_decide, which is SECURITY DEFINER, can.
 BEGIN PERFORM public.cbm_wf3_begin_action(jsonb_build_object('action','approve_intervention','ticketId',1,
   'approvalId',gen_random_uuid(),'requestId','x','sessionId','x','question','q','actor','FM_CHAT','truncated',false));
  RAISE EXCEPTION 'API login reached tickets through the workflow action helper';
 EXCEPTION WHEN insufficient_privilege THEN NULL; END;
 ASSERT cbm_app.fm_queue(repeat('0',64))->>'status'='UNAUTHENTICATED', 'the FM queue needs a session';
 ASSERT cbm_app.fm_decide('{"token":"x"}')->>'status'='UNAUTHENTICATED', 'a decision needs a session';
 ASSERT cbm_app.technician_jobs(repeat('0',64))->>'status'='UNAUTHENTICATED', 'the job list needs a session';
 r := cbm_app.decide_membership(jsonb_build_object('membership_id',(SELECT gen_random_uuid()),'decision','APPROVE','operator',true));
 ASSERT r->>'status' IN ('FORBIDDEN','NOT_FOUND'), 'operator path: '||r;
END $$;
RESET SESSION AUTHORIZATION;
DO $$ DECLARE mid uuid; BEGIN
 -- A real pending request, so that only the session_user check can refuse the operator path.
 SELECT id INTO mid FROM cbm_app.memberships WHERE status='PENDING' LIMIT 1;
 ASSERT mid IS NOT NULL, 'fixture: a pending membership exists';
 PERFORM set_config('test.mid',mid::text,false);
END $$;
SET SESSION AUTHORIZATION cbm_app_api;
DO $$ BEGIN
 ASSERT cbm_app.decide_membership(jsonb_build_object('membership_id',current_setting('test.mid'),'decision','REJECT','reason','x','operator',true))->>'status'='FORBIDDEN', 'operator path refused to the API login';
END $$;
RESET SESSION AUTHORIZATION;
ALTER ROLE cbm_app_api NOLOGIN;

SELECT 'app schema: all assertions passed' AS result;
