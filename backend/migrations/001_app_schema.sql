-- CBM App schema. Owned by the app, not by the workflows: everything lives in schema cbm_app, next
-- to the workflows' public schema in the same database. Repeatable (IF NOT EXISTS / OR REPLACE).
--
-- Accounts log in with email + password (bcrypt, pgcrypto) or Google. A login opens a session that
-- lasts exactly one hour and is never extended. The role lives on a site membership: USER and
-- TECHNICIAN are active at sign-up (the app is open to anyone who has the site's code); FM waits
-- for approval by the operator, and so does a password sign-up that would take over an existing
-- technician's dispatch row (see sign_up). reports / report_photos hold the reporter's
-- photo + description; WF1 takes each capture into the workflows' intake (public.cbm_capture_begin).
--
-- Every function is SECURITY DEFINER with a fixed search_path: the API's login (002_api_role.sql)
-- holds no table privileges and reaches data only through the entry functions granted to it.
BEGIN;
CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE SCHEMA IF NOT EXISTS cbm_app;

-- Sites and the codes printed in their QR posters ---------------------------------------------
CREATE TABLE IF NOT EXISTS cbm_app.sites (
 id text PRIMARY KEY CHECK (id ~ '^[A-Za-z0-9_-]{1,64}$'),   -- equals the capture building_id
 name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 200),
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS cbm_app.site_access_codes (
 code text PRIMARY KEY CHECK (code ~ '^[A-Za-z0-9_-]{8,64}$'),
 site_id text NOT NULL REFERENCES cbm_app.sites(id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 revoked_at timestamptz
);

-- Accounts and how they authenticate. The email is the username. ------------------------------
CREATE TABLE IF NOT EXISTS cbm_app.users (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 email text NOT NULL UNIQUE CHECK (email = lower(email) AND length(email) <= 254
  AND email ~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$'),
 display_name text CHECK (length(display_name) <= 150),
 status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','DISABLED')),
 failed_logins integer NOT NULL DEFAULT 0 CHECK (failed_logins >= 0),
 locked_until timestamptz,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 last_login_at timestamptz
);
-- Kept apart from users so that no query over accounts can return a hash by accident.
CREATE TABLE IF NOT EXISTS cbm_app.password_credentials (
 user_id uuid PRIMARY KEY REFERENCES cbm_app.users(id),
 password_hash text NOT NULL CHECK (password_hash LIKE '$2%'),
 changed_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- What the bcrypt hash was computed over. bcrypt reads only the first 72 bytes of its input, and a
-- password may be up to 128 characters (more bytes still in UTF-8), so a password is hashed as
-- bcrypt(base64(sha256(password))) - 44 characters that depend on every byte ('bcrypt-sha256').
-- 'bcrypt' marks a hash of the raw password from before; login replaces it (see login).
-- Repeatable: the column arrives with 'bcrypt' for the rows that exist, then new rows default to
-- the new scheme.
ALTER TABLE cbm_app.password_credentials ADD COLUMN IF NOT EXISTS scheme text NOT NULL DEFAULT 'bcrypt';
ALTER TABLE cbm_app.password_credentials ALTER COLUMN scheme SET DEFAULT 'bcrypt-sha256';
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='cbm_app.password_credentials'::regclass
                AND conname='password_credentials_scheme') THEN
  ALTER TABLE cbm_app.password_credentials ADD CONSTRAINT password_credentials_scheme
   CHECK (scheme IN ('bcrypt','bcrypt-sha256'));
 END IF;
END $$;
-- Google identities. The ID token is verified by the API; only its verified claims reach here.
CREATE TABLE IF NOT EXISTS cbm_app.external_identities (
 provider text NOT NULL CHECK (provider IN ('GOOGLE')),
 subject text NOT NULL CHECK (length(subject) BETWEEN 1 AND 255),
 user_id uuid NOT NULL REFERENCES cbm_app.users(id),
 email text NOT NULL,
 linked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 PRIMARY KEY (provider, subject),
 UNIQUE (user_id, provider)
);

-- Roles: one row per (person, site, role) ------------------------------------------------------
CREATE TABLE IF NOT EXISTS cbm_app.memberships (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 user_id uuid NOT NULL REFERENCES cbm_app.users(id),
 site_id text NOT NULL REFERENCES cbm_app.sites(id),
 role text NOT NULL CHECK (role IN ('USER','TECHNICIAN','FM','ADMIN')),
 status text NOT NULL CHECK (status IN ('PENDING','ACTIVE','REJECTED','REVOKED')),
 technician_id integer REFERENCES public.technicians(id),
 requested_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 decided_at timestamptz,
 decided_by uuid REFERENCES cbm_app.users(id),        -- NULL: automatic (USER) or database operator
 decision_reason text CHECK (length(decision_reason) <= 1000),
 UNIQUE (user_id, site_id, role),
 CHECK (technician_id IS NULL OR role = 'TECHNICIAN'),
 CHECK (NOT (role = 'TECHNICIAN' AND status = 'ACTIVE' AND technician_id IS NULL)),
 CHECK ((status = 'PENDING') = (decided_at IS NULL))
);
-- A technicians row is driven by at most one active account.
CREATE UNIQUE INDEX IF NOT EXISTS memberships_one_account_per_technician
 ON cbm_app.memberships(technician_id) WHERE technician_id IS NOT NULL AND status = 'ACTIVE';

-- Devices and sessions. A device is not owned by a person: several people may use one phone. ---
CREATE TABLE IF NOT EXISTS cbm_app.devices (
 id uuid PRIMARY KEY,                                   -- install id generated by the app
 platform text NOT NULL CHECK (platform IN ('ANDROID','IOS')),
 model text CHECK (length(model) <= 100),
 os_version text CHECK (length(os_version) <= 50),
 app_version text CHECK (length(app_version) <= 50),
 push_token text CHECK (length(push_token) <= 4096),
 first_seen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 last_seen_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE IF NOT EXISTS cbm_app.sessions (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 token_sha256 text NOT NULL UNIQUE CHECK (token_sha256 ~ '^[0-9a-f]{64}$'),
 user_id uuid NOT NULL REFERENCES cbm_app.users(id),
 membership_id uuid REFERENCES cbm_app.memberships(id),   -- NULL until the role is chosen
 device_id uuid NOT NULL REFERENCES cbm_app.devices(id),
 method text NOT NULL CHECK (method IN ('PASSWORD','GOOGLE')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 expires_at timestamptz NOT NULL,
 last_used_at timestamptz,
 revoked_at timestamptz,
 -- The one-hour rule is a constraint, not a convention: no writer can issue a longer session.
 CHECK (expires_at <= created_at + interval '1 hour')
);
CREATE INDEX IF NOT EXISTS sessions_live_by_user ON cbm_app.sessions(user_id) WHERE revoked_at IS NULL;
CREATE TABLE IF NOT EXISTS cbm_app.login_events (
 id bigserial PRIMARY KEY,
 user_id uuid REFERENCES cbm_app.users(id),
 email text,
 device_id uuid,
 method text NOT NULL,
 outcome text NOT NULL CHECK (outcome IN ('SIGNED_UP','OK','INVALID_CREDENTIALS','LOCKED','DISABLED','LOGOUT')),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

-- The reporter's part: a report (description) and its photos -----------------------------------
-- reports.id is also the intake report id, so the workflows' four-capture budget applies.
CREATE TABLE IF NOT EXISTS cbm_app.reports (
 id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES cbm_app.users(id),
 membership_id uuid NOT NULL REFERENCES cbm_app.memberships(id),
 site_id text NOT NULL REFERENCES cbm_app.sites(id),
 description text CHECK (description IS NULL OR length(description) BETWEEN 1 AND 500),
 intake_report_id uuid UNIQUE REFERENCES public.cbm_intake_reports(id)
  CHECK (intake_report_id IS NULL OR intake_report_id = id),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX IF NOT EXISTS reports_by_user ON cbm_app.reports(user_id, created_at DESC);
-- A photo's life: RECEIVED (metadata recorded) -> STORED (image saved by the API, n8n notified) ->
-- SUBMITTED (the workflows' intake took it) | PAUSED (intake paused for configuration; retried) |
-- NOT_PROCESSED (intake declined it: report busy or finished).
CREATE TABLE IF NOT EXISTS cbm_app.report_photos (
 capture_id uuid PRIMARY KEY,                           -- the app's idempotency key
 report_id uuid NOT NULL REFERENCES cbm_app.reports(id),
 status text NOT NULL DEFAULT 'RECEIVED',
 note text CHECK (note IS NULL OR length(note) BETWEEN 1 AND 500),   -- optional text on a replacement photo
 image_sha256 text NOT NULL CHECK (image_sha256 ~ '^[0-9a-f]{64}$'),
 image_width integer NOT NULL CHECK (image_width > 0),
 image_height integer NOT NULL CHECK (image_height > 0),
 camera_intrinsics jsonb NOT NULL,
 target_pixel jsonb NOT NULL,
 capture_pose jsonb,
 client jsonb,
 captured_at timestamptz NOT NULL,
 storage_ref text,                                      -- file name in the API's capture store
 image_bytes integer,
 photo_url text,
 attempt_file_id text REFERENCES public.cbm_capture_attempts(file_id),
 intake_result jsonb,
 received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 stored_at timestamptz,
 intake_checked_at timestamptz,
 submitted_at timestamptz,
 -- The frame invariant of the capture contract: K, image and tap share one coordinate system.
 CONSTRAINT report_photos_frame_size CHECK ((camera_intrinsics->>'width')::integer = image_width
    AND (camera_intrinsics->>'height')::integer = image_height),
 CONSTRAINT report_photos_target_in_frame CHECK ((target_pixel->>'x')::float8 >= 0 AND (target_pixel->>'x')::float8 < image_width
    AND (target_pixel->>'y')::float8 >= 0 AND (target_pixel->>'y')::float8 < image_height)
);
-- The capture's geometry, complete and usable: the ray to the damage is cast from it. K with
-- positive focal lengths and its principal point in the frame, K's frame the image's size, the tap
-- inside the frame, and where K came from. A missing member or a JSON null makes it false, never
-- unknown: a CHECK lets an unknown result through, which is how camera={} and a null tap passed the
-- two checks above.
CREATE OR REPLACE FUNCTION cbm_app.capture_geometry_valid(p_camera jsonb, p_pixel jsonb, p_width integer, p_height integer)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE SET search_path = cbm_app, public, pg_temp AS $$
BEGIN
 IF jsonb_typeof(p_camera) IS DISTINCT FROM 'object' OR jsonb_typeof(p_pixel) IS DISTINCT FROM 'object'
  OR p_width IS NULL OR p_height IS NULL THEN RETURN false; END IF;
 IF EXISTS (SELECT 1 FROM unnest(ARRAY[p_camera->'fx', p_camera->'fy', p_camera->'cx', p_camera->'cy',
     p_camera->'width', p_camera->'height', p_pixel->'x', p_pixel->'y']) v
    WHERE jsonb_typeof(v) IS DISTINCT FROM 'number') THEN RETURN false; END IF;
 -- numeric, not float8: a JSON number of any size compares without overflowing.
 RETURN (p_camera->>'fx')::numeric > 0 AND (p_camera->>'fy')::numeric > 0
  AND (p_camera->>'cx')::numeric BETWEEN 0 AND p_width AND (p_camera->>'cy')::numeric BETWEEN 0 AND p_height
  AND (p_camera->>'width')::numeric = p_width AND (p_camera->>'height')::numeric = p_height
  AND (p_pixel->>'x')::numeric >= 0 AND (p_pixel->>'x')::numeric < p_width
  AND (p_pixel->>'y')::numeric >= 0 AND (p_pixel->>'y')::numeric < p_height
  AND coalesce(p_camera->>'source' IN ('ARKIT','ARCORE','ANDROID_CAMERA2','EXIF','MANUAL_OVERRIDE'), false)
  AND jsonb_typeof(p_camera->'trusted') IS NOT DISTINCT FROM 'boolean';
END $$;

-- Repeatable upgrade of the photo lifecycle (also brings a table created by an earlier version of
-- this file up to date): replace the status checks with the named ones below.
ALTER TABLE cbm_app.report_photos ADD COLUMN IF NOT EXISTS image_bytes integer;
ALTER TABLE cbm_app.report_photos ADD COLUMN IF NOT EXISTS stored_at timestamptz;
ALTER TABLE cbm_app.report_photos ADD COLUMN IF NOT EXISTS intake_checked_at timestamptz;
DO $$ DECLARE c record; BEGIN
 FOR c IN SELECT conname FROM pg_constraint WHERE conrelid='cbm_app.report_photos'::regclass AND contype='c'
  AND conname NOT IN ('report_photos_frame_size','report_photos_target_in_frame','report_photos_status','report_photos_stage',
                      'report_photos_geometry')
  AND pg_get_constraintdef(oid) ~ '(status|storage_ref)'
 LOOP EXECUTE format('ALTER TABLE cbm_app.report_photos DROP CONSTRAINT %I', c.conname); END LOOP;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='cbm_app.report_photos'::regclass AND conname='report_photos_status') THEN
  ALTER TABLE cbm_app.report_photos ADD CONSTRAINT report_photos_status
   CHECK (status IN ('RECEIVED','STORED','SUBMITTED','PAUSED','NOT_PROCESSED'));
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='cbm_app.report_photos'::regclass AND conname='report_photos_stage') THEN
  ALTER TABLE cbm_app.report_photos ADD CONSTRAINT report_photos_stage CHECK (
   (status = 'RECEIVED') = (storage_ref IS NULL) AND (status = 'RECEIVED') = (stored_at IS NULL)
   AND (storage_ref IS NULL OR storage_ref = 'app-' || capture_id::text));
 END IF;
 IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid='cbm_app.report_photos'::regclass AND conname='report_photos_geometry') THEN
  ALTER TABLE cbm_app.report_photos ADD CONSTRAINT report_photos_geometry
   CHECK (cbm_app.capture_geometry_valid(camera_intrinsics, target_pixel, image_width, image_height));
 END IF;
END $$;
CREATE INDEX IF NOT EXISTS report_photos_by_report ON cbm_app.report_photos(report_id, received_at);
CREATE INDEX IF NOT EXISTS report_photos_awaiting_intake ON cbm_app.report_photos(stored_at) WHERE status IN ('STORED','PAUSED');

-- Internal helpers (not granted to the API) ----------------------------------------------------
CREATE OR REPLACE FUNCTION cbm_app.token_hash(p_token text) RETURNS text
LANGUAGE sql IMMUTABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT CASE WHEN p_token ~ '^[0-9a-f]{64}$' THEN encode(sha256(convert_to(p_token,'UTF8')),'hex') END
$$;

-- The input bcrypt sees for a password ('bcrypt-sha256', see password_credentials.scheme).
CREATE OR REPLACE FUNCTION cbm_app.password_input(p_password text) RETURNS text
LANGUAGE sql IMMUTABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT encode(sha256(convert_to(coalesce(p_password,''),'UTF8')),'base64')
$$;

CREATE OR REPLACE FUNCTION cbm_app.register_device(p jsonb) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE did uuid;
BEGIN
 IF coalesce(p->>'id','') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  OR coalesce(p->>'platform','') NOT IN ('ANDROID','IOS') THEN RETURN NULL; END IF;
 did := (p->>'id')::uuid;
 INSERT INTO devices(id,platform,model,os_version,app_version)
 VALUES (did,p->>'platform',left(p->>'model',100),left(p->>'os_version',50),left(p->>'app_version',50))
 ON CONFLICT (id) DO UPDATE SET model=EXCLUDED.model, os_version=EXCLUDED.os_version,
  app_version=EXCLUDED.app_version, last_seen_at=clock_timestamp();
 RETURN did;
END $$;

CREATE OR REPLACE FUNCTION cbm_app.membership_list(p_user uuid) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT coalesce(jsonb_agg(jsonb_build_object('id',m.id,'site_id',m.site_id,'site_name',s.name,
  'role',m.role,'status',m.status,'technician_id',m.technician_id) ORDER BY m.requested_at),'[]'::jsonb)
 FROM memberships m JOIN sites s ON s.id=m.site_id
 WHERE m.user_id=p_user AND m.status IN ('ACTIVE','PENDING')
$$;

-- Issues a one-hour session. The role is bound now if the person has exactly one live membership;
-- otherwise the app asks which one and calls select_membership().
CREATE OR REPLACE FUNCTION cbm_app.open_session(p_user uuid,p_device uuid,p_method text,p_outcome text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE token text := encode(gen_random_bytes(32),'hex'); sid uuid; mid uuid; ids uuid[];
 first_on_device boolean; exp timestamptz := clock_timestamp() + interval '1 hour';
BEGIN
 SELECT array_agg(id) INTO ids FROM memberships WHERE user_id=p_user AND status IN ('ACTIVE','PENDING');
 IF cardinality(ids) = 1 THEN mid := ids[1]; END IF;
 first_on_device := NOT EXISTS (SELECT 1 FROM sessions WHERE user_id=p_user AND device_id=p_device);
 INSERT INTO sessions(token_sha256,user_id,membership_id,device_id,method,expires_at)
 VALUES (token_hash(token),p_user,mid,p_device,p_method,exp) RETURNING id INTO sid;
 UPDATE users SET last_login_at=clock_timestamp(), failed_logins=0, locked_until=NULL WHERE id=p_user;
 INSERT INTO login_events(user_id,email,device_id,method,outcome)
 SELECT id,email,p_device,p_method,p_outcome FROM users WHERE id=p_user;
 RETURN jsonb_build_object('status','OK','token',token,'session_id',sid,'expires_at',exp,
  'new_device',first_on_device,'membership_id',mid,'memberships',membership_list(p_user),
  'user',(SELECT jsonb_build_object('id',id,'email',email,'display_name',display_name) FROM users WHERE id=p_user));
END $$;

-- Entry points (granted to the API) ------------------------------------------------------------

-- Sign-up: email + password + role, or verified Google claims + role. The site comes from the QR.
-- p: {site_code, role, email?, password?, display_name?, google?:{subject,email,email_verified,name}, device}
CREATE OR REPLACE FUNCTION cbm_app.sign_up(p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE g jsonb := p->'google'; v_role text := upper(coalesce(p->>'role','')); v_site text;
 v_email text; v_method text; did uuid; uid uuid; v_status text; tid integer;
BEGIN
 IF v_role NOT IN ('USER','TECHNICIAN','FM') THEN RETURN jsonb_build_object('status','INVALID_ROLE'); END IF;
 SELECT s.id INTO v_site FROM site_access_codes c JOIN sites s ON s.id=c.site_id
 WHERE c.code=p->>'site_code' AND c.revoked_at IS NULL AND s.active;
 IF v_site IS NULL THEN RETURN jsonb_build_object('status','INVALID_SITE_CODE'); END IF;
 IF g IS NOT NULL AND jsonb_typeof(g)='object' THEN
  IF coalesce(g->>'subject','')='' OR coalesce((g->>'email_verified')::boolean,false) IS NOT TRUE THEN
   RETURN jsonb_build_object('status','INVALID_GOOGLE_IDENTITY'); END IF;
  v_email := lower(btrim(g->>'email')); v_method := 'GOOGLE';
 ELSE
  IF coalesce(length(p->>'password'),0) NOT BETWEEN 8 AND 128 THEN
   RETURN jsonb_build_object('status','WEAK_PASSWORD'); END IF;
  v_email := lower(btrim(p->>'email')); v_method := 'PASSWORD';
 END IF;
 IF v_email IS NULL OR length(v_email)>254 OR v_email !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' THEN
  RETURN jsonb_build_object('status','INVALID_EMAIL'); END IF;
 did := register_device(p->'device');
 IF did IS NULL THEN RETURN jsonb_build_object('status','INVALID_DEVICE'); END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('cbm-app-user:'||v_email,0));
 IF EXISTS (SELECT 1 FROM users WHERE email=v_email)
  OR (v_method='GOOGLE' AND EXISTS (SELECT 1 FROM external_identities WHERE provider='GOOGLE' AND subject=g->>'subject')) THEN
  RETURN jsonb_build_object('status','ACCOUNT_EXISTS'); END IF;
 INSERT INTO users(email,display_name)
 VALUES (v_email,left(nullif(btrim(coalesce(p->>'display_name',g->>'name')),''),150)) RETURNING id INTO uid;
 IF v_method='PASSWORD' THEN
  INSERT INTO password_credentials(user_id,password_hash,scheme)
  VALUES (uid,crypt(password_input(p->>'password'),gen_salt('bf',12)),'bcrypt-sha256');
 ELSE
  INSERT INTO external_identities(provider,subject,user_id,email) VALUES ('GOOGLE',g->>'subject',uid,v_email);
 END IF;
 -- USER and TECHNICIAN are open roles. FM can authorize work and close tickets, so it stays a
 -- request until the operator approves it.
 v_status := CASE WHEN v_role='FM' THEN 'PENDING' ELSE 'ACTIVE' END;
 IF v_role='TECHNICIAN' THEN
  -- The workflows dispatch to public.technicians. A new technician gets a new row with no skills,
  -- which dispatch never selects until skills are set. An address that already names a technician
  -- is different: nothing here proves the person signing up owns that mailbox, and the row carries
  -- someone's assigned jobs and history. So a password sign-up for it waits for the operator, who
  -- links it after checking (decide_membership). A Google address is verified by Google, so it
  -- links at once, as long as no other account drives the row.
  SELECT t.id INTO tid FROM public.technicians t WHERE lower(t.email)=v_email ORDER BY t.id LIMIT 1;
  IF tid IS NULL THEN
   INSERT INTO public.technicians(full_name,email,skills)
   VALUES (coalesce(left(nullif(btrim(coalesce(p->>'display_name',g->>'name')),''),150),v_email),v_email,'{}')
   RETURNING id INTO tid;
  ELSIF v_method <> 'GOOGLE'
   OR EXISTS (SELECT 1 FROM memberships m WHERE m.technician_id=tid AND m.status='ACTIVE') THEN
   v_status := 'PENDING';
   tid := NULL;
  END IF;
 END IF;
 INSERT INTO memberships(user_id,site_id,role,status,decided_at,technician_id)
 VALUES (uid,v_site,v_role,v_status,CASE WHEN v_status='ACTIVE' THEN clock_timestamp() END,tid);
 RETURN open_session(uid,did,v_method,'SIGNED_UP');
END $$;

-- Login. p: {email, password, device} or {google:{subject,email,email_verified}, device}
-- Five wrong passwords lock the account for 15 minutes. Unknown email and wrong password are
-- indistinguishable, in answer and in time (a bcrypt is computed either way).
CREATE OR REPLACE FUNCTION cbm_app.login(p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE g jsonb := p->'google'; u users; h text; v_scheme text; did uuid; v_email text; v_method text;
 v_password text := coalesce(p->>'password','');
BEGIN
 did := register_device(p->'device');
 IF did IS NULL THEN RETURN jsonb_build_object('status','INVALID_DEVICE'); END IF;
 IF g IS NOT NULL AND jsonb_typeof(g)='object' THEN
  v_method := 'GOOGLE';
  IF coalesce(g->>'subject','')='' OR coalesce((g->>'email_verified')::boolean,false) IS NOT TRUE THEN
   RETURN jsonb_build_object('status','INVALID_GOOGLE_IDENTITY'); END IF;
  v_email := lower(btrim(g->>'email'));
  SELECT u2.* INTO u FROM external_identities i JOIN users u2 ON u2.id=i.user_id
  WHERE i.provider='GOOGLE' AND i.subject=g->>'subject' FOR UPDATE OF u2;
  IF u.id IS NULL THEN
   -- A verified Google address may link to an existing password account with the same email.
   SELECT * INTO u FROM users WHERE email=v_email FOR UPDATE;
   IF u.id IS NULL THEN RETURN jsonb_build_object('status','NO_ACCOUNT'); END IF;
   INSERT INTO external_identities(provider,subject,user_id,email)
   VALUES ('GOOGLE',g->>'subject',u.id,v_email) ON CONFLICT DO NOTHING;
   IF NOT FOUND THEN RETURN jsonb_build_object('status','ACCOUNT_EXISTS'); END IF;
  END IF;
 ELSE
  v_method := 'PASSWORD';
  v_email := lower(btrim(coalesce(p->>'email','')));
  SELECT * INTO u FROM users WHERE email=v_email FOR UPDATE;
  IF u.id IS NOT NULL AND u.locked_until > clock_timestamp() THEN
   INSERT INTO login_events(user_id,email,device_id,method,outcome) VALUES (u.id,u.email,did,v_method,'LOCKED');
   RETURN jsonb_build_object('status','LOCKED','locked_until',u.locked_until);
  END IF;
  SELECT password_hash, scheme INTO h, v_scheme FROM password_credentials WHERE user_id=u.id;
  IF h IS NULL THEN PERFORM crypt(password_input(v_password),gen_salt('bf',12)); END IF;
  IF h IS NULL OR crypt(CASE WHEN v_scheme='bcrypt-sha256' THEN password_input(v_password) ELSE v_password END,h) <> h THEN
   IF u.id IS NOT NULL THEN
    UPDATE users SET failed_logins = CASE WHEN failed_logins+1 >= 5 THEN 0 ELSE failed_logins+1 END,
     locked_until = CASE WHEN failed_logins+1 >= 5 THEN clock_timestamp()+interval '15 minutes' ELSE locked_until END
    WHERE id=u.id;
   END IF;
   INSERT INTO login_events(user_id,email,device_id,method,outcome)
   VALUES (u.id,left(v_email,254),did,v_method,'INVALID_CREDENTIALS');
   RETURN jsonb_build_object('status','INVALID_CREDENTIALS');
  END IF;
  -- A hash of the raw password (from before 'bcrypt-sha256') is replaced now that the password is
  -- in hand. Only when the password fits in bcrypt's 72 bytes: then the old check saw all of it,
  -- and the new hash binds exactly what the account always had. A longer one keeps working as it
  -- did until the password is changed.
  IF v_scheme <> 'bcrypt-sha256' AND octet_length(v_password) <= 72 THEN
   UPDATE password_credentials SET password_hash=crypt(password_input(v_password),gen_salt('bf',12)),
    scheme='bcrypt-sha256', changed_at=clock_timestamp()
   WHERE user_id=u.id;
  END IF;
 END IF;
 IF u.status <> 'ACTIVE' THEN
  INSERT INTO login_events(user_id,email,device_id,method,outcome) VALUES (u.id,u.email,did,v_method,'DISABLED');
  RETURN jsonb_build_object('status','DISABLED');
 END IF;
 RETURN open_session(u.id,did,v_method,'OK');
END $$;

-- Binds the role for this session when the person holds more than one. A session never changes
-- role; switching means logging in again.
CREATE OR REPLACE FUNCTION cbm_app.select_membership(p_token text,p_membership uuid) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE s sessions;
BEGIN
 SELECT * INTO s FROM sessions WHERE token_sha256=token_hash(p_token)
  AND revoked_at IS NULL AND expires_at > clock_timestamp() FOR UPDATE;
 IF s.id IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 IF s.membership_id IS NOT NULL THEN
  RETURN jsonb_build_object('status',CASE WHEN s.membership_id=p_membership THEN 'OK' ELSE 'ALREADY_BOUND' END,'membership_id',s.membership_id);
 END IF;
 IF NOT EXISTS (SELECT 1 FROM memberships WHERE id=p_membership AND user_id=s.user_id AND status IN ('ACTIVE','PENDING')) THEN
  RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 UPDATE sessions SET membership_id=p_membership WHERE id=s.id;
 RETURN jsonb_build_object('status','OK','membership_id',p_membership);
END $$;

-- The session as the app sees it: works for PENDING memberships too, so the app can show
-- "waiting for approval". Grants nothing.
CREATE OR REPLACE FUNCTION cbm_app.me(p_token text) RETURNS jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT jsonb_build_object('status','OK','expires_at',s.expires_at,
  'user',jsonb_build_object('id',u.id,'email',u.email,'display_name',u.display_name),
  'membership',(SELECT jsonb_build_object('id',m.id,'site_id',m.site_id,'site_name',st.name,'role',m.role,
     'status',m.status,'technician_id',m.technician_id)
   FROM memberships m JOIN sites st ON st.id=m.site_id WHERE m.id=s.membership_id),
  'memberships',membership_list(u.id))
 FROM sessions s JOIN users u ON u.id=s.user_id
 WHERE s.token_sha256=token_hash(p_token) AND s.revoked_at IS NULL
  AND s.expires_at > clock_timestamp() AND u.status='ACTIVE'
$$;

-- The gate every role-restricted call goes through. NULL means 401/403: unknown, expired or revoked
-- token, disabled account, membership not ACTIVE, inactive site, or a role not in p_roles.
CREATE OR REPLACE FUNCTION cbm_app.authenticate(p_token text,p_roles text[]) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE r jsonb; sid uuid;
BEGIN
 SELECT s.id, jsonb_build_object('session_id',s.id,'user_id',u.id,'email',u.email,'membership_id',m.id,
   'role',m.role,'site_id',m.site_id,'technician_id',m.technician_id,'expires_at',s.expires_at)
 INTO sid, r
 FROM sessions s JOIN users u ON u.id=s.user_id
 JOIN memberships m ON m.id=s.membership_id JOIN sites st ON st.id=m.site_id
 WHERE s.token_sha256=token_hash(p_token) AND s.revoked_at IS NULL AND s.expires_at > clock_timestamp()
  AND u.status='ACTIVE' AND m.status='ACTIVE' AND st.active AND m.role = ANY(p_roles);
 IF sid IS NOT NULL THEN UPDATE sessions SET last_used_at=clock_timestamp() WHERE id=sid; END IF;
 RETURN r;
END $$;

CREATE OR REPLACE FUNCTION cbm_app.logout(p_token text) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE s sessions;
BEGIN
 UPDATE sessions SET revoked_at=clock_timestamp()
 WHERE token_sha256=token_hash(p_token) AND revoked_at IS NULL RETURNING * INTO s;
 IF s.id IS NOT NULL THEN
  INSERT INTO login_events(user_id,email,device_id,method,outcome)
  SELECT s.user_id,email,s.device_id,s.method,'LOGOUT' FROM users WHERE id=s.user_id;
 END IF;
 RETURN jsonb_build_object('status','OK');
END $$;

-- Approves or rejects a requested role. p: {membership_id, decision: APPROVE|REJECT, reason?,
--  decider_token? | operator:true, technician_id?, technician?:{skills:[...], zone?}}
-- An FM of the same site decides USER and TECHNICIAN requests; FM and ADMIN requests need an ADMIN
-- of the site or the database operator. The operator path is refused to the API's login, so no
-- API bug can reach it. Nobody decides their own request.
CREATE OR REPLACE FUNCTION cbm_app.decide_membership(p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE m memberships; d jsonb; decider uuid; u users; tid integer;
 v_decision text := upper(coalesce(p->>'decision',''));
BEGIN
 IF v_decision NOT IN ('APPROVE','REJECT') THEN RETURN jsonb_build_object('status','INVALID_DECISION'); END IF;
 SELECT * INTO m FROM memberships WHERE id=(p->>'membership_id')::uuid FOR UPDATE;
 IF m.id IS NULL THEN RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 IF coalesce((p->>'operator')::boolean,false) THEN
  IF session_user = 'cbm_app_api' THEN RETURN jsonb_build_object('status','FORBIDDEN'); END IF;
  decider := NULL;
 ELSE
  d := authenticate(p->>'decider_token',
        CASE WHEN m.role IN ('FM','ADMIN') THEN ARRAY['ADMIN'] ELSE ARRAY['FM','ADMIN'] END);
  IF d IS NULL OR d->>'site_id' <> m.site_id THEN RETURN jsonb_build_object('status','FORBIDDEN'); END IF;
  decider := (d->>'user_id')::uuid;
  IF decider = m.user_id THEN RETURN jsonb_build_object('status','FORBIDDEN'); END IF;
 END IF;
 IF m.status <> 'PENDING' THEN RETURN jsonb_build_object('status','ALREADY_DECIDED','membership_status',m.status); END IF;
 IF v_decision='REJECT' THEN
  IF length(btrim(coalesce(p->>'reason',''))) = 0 THEN RETURN jsonb_build_object('status','REASON_REQUIRED'); END IF;
  UPDATE memberships SET status='REJECTED',decided_at=clock_timestamp(),decided_by=decider,
   decision_reason=left(btrim(p->>'reason'),1000) WHERE id=m.id;
  RETURN jsonb_build_object('status','REJECTED','membership_id',m.id);
 END IF;
 IF m.role='TECHNICIAN' THEN
  SELECT * INTO u FROM users WHERE id=m.user_id;
  tid := nullif(p->>'technician_id','')::integer;
  IF tid IS NULL THEN SELECT id INTO tid FROM public.technicians WHERE lower(email)=u.email; END IF;
  IF tid IS NULL THEN
   IF jsonb_typeof(p#>'{technician,skills}') IS DISTINCT FROM 'array' OR jsonb_array_length(p#>'{technician,skills}') = 0 THEN
    RETURN jsonb_build_object('status','SKILLS_REQUIRED'); END IF;
   INSERT INTO public.technicians(full_name,email,skills,zone)
   VALUES (coalesce(u.display_name,u.email),u.email,
    ARRAY(SELECT jsonb_array_elements_text(p#>'{technician,skills}')),coalesce(p#>>'{technician,zone}','building-A'))
   RETURNING id INTO tid;
  ELSIF NOT EXISTS (SELECT 1 FROM public.technicians WHERE id=tid) THEN
   RETURN jsonb_build_object('status','TECHNICIAN_NOT_FOUND');
  ELSIF EXISTS (SELECT 1 FROM memberships WHERE technician_id=tid AND status='ACTIVE' AND id<>m.id) THEN
   RETURN jsonb_build_object('status','TECHNICIAN_ALREADY_LINKED','technician_id',tid);
  END IF;
 END IF;
 UPDATE memberships SET status='ACTIVE',decided_at=clock_timestamp(),decided_by=decider,
  technician_id=tid,decision_reason=left(nullif(btrim(p->>'reason'),''),1000) WHERE id=m.id;
 RETURN jsonb_build_object('status','APPROVED','membership_id',m.id,'technician_id',tid);
END $$;

-- Reporter capture, step 1 of 2: before the image is stored. Identity comes from the token; the
-- payload is the capture metadata ({capture_id, report_id, building_id, description?, image, camera,
-- target, pose?, client?, captured_at}). Replaying a capture_id is harmless.
CREATE OR REPLACE FUNCTION cbm_app.claim_capture(p_token text,p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; cid uuid; rid uuid; r reports; ph report_photos;
 v_text text := nullif(btrim(coalesce(p->>'description','')),'');
BEGIN
 a := authenticate(p_token,ARRAY['USER']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 IF coalesce(p->>'capture_id','') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
  OR coalesce(p->>'report_id','') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
  RETURN jsonb_build_object('status','INVALID','reason','IDS'); END IF;
 IF p->>'building_id' IS DISTINCT FROM a->>'site_id' THEN RETURN jsonb_build_object('status','SITE_MISMATCH'); END IF;
 -- Named, so the app can say which field to correct rather than "the request is not valid".
 IF length(v_text) > 500 THEN RETURN jsonb_build_object('status','DESCRIPTION_TOO_LONG'); END IF;
 cid := (p->>'capture_id')::uuid; rid := (p->>'report_id')::uuid;
 PERFORM pg_advisory_xact_lock(hashtextextended('cbm-app-report:'||rid,0));
 SELECT * INTO ph FROM report_photos WHERE capture_id=cid;
 IF ph.capture_id IS NOT NULL THEN
  IF ph.report_id <> rid OR NOT EXISTS (SELECT 1 FROM reports WHERE id=rid AND user_id=(a->>'user_id')::uuid)
   OR ph.image_sha256 IS DISTINCT FROM lower(p#>>'{image,sha256}') THEN
   RETURN jsonb_build_object('status','CONFLICT'); END IF;
  RETURN jsonb_build_object('status',CASE WHEN ph.status='RECEIVED' THEN 'UPLOAD' ELSE 'DUPLICATE' END,
   'capture_id',cid,'report_id',rid,'photo_status',ph.status);
 END IF;
 SELECT * INTO r FROM reports WHERE id=rid;
 IF r.id IS NULL THEN
  -- A report id already used by the Drive intake (or anyone else) cannot be adopted.
  IF EXISTS (SELECT 1 FROM public.cbm_intake_reports WHERE id=rid) THEN RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
  INSERT INTO reports(id,user_id,membership_id,site_id,description)
  VALUES (rid,(a->>'user_id')::uuid,(a->>'membership_id')::uuid,a->>'site_id',v_text);
 ELSIF r.user_id <> (a->>'user_id')::uuid THEN
  RETURN jsonb_build_object('status','NOT_FOUND');
 END IF;
 BEGIN
  INSERT INTO report_photos(capture_id,report_id,note,image_sha256,image_width,image_height,
   camera_intrinsics,target_pixel,capture_pose,client,captured_at)
  VALUES (cid,rid,CASE WHEN r.id IS NOT NULL THEN v_text END,lower(p#>>'{image,sha256}'),
   (p#>>'{image,width}')::integer,(p#>>'{image,height}')::integer,p->'camera',p#>'{target,pixel}',
   p->'pose',p->'client',(p->>'captured_at')::timestamptz);
 EXCEPTION WHEN check_violation OR not_null_violation OR invalid_text_representation
  OR invalid_datetime_format OR datetime_field_overflow OR numeric_value_out_of_range THEN
  RAISE EXCEPTION USING ERRCODE='22023', MESSAGE='claim_capture: capture metadata violates the contract ('||SQLERRM||')';
 END;
 RETURN jsonb_build_object('status','UPLOAD','capture_id',cid,'report_id',rid,'reporter_email',a->>'email');
END $$;

-- Reporter capture, step 2 of 2: the API has written the image to its capture store. Marks the photo
-- STORED and tells the workflows (NOTIFY cbm_app_capture, delivered at commit). The app does not
-- start the intake itself: WF1 does, exactly as for a Drive photo. p: {capture_id, image_bytes}
CREATE OR REPLACE FUNCTION cbm_app.store_capture(p_token text,p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb; ph report_photos;
BEGIN
 a := authenticate(p_token,ARRAY['USER']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 IF coalesce(p->>'capture_id','') !~* '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
  RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 SELECT ph2.* INTO ph FROM report_photos ph2 JOIN reports r ON r.id=ph2.report_id
 WHERE ph2.capture_id=(p->>'capture_id')::uuid AND r.user_id=(a->>'user_id')::uuid FOR UPDATE OF ph2;
 IF ph.capture_id IS NULL THEN RETURN jsonb_build_object('status','NOT_FOUND'); END IF;
 IF ph.status = 'RECEIVED' THEN
  UPDATE report_photos SET status='STORED', storage_ref='app-'||capture_id::text, stored_at=clock_timestamp(),
   image_bytes=nullif(p->>'image_bytes','')::integer, photo_url='cbm-app://captures/'||capture_id::text
  WHERE capture_id=ph.capture_id;
  PERFORM pg_notify('cbm_app_capture', ph.capture_id::text);
  RETURN jsonb_build_object('status','STORED','capture_id',ph.capture_id,'report_id',ph.report_id);
 END IF;
 RETURN jsonb_build_object('status',ph.status,'capture_id',ph.capture_id,'report_id',ph.report_id);
END $$;
DROP FUNCTION IF EXISTS cbm_app.attach_capture(text, jsonb);

-- For WF1 (not granted to the API). Returns captures waiting for the intake, shaped as the input of
-- WF1's 'Capture Input' node. With a capture id (from NOTIFY): that capture if it is still waiting.
-- Without (the one-minute sweep): STORED captures older than two minutes, which the NOTIFY path
-- missed, and PAUSED ones last tried over ten minutes ago, oldest first.
CREATE OR REPLACE FUNCTION cbm_app.captures_for_intake(p_capture uuid DEFAULT NULL,p_limit integer DEFAULT 1)
RETURNS SETOF jsonb
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
 SELECT jsonb_build_object('source','APP','capture_id',ph.capture_id,'id',ph.storage_ref,
  'name',ph.storage_ref||'.jpg','report_id',ph.report_id,'reporter_email',u.email,'site_id',r.site_id,
  'webViewLink',ph.photo_url,'description',coalesce(ph.note,r.description),
  'image',jsonb_build_object('width',ph.image_width,'height',ph.image_height,'sha256',ph.image_sha256,'bytes',ph.image_bytes),
  'camera',ph.camera_intrinsics,'target_pixel',ph.target_pixel,'pose',ph.capture_pose,'client',ph.client,
  'captured_at',ph.captured_at,'photo_status',ph.status)
 FROM report_photos ph JOIN reports r ON r.id=ph.report_id JOIN users u ON u.id=r.user_id
 WHERE CASE WHEN p_capture IS NOT NULL THEN ph.capture_id=p_capture AND ph.status IN ('STORED','PAUSED')
  ELSE (ph.status='STORED' AND ph.stored_at < clock_timestamp()-interval '2 minutes')
    OR (ph.status='PAUSED' AND ph.intake_checked_at < clock_timestamp()-interval '10 minutes') END
 ORDER BY ph.stored_at
 LIMIT greatest(1,least(coalesce(p_limit,1),20))
$$;

-- For WF1 (not granted to the API), right after the intake's claim (public.cbm_capture_begin).
-- p: {source, capture_id, capture: <cbm_capture_begin result>}. Records the outcome on the app photo
-- and returns the claim result unchanged, so WF1's next node sees exactly what it saw before.
-- A Drive item (source not APP) passes straight through.
CREATE OR REPLACE FUNCTION cbm_app.record_intake(p jsonb) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE ph report_photos; res jsonb := p->'capture'; v_status text;
BEGIN
 IF p->>'source' IS DISTINCT FROM 'APP' THEN RETURN res; END IF;
 SELECT * INTO ph FROM report_photos WHERE capture_id=(p->>'capture_id')::uuid FOR UPDATE;
 IF ph.capture_id IS NULL OR ph.status NOT IN ('STORED','PAUSED') THEN RETURN res; END IF;
 v_status := CASE
  WHEN coalesce((res->>'process')::boolean,false) THEN 'SUBMITTED'
  WHEN EXISTS (SELECT 1 FROM public.cbm_capture_attempts WHERE file_id=ph.storage_ref AND status='CONFIGURATION_REQUIRED') THEN 'PAUSED'
  WHEN res->>'reason' = 'FILE_ALREADY_RECORDED' THEN 'SUBMITTED'
  ELSE 'NOT_PROCESSED' END;
 UPDATE report_photos SET status=v_status, intake_result=res, intake_checked_at=clock_timestamp(),
  attempt_file_id=CASE WHEN EXISTS (SELECT 1 FROM public.cbm_capture_attempts WHERE file_id=ph.storage_ref) THEN ph.storage_ref END,
  submitted_at=CASE WHEN v_status='SUBMITTED' THEN clock_timestamp() ELSE submitted_at END
 WHERE capture_id=ph.capture_id;
 UPDATE reports SET intake_report_id=id
 WHERE id=ph.report_id AND intake_report_id IS NULL AND EXISTS (SELECT 1 FROM public.cbm_intake_reports WHERE id=ph.report_id);
 RETURN res;
END $$;

-- The reporter's "My reports" list: own reports only, one plain-language status code each.
-- Codes: RECEIVED, ANALYSING, PHOTO_NEEDED, OFFICE_NOTIFIED, AWAITING_FM, NOT_SCHEDULED,
-- IN_PROGRESS, FIXED, ALREADY_REPORTED. The app turns codes into text in the user's language.
CREATE OR REPLACE FUNCTION cbm_app.reporter_reports(p_token text,p_limit integer DEFAULT 50) RETURNS jsonb
LANGUAGE plpgsql SECURITY DEFINER SET search_path = cbm_app, public, pg_temp AS $$
DECLARE a jsonb;
BEGIN
 a := authenticate(p_token,ARRAY['USER']);
 IF a IS NULL THEN RETURN jsonb_build_object('status','UNAUTHENTICATED'); END IF;
 RETURN jsonb_build_object('status','OK','reports',coalesce((
  SELECT jsonb_agg(x.item ORDER BY x.created_at DESC) FROM (
   SELECT r.created_at, jsonb_build_object('report_id',r.id,'description',r.description,'created_at',r.created_at,
    'photos',(SELECT count(*) FROM report_photos WHERE report_id=r.id),
    'attempts_used',coalesce(i.attempts,0),'attempts_left',4-coalesce(i.attempts,0),
    'status',CASE
      WHEN i.id IS NULL THEN 'RECEIVED'
      WHEN i.state IN ('NEW','PROCESSING') THEN 'ANALYSING'
      WHEN i.state = 'AWAITING_PHOTO' THEN 'PHOTO_NEEDED'
      WHEN i.state IN ('IT_ISSUE','CONFIGURATION_REQUIRED') THEN 'OFFICE_NOTIFIED'
      WHEN t.id IS NULL THEN 'ANALYSING'
      WHEN t.intake_report_id IS DISTINCT FROM r.id OR t.status='DUPLICATE' THEN 'ALREADY_REPORTED'
      WHEN t.status='PENDING_AUTHORIZATION' THEN 'AWAITING_FM'
      WHEN t.status='REJECTED' THEN 'NOT_SCHEDULED'
      WHEN t.status='CLOSED' THEN 'FIXED'
      ELSE 'IN_PROGRESS' END,
    'updated_at',greatest(r.created_at,i.updated_at,t.updated_at)) AS item
   FROM reports r
   LEFT JOIN public.cbm_intake_reports i ON i.id=r.intake_report_id
   LEFT JOIN public.tickets t ON t.id=i.ticket_id
   WHERE r.user_id=(a->>'user_id')::uuid AND r.site_id=a->>'site_id'
   ORDER BY r.created_at DESC LIMIT greatest(1,least(coalesce(p_limit,50),200))) x),'[]'::jsonb));
END $$;
COMMIT;
