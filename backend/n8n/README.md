# WF1 app branch

WF1 consumes the app's photos next to its Google Drive photos. The Drive branch is unchanged and
will be deleted later. The app branch ends in the same node, `Capture Input`. Seven downstream nodes
read `$('Capture Input')` by name, so joining there keeps all of them working for both sources.

```
Drive Trigger - New Snapshot ───────────────────────────────────────┐
App Capture Notification (LISTEN cbm_app_capture) ─► Read App Capture ┤
Phase B Recovery Tick ─► Sweep App Captures ────────────────────────┘
                                                                    ▼
Capture Input (shared) ─► Check IFC Registration ─► Claim Capture Attempt ─► Record App Intake
  ─► Capture Accepted? ─► App Capture? ─┬ yes ─► Fetch App Snapshot ─┐
                                        └ no  ─► Download Snapshot ──┴► Prepare Image & Metadata ─► …
```

## How a photo gets from the phone into WF1

1. **Photo stored.** The App API stores the photo, and `cbm_app.store_capture()` sends
   `NOTIFY cbm_app_capture, '<capture id>'`, which is delivered at commit.
2. **n8n picks it up.** `App Capture Notification` (Postgres Trigger, listen mode) starts a run, and
   `Read App Capture` loads the capture with `cbm_app.captures_for_intake(id)`. That function
   returns the capture only while it still waits for the intake, so a repeated or late
   notification does nothing.
3. **Safety net.** Notifications are not queued: one sent while n8n is down is lost. So the
   existing one-minute `Phase B Recovery Tick` also runs `Sweep App Captures`. That picks up
   captures stored over two minutes ago that were never taken, and paused captures due for another
   try (every ten minutes). It takes at most one per tick.
4. **Intake.** `Capture Input` accepts either source. From there the pipeline is the Drive one. The
   intake is started by WF1's own `Claim Capture Attempt` (`cbm_capture_begin`), exactly as for
   Drive. The app never starts it.
5. **Outcome recorded.** `Record App Intake` (`cbm_app.record_intake`) stores the intake's answer on
   the app photo: `SUBMITTED`, `PAUSED` (configuration pause; retried by the sweep) or
   `NOT_PROCESSED`. It returns the claim result unchanged, so `Capture Accepted?` sees what it saw
   before. Drive items pass straight through.
6. **Image fetched.** `Fetch App Snapshot` downloads the photo from the App API's internal service,
   `http://cbm-app-internal:8081`. That service has no published port: only containers on the
   Docker network can reach it.
7. **Calibration kept.** For app photos, `Prepare Image & Metadata` uses the phone's own
   calibration instead of re-estimating it from EXIF. The image is already upright and at most
   1280 px. K counts as trusted only if the phone trusted it **and** the decoded image has the size
   K refers to. The reporter's tap is carried along as `targetPixel`; nothing uses it yet.

## Files

| File | Purpose |
|---|---|
| `wf1_app_branch.py` | Applies the branch to an export of WF1. Checks the expected wiring first; refuses to run twice |
| `test_wf1_app_branch.mjs` | Graph checks and runs of the two changed Code nodes: Drive output must be identical to the original code's |

```bash
python wf1_app_branch.py wf1-live.json wf1-with-app-branch.json
node test_wf1_app_branch.mjs wf1-live.json wf1-with-app-branch.json
```

## Applied on 19 Sep 2026

The base was the WF1 running at the time (version `0a97d2c9…`, saved 08:53, 134 nodes), exported
with `n8n export:workflow`. The patched workflow (140 nodes) was imported in place with
`n8n import:workflow` as version `e58e065a…`. It is **unpublished**, like the base, which had been
deactivated at 16:57 that day. Both files are in
`n8n_deploy\backups\wf1-before-app-branch-20260919-174010`.

Before publishing, check the following:
- The `CBM Postgres - Local Demo` credential is bound on the four new Postgres nodes. The import
  kept the binding by ID.
- The Postgres Trigger holds one connection open while WF1 is published.
