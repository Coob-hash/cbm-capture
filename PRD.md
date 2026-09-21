# CBM App — Product Requirements Document

**Product:** CBM App, the single mobile front end of the Community-Based Maintenance pipeline, with n8n as its backend
**Version:** 2.0 (draft for discussion)
**Date:** 20 September 2026
**Status:** Requirements rewritten for role-based access. Decided 19 Sep 2026: authentication (Q1),
Android-first on Kotlin Multiplatform (Q5), roles per site (Q6), self sign-up (Q8), a dedicated App
API over a shared database (Q2; Q3 falls away) — see § 14. Decided 20 Sep 2026: users and technicians
join freely and only FMs are approved; the FM decides from the card *and* from the chat (Q10); every
active FM of a site sees that site's queue (Q12, in part); a technician sets their own skills (Q17).
**Live on the deployment:** schema `cbm_app`, the account endpoints, the reporter's capture, and —
since 20 Sep — the FM's two decisions and the technician's offers, jobs and skills (`backend/`).
**Not built yet:** the technician and FM screens. Other details still open.
**Screens under review:** an interactive prototype of the FM, technician and reporter screens exists
(canvas shared with the team, deliberately not linked from this public repository).
**Supersedes:** PRD 1.0 of 27 August 2026 as the product definition. PRD 1.0 is kept, unchanged, as
[`docs/PRD_v1_capture.md`](docs/PRD_v1_capture.md): its capture, intrinsics, queue and wire-contract
requirements remain **normative** for the reporter's capture flow and are referenced, not repeated, here.
**Backend baseline:** workflow release `16_09_2026 CBM OpenRouter Vision Release` (WF1 intake and
dispatch, Technician Report Portal, WF2 completion approval, WF3 FM dashboard with guarded actions).

---

## 1. Where things stand

### 1.1 The app today (pre-alpha)

| Area | State |
|---|---|
| Android (Kotlin, Compose, ARCore) | Builds, 16/16 unit tests pass, debug APK assembles. Four screens: Capture, Review, My Reports, Settings. |
| iOS (Swift 6, SwiftUI, ARKit) | Builds on CI (macOS runner), 17/17 tests pass. Not yet run on a physical iPhone. |
| Capture pipeline | Complete: factory K, tap-as-shutter, image/K/tap transform, plausibility gate, durable outbox. |
| Contract | `POST /cbm/capture` multipart, JSON Schema + OpenAPI, mock server with 19/19 assertions. |
| Identity | **None.** One shared bearer token per handset; reporter email is a free-text setting. |
| Roles | **None.** Every user is a reporting worker. PRD 1.0 declared technician workflows and ticket browsing out of scope. |

### 1.2 The integration gap with the current workflows

The app's `server/` half was written against release `2026_07_13` and is **not compatible with the
current backend**:

| App assumes (2026_07_13) | Current backend (16_09_2026) |
|---|---|
| Staging table `maintenance_requests`, idempotency on `source_file_id` | Intake is `cbm_intake_reports` + `cbm_capture_attempts`, driven by `cbm_capture_begin()` / `cbm_capture_failed()` / `cbm_capture_identified()`; tickets are created in `PENDING_AUTHORIZATION` |
| WF1 triggered by a webhook | WF1 triggered by a **Google Drive** `fileCreated` trigger; reporter email and report UUID are encoded in the file name (`report_<email>_<UUID>_<photo>.jpg`) |
| One photo per report | Up to **four captures per report** (initial + 3 replacements) under one report UUID; the fourth failure opens an IT issue |
| Factory K from the handset | Case-study pipeline uses `EXIF_ESTIMATE_NOT_FACTORY_CALIBRATION` — the problem the app was built to solve is still live |
| Everything after capture happens by email | Still true: FM authorization (`/cbm-wf1-authorize`), technician offers (`/cbm-wf1-offer`), technician report (`/cbm-technician-report` portal → PDF → Drive), completion approval (shared email form) and the FM chat (n8n hosted chat, `n8nUserAuth`) are all separate web/email surfaces |

So "merging the workflows with the app" means two things: (a) re-porting the capture intake onto the
current intake functions, and (b) giving each email/web surface above an in-app equivalent for the
role that uses it. The database functions and guards that make the workflows safe are **reused as-is**;
the app never writes ticket state directly.

---

## 2. What v2 is

**One app, one login screen, three role-specific experiences.** The role is *requested* at sign-up
and *granted* by the server (§ 3); after login the app opens directly into the granted role's home
and shows nothing belonging to the other roles.

| Role | Login # | Home | In one sentence |
|---|---|---|---|
| **Reporter** (user) | 1 | My reports + "Open a report" | Take a photo of a problem, optionally say what is wrong, see what happened to it. |
| **Technician** | 2 | Jobs dashboard | Accept proposed jobs, work them, fill the report template, see completed work. |
| **Facility Manager (FM)** | 3 | Split screen: dashboard above, agent chat below | See the state of the building at a glance and act through the WF3 agent. |
| Admin | — | *No app experience in v2* | Manages accounts and roles on the backend (§ 8). |

### Goals

| # | Goal | Measured by |
|---|---|---|
| G1 | Each role sees only what it needs and can do only what it is allowed to | Server-side role check on 100% of endpoints; zero cross-role data in responses (§ 12 tests) |
| G2 | The app replaces email/web links as the primary channel for every role | Share of authorizations, offer responses, reports and approvals performed in-app |
| G3 | No new path around the existing guards | Every state change goes through the same DB function the email/chat path uses |
| G4 | The capture guarantees of PRD 1.0 survive intact | PRD 1.0 acceptance criteria 1–8 still pass |
| G5 | A reporter files a report in ≤ 15 s; a technician submits a report without leaving the app | Timed walkthroughs |

### Non-goals for v2

- **Admin UI** in the app. Accounts are provisioned on the backend.
- **Reporters seeing or acting on tickets.** They see the status of their own reports only.
- **FM editing assets or GlobalIds.** Removed in release 2026.09.15 and stays removed.
- **Technicians changing ticket status.** A submitted report is a declaration; WF2 and the FM decide.
- **Replacing WF1/WF2/WF3 logic.** The app is a client of the workflows, not a re-implementation.

---

## 3. Roles and permissions

The matrix below is the contract the server enforces. "Existing function" is what the app's
endpoint ultimately calls, so the app inherits every guard those functions already carry.

| Capability | Reporter | Technician | FM | Existing function / workflow reused |
|---|:-:|:-:|:-:|---|
| Take a photo and submit a report (+ optional description) | ✅ | — | — | WF1 capture path → `cbm_capture_begin()` |
| Send a replacement photo when asked | ✅ own report | — | — | `cbm_capture_begin()` with the same `report_id` |
| See status of own reports | ✅ own | — | — | `cbm_intake_reports`, `tickets` (read) |
| Receive new job offers | — | ✅ own | — | Dispatch helpers, `CBM_DISPATCH_STATE` offers |
| Accept / decline an offer | — | ✅ own | — | `cbm_record_offer_response()` → Dispatch - Process Responses |
| See accepted jobs pending report (`ASSIGNED`, `REWORK`) | — | ✅ own | — | `tickets` where `technician_id` = self |
| Fill and submit the report template | — | ✅ own job | — | `cbm_technician_report_access()` → `cbm_claim_technician_report()` → `cbm_record_technician_report()` → WF2 |
| See completed-work dashboard | — | ✅ own | — | `tickets`, `cbm_technician_submissions` (read) |
| Building dashboard (all tickets, KPIs) | — | — | ✅ | WF3 read queries |
| Chat with the agent | — | — | ✅ | WF3 `FM Dashboard Agent` (9 read tools + 5 guarded actions) |
| Authorize / reject intervention | — | — | ✅ | `approve_intervention` / `reject_intervention` → `cbm_authorize_dispatch()` |
| Approve completion / request rework | — | — | ✅ | `approve_completion` / `request_rework` → Guarded FM Ticket Action |
| Manage accounts and roles | — | — | — | Admin, backend only |

Rules that follow from the matrix:

- **Ownership is derived, never supplied.** A reporter's identity comes from the session, not from a
  `reporter_email` field; a technician's `technician_id` comes from the session, not from the request.
  Today both are client-supplied (file name / settings field / email link token) and v2 removes that.
- **Roles belong to a site membership, not to the account.** One account (one email) can hold
  several memberships — different sites, or two roles on one site. Each **session is bound to exactly
  one membership**, so every screen and endpoint still serves a single role; switching role means
  logging in again. This also covers several FMs across the places where the app is installed.
- **Users and technicians join freely; only FMs are approved.** Anyone with the site's code can
  sign up as `USER` or `TECHNICIAN`, active at once: nobody confirms them. A technician account is
  linked to the `technicians` row with the same email (keeping its skills) or gets a new row with no
  skills, which dispatch never selects until skills are set (Q17). `FM` can authorize work and close
  tickets, so it stays `PENDING` until the operator approves it (`backend/deploy/Approve-CbmFm.ps1`);
  the person can log in meanwhile and sees "waiting for approval". Nobody approves their own
  request.

---

## 4. Login and session

### 4.1 From poster to first report

```
 QR poster on site            Play Store                 First launch
┌──────────────┐  scan   ┌──────────────┐ install  ┌──────────────────────────┐
│  ▓▓ ▓ ▓▓ ▓   │ ──────► │ CBM  [Install]│ ───────► │ Maddaloni – Office       │ ← site from the QR
│  ▓ ▓▓  ▓ ▓   │         └──────────────┘          │ [ Continue with Google ] │
│ Report a     │   the site code travels through   │ ─────── or ───────       │
│ problem here │   the store (Play Install         │ Email     [__________]   │
└──────────────┘   Referrer) or the app's own      │ Password  [__________]   │
                   deep link if already installed  │ I am a  (●) User         │
                                                   │         ( ) Technician   │
                                                   │         ( ) Facility mgr │
                                                   │ [ Create account ]       │
                                                   │ Have an account? Log in  │
                                                   └──────────────────────────┘
                                                        │ one-hour session
                                                        ├── USER        → My reports + [Open a report]
                                                        ├── TECHNICIAN  → Jobs dashboard   (after approval)
                                                        └── FM          → Split dashboard  (after approval)
```

- **Sign-up is three fields**: email (the username), password, role. With Google it is one field —
  the role — because Google supplies a verified email and name. The site is never typed: it comes
  from the QR code. A person who installed the app some other way scans the QR from inside the app.
- **Every use starts with a login.** A login opens a session of **exactly one hour**, never extended
  by activity; after that the app returns to the login screen. The hour is a database constraint
  (`cbm_app.sessions`), so no endpoint can issue a longer session by mistake.
- **Password login**: bcrypt (cost 12) in PostgreSQL. Five wrong passwords lock the account for
  15 minutes; an unknown email and a wrong password get the same answer. **Google login**: the app
  obtains a Google ID token; n8n verifies its signature, audience and expiry and passes only the
  verified claims to the database. A verified Google email may link to an existing password account
  with the same email.
- **Shared devices are allowed.** A device is not owned by anybody; any account can log in on any
  device. The app keeps each account's data (outbox, drafts, cache) separate on the device, and
  logging out removes the session token from the device.
- **First login from a new device** needs nothing special: it is an ordinary login that also
  registers the device (install id, model, app version). The server flags it (`new_device`) so that
  a notification ("new sign-in on Pixel 8") can be sent — relevant above all for FMs. Push tokens
  are per device.
- **Queued reports and the one-hour rule.** A report captured while logged in stays in that
  account's outbox if the session expires before it is uploaded (typical in basements). It is sent
  after that person's next login and never under another account (acceptance test 8). Whether to
  add a narrower "upload-only" credential so queued reports can go out without a login is open (Q11).
- The session token lives in EncryptedSharedPreferences (PRD 1.0 FR-14); only its SHA-256 is stored
  on the server.
- Requests are never logged or echoed by the API, so passwords do not end up in any log (§ 9.1).

### 4.2 Role-specific branding of the login

The login screen is shared, but after sign-in each role gets its own visual identity (accent colour,
app bar title, navigation) so that a shared device can never be mistaken for another role's session:

| Role | Title | Accent | Navigation |
|---|---|---|---|
| Reporter | "My reports" | neutral / blue | none — one screen with the "Open a report" button |
| Technician | "My jobs" | orange | bottom bar: Dashboard · Offers · To report |
| FM | "Building overview" | dark / teal | none — split screen, profile menu |

---

## 5. Reporter experience (login #1)

The reporter experience is PRD 1.0's app, narrowed and bound to an identity. Everything about the
capture itself — tap-as-shutter, intrinsics, transform, gate, outbox, "saved, not sent" wording —
is unchanged and specified in [`docs/PRD_v1_capture.md`](docs/PRD_v1_capture.md) §§ 4–7.

### 5.1 Screens

```
 MY REPORTS (home)                CAMERA                    CHECK THE PHOTO
┌────────────────────────┐      ┌──────────────────┐       ┌──────────────────┐
│ My reports     [⏻]     │      │ ● calibrated     │  tap  │ [photo + marker] │
│ ▸ Door, 1st floor      │ ───► │                  │ ────► │ What is wrong?   │
│   Being fixed          │      │ "Tap the damaged │       │ [optional, 500c] │
│ ▸ Radiator, room 3     │      │      part"       │       │ [Retake] [Send]  │
│   ⚠ Please take        │      └──────────────────┘       └────────┬─────────┘
│     another photo  ▶   │                                          │ Send
│                        │   "Report saved. It will upload automatically."
│ [   Open a report   ]  │ ◄────────────────────────────────────────┘
└────────────────────────┘
```

The home is a very small dashboard: the reporter's own reports with their status, and one large
**Open a report** button that opens the camera. Tapping "Please take another photo" opens the camera
bound to that same report.

### 5.2 Requirements

- **R-1** The reporter can do exactly two things: submit a photo with an optional description, and
  see the status of their own reports. No ticket numbers, assets, technicians or costs are shown
  beyond the plain-language status in R-4.
- **R-2** Description is optional, free text, ≤ 500 characters, never required to send.
- **R-3** Reporter identity comes from the session. The `reporter_email` field in the capture
  metadata is removed from the client contract; the server fills it from the account.
- **R-4** Report status is shown in plain language. The mapping is done once, in the database
  (`cbm_app.reporter_reports()` returns a code: `RECEIVED`, `ANALYSING`, `PHOTO_NEEDED`,
  `OFFICE_NOTIFIED`, `AWAITING_FM`, `NOT_SCHEDULED`, `IN_PROGRESS`, `FIXED`, `ALREADY_REPORTED`);
  the app only translates codes into the user's language:

  | Backend state | Reporter sees |
  |---|---|
  | outbox `QUEUED` / `UPLOADING` | Waiting to send / Sending |
  | intake `NEW`, `PROCESSING` | Received — being analysed |
  | intake `AWAITING_PHOTO` | **Please take another photo** (n of 3 left) |
  | intake `IT_ISSUE`, `CONFIGURATION_REQUIRED` | We could not locate it automatically — the office has been told |
  | ticket `PENDING_AUTHORIZATION` | Waiting for the facility manager |
  | ticket `REJECTED` | Not scheduled (with the FM's reason if the FM chose to share it — Q7) |
  | ticket `LOCALIZED`, `DISPATCHING`, `ASSIGNED`, `REWORK`, `PENDING_APPROVAL`, `ESCALATED` | Being fixed |
  | ticket `CLOSED` | Fixed ✓ |
  | reused existing ticket (`DUPLICATE` outbox kind) | Already reported — being handled |

- **R-5** **Replacement photo.** When a report enters `AWAITING_PHOTO`, the reporter gets a
  notification. Opening it launches the camera bound to that report's `report_id`, so the new
  capture counts as the next attempt of the same report (today this requires re-running
  `Prepare-CaseStudyPhotos.ps1` with `-ReportId`). The remaining-attempt count is shown.
- **R-6** A new report while another of the same reporter is still `PROCESSING` is allowed on the
  device and queued; the server's existing `BUSY` handling applies only to the *same* report.
- **R-7** Notifications for the reporter: another photo needed, report accepted for work,
  report not scheduled, fixed. These replace the corresponding `cbm_intake_outbox` emails
  (`RETRY`, `RECEIVED`, `DUPLICATE`, `REJECTED`, `FINISHED`); email stays as a fallback while the
  app is rolled out (§ 11).

---

## 6. Technician experience (login #2)

Today a technician receives an offer email, clicks a link to accept, later receives a report-portal
link, fills an HTML form, downloads a PDF, and uploads it to a Drive folder. v2 collapses this into
three sections of one app.

### 6.1 Screens

```
 DASHBOARD (home)                OFFERS                         TO REPORT
┌──────────────────────────┐   ┌──────────────────────────┐   ┌──────────────────────────┐
│ This month               │   │ 🔔 NEW                    │   │ #42 Door handle, 1F      │
│  Completed      7        │   │ #57 Radiator leak, R3    │   │   ASSIGNED · due Tue     │
│  Awaiting FM    2        │   │ Sev 3 · plumbing         │   │   [ Fill report ]        │
│  Rework         1        │   │ Proposed: Tue 09–12      │   │                          │
│  Avg. days      2.4      │   │ Expires in 3h 12m        │   │ #38 Window frame, 2F     │
│                          │   │ [photo]                  │   │   ⚠ REWORK: "seal still  │
│ Recent                   │   │ [Decline]   [Accept]     │   │   leaking"               │
│ ✓ #31 Closed  12 Sep     │   └──────────────────────────┘   │   [ Fill report ]        │
│ ⏳ #40 Awaiting FM       │                                  └──────────────────────────┘
├──────────────────────────┤
│ Dashboard · Offers · To report │
└──────────────────────────┘
```

### 6.2 Requirements

**First run — what do you work on? (Q17)**

- **T-0** Right after a technician's first sign-in, one screen asks which trades they work in, from
  the five words triage produces (`plumbing`, `electrical`, `hvac`, `carpentry`, `general`), at least
  one. It writes their own `technicians.skills` (`POST /v1/technician/skills`), which is what
  dispatch matches; until it is answered they receive no offers, and the job list says so. They can
  change it later in their profile. **Nobody else edits another person's skills** — not the FM;
  only an administrator, in the database, and normally nobody does.

**Offers — notification of new proposed jobs**

- **T-1** When the dispatch agent reserves an offer for this technician, the app shows a push
  notification and the offer appears in **Offers** with: asset, location/storey, issue description,
  severity, required skill, proposed slot, before-photo, and the offer's expiry.
- **T-2** Accept / Decline are explicit actions with a confirmation. They call the same response
  path as the email link today (`cbm_record_offer_response()`), so expiry, "still eligible" and
  first-valid-acceptance rules are unchanged. Opening an offer is not a response (same rule as GET
  on the email link).
- **T-3** An offer that expired, was withdrawn, or was won by someone else is shown as such and
  cannot be acted on; the server's answer is authoritative.

**To report — accepted jobs pending the report**

- **T-4** Lists the technician's tickets in `ASSIGNED` and `REWORK`. A `REWORK` item shows the FM's
  reason prominently. Items leave this list when a report is submitted for the current approval
  cycle.
- **T-5** **The report is the existing template, filled in-app — never a manual upload.** Today the
  app opens that template's own page inside itself (the page builds and posts the PDF, so nothing is
  uploaded by hand); the Kotlin form below replaces it once the app can submit fields and have the
  PDF rendered for it (Q18, Q19). The form is
  `cbm/templates/technician-report/submission.schema.json`:
  - *prefilled and locked* from `cbm_technician_report_access()`: ticket, technician name/email,
    asset, location, reported issue;
  - *entered by the technician*: work date, findings, work performed, materials, checks, check
    result, outcome (`COMPLETED` / `PARTIAL` / `NOT_COMPLETED`), remaining issues, declaration,
    optional AFTER photo taken in-app with caption.
- **T-6** Drafts are saved on the device and survive app restarts; submission uses the same durable
  outbox as reporter captures, so a report written in a basement is delivered later.
- **T-7** On submission the backend renders the PDF with the existing `report-pdf.js` renderer,
  stores it where WF2 expects it and records it via `cbm_claim_technician_report()` /
  `cbm_record_technician_report()`. The technician never sees a Drive folder or a file name.
- **T-8** `outcome = COMPLETED` is labelled "I declare the work completed" and never implies closure.
  After submission the job shows "Awaiting FM approval".

**Dashboard — work completed**

- **T-9** Counts for a selectable period (default: this month): completed & closed, awaiting FM
  approval, sent back for rework, average days from assignment to closure; plus a recent-activity
  list with status per job.
- **T-10** Notifications: new offer, offer about to expire, rework requested (with reason), job
  closed.
- **T-11** A technician sees only their own offers and jobs.

---

## 7. Facility Manager experience (login #3)

### 7.1 The split screen

The FM home is **one screen split horizontally**: a manager's dashboard on top, a chat with the
WF3 agent below. Both halves are always visible, so the FM can look at a number and ask about it
without changing screen.

```
┌─────────────────────────────────────┐
│ Building overview         [👤]      │
│ ┌────────┐┌────────┐┌────────┐┌───┐ │
│ │   3    ││   2    ││   1    ││ 5 │ │   KPI tiles (tap = filter the list below)
│ │Authorize││Approve ││Escalated││>30d│ │
│ └────────┘└────────┘└────────┘└───┘ │
│ Needs you                            │
│ #57 Radiator leak · Sev 3 · 2h  [›]  │   action queue, oldest first
│ #42 Door handle · report in    [›]   │
│ #33 Window · ESCALATED          [›]  │
│ Open by status ▁▃▅▂  Tech load ▂▅▃  │   small charts
├══════════════ ═══ ══════════════════┤   ← draggable divider
│ 🤖 Good morning. 3 interventions are │
│    waiting for your authorization.  │
│ 👤 What's wrong with #57?            │
│ 🤖 Radiator in room 3 is leaking at  │
│    the valve (photo). Severity 3…    │
│ 👤 Approve it.                       │
│ 🤖 Approve intervention #57? [Yes]   │
│ [ Ask about the building…    ] [➤]  │
└─────────────────────────────────────┘
```

### 7.2 Requirements

**Dashboard (top half)**

- **F-1** KPI tiles: awaiting my authorization (`PENDING_AUTHORIZATION`), awaiting completion
  approval (`PENDING_APPROVAL`), escalated (`ESCALATED`), overdue > 30 days (WF3 definition), open
  tickets total. Tapping a tile filters the action queue.
- **F-2** **Action queue ("Needs you")**: every ticket waiting on an FM decision, oldest first, with
  asset, severity, age, and photo thumbnail. Opening an item shows a detail sheet (issue, photos
  before/after, technician report summary and AI assessment when present, history).
- **F-3** Two small charts: open tickets by status, and open workload per technician (WF3
  `ticket_counts`, `technician_workload`). Values come from the same read-only queries the agent
  uses, so the dashboard and the chat can never quote different numbers.
- **F-2a** **Decide from the card.** Every queue item carries the two buttons of its stage —
  *Authorize* / *Reject* before the job is offered, *Approve* / *Send back* after the technician's
  report — and rejecting or sending back opens a free-text reason, which is required. The buttons
  call the workflows' own guarded action (`cbm_app.fm_decide` → `public.cbm_wf3_begin_action`,
  actor `FM_APP`), so **a tap in the app, a click in the email and a sentence in the chat are one
  decision**: the first one wins and the others are refused. When the ticket has moved on, the app
  says so and reloads the queue instead of deciding blind (the card carries the approval cycle and
  the revision it was drawn from). Built: § 9.2, `backend/migrations/003_decisions.sql`.
- **F-4** Data refreshes on open, on pull-to-refresh, and when a notification arrives.
- **F-4a** **"First job" tag.** Wherever the FM sees a technician (offers, tickets, workload), a
  technician with no completed job carries a small blinking **first job** tag, smaller than the
  main text, like a notification LED, so a newcomer is noticed before work is authorized.

**Chat (bottom half)**

- **F-5** The chat is the existing WF3 `FM Dashboard Agent`, reached through an authenticated app
  endpoint instead of the n8n hosted chat page. Memory (50 turns), the 9 read tools, the 5 guarded
  action tools, the 1500-character limit, and `CBM_WF3_QUERY` logging are unchanged.
- **F-6** **Dashboard → chat hand-off.** Every dashboard item has "Ask the agent", which inserts a
  reference (e.g. "Ticket #57") into the chat input; it does not send on its own.
- **F-7** **Actions stay explicit.** When the agent proposes a state-changing action, the app
  renders it as a confirmation card (action, ticket, reason if required) with **Confirm / Cancel**.
  Only Confirm causes the guarded action to run — this mirrors the existing rule that an action must
  be explicitly requested by the authenticated FM in the current turn. **This is a change to WF3:**
  today the agent runs an explicitly requested action in the same turn; v2 needs it to return a
  structured proposal first and run the guarded action only on the confirming call.
- **F-8** Action outcomes (`APPLIED`, `QUEUED`, `SENT`, `UNCONFIRMED`, `BLOCKED`, `INCOMPLETE`) are
  shown verbatim with a plain-language explanation, and the dashboard refreshes after each one.
- **F-9** **Adaptive split.** The layout is chosen at runtime from the window's size class
  (Compose `WindowSizeClass`), not from a device list recorded at login — so it also follows
  rotation, foldables and Android split-screen multitasking, and a new device needs no configuration:

  | Window | Layout |
  |---|---|
  | Compact (phone, portrait) | Horizontal split: dashboard on top, chat below, default 55 / 45, draggable divider. Keyboard open → dashboard collapses to the KPI row. |
  | Medium (small tablet, phone landscape, unfolded foldable) | Side by side, 50 / 50. |
  | Expanded (tablet landscape, desktop window) | Side by side, dashboard 60 / chat 40, room for the ticket detail sheet next to the list. |

  The divider position the FM chooses is remembered per device and size class.
- **F-10** Notifications: new intervention to authorize, completion report ready for approval,
  ticket escalated, closure incomplete. The existing FM emails remain as fallback (§ 11); the weekly
  report stays an email.

---

## 8. Admin (backend only)

No admin screens in the app. The admin, through the backend:

- creates accounts and assigns exactly one role;
- links a technician account to its `technicians` row (skills, zone, rating keep living there);
- deactivates accounts (a deactivated technician also stops receiving offers — `technicians.active`);
- revokes sessions/devices.

---

## 9. Backend: the App API over a shared database

### 9.1 Principle — the app is independent of the workflows

```
 Phone ──HTTPS──► CBM App API ──► PostgreSQL cbm_demo ◄── n8n workflows
                  (backend/)      cbm_app  │  public
                                  (app)    │  (workflows)
```

- **The phone talks only to the App API** (`backend/`, FastAPI). It never calls n8n.
- **The app owns its data** in schema `cbm_app`; the workflows own schema `public`; both live in the
  same database. The app's role is to make the workflows usable in the field: a real photo taken
  with the app replaces a file dropped in Drive, an in-app button replaces an email link.
- **The workflows run on top of the app's data.** They read what the app wrote and act on it, as
  they react to a new Drive file today. How n8n notices new rows is Q14.
- **Rules that protect data are in PostgreSQL** (sessions, passwords, lockout, roles, ownership,
  the capture invariant). The API adds HTTP, Google token verification, rate and size limits, and
  never echoes or logs a request body.
- **The API logs in as `cbm_app_api`**, which holds no table privileges and may only call the
  app's entry functions. It cannot read or change workflow tables.
- **One exception to "the API never calls n8n":** the FM chat (§ 7). The agent lives in WF3, so the
  API forwards each chat turn to an internal n8n webhook that the phone cannot reach.
- The app is installed by its own installer (`backend/deploy/Install-CbmApp.ps1`) as its own
  Compose project, and never overwrites workflow files.

### 9.2 Endpoints

`/v1/auth/*`, `/v1/me` and `/healthz` are **implemented and running** (see `backend/README.md`).
The rest are planned; "Backed by" names what the API calls or writes.

| Endpoint | Role | Backed by | New or existing |
|---|---|---|---|
| `POST /v1/auth/signup`, `/v1/auth/signup/google` | anyone with a site code | `cbm_app.sign_up()` | ✅ running |
| `POST /v1/auth/login`, `/v1/auth/login/google` | anyone | `cbm_app.login()` | ✅ running |
| `POST /v1/auth/role` | logged in, >1 role | `cbm_app.select_membership()` | ✅ running |
| `POST /v1/auth/logout` | logged in | `cbm_app.logout()` | ✅ running |
| `GET /v1/me` | logged in, incl. pending | `cbm_app.me()` | ✅ running |
| `POST /v1/memberships/{id}/decision` | FM (technician/user requests), admin (FM requests) | `cbm_app.decide_membership()` | DB done |
| `POST /v1/devices` (push token) | all | device table | **new** |
| `GET /v1/notifications?since=` | all | notification feed (§ 9.4) | **new** |
| `POST /v1/captures` | reporter | `cbm_app.claim_capture()` → store image (Q15) → `cbm_app.attach_capture()` → workflows' `cbm_capture_begin()`; WF1 then runs VPS/IFC/vision on it (Q14) | contract **changed** (§ 9.3), DB done |
| `GET /v1/reports` | reporter | `cbm_app.reporter_reports()` | DB done |
| `GET /v1/technician/jobs` | technician | `cbm_app.technician_jobs()`: live offers from `CBM_DISPATCH_STATE`, jobs in hand, work completed, own skills | ✅ running |
| `POST /v1/technician/offers` | technician | `cbm_app.technician_respond()` → `cbm_record_offer_response()` → WF1 dispatch | ✅ running |
| `POST /v1/technician/skills` | technician | `cbm_app.set_technician_skills()` on their own `technicians` row (Q17) | ✅ running |
| `GET /v1/technician/jobs/{id}/report-link` | technician | `cbm_issue_technician_report_link()`; opens the workflows' existing template (T-5 replaces it with an in-app form) | ✅ running |
| `GET /v1/tech/jobs/{ticket}/report` | technician | `cbm_technician_report_access()` (prefill) | new wrapper |
| `POST /v1/tech/jobs/{ticket}/report` | technician | render PDF (`report-pdf.js`) → `cbm_claim_technician_report()` → store → `cbm_record_technician_report()` → WF2 | new wrapper, existing logic |
| `GET /v1/tech/summary?from=&to=` | technician | read-only aggregate for self | **new**, read-only |
| `GET /v1/fm/queue` | FM | `cbm_app.fm_queue()`: the two decision queues and the site's counts | ✅ running |
| `POST /v1/fm/decisions` | FM | `cbm_app.fm_decide()` → `public.cbm_wf3_begin_action()` (actor `FM_APP`) | ✅ running |
| `GET /v1/photos/{capture_id}` | FM, technician on their own job | the reporter's photo from the capture store | ✅ running |
| `GET /v1/fm/dashboard` | FM | WF3 read queries (`ticket_counts`, `overdue_tickets`, `technician_workload`) for the charts | new wrapper, existing queries |
| `GET /v1/fm/tickets/{id}` | FM | WF3 `ticket_lookup` / `ticket_history` | new wrapper |
| `POST /v1/fm/chat` | FM | internal n8n webhook → WF3 `FM Dashboard Agent`, `sessionId` = account (the one API → n8n call, § 9.1) | new trigger, existing agent |

The technician's offer and report endpoints replace token-in-URL links. The link tokens stay valid
for the email fallback; the app endpoints authenticate by session and resolve ownership in the database.

**One decision, three channels.** `POST /v1/fm/decisions` and `POST /v1/technician/offers` do not
re-implement anything: they authenticate the person, check the ticket belongs to their site (or the
offer to them), and then call the very function the email link and the WF3 chat call. The workflows
keep deciding what a decision means and carrying it out — an authorization is applied at once and
WF1 dispatches; a completion decision is recorded and WF2's one-minute review loop performs the IFC
write, the closure and the notices. The app never writes a ticket's status itself.

This costs **two lines in the workflow release**, both additive, and nothing else:

| Change | Where | Why |
|---|---|---|
| `FM_APP` accepted as an actor | `database/wf3/actions/schema.sql`, `cbm_wf3_begin_action()` | The decision arrives from an authenticated app session instead of the chat or a mail link; every other guard is untouched |
| An `FM_APP` decision routes to `DECIDED` | `database/wf2/review-mail.sql`, `cbm_wf2_review_status()` | That router told "WF3 is carrying this out" from "nobody is". An app decision has no WF3 execution behind it, so without this WF2 would wait for a workflow that never ran and, after ten minutes, email the FM *closure needs attention* while the ticket stayed open |

Both were applied to the live database on 20 Sep after checking the live definitions matched the
release repository exactly.

### 9.3 Capture contract changes (`contract/`, schema 1.0.0 → 2.0.0)

| Field | Change | Why |
|---|---|---|
| `report_id` | **added**, UUID, generated by the app for a new report and reused for its replacement photos | Current intake groups up to 4 captures under one report; it is also the `cbm_intake_reports.id` |
| `capture_id` | kept; primary key of `cbm_app.report_photos`, so a replayed upload is recognised before the image is stored twice. The intake's attempt key is the image's storage reference (Q15) | Idempotency |
| `building_id` | kept; must equal the session's site | A report cannot be filed into another site |
| `reporter_email` | **removed** from the client payload; server derives it from the session | Identity must not be client-supplied |
| `description` | kept, optional, ≤ 500 chars | R-2 |
| `camera`, `target`, `image`, `pose` | unchanged | PRD 1.0 § 7 |

WF1 gains a webhook entry that feeds the existing `One Capture Input` sub-flow alongside the Drive
trigger (the Drive path stays for the demo and as fallback). The image is still stored in Drive, so
`photo_url` and everything downstream of it is unchanged. The server-side intrinsics changes of
PRD 1.0 § 10 (read K from the package, fail closed on untrusted K, give the vision prompt real
dimensions, prefer the tap over a bounding box) are **re-applied to the current WF1 vision/IFC
chain**; the old `server/` folder targeting `maintenance_requests` was removed on 19 Sep 2026.

### 9.4 Notifications

The backend already produces the events each role needs; today they are delivered only as email:

| Event source (existing) | Recipient role | App notification |
|---|---|---|
| `cbm_intake_outbox` kinds `RETRY`, `RECEIVED`, `DUPLICATE`, `REJECTED`, `FINISHED` | reporter | R-7 |
| offer reserved in `CBM_DISPATCH_STATE` | technician | new offer (T-1) |
| `REWORK` transition, `CLOSED` transition (`CBM_STATUS_CHANGED`) | technician | T-10 |
| `cbm_intake_outbox` kind `AUTHORIZATION` | FM | F-10 |
| `PENDING_APPROVAL` transition, `ESCALATED` transition | FM | F-10 |

The app notification is an **additional channel** on the same event, claimed and receipted like the
emails, so an event is never "sent" by one channel and lost by the other. Delivery mechanism (push
vs polling) is Q4.

---

## 10. Data model (backend)

**Implemented and live** in schema `cbm_app`, owned by the app: `backend/migrations/001_app_schema.sql`
(tables and functions, repeatable), `002_api_role.sql` (the API's login) and `003_decisions.sql`
(what the FM and the technician decide). Tested by `backend/tests/run-tests.sh` (SQL suite + 33 API
tests) against a structure-only copy of the live workflow schema, with every migration applied twice. On 19 Sep the first version, created that
morning in `public`, was moved into `cbm_app` by a guarded one-off script (only the site `ROOM-POC`
and its access code existed).

```
 cbm_app.sites ──< site_access_codes            (the code printed in the QR)
     │
     └──< memberships >── users ──1:1── password_credentials
          role USER|TECHNICIAN|FM|ADMIN │   └──< external_identities (GOOGLE)
          status PENDING|ACTIVE|…       │
          technician_id ──> public.technicians
                                        ├──< sessions ──> devices     exactly 1 h, one membership
                                        └──< login_events (audit)

 cbm_app.reports (id = public.cbm_intake_reports.id, description ≤ 500)
     └──< report_photos (capture_id PK, K, tap, pose, sha256, note ≤ 500)
             ──> public.cbm_capture_attempts(file_id)  ← the workflows' four-attempt intake

 cbm_app.fm_decisions (who tapped: user, membership, session, ticket, action, reason, outcome)
     └── public.ticket_events holds the decision itself; it records the channel, not the person
```

| Rule | Where it is enforced |
|---|---|
| Session ≤ 1 hour, never extended | `CHECK (expires_at <= created_at + 1 h)`; `authenticate()` never updates `expires_at` |
| Tokens never stored | only `SHA-256(token)`; the token is returned once, at login |
| Passwords | bcrypt cost 12 (`pgcrypto`), in a separate table |
| Only FM is a request | `sign_up()`: `USER` and `TECHNICIAN` active, `FM` `PENDING`; `ADMIN` not selectable |
| Active technician ⇒ linked `technicians` row, one account per row | `CHECK` + partial unique index |
| Frame invariant of the capture (K, image, tap in one coordinate system) | `CHECK`s on `report_photos` |
| Reporter sees and writes only own reports, only in the session's site | `claim_capture()`, `attach_capture()`, `reporter_reports()` |
| Reporter email in intake comes from the account, not the client | `attach_capture()` → `public.cbm_capture_begin()` |
| The API reaches data only through entry functions | `cbm_app_api`: no table grants; functions `SECURITY DEFINER` with fixed `search_path`; the operator approval path is refused to it |
| A decision is the workflows' to validate and to carry out | `fm_decide()` only authenticates, checks the site and calls `public.cbm_wf3_begin_action()`; the app writes no ticket column |
| An FM decides only their own site's tickets | `fm_decide()` / `fm_queue()` over `site_tickets()` |
| A technician answers only an offer addressed to them, once | `technician_respond()` over `live_offers()`, then `public.cbm_record_offer_response()` |
| A technician's skills are their own | `set_technician_skills()` writes only the `technicians` row their membership is linked to, from a fixed vocabulary |

**Which site a ticket belongs to.** The workflows' `tickets` carries no site. A ticket created from
an app report is matched through that report (`reports.intake_report_id`); a ticket from the older
Drive/email intake belongs to the single site whose `sites.receives_unassigned_tickets` is true
(`ROOM-POC` on the deployment; a unique index allows only one). This is a pilot measure: a second
site needs a real site column on `tickets`, which belongs to the multi-FM discussion (Q12).

Not yet built: notifications (§ 9.4).

The app's `building_id` is the site id: `ROOM-POC`, "Maddaloni Office", the value of the
workflows' `CBM_BUILDING_ID`.

On the device, the Room / SwiftData outbox of PRD 1.0 § 6.3 gains a `kind` column
(`CAPTURE` | `TECH_REPORT`) and an `account_id`, so both queued captures and queued technician
reports survive restarts and are never delivered under a different account.

---

## 11. Rollout

**Android only.** iOS is set aside: the Swift code stays in the repository, frozen, and no iOS target
is built for v2. The shared code is organised as Kotlin Multiplatform (`shared` module + `androidApp`),
so an iOS target can be added later without restructuring (Q5).

| Phase | Reporter | Technician | FM |
|---|---|---|---|
| **0 — structure** | move the Android code into KMP modules; no behaviour change, the 16 tests still pass | | |
| **1 — identity** | ✅ built (Android): join by QR link or code, sign-up, login, one-hour sessions, My reports, Open a report → `/v1/captures` → WF1; not yet tried on a phone | sign-up ✅; API for skills, offers and jobs ✅, screens next | sign-up ✅; API for the queue and both decisions ✅, screens next |
| **2 — technician** | notifications | offers, accept/decline, in-app report, dashboard | — |
| **3 — FM** | — | — | split dashboard + chat, confirmation cards |
| **4 — email off** | email fallback disabled per account once the app is confirmed on their device | same | same (weekly report stays email) |

Every phase keeps the email/Drive path working, so the case-study demo is never broken by an
unfinished app phase.

---

## 12. Acceptance criteria (new in v2; PRD 1.0's eight still apply to capture)

1. Signing in with a reporter, a technician and an FM account on the same device opens three
   different homes; no screen of one role is reachable from another's session.
2. Every role-restricted endpoint returns 403 to the other two roles, and a technician requesting
   another technician's offer or job gets 404 (not 403, to avoid confirming it exists).
3. A reporter capture creates a `cbm_intake_reports` row with the account's email, without the
   client sending an email; a failed identification produces an in-app "take another photo"
   prompt, and the replacement is recorded as attempt 2 of the same report.
4. A technician accepts an offer in-app; the ticket reaches `ASSIGNED` through the unchanged
   dispatch helper, and the email link for the same offer then reports it as already answered.
5. A technician submits the report in airplane mode; it is delivered when connectivity returns,
   WF2 receives a PDF identical in content to the portal's, and the ticket reaches
   `PENDING_APPROVAL`.
6. The FM dashboard counts equal the agent's answer to "how many tickets in each status?" at the
   same moment.
7. An FM action proposed by the agent does nothing until Confirm is tapped; confirming a stale
   action returns `BLOCKED` and changes nothing.
8. Sign-out with queued captures, then sign-in as a different account: the queued captures are not
   sent under the new account.
9. The same ticket decided in the app and then from the email link (or the reverse) is applied once:
   the second channel says it has already been decided, and `ticket_events` holds one decision.
10. An approval taken in the app closes the ticket, updates the model and notifies the technician
    within about a minute, with no WF3 execution involved and no "closure needs attention" email.

---

## 13. Risks

| Risk | Mitigation |
|---|---|
| Three role UIs on two native codebases triples UI work | Resolved by Q5: one Compose Multiplatform UI, Android first; capture stays native |
| App endpoints become a second, weaker path to state changes | Endpoints only call existing guarded functions; § 12 tests 2, 4, 7 |
| Email and app both deliver the same notice twice, or neither | Same event key, claimed once per channel, receipted (§ 9.4) |
| FM chat over a phone keyboard is slower than the web chat | Dashboard hand-off (F-6), suggested prompts, tablet side-by-side layout |
| Technician report PDF differs between portal and app | Single renderer (`report-pdf.js`), server-side, acceptance test 5 |
| Shared devices on site | Role-specific branding (§ 4.2), explicit sign-out, per-account outbox (test 8) |

---

## 14. Open questions — for the implementation discussion

| # | Question | Options to discuss |
|---|---|---|
| Q1 | ~~How are users authenticated?~~ **Decided 19 Sep** | Own accounts in PostgreSQL: email + password (bcrypt) or Google sign-in; one-hour sessions, a login at every use; implemented in `backend/` (schema `cbm_app`) (§ 4.1, § 10) |
| Q2 | ~~Where do the app endpoints live?~~ **Decided 19 Sep** | A dedicated App API (`backend/`) over the shared database; the phone never calls n8n (§ 9.1) |
| Q3 | ~~One webhook per endpoint, or per role?~~ **Moot** | With Q2 = App API, the phone reaches no n8n webhook; what remains is Q14 |
| Q4 | Notifications | FCM + APNs push · polling `GET /v1/notifications` (no Google/Apple dependency, fine for a PoC) · both |
| Q5 | ~~Stay with two native apps?~~ **Decided 19 Sep** | Kotlin Multiplatform + Compose Multiplatform, **Android only for now**; iOS set aside (§ 11) |
| Q6 | ~~Can one person hold two roles?~~ **Decided 19 Sep** | Yes: roles are site memberships; one account can hold several; each session is bound to one (§ 3) |
| Q7 | What does a reporter see when the FM rejects? | generic "not scheduled" · FM's reason · FM chooses per decision |
| Q8 | ~~Reporter accounts~~ **Decided 19 Sep** | Named accounts, self sign-up from the site QR code; USER active at once, TECHNICIAN/FM approved (§ 3, § 4.1) |
| Q9 | Languages | Italian + English, as the technician template already is |
| Q10 | ~~Approve/reject buttons, or only the chat?~~ **Decided 20 Sep** | Both: the card carries the buttons and the chat takes the same action, through one guarded helper (F-2a). Whoever decides first wins — app, email link or chat — and the emails stay on during the pilot |
| Q11 | Queued reports after the hour expires | wait for the next login (current rule) · a narrow upload-only credential issued at login, valid e.g. 24 h, usable only to deliver captures already in that account's outbox |
| Q12 | Several FMs and several places (partly decided 20 Sep) | **Every active FM of a site sees that site's queue and any one of them may decide; the event records which person did.** A session serves one site. Still open: a ticket carries no site of its own — an app ticket is matched through its report, and tickets from the older Drive/email intake go to the one site flagged `receives_unassigned_tickets`. That flag is a pilot measure; with a second site, tickets need a real site column. Also open: who is a site's admin |
| Q13 | Distribution before the Play Store | For now: the QR code carries `cbmapp://join?site=<code>`, which the phone's camera opens in the installed app (APK sideloaded). Play Console internal testing, with the site code passed through the Play Install Referrer, remains open |
| Q14 | ~~How do the workflows notice new app rows?~~ **Decided 19 Sep** | `NOTIFY cbm_app_capture` received by a Postgres Trigger in WF1, plus a sweep on the existing one-minute tick. Built for captures; WF1's app branch joins the Drive branch at `Capture Input` (`backend/n8n/README.md`) |
| Q15 | ~~Where are capture images stored?~~ **Decided 19 Sep** | On the App API's own volume; WF1 fetches them from the internal image service (`cbm-app-internal:8081`, not published). The Drive branch stays until it is deleted |
| Q16 | ~~How does the phone reach the API over HTTPS?~~ **Decided 19 Sep** | The existing ngrok domain. A Caddy proxy (`edge`) in the workflow stack sends `/v1/*` to the App API and everything else to n8n, as before; n8n never depends on the app being up |
| Q17 | ~~Where does a self-registered technician's skill list come from?~~ **Decided 20 Sep** | The technician alone: a one-time "What do you work on?" step right after sign-up, changeable later in their own settings. The FM never touches another person's skills; only an administrator could, directly in the database, and normally nobody does. Until skills are set, dispatch offers that technician nothing |
| Q18 | ~~Who renders the technician's report PDF?~~ **Decided 21 Sep** | n8n, with the existing `report-pdf.js` (pdf-lib), so an app report and a portal report are the same document. `pdf-lib` is allowed in the runner exactly as `pdf-parse` already is. A renderer inside the App API is the likely end state, once the layout no longer has to match |
| Q19 | ~~How does WF2 learn about a report submitted in the app?~~ **Decided 21 Sep** | The WF1 pattern: a notification when the app stores the report, plus a one-minute sweep. The branch joins **through** `Extract Ticket ID` (two nodes read it by name, as seven read `Capture Input` in WF1), then renders the PDF and hands the binary to the existing extraction. The Drive trigger stays until it is deleted |

---

## 15. What exists vs. what v2 needs

| Component | Exists | v2 work |
|---|---|---|
| Capture, intrinsics, outbox (iOS + Android) | ✅ built and tested | bind to account; add `report_id`; replacement-photo entry point |
| Capture contract | ✅ 1.0.0 | 2.0.0 (§ 9.3) |
| Reporter status list | ✅ local outbox only | server status mapping (R-4) |
| Login, roles, sessions | ✅ database + API endpoints, live and tested (`backend/`) | Android screens; HTTPS exposure (Q16) |
| Reporter photo + description records | ✅ database, `POST /v1/captures`, `GET /v1/reports`, image store, WF1 app branch (imported, unpublished) | Android capture screens; publish WF1 |
| Technician offers / jobs / dashboard | ✅ endpoints live (`/v1/technician/*`) over the existing dispatch functions | Android screens |
| Technician skills (Q17) | ✅ `POST /v1/technician/skills` | the one-time "what do you work on?" screen |
| Technician report template | ✅ schema + renderer + portal | in-app form; server-side PDF |
| FM decisions (authorize, reject, approve, rework) | ✅ `/v1/fm/queue`, `/v1/fm/decisions` over the workflows' guarded action | Android cards and the reason box |
| FM dashboard charts | — (weekly email only) | new client screen over WF3 queries |
| FM chat | ✅ WF3 agent on n8n hosted chat | app chat endpoint + confirmation cards |
| WF1 intake of app photos on the current schema | ✅ WF1 app branch (`backend/n8n/`), imported unpublished | publish WF1 |
| Notifications | ✅ email outbox | app channel on the same events |
