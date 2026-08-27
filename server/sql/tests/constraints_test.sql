-- Constraint tests for migration 001.
--
-- Run with ON_ERROR_STOP=1. Every expected rejection is wrapped in a block that raises if the
-- insert *succeeds*, so a constraint that stops working fails this script rather than printing
-- an error nobody reads. A test that cannot fail is not a test.
--
--   psql -v ON_ERROR_STOP=1 -d cbm -f constraints_test.sql

\set ON_ERROR_STOP on
\pset pager off

BEGIN;

-- ---------------------------------------------------------------------------
-- Accepted
-- ---------------------------------------------------------------------------

INSERT INTO maintenance_requests(
  source_system, source_file_id, source_file_name, source_mime_type,
  reporter_email, building_id, status,
  camera_intrinsics, target_pixel, captured_at, image_sha256
) VALUES (
  'mobile_app', 'cap-0001', 'cap-0001.jpg', 'image/jpeg',
  'worker@example.com', 'ROOM-POC', 'RECEIVED',
  '{"source":"ARKIT","trusted":true,"fx":954.63,"fy":955.07,"cx":480.19,"cy":639.65,"width":960,"height":1280,"skew":0}'::jsonb,
  '{"x":512.0,"y":706.5,"centrality":0.104}'::jsonb,
  '2026-08-27T18:32:14.482Z', repeat('a',64)
);
\echo 'OK  a well-formed capture package is accepted'

-- An untrusted package is a real defect report and is still recorded; only the automatic
-- grounding is withheld.
INSERT INTO maintenance_requests(
  source_system, source_file_id, source_file_name, building_id, status,
  camera_intrinsics, target_pixel
) VALUES (
  'mobile_app', 'cap-0006', 'cap-0006.jpg', 'ROOM-POC', 'NEEDS_LOCALIZATION',
  '{"source":"EXIF","trusted":false,"fx":1450,"fy":1450,"cx":960,"cy":720,"width":1920,"height":1440}'::jsonb,
  '{"x":900.0,"y":700.0}'::jsonb
);
\echo 'OK  an untrusted EXIF package is accepted for manual placement'

-- Rows created before the mobile app existed must keep working.
INSERT INTO maintenance_requests(
  source_system, source_file_id, source_file_name, building_id, status
) VALUES ('google_drive','legacy-1','report_x.jpg','ROOM-POC','RECEIVED');
\echo 'OK  legacy Drive rows still insert (new columns are nullable)'

-- ---------------------------------------------------------------------------
-- Rejected - each of these MUST raise
-- ---------------------------------------------------------------------------

DO $$
BEGIN
  BEGIN
    INSERT INTO maintenance_requests(
      source_system, source_file_id, source_file_name, building_id, status,
      camera_intrinsics, target_pixel
    ) VALUES (
      'mobile_app','cap-0002','cap-0002.jpg','ROOM-POC','RECEIVED',
      '{"source":"ARKIT","trusted":true,"fx":954.63,"fy":955.07,"cx":480.19,"cy":639.65,"width":960,"height":1280}'::jsonb,
      '{"x":5000.0,"y":10.0}'::jsonb);
    RAISE EXCEPTION 'REGRESSION: a target pixel outside the frame was accepted';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'OK  a target pixel outside the frame is rejected';
  END;

  BEGIN
    INSERT INTO maintenance_requests(
      source_system, source_file_id, source_file_name, building_id, status, camera_intrinsics
    ) VALUES (
      'mobile_app','cap-0003','cap-0003.jpg','ROOM-POC','RECEIVED',
      '{"source":"ARKIT","trusted":true,"fx":954.63,"fy":955.07,"cy":639.65,"width":960,"height":1280}'::jsonb);
    RAISE EXCEPTION 'REGRESSION: intrinsics missing cx were accepted';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'OK  intrinsics missing cx are rejected';
  END;

  BEGIN
    INSERT INTO maintenance_requests(
      source_system, source_file_id, source_file_name, building_id, status, camera_intrinsics
    ) VALUES (
      'mobile_app','cap-0004','cap-0004.jpg','ROOM-POC','RECEIVED',
      '{"source":"EXIF","trusted":false,"fx":0,"fy":955.07,"cx":480,"cy":640,"width":960,"height":1280}'::jsonb);
    RAISE EXCEPTION 'REGRESSION: a non-positive focal length was accepted';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'OK  a non-positive focal length is rejected';
  END;

  BEGIN
    INSERT INTO maintenance_requests(
      source_system, source_file_id, source_file_name, building_id, status, camera_intrinsics
    ) VALUES (
      'mobile_app','cap-0005','cap-0005.jpg','ROOM-POC','RECEIVED',
      '{"source":"ARKIT","trusted":"true","fx":954,"fy":955,"cx":480,"cy":640,"width":960,"height":1280}'::jsonb);
    RAISE EXCEPTION 'REGRESSION: a string "true" was accepted as the trusted flag';
  EXCEPTION WHEN check_violation THEN
    RAISE NOTICE 'OK  the trusted flag must be a real boolean';
  END;
END $$;

-- ---------------------------------------------------------------------------
-- Idempotency and reporting
-- ---------------------------------------------------------------------------

INSERT INTO maintenance_requests(
  source_system, source_file_id, source_file_name, building_id, status
) VALUES ('mobile_app','cap-0001','cap-0001.jpg','ROOM-POC','RECEIVED')
ON CONFLICT (source_system, source_file_id) DO NOTHING;

DO $$
DECLARE n integer;
BEGIN
  SELECT count(*) INTO n FROM maintenance_requests
   WHERE source_system='mobile_app' AND source_file_id='cap-0001';
  IF n <> 1 THEN
    RAISE EXCEPTION 'REGRESSION: replaying a capture_id produced % rows', n;
  END IF;
  RAISE NOTICE 'OK  replaying a capture_id does not duplicate the request';
END $$;

DO $$
DECLARE n integer;
BEGIN
  SELECT count(*) INTO n FROM cbm_intrinsics_accuracy;
  IF n < 3 THEN
    RAISE EXCEPTION 'REGRESSION: ablation view returned % groups, expected at least 3', n;
  END IF;
  RAISE NOTICE 'OK  the ablation view groups by calibration provenance';
END $$;

ROLLBACK;

\echo ''
\echo 'constraints_test.sql: all assertions held'
