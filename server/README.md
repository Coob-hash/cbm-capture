# Server side: webhook intake and device intrinsics

The n8n and PostgreSQL half of the capture design. Turns the CBM pipeline from *fail-open* —
where a fabricated `K` silently unprojects onto the wrong element and `resolve_ray` still
returns `found: true` — into *fail-closed*, where a request whose calibration cannot be trusted
is routed to a human instead.

```
server/
  sql/
    000_base_schema.reference.sql   vendored copy of release 2026_07_13 schema, for CI only
    001_add_camera_intrinsics.sql   the migration
    tests/constraints_test.sql      self-asserting; fails if a constraint stops working
    tests/staging_test.sql          the exact idempotent INSERT that WF1 runs
  n8n/
    WF1_Capture_Intake.json         NEW: webhook intake, replaces the Drive trigger
    WF2_EOD_AI_Agent_and_FM_Approval.json   patched: reads K from the request
    build_workflows.py              generates both; the diff to WF2 lives here
    validate_workflows.py           structural checks
    test_code_nodes.mjs             runs the Code nodes' JavaScript against fixtures
```

---

## What changes, and why

| # | Change | Reason |
|---|--------|--------|
| 1 | `maintenance_requests` gains `camera_intrinsics`, `target_pixel`, `capture_pose`, `captured_at`, `reporter_description`, `image_sha256` | The calibration exists only on the handset at capture time. If it is not stored with the request, it is gone. |
| 2 | Two CHECK constraints | The frame invariant, enforced by the database — the one place that survives a hand-written INSERT or a future workflow. |
| 3 | WF1 intake becomes `POST /cbm/capture` | The image and its calibration arrive as one atomic package, instead of two objects that must be correlated. |
| 4 | `Intrinsics Trusted?` gate in WF2 | Converts the fail-open unprojection into fail-closed, and saves a vision + MultiSet call on hopeless items. |
| 5 | `Prepare Image and Intrinsics` reads `camera_intrinsics` | The `$env.CAMERA_FX \|\| 1200` fallback is deleted, not merely overridden. |
| 6 | `Parse Visual Evidence` prefers the worker's tap | A tap is an observation; a bounding box is a generation. |
| 7 | The vision prompt is given the real dimensions | It asked for pixels "using the supplied image dimensions" and never supplied them — the live defect in §3 of the calibration note. |

Google Drive stops being the intake mechanism and becomes image storage.

---

## Deploying

### 1. Migration

```bash
psql -v ON_ERROR_STOP=1 -d cbm -f sql/001_add_camera_intrinsics.sql
```

Idempotent and additive — every new column is nullable, so rows created by the Drive-trigger
flow keep working and nothing needs backfilling. No downtime.

### 2. Import the workflows

Import `n8n/WF1_Capture_Intake.json` and `n8n/WF2_EOD_AI_Agent_and_FM_Approval.json`.
`WF1_Capture_Intake` **replaces** the old `CBM WF1 - Intake and Staging`; deactivate that one
rather than running both, or a photograph dropped in Drive will be staged without intrinsics.

Then, by hand, because credentials are never in exported JSON:

- **Header Auth credential** on `Capture Webhook` and `Health Webhook` — name `Authorization`,
  value `Bearer <token>`, matching what is provisioned on each handset.
- **Google Drive** credential on `Store Image in Drive`.
- **Postgres** credential on the two Postgres nodes.

### 3. Environment

```diff
- CAMERA_WIDTH=1920
- CAMERA_HEIGHT=1440
- CAMERA_FX=1450
- CAMERA_FY=1450
- CAMERA_PX=960
- CAMERA_PY=720
```

Delete them. Leaving them defined is harmless today because nothing reads them, but it invites
someone to reintroduce the fallback the next time a device reports no calibration.

`CBM_BUILDING_ID`, `INCOMING_DRIVE_FOLDER_ID`, `IFC_SERVICE_URL`, `MULTISET_MAP_CODE` and
`MULTISET_CONFIDENCE_THRESHOLD` are unchanged.

### 4. Point the apps at it

In the app's Settings screen, the server address is the n8n webhook base **without**
`/cbm/capture` — for example `https://n8n.example.com/webhook`. Use **Test connection**: it
calls `/cbm/capture/health` and reports a building-ID mismatch explicitly, which is the
misconfiguration worth catching in an office rather than in a basement.

---

## Verifying a change

```bash
# structure: connections and $('Node') references resolve
python n8n/validate_workflows.py n8n/*.json

# behaviour: the Code nodes' JavaScript, against fixtures
node n8n/test_code_nodes.mjs

# database: constraints and the idempotent staging query, against a real Postgres
psql -v ON_ERROR_STOP=1 -d cbm_test -f sql/000_base_schema.reference.sql
psql -v ON_ERROR_STOP=1 -d cbm_test -f sql/001_add_camera_intrinsics.sql
psql -v ON_ERROR_STOP=1 -d cbm_test -f sql/tests/constraints_test.sql
psql -v ON_ERROR_STOP=1 -d cbm_test -f sql/tests/staging_test.sql
```

All four run in CI on every push.

`constraints_test.sql` wraps each expected rejection in a block that raises if the insert
*succeeds*, so removing a constraint fails the suite. That was checked by removing one: the
suite exits non-zero and names the regression.

---

## The request lifecycle, after this change

```
  app  ──POST /cbm/capture──►  Capture Webhook
                                    │
                              Hash Uploaded Image        sha256 of the received bytes
                                    │
                              Validate Capture Package   7 checks
                                    │
                         ┌──────────┴───────────┐
                      valid                  invalid
                         │                       │
                 Store Image in Drive    Record Rejected Capture  → 400/422
                         │
                 Stage Capture Idempotently      ON CONFLICT DO NOTHING
                         │
                 ┌───────┴────────┐
              trusted         untrusted
                 │                │
              200 OK       422 UNTRUSTED_INTRINSICS
                                  (recorded as NEEDS_LOCALIZATION)
```

Untrusted intrinsics get a 422 deliberately: the app's outbox treats it as permanent, stops
retrying, and tells the worker the office will place the report by hand. The report is not lost
— it is staged, and appears in the FM's queue for manual localization.

---

## What is deliberately not done

- **No backfill.** Requests captured before the app have no intrinsics and never will. They
  degrade to `NEEDS_LOCALIZATION` rather than being unprojected with a guess.
- **The AR pose is stored but unused.** `capture_pose` is in the device's own world frame, which
  has no relationship to the MultiSet map or `T_VPS_TO_IFC`. Feeding it to `/elements/resolve-ray`
  would produce a confidently wrong GlobalId. It is kept so the device pose and the VPS pose can
  be compared offline.
- **`skew` is stored but unused.** The ray math uses only `fx, fy, cx, cy`.
- **The bounding box is still requested from the vision model.** Its centre is the fallback when
  a request has no tap, which is every pre-app request.
