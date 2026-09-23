# CBM App — merged front end

The working app's machinery with the second front end's look, in one project.

It started as a copy of `2026_08_27_CBM_Capture_App/android` (the app that runs today) and took the
design and the screen ideas from `generated_frontend`. Nothing was copied blind: every screen here
is wired to the real App API, and no screen shows data that does not come from it.

| Taken from the app that runs | Taken from the generated front end |
|---|---|
| The whole capture pipeline: ARCore, lens intrinsics, the frame invariant, the JPEG and its hash | The palette, the type scale and the squared "instrument" geometry |
| The App API client, sessions of exactly one hour, the sign-in rules | The ink title bar with the site code and the session countdown |
| The outbox: photos survive a restart, a flat battery and a change of account | The role accent — blueprint blue reporter, hi-vis orange technician, site teal FM |
| Hilt, Room, WorkManager, the deep link from the QR poster | The card, panel, chip, badge, alert and figure-tile kit (`ui/design`) |
| Its 33 tests | The three-tab technician layout and the FM's split screen |

## What is new here, and real

The facility manager's and the technician's screens, over the endpoints that went live on 20 Sep:

| Screen | Endpoint |
|---|---|
| FM · the two queues and their counts | `GET /v1/fm/queue` |
| FM and technician · the reporter's photo on a card | `GET /v1/photos/{capture_id}` |
| FM · Authorize / Reject / Approve / Send back | `POST /v1/fm/decisions` |
| Technician · offers, jobs, work done, own trades | `GET /v1/technician/jobs` |
| Technician · Accept / Decline | `POST /v1/technician/offers` |
| Technician · "What do you work on?" (Q17) | `POST /v1/technician/skills` |
| Technician · Fill the report | `GET /v1/technician/jobs/{id}/report-link`, opened inside the app |

A decision taken here is the same decision as the email link and the WF3 chat: the app records it
through the workflows' own guarded action and they carry it out. The app never writes a ticket's
status. Authorizing applies at once; approving a completion is recorded and WF2 finishes it within
about a minute, which is what the screen says.

## Not built yet

- **The assistant chat** in the lower half of the FM screen. WF3's chat entry is an n8n chat trigger
  behind an n8n login, so the app cannot reach it: WF3 needs a webhook entry of its own, and the
  confirmation-card protocol of PRD F-7 (the agent proposes, the app confirms). Until then the panel
  says so instead of answering.
- **The report form written in Kotlin.** The form is in the app (below), but it is the workflows'
  own page: the PDF is built in the page and the portal verifies its text. A native form needs an
  endpoint that takes the structured fields and renders the PDF server-side with the shared
  `report-pdf.js`, as `cbm/templates/technician-report/README.md` proposes.
- **Notifications.** No push channel yet, in this or any version.

## Closed on 21 Sep

- **The photo on a card.** The FM's queue and the technician's offers show the reporter's photo,
  from `GET /v1/photos/{capture_id}`. The image loader adds the session's token, and only for this
  API's address; photos are held in memory and never written to disk, because they belong to a
  ticket and not to the phone.
- **The report, inside the app.** "Fill the report" no longer throws the technician into a browser:
  the template opens in the app, with the file chooser wired so the AFTER photo can be attached.
  The page still builds and posts the PDF itself, so nobody uploads a file anywhere.

## Build and run

```bash
./gradlew :app:assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew :shared:testDebugUnitTest :app:testDebugUnitTest
```

`local.properties` needs the API address, which is never committed:

```properties
sdk.dir=C:/Users/USER/toolchains/android-sdk
cbm.apiBaseUrl=https://<your ngrok domain>/
```

## Seeing the screens without a phone

```bash
./gradlew :shared:screenshots         # writes shots/*.png at 390 x 844 dp
```

A desktop target exists in `:shared` for that one purpose: it renders the same composition Android
draws, with sample data in the API's shapes (`shared/src/desktopMain/.../screenshots/Shots.kt`).
It is a review tool — the product is still Android only.

## Layout

```
shared/src/commonMain/kotlin/ai/cbm/capture/
  domain/model/     the API's wire types, including WorkModels.kt (FM and technician)
  ui/theme/         palette, type, dimensions, the role accent
  ui/design/        the component kit: panels, buttons, chips, badges, alerts, top bar
  ui/auth/          join, log in, sign up, choose a role, waiting
  ui/reports/       the reporter's home
  ui/fm/            the facility manager's split screen
  ui/technician/    offers, work, to report, trades
app/src/main/java/ai/cbm/capture/
  data/capture/     ARCore, intrinsics, the capture assembly   (unchanged)
  data/remote/      the App API client                          (extended)
  data/work/        the FM's decisions and the technician's jobs (new)
  data/local/       the outbox                                  (unchanged)
  ui/…ViewModel.kt  the state behind each screen
  app/MainActivity  one login, a home per role
```
