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

## Closed on 25 Sep (1.0.4, versionCode 5) — the third audit

The third audit, of 1.0.3 (`output/cbm-audit-2026-09-25-third/CBM_THIRD_AUDIT.md`), found eight
defects. The app's four are fixed here; the server's four are in `backend/`.

- **An answer that is not the API's no longer closes the app (3).** A 200 with an HTML body - a
  Wi-Fi sign-in page, a gateway page - failed to decode inside Retrofit, and the exception reached no
  handler: the app closed at Log in. The converter (`Json.appConverterFactory()` in
  `data/remote/AppApi.kt`) now turns an unreadable body into an `IOException`, which every caller
  already handles. The screen says *The server's answer could not be read. If this Wi-Fi asks you
  to sign in, do that first, then try again.*, the form can be sent again, and the uploader retries.
- **A late log-out answer no longer undoes the next login (4).** Log out waited for the server's
  answer, then reset the form and went to Log in. An answer 30 seconds late did that over the next
  person's session. The session now ends on the phone at once and the server is told in the
  background (`AuthRepository.logout`). Nothing on screen waits for the server's answer.
- **A photo taken at one site waits for that site (5).** The queue was read by account alone. After
  logging in at another site, a waiting photo went out under that session, was refused
  (`SITE_MISMATCH`) and was marked *Not accepted*. It is now sent only under a reporter session of
  its own site (`OutboxDao.nextDue`, `CaptureRepository.drain`). Meanwhile it reads *Saved on this
  phone — sent when you log in to <site>*. A `SITE_MISMATCH` from the server keeps it queued too.
- **The password field tells the keyboard it is a password (8).** It was masked on screen, but the
  keyboard was told it was ordinary text with autocorrect (`inputType 0x8001`). It is now
  `KeyboardType.Password` without autocorrect (`0x81`).

All four were re-run on the emulator with the audit's own steps and fault proxy: no crash, the
screen kept, the photo sent at its own site, `inputType=0x81`. Tests: 6 more (60).

## Closed on 24 Sep (1.0.3, versionCode 4) — the second audit

The second audit, of 1.0.2 (`output/cbm-audit-2026-09-24-second/CBM_SECOND_AUDIT.md`), found six
defects; the app's share of them is fixed here (the server's share is in `backend/`):

- **The report form survives Android reclaiming the app (1).** What the technician had written, the
  attached photo and the file the camera app was writing into lived only in memory - the file in a
  Compose `remember`. When Android reclaimed CBM while the camera was in front, the form came back
  empty and the new photo was dropped. They now live in the form's `SavedStateHandle` as well
  (`ui/technician/ReportDraft.kt`), and the camera's answer reaches the restored form.
- **"Send photos over mobile data" reaches photos already waiting (2).** Switching it on only saved
  the preference: the queued send kept the Wi-Fi-only constraint it had been queued with
  (`ExistingWorkPolicy.KEEP`). The switch, and every return to the app, now updates a waiting send
  in place to the network the preference allows (`UploadWorker.enqueueForPreference`, WorkManager's
  `updateWork`). Nothing queued is dropped, and a send in progress is not interrupted.
- **A sent report is no longer offered as "Fill the report" (3).** The ticket stays assigned until
  the office takes the report, and the list used to go by the ticket alone. It now shows *Report
  sent. It reaches the facility manager in a few minutes.* (`report_state = PROCESSING`, from the
  server). A form opened before says there is nothing to send, and the server's refusal of a
  second, different report (409) is shown, never a "sent" screen.

Tests: 4 more (the draft's round trip, with and without its photo).

## Closed on 24 Sep (1.0.2, versionCode 3) — the bug audit

The audit of 1.0.1 (`output/cbm-audit-2026-09-24/CBM_BUG_AUDIT.md`) found eleven defects; the app's
share of them, and two follow-ups, are fixed here (the server's share is in `backend/`):

- **Take a photo no longer crashes the report form (2).** The app declares CAMERA, so the camera app
  refused the picture request until the permission was granted - by throwing, which took the report
  being written with it. The form now asks first, opens the camera on a yes, and on a no shows why
  beside the photo button; the report is kept either way.
- **Android 8 (3).** `LENS_DISTORTION` is read only on API 28+; on 26/27 the field does not exist and
  the `NoSuchFieldError` escaped `catch (Exception)`. Lint: 0 errors.
- **The description (6).** The review sheet stops at 500 characters and shows the count. A photo the
  server refused for its text - including one queued by 1.0.1 - offers *Edit the description*:
  only the description in the queued metadata changes (the photo, its hash and camera data stay),
  and it goes back in the queue. Rejections now keep the server's code (`outbox.server_status`).
- **Trades can be changed (7)**, from *Change your trades* (or a trade chip) on My jobs: the first
  sign-in picker, reopened on the trades already set, with Cancel.
- **The work date (9)** must be a real day (`domain/WorkDate.kt`, the server's rule); the field says
  so, and Send waits for it.
- **The last email (11)** survives the app being closed: `SessionStore` keeps it (also when clearing a
  session opened by an older version), and Log in offers it; sign-up still starts empty.
- A pending technician account (finding 1, server side) reads *Waiting for confirmation*.
- Follow-ups: an AR capture that gets no frame (session paused, camera lost) fails after 3 s instead
  of waiting for ever; the standard camera's capture thread is shut down with its screen.

Tests: 50 (6 new: the work date, the AR capture that cannot hang, the description correction), plus
the App ↔ API contract test, which now uploads a real JPEG (`src/test/resources/contract-960x1280.jpg`)
because the API decodes what it accepts.

## Closed on 23 Sep (1.0.1, versionCode 2)

Found by running the 21 Sep APK against the live deployment, fixed, and re-tested on an emulator.

- **A QR sign-up no longer lands on Log in.** `MainActivity` passed `startDestination = startRoute()`,
  re-read on every recomposition; once the join link stored the site code, the start became Log in,
  NavHost rebuilt its graph and started over there. The start is now computed once (`remember`), and a
  recreated activity no longer re-reads the launch link.
- **Phones without ARCore get a camera.** Before, the capture screen stayed black with no message
  (`runCatching` swallowed the install result, and the Camera2 fallback was wired in DI but never
  called). Now `CaptureViewModel.resolveCameraMode` follows ARCore's install flow and, when AR is
  unsupported, declined, or its session cannot start, switches to the standard camera
  (`data/capture/StillCameraController.kt`, CameraX): the whole frame letterboxed, one tap takes the
  photo and marks the damage. K is the factory calibration (`ANDROID_CAMERA2`, converted by
  `domain/intrinsics/Camera2Calibration.kt`) or the photo's EXIF (`EXIF`), recorded as such; with
  neither, the photo is refused rather than given an invented K. The tap is carried view → preview →
  sensor → photograph by `domain/imaging/StillFraming.kt`. No pose on this path.
- **The camera starts after ARCore is installed**, and the AR session now pauses and resumes with the
  app: session creation and resume are driven by lifecycle events instead of a one-shot
  `DisposableEffect(Unit)`.
- **Sign-up starts from an empty email.** The last account's address stays on Log in, but opening
  sign-up clears it, so a new address is no longer appended to the old one.
- `client.app_version` now comes from the build (`1.0.1 (2)`), not a hard-coded string.

Tests: 44 (11 new, in `StillFramingTest`: unrotate, the tap mapping, the calibration crop, and the ray
under the tap through the whole pipeline).

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
