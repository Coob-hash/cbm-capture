-- The exact statement WF1's "Stage Capture Idempotently" node runs, exercised twice.
--
-- The property under test is the one the mobile outbox depends on: a client that never saw the
-- response will send the same package again, and that must return the original request rather
-- than putting the same defect on two work orders.
--
--   psql -v ON_ERROR_STOP=1 -d cbm -f staging_test.sql

\set ON_ERROR_STOP on
\pset pager off

BEGIN;

-- Wrapped in a temporary function rather than PREPARE so it can be called three times and its
-- results collected: `INSERT INTO ... EXECUTE prepared_statement` is not valid Postgres.
--
-- The body is the WF1 node's statement with its positional placeholders $1..$13 renamed to
-- p1..p13, which is the only difference; n8n binds the same thirteen values in the same order.
CREATE FUNCTION pg_temp.stage(
  p1 text, p2 text, p3 text, p4 text, p5 text, p6 text, p7 text,
  p8 jsonb, p9 jsonb, p10 jsonb, p11 timestamptz, p12 text, p13 text
) RETURNS TABLE(request_id uuid, status text, duplicate boolean)
LANGUAGE sql AS $fn$
WITH inserted AS (
  INSERT INTO maintenance_requests(
    source_system, source_file_id, source_file_name, source_mime_type, source_web_url,
    reporter_email, building_id, status,
    camera_intrinsics, target_pixel, capture_pose, captured_at, reporter_description, image_sha256
  ) VALUES (
    'mobile_app', p1, p2, p3, p4, p5, p6, p7::request_status,
    p8, p9, p10, p11, p12, p13
  )
  ON CONFLICT (source_system, source_file_id) DO NOTHING
  RETURNING id, status
), logged AS (
  INSERT INTO ticket_events(request_id, event_type, actor, payload)
  SELECT id, 'CAPTURE_RECEIVED', 'mobile_app',
         jsonb_build_object('capture_id', p1,
                            'intrinsics_source', p8->>'source',
                            'intrinsics_trusted', p8->'trusted')
  FROM inserted
)
SELECT i.id AS request_id, i.status::text AS status, false AS duplicate FROM inserted i
UNION ALL
SELECT r.id, r.status::text, true
  FROM maintenance_requests r
 WHERE r.source_system = 'mobile_app' AND r.source_file_id = p1
   AND NOT EXISTS (SELECT 1 FROM inserted);
$fn$;

CREATE TEMP TABLE stage_result AS
SELECT * FROM pg_temp.stage('cap-1000','cap-1000.jpg','image/jpeg','https://drive/x','w@e.com','ROOM-POC','RECEIVED',
  '{"source":"ARCORE","trusted":true,"fx":900,"fy":900,"cx":480,"cy":640,"width":960,"height":1280}',
  '{"x":100,"y":200}', NULL, '2026-08-27T18:00:00Z', 'Cracked tile', repeat('b',64));

INSERT INTO stage_result
SELECT * FROM pg_temp.stage('cap-1000','cap-1000.jpg','image/jpeg','https://drive/x','w@e.com','ROOM-POC','RECEIVED',
  '{"source":"ARCORE","trusted":true,"fx":900,"fy":900,"cx":480,"cy":640,"width":960,"height":1280}',
  '{"x":100,"y":200}', NULL, '2026-08-27T18:00:00Z', 'Cracked tile', repeat('b',64));

INSERT INTO stage_result
SELECT * FROM pg_temp.stage('cap-1001','cap-1001.jpg','image/jpeg',NULL,'w@e.com','ROOM-POC','NEEDS_LOCALIZATION',
  '{"source":"EXIF","trusted":false,"fx":2900,"fy":2900,"cx":2016,"cy":1512,"width":4032,"height":3024}',
  '{"x":2000,"y":1500}', NULL, '2026-08-27T18:05:00Z', NULL, repeat('c',64));

DO $$
DECLARE
  first_id  uuid;
  second_id uuid;
  first_dup boolean;
  second_dup boolean;
  n integer;
BEGIN
  SELECT request_id, duplicate INTO first_id, first_dup
    FROM stage_result WHERE status = 'RECEIVED' ORDER BY duplicate LIMIT 1;
  SELECT request_id, duplicate INTO second_id, second_dup
    FROM stage_result WHERE status = 'RECEIVED' ORDER BY duplicate DESC LIMIT 1;

  IF first_dup IS NOT FALSE THEN
    RAISE EXCEPTION 'REGRESSION: the first submission was reported as a duplicate';
  END IF;
  RAISE NOTICE 'OK  the first submission is not a duplicate';

  IF second_dup IS NOT TRUE THEN
    RAISE EXCEPTION 'REGRESSION: the replay was not reported as a duplicate';
  END IF;
  RAISE NOTICE 'OK  a replayed capture_id is reported as a duplicate';

  IF first_id <> second_id THEN
    RAISE EXCEPTION 'REGRESSION: the replay returned a different request_id (% vs %)', first_id, second_id;
  END IF;
  RAISE NOTICE 'OK  the replay returns the original request_id';

  SELECT count(*) INTO n FROM maintenance_requests
   WHERE source_system='mobile_app' AND source_file_id='cap-1000';
  IF n <> 1 THEN
    RAISE EXCEPTION 'REGRESSION: cap-1000 produced % rows', n;
  END IF;
  RAISE NOTICE 'OK  exactly one request row per capture_id';

  -- An event per replay would make the audit trail lie about how many reports arrived.
  SELECT count(*) INTO n FROM ticket_events
   WHERE event_type='CAPTURE_RECEIVED' AND payload->>'capture_id'='cap-1000';
  IF n <> 1 THEN
    RAISE EXCEPTION 'REGRESSION: cap-1000 logged % CAPTURE_RECEIVED events', n;
  END IF;
  RAISE NOTICE 'OK  the replay logs no second event';

  SELECT count(*) INTO n FROM maintenance_requests
   WHERE source_file_id='cap-1001' AND status='NEEDS_LOCALIZATION'
     AND camera_intrinsics->>'source'='EXIF';
  IF n <> 1 THEN
    RAISE EXCEPTION 'REGRESSION: untrusted capture was not staged as NEEDS_LOCALIZATION';
  END IF;
  RAISE NOTICE 'OK  untrusted intrinsics stage as NEEDS_LOCALIZATION with provenance intact';
END $$;

ROLLBACK;

\echo ''
\echo 'staging_test.sql: all assertions held'
