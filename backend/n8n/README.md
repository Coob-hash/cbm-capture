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


## WF2: a report written in the app

    python wf2_app_branch.py <wf2-export.json> <out.json> <release>/cbm/templates/technician-report/report-pdf.js
    node test_wf2_app_branch.mjs <wf2-export.json> <out.json>

The same shape as the WF1 branch: a notification (`cbm_app_report`), a one-minute sweep behind it,
and a join at the node the rest of the workflow reads by name — here `Extract Ticket ID`, because
`Download Report PDF` and `Extract Report Text and Photo` both ask it for their data.

What is new is the document. The app sends the template's fields, never a PDF; `Render Report PDF`
makes one with the template's own renderer (embedded by the script, so it is always the release's
current version) and hands it to the extraction step exactly as the Drive download does. It needs
`pdf-lib` in the JavaScript runner, allowed the way `pdf-parse` already is:
`cbm/Dockerfile.runners`, `cbm/task-runners.json` and the `n8n` service's
`NODE_FUNCTION_ALLOW_EXTERNAL` in the workflow release.

`Record App Submission` then claims the report for its approval cycle through the workflows' own
`cbm_claim_technician_report()`, so one cycle still takes exactly one report whichever route it came
by, and writes the outcome back to `cbm_app.technician_reports`.

```
App Report? ─┬ yes ─► Render Report PDF ─► Record App Submission ─► App Report Claimed? ─┐
             └ no  ─► Download Report PDF ───────────────────────────────────────────────┴► Extract Report Text and Photo
… ─► Set Pending Approval ─► Approval Cycle        (unchanged)
```

The claim runs **as soon as the document exists**, while the ticket is still the technician's to
report (`ASSIGNED`, `REWORK`). That is the only time the workflows' claim accepts a report, and the
cycle it is claimed for is the one the ticket is in. `Set Pending Approval` moves the ticket on and
opens the next cycle, and `Approval Cycle` reads the ticket id and `approval_id` from the row it
returns, so nothing may stand between them.

The branch applied on 21 Sep did both of those wrong: it claimed after `Set Pending Approval`, and
its claim node stood between that and `Approval Cycle`. Every app report came back `NOT_PROCESSED`,
and the FM review loop never started (third audit, 25 Sep, finding 1).

- **`App Report Claimed?`** lets the run continue only when the database answers `proceed: true`,
  for a new claim or for a claim resumed by the sweep. Otherwise the run stops there, with the reason
  already written on the app's row, and the app offers the job to be reported again. The node hands
  the extraction the PDF from `Render Report PDF`, as the Drive download does.
- **A run that stops after the claim** (the assessment fails, say) leaves the claim in place. After
  ten minutes the sweep offers the report again, and `record_app_report_submission` answers with the
  same claim, so the run finishes. Once `Set Pending Approval` has taken it, it is not offered again.
- **A report written in an earlier round** (the job was reported another way and sent back before WF2
  took this one) is not claimed for the new round.

### Applying it to the running WF2

The live WF2 has had the branch of 21 Sep since that day. `--upgrade` removes it (its eight nodes,
the two connections it rerouted, its lines in `Extract Ticket ID`) and adds the current one, keeping
every other change made since:

    python wf2_app_branch.py --upgrade wf2-live.json wf2-upgraded.json <release>/cbm/templates/technician-report/report-pdf.js

**Order matters.** `App Report Claimed?` reads the `proceed` flag, which only the database functions
of this version return. Install the backend first (`deploy/Install-CbmApp.ps1`, which applies the
migrations), then import the workflow. Importing it before the migrations would stop every app
report at `App Report Claimed?`.
