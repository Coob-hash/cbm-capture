# CBM App backend

The phone talks only to this API. The API owns the app's data in schema `cbm_app`; the n8n workflows
run on top of the same PostgreSQL database (schema `public`) and never receive calls from the phone.

```
 Phone ──HTTPS──► CBM App API ──► PostgreSQL cbm_demo ◄── n8n workflows
                  (this folder)   cbm_app  │  public
                                  (app)    │  (workflows)
```

## Layout

| Path | Contents |
|---|---|
| `migrations/001_app_schema.sql` | Schema `cbm_app`: sites, accounts, roles, sessions, devices, reports, photos, and every function. Repeatable. |
| `migrations/002_api_role.sql` | The API's login `cbm_app_api`: no table privileges, `EXECUTE` on the entry functions only. |
| `migrations/004_technician_reports.sql` | The technician's report, written in the app: the template's fields and an optional AFTER photo. WF2 renders the document from them. |
| `migrations/003_decisions.sql` | What the FM and the technician decide from the phone. No second decision path: it authenticates, checks the site, and calls the workflows' own guarded functions. |
| `cbm_api/` | FastAPI service: HTTP, Google token verification, rate and size limits. |
| `tests/` | SQL suite (`test_app_schema.sql`), API suites (`test_api_auth.py`, `test_api_captures.py`, `test_api_decisions.py`), `run-tests.sh`. |
| `deploy/` | `Install-CbmApp.ps1`, `docker-compose.app.yml`, and `Approve-CbmFm.ps1` (list, approve or reject pending requests: FMs, and technicians whose email is already on the dispatch list). |
| `n8n/` | The WF1 app branch: patch script and tests. |

## Where the rules live

Everything that protects data is enforced by PostgreSQL, not by the API. That includes bcrypt
passwords, the five-attempt lockout, sessions of exactly one hour (a `CHECK`), roles and approvals,
ownership of reports, and the frame invariant of a capture. The API adds only what a database
cannot do:
- HTTP and a single error shape, `{"error": CODE, "message": text}`;
- Google ID-token verification;
- a per-address limit on login and sign-up;
- a 64 KiB body limit;
- never echoing a request body. Pydantic's default 422 would echo the password back; this API
  doesn't.

`cbm_app_api` cannot read any table, call any internal helper, run a workflow function, or use
the database-operator path of `decide_membership`. `tests/test_app_schema.sql` and
`test_api_auth.py` check each of these.

**Decisions belong to the workflows.** The app never writes a ticket's status. `fm_decide` calls
`public.cbm_wf3_begin_action` with actor `FM_APP` — the same function the approval email and the
WF3 chat call, with the same guards (stage, current approval cycle, expected revision, no opposite
decision, one decision per request key). An authorization is applied there and then, and WF1
dispatches; a completion decision is recorded and WF2's one-minute review loop does the IFC write,
the closure and the notices. A technician's answer to an offer goes through
`public.cbm_record_offer_response`, exactly as the offer email's link does. Two small changes were
needed on the workflow side, both in the release repository: `FM_APP` accepted as an actor, and
WF2's review router treating an app decision as ready to settle (it has no WF3 execution to wait
for).

## Endpoints (v1)

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/healthz` | — | Database reachable |
| POST | `/v1/auth/signup` | site code | Email + password + role. `USER` and a new `TECHNICIAN` active at once (a new dispatch row, no skills); `FM` pending until the operator approves it, and so is a `TECHNICIAN` whose email already names a dispatch row, until the operator links it |
| POST | `/v1/auth/signup/google` | site code | Google ID token + role |
| POST | `/v1/auth/login` | — | Email + password → one-hour session |
| POST | `/v1/auth/login/google` | — | Google ID token → one-hour session |
| POST | `/v1/auth/role` | Bearer | Bind the session to one membership when the person holds several |
| GET | `/v1/me` | Bearer | Account, current membership (including `PENDING`), expiry |
| POST | `/v1/auth/logout` | Bearer | Revoke the session |
| POST | `/v1/captures` | Bearer (reporter) | Multipart `metadata` (JSON) + `image` (JPEG, ≤ 5 MiB). Checks SHA-256, decodes the JPEG (Pillow; a header with no pixels is refused) and its size, stores the image, marks it `STORED`, which notifies WF1. Repeatable per `capture_id`, also by two requests at once (each write has its own temporary file). A description over 500 characters is `422 DESCRIPTION_TOO_LONG`. The camera and the tap must be complete and usable - K an object with positive focal lengths and its centre in the frame, K's size the image's, the tap in the frame, `source` and `trusted` present - or `422 INVALID_CAPTURE`; the table refuses the same (`report_photos_geometry`). `NaN`/`Infinity` in the metadata is `422 INVALID_REQUEST` |
| GET | `/v1/reports` | Bearer (reporter) | Own reports with a plain-language status code |
| GET | `/v1/fm/queue` | Bearer (FM) | The two queues that wait for a decision — interventions to authorize, completions to approve — plus the site's counts |
| POST | `/v1/fm/decisions` | Bearer (FM) | `approve_intervention` · `reject_intervention` · `approve_completion` · `request_rework`. A rejection or rework needs a reason. `409 BLOCKED` when the ticket moved on |
| GET | `/v1/photos/{capture_id}` | Bearer (FM, or the technician on that job) | The reporter's photo behind a card |
| GET | `/v1/technician/jobs` | Bearer (technician) | Offers to answer, jobs in hand, work completed, own skills. A job whose report was sent this approval cycle is `report_state = PROCESSING`, `report_needed = false`, until WF2 moves the ticket on |
| POST | `/v1/technician/offers` | Bearer (technician) | Accept or decline an offer. `409 OFFER_GONE` when it expired or was answered |
| POST | `/v1/technician/skills` | Bearer (technician) | Their own skills, from the listed vocabulary. Nobody else sets them |
| POST | `/v1/technician/jobs/{id}/report` | Bearer (technician) | The work report: multipart `report` (the template's fields; the work date a real day) and an optional `photo` (a decodable JPEG). One report per approval cycle, counted as the workflows count it (`approval_id`, or `initial`). A repeat of this cycle's report answers with the same receipt - also after WF2 has taken it and moved the ticket on; a different report in a cycle that has one is `409 REPORT_ALREADY_SENT` (with its `report_id`), never answered "sent" and dropped; after a rework it is a new report. A retry after an interrupted upload replaces it whole. The document is rendered by WF2, not here |
| GET | `/v1/technician/jobs/{id}/report` | Bearer (technician) | Whether this job's report has been sent in its current approval cycle |
| GET | `/v1/technician/jobs/{id}/report-link` | Bearer (technician) | A link to the workflows' report template, kept for the browser route |

The internal image service (`cbm_api.internal`, container `api-internal`, alias
`cbm-app-internal:8081`, **no published port**) serves `GET /internal/captures/{id}/image` to
WF1. How WF1 consumes app photos: [`n8n/README.md`](n8n/README.md).

Every request carries a `device` (`id` = the app's install UUID, `platform`, optional model and
versions). The first login of an account on a device returns `new_device: true`.

## Tests

```bash
backend/tests/run-tests.sh
```

This starts a throwaway PostgreSQL 16, loads `tests/fixtures/workflow_schema.reference.sql`,
applies every migration twice (they must be repeatable), and then runs:
- the SQL suite as the owner;
- the API suite in its Docker test image, connected as `cbm_app_api` exactly as deployed.

It never touches the deployment. The reference schema is a structure-only dump of the live
workflow schema; refresh it when the workflows' SQL changes.

```bash
backend/tests/run-app-contract.sh     # the Android app's client code against a throwaway API
```

This one runs the app's own Retrofit client, uploader and capture contract against a real API on a
local port: sign-up, photo upload, replay, report list, login, log-out.

## Install or update on the deployment

```powershell
.\backend\deploy\Install-CbmApp.ps1
```

The installer writes only app parts:
- `n8n_deploy\app\backend`;
- `n8n_deploy\app\docker-compose.app.yml`;
- `n8n_deploy\app\app.env` (created once, then kept);
- schema `cbm_app`.

It never writes workflow exports or workflow SQL. Before migrating, it dumps the database to
`n8n_deploy\backups\app-install-<time>`. The API runs as its own Compose project, `cbm_app`,
joined to `n8n_deploy_default` only to reach `cbm-postgres`. It listens on `127.0.0.1:8080`. To
test from a phone on the same Wi-Fi, set `CBM_APP_BIND=0.0.0.0` in `app.env`; that is plain
HTTP, so use test accounts only until HTTPS is in front of it.

**HTTPS.** Phones reach the API at `https://<ngrok domain>/v1/…`. The workflow stack's `edge` proxy
(Caddy, `edge/Caddyfile` in the workflow release) receives ngrok's traffic. It sends `/v1/*` to this
API (network alias `cbm-app-api`) and everything else to n8n, as before. If the app is down, only
`/v1/*` fails, with `503 APP_UNAVAILABLE`. `CBM_APP_TRUST_PROXY=1` makes the rate limit key on the
client address ngrok observed, which is the **last** `X-Forwarded-For` entry. Earlier entries can
be forged by the client.

`app.env` also holds `CBM_APP_GOOGLE_CLIENT_IDS`, empty until a Google OAuth client exists; while
empty, the Google endpoints answer `503 GOOGLE_NOT_CONFIGURED`. And `CBM_APP_PORTAL_BASE_URL`,
where n8n serves the technician report template (e.g. `https://<ngrok domain>/webhook`); while
empty, the report-link endpoint answers `503 PORTAL_NOT_CONFIGURED`.

## Dependencies

`requirements.in` lists the direct dependencies. `requirements.lock` is resolved from it inside
the pinned base image, and is what the image installs. FastAPI, uvicorn, psycopg and pydantic
match the CBM Python services exactly.
