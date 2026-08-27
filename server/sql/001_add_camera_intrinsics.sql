-- CBM Capture, server migration 001
--
-- Adds the columns the mobile capture package needs, and expresses the frame invariant as a
-- database constraint rather than only as application logic.
--
-- Apply after the release 2026_07_13 schema.sql. Idempotent: safe to run twice.
--
--   psql -v ON_ERROR_STOP=1 -d cbm -f 001_add_camera_intrinsics.sql

BEGIN;

-- ---------------------------------------------------------------------------
-- Columns
-- ---------------------------------------------------------------------------

ALTER TABLE maintenance_requests
  -- Pinhole intrinsics in the coordinate system of the stored image, plus provenance:
  --   {"source":"ARKIT","trusted":true,"fx":..,"fy":..,"cx":..,"cy":..,
  --    "width":960,"height":1280,"skew":0,"lens":"wide"}
  --
  -- jsonb rather than eight columns because iOS, ARCore and Camera2 report slightly different
  -- accompanying fields (skew, lens, distortion) and the set will grow; the four numbers the
  -- ray math actually needs are guaranteed by the shape constraint below.
  ADD COLUMN IF NOT EXISTS camera_intrinsics jsonb,

  -- The worker's tap, in that same pixel frame: {"x":512.0,"y":706.5,"centrality":0.104}.
  -- This is what replaces the vision model's bounding-box centre as the ray origin.
  ADD COLUMN IF NOT EXISTS target_pixel jsonb,

  -- The AR session's own world pose. ADVISORY ONLY: it is not in the MultiSet or IFC frame and
  -- must never be fed to /elements/resolve-ray. Stored so the device pose and the VPS pose can
  -- be compared offline.
  ADD COLUMN IF NOT EXISTS capture_pose jsonb,

  -- Shutter time from the device, which is not the upload time: an outbox item may arrive days
  -- late, and the end-of-day batch should reason about when the damage was seen.
  ADD COLUMN IF NOT EXISTS captured_at timestamptz,

  -- Free text typed by the worker. Supplements, never replaces, the vision analysis.
  ADD COLUMN IF NOT EXISTS reporter_description text,

  -- sha256 of the stored image, so a later re-download can be checked against what was received.
  ADD COLUMN IF NOT EXISTS image_sha256 text;

-- ---------------------------------------------------------------------------
-- Shape: the four numbers the ray math needs must be present
-- ---------------------------------------------------------------------------

ALTER TABLE maintenance_requests
  DROP CONSTRAINT IF EXISTS maintenance_requests_camera_intrinsics_shape;

ALTER TABLE maintenance_requests
  ADD CONSTRAINT maintenance_requests_camera_intrinsics_shape CHECK (
    camera_intrinsics IS NULL OR (
      camera_intrinsics ?& array['source','trusted','fx','fy','cx','cy','width','height']
      AND jsonb_typeof(camera_intrinsics->'trusted') = 'boolean'
      AND (camera_intrinsics->>'fx')::numeric > 0
      AND (camera_intrinsics->>'fy')::numeric > 0
      AND (camera_intrinsics->>'width')::numeric > 0
      AND (camera_intrinsics->>'height')::numeric > 0
    )
  );

-- ---------------------------------------------------------------------------
-- The frame invariant, enforced by the database
-- ---------------------------------------------------------------------------
--
-- The target pixel must lie inside the frame that K describes. The app checks this before it
-- persists anything, and the webhook checks it on receipt; this is the third and last line,
-- and the only one that survives someone inserting a row by hand or by a future workflow.
--
-- A row that violates it would unproject a pixel from one coordinate system using a principal
-- point from another - the exact defect this whole change exists to remove.

ALTER TABLE maintenance_requests
  DROP CONSTRAINT IF EXISTS maintenance_requests_target_pixel_in_frame;

ALTER TABLE maintenance_requests
  ADD CONSTRAINT maintenance_requests_target_pixel_in_frame CHECK (
    target_pixel IS NULL OR camera_intrinsics IS NULL OR (
          (target_pixel->>'x')::numeric >= 0
      AND (target_pixel->>'y')::numeric >= 0
      AND (target_pixel->>'x')::numeric < (camera_intrinsics->>'width')::numeric
      AND (target_pixel->>'y')::numeric < (camera_intrinsics->>'height')::numeric
    )
  );

-- ---------------------------------------------------------------------------
-- Indexes
-- ---------------------------------------------------------------------------

-- The accuracy ablation groups by how the camera was calibrated: hardcoded vs EXIF vs factory.
CREATE INDEX IF NOT EXISTS maintenance_requests_intrinsics_source_idx
  ON maintenance_requests ((camera_intrinsics->>'source'));

-- Finding what still needs a human is a routine query for the FM.
CREATE INDEX IF NOT EXISTS maintenance_requests_untrusted_idx
  ON maintenance_requests ((camera_intrinsics->>'trusted'))
  WHERE camera_intrinsics IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Reporting view for the evaluation protocol
-- ---------------------------------------------------------------------------
--
-- Turns section 8 of the calibration note from a study into a query: per calibration source and
-- device, how many requests were grounded to an IFC occurrence and how many needed a human.

CREATE OR REPLACE VIEW cbm_intrinsics_accuracy AS
SELECT
  COALESCE(r.camera_intrinsics->>'source', 'NONE')          AS intrinsics_source,
  COALESCE(r.camera_intrinsics->>'trusted', 'unknown')      AS intrinsics_trusted,
  r.source_system,
  COUNT(*)                                                  AS request_count,
  COUNT(*) FILTER (WHERE bi.ifc_global_id IS NOT NULL)       AS grounded_count,
  COUNT(*) FILTER (WHERE r.status = 'NEEDS_LOCALIZATION')    AS needs_localization_count,
  ROUND(AVG(bi.confidence) FILTER (WHERE bi.confidence IS NOT NULL), 4) AS mean_localization_confidence
FROM maintenance_requests r
LEFT JOIN batch_items bi ON bi.request_id = r.id
GROUP BY 1, 2, 3;

COMMENT ON VIEW cbm_intrinsics_accuracy IS
  'Grounding outcomes grouped by camera-calibration provenance. Backs the hardcoded-K vs '
  'EXIF-K vs factory-K ablation in section 8 of the camera intrinsics note.';

COMMIT;
