# CBM Capture — Product Requirements Document

**Product:** CBM Capture, the mobile front end of the Community-Based Maintenance end-of-day AI agent
**Version:** 1.0 (PoC)
**Date:** 27 August 2026
**Status:** Specification complete, reference implementation delivered for both platforms
**Supersedes:** the Google Drive photo-drop intake described in release `2026_07_13`

---

## 1. Why this app exists

The CBM pipeline turns a photograph of building damage into an exact IFC occurrence `GlobalId`.
To do that it must unproject one pixel of that photograph into a 3D ray, which requires the
camera's intrinsic matrix **K**. Today the pipeline invents K from environment variables:

```js
fx: Number($env.CAMERA_FX || 1200), fy: Number($env.CAMERA_FY || 1200),
px: Number($env.CAMERA_PX || 960),  py: Number($env.CAMERA_PY || 540),
```

This has three consequences, established in *Device-Agnostic Camera Intrinsics for the CBM
Spatial-Grounding Pipeline* (27 August 2026):

1. **It is already wrong**, before any device change. The vision prompt asks for a bounding box
   "in absolute image pixels", but the image dimensions are never interpolated into the prompt,
   so `target_pixel` is expressed in the real image's pixel space while K describes an assumed
   1920×1440 space. A stock 4032×3024 photograph unprojects a pixel from one coordinate system
   using a principal point from another.
2. **It fails open.** A wrong pose is caught by `MULTISET_CONFIDENCE_THRESHOLD` and routed to
   `NEEDS_LOCALIZATION`. A wrong unprojection is not caught by anything: the ray rotates a few
   degrees, hits a different element, and `resolve_ray` returns `found: true` with no confidence
   signal attached. A technician is then dispatched to the wrong asset.
3. **It cannot survive a device change,** and cannot handle lens switching even on one device.

The root cause is architectural, not numeric. By the time an ordinary JPEG appears in Google
Drive, the factory calibration that produced it is gone. **The only place the true K exists is
on the handset, at the moment of capture.** This app is that place.

Its second job follows from the first. Once a native app is capturing the frame, the worker can
*tap the defect*, which retires the hallucinated bounding box: the ray's origin pixel becomes an
observed fact rather than a language model's guess.

---

## 2. Goals and non-goals

### Goals

| # | Goal | Measured by |
|---|------|-------------|
| G1 | Every uploaded photograph carries factory-calibrated intrinsics for that exact frame | Share of captures with `camera.source ∈ {ARKIT, ARCORE, ANDROID_CAMERA2}` and `trusted: true` |
| G2 | A capture whose calibration cannot be trusted is never silently unprojected | Zero packages accepted with `trusted: false` on the grounding path |
| G3 | The target pixel is designated by the worker, not inferred | 100% of packages carry `target.source = USER_TAP` |
| G4 | No report is lost to poor connectivity | Reports queued while offline that are eventually delivered: 100% |
| G5 | A worker can file a report without training | Time from app open to sent report ≤ 15 s; one gesture to capture |

### Non-goals for v1

- **On-device MultiSet localization.** WF2 continues to call `query-form` server-side. Doing it
  on the handset would duplicate working logic and put MultiSet credentials on every phone.
- **Lens undistortion.** The pipeline assumes a pinhole model; v1 mitigates by rejecting
  off-centre targets rather than by implementing a correction (§ 6.2 of the calibration note).
- **Ticket browsing, technician workflows, AR overlays.** The app reports damage. Everything
  after that stays in n8n.
- **iPad, tablets, landscape capture UI.** Supported by the transform layer, not by the UI.

---

## 3. Users

**Primary — the reporting worker.** Walks the building, finds damage, photographs it. Not
technical, often wearing gloves, frequently with no signal in plant rooms and basements. Cares
about one thing: *did my report get through?*

**Secondary — the facility manager.** Never opens the app. Receives the end-of-day batch report
from WF2 and needs to know, per item, whether its location was machine-derived or needs a human.

**Tertiary — the researcher (you).** Needs `camera.source` and `device_model` recorded on every
capture so that the hardcoded-K vs EXIF-K vs factory-K ablation in § 8 of the calibration note is
computable from stored data.

---

## 4. The core interaction

> **The tap is the shutter.**

The capture screen shows the camera feed and one instruction: **"Tap the damaged part."** A
single tap simultaneously fires the shutter and designates the target pixel.

This is not only a simplification for gloved hands. Separating the two — aim, shoot, then mark
the defect on a still — would let the phone move between the frame and the designation, so the
marked pixel would belong to a different view of the room than the photograph. Fusing them makes
"the tap and the frame are the same instant" true by construction rather than by discipline.

### Screen flow

```
                    ┌──────────────────────┐
                    │   CAMERA (default)   │
                    │                      │   badge: calibration state
                    │  "Tap the damaged    │   badge: N reports queued
                    │       part"          │
                    └──────────┬───────────┘
                               │ one tap
                               ▼
                    ┌──────────────────────┐
                    │  CHECK THE PHOTO     │   photo + marker at the
                    │                      │   *transformed* target pixel
                    │  [warnings]          │   off-centre / untrusted K
                    │  "What is wrong?"    │
                    │  [Retake]   [Send]   │
                    └──────────┬───────────┘
                               │ Send
                               ▼
                    "Report saved. It will upload automatically."
                               │
                               ▼
                    ┌──────────────────────┐
                    │     MY REPORTS       │   status per report,
                    │  Waiting / Sending / │   retry on rejection
                    │  Sent / Not accepted │
                    └──────────────────────┘
```

**Settings** is a fourth screen, opened once at enrolment and then effectively never.

### Two deliberate wording choices

- On send, the worker is told **"saved"**, not "sent". The report is durable at that moment;
  whether it has reached n8n is a separate fact shown on My Reports. Promising delivery the app
  cannot yet guarantee is how a queue quietly loses the user's trust.
- When K is untrusted, the worker sees **"Location will be checked by hand — you can still send
  it."** Never an error, never a blocked path. The photograph is still valuable evidence; only
  the automatic grounding is unavailable.

### The marker as a self-check

On the review screen, the marker is drawn from `metadata.target.pixel` over the transmitted JPEG
— not from the raw screen tap. If the rotation applied to the pixels ever disagreed with the
rotation applied to K and to the tap, the marker would visibly sit somewhere other than the
damage the worker touched. The most dangerous class of bug in this app is therefore visible to a
non-technical user on every single capture.

---

## 5. Functional requirements

### Capture

- **FR-1** Read K from the highest-trust available source, recording which in `camera.source`:
  ARKit / ARCore → Camera2 → EXIF. Never fabricate a value.
- **FR-2** Capture the frame at the instant of the tap; map the tap to a sensor-frame pixel using
  the platform's own view→image transform (`ARFrame.displayTransform`, ARCore
  `transformCoordinates2d`), not a hand-rolled aspect-ratio calculation.
- **FR-3** Rotate the image upright and downscale its long side to 1280 px, applying the identical
  transform to K and to the target pixel in the same operation.
- **FR-4** Evaluate the plausibility gate on the final transmitted-frame K and set
  `camera.trusted` accordingly. Never repair a failing K.
- **FR-5** Warn when `centrality > 0.6` and offer a retake.
- **FR-6** Refuse to persist a package whose K, image dimensions, and target pixel are not in one
  coordinate system.

### Queue and delivery

- **FR-7** Persist the package to durable local storage before attempting any upload.
- **FR-8** Retry on transient failure with exponential backoff capped at 15 minutes; stop
  permanently on 400/401/413/422.
- **FR-9** Treat HTTP 409 as success — the first attempt got through and the response was lost.
- **FR-10** Show every report's status, with a manual retry for rejected ones.
- **FR-11** Respect a "Wi-Fi only" preference; default to allowing mobile data.

### Enrolment

- **FR-12** Configure endpoint, bearer token, building ID, and reporter email on one screen.
- **FR-13** Offer a "Test connection" probe that reports a building-ID mismatch explicitly.
- **FR-14** Store the token in the platform keystore (iOS Keychain / Android
  EncryptedSharedPreferences), never in plain preferences and never in a backup.

---

## 6. Architecture

### 6.1 Shape: offline-first client, thin HTTP boundary

Two native apps, one shared JSON contract, no shared runtime. The apps are **not** thin clients:
they own a durable queue and a full transform pipeline, because the network is the least reliable
component in the system and the calibration data cannot be reconstructed after the fact.

```
  ┌────────────────────────────────────────────┐
  │  HANDSET                                    │
  │                                             │
  │  ARKit / ARCore ──► frame + K + pose         │
  │        │                                     │
  │        ▼                                     │
  │  ImageTransform ──► rotate + scale           │   ← K, pixels, and tap
  │        │              (image, K, tap)        │     move together
  │        ▼                                     │
  │  IntrinsicsGate ──► trusted / not            │
  │        │                                     │
  │        ▼                                     │
  │  Outbox (SwiftData / Room + files)  ◄── durable, survives force-quit
  │        │                                     │
  │        ▼                                     │
  │  Synchroniser (actor / WorkManager)          │
  └────────┬────────────────────────────────────┘
           │  multipart POST, bearer token
           ▼
  ┌────────────────────────────────────────────┐
  │  n8n WF1  — Webhook /cbm/capture            │
  │    validate package → validate K → stage    │
  │    → Drive (image) + Postgres (metadata)    │
  └────────┬────────────────────────────────────┘
           ▼
     WF2: MultiSet VPS → vision → K⁻¹[u,v,1]ᵀ → ray → IFC GlobalId
```

Google Drive stops being the intake mechanism and becomes image storage. This removes the
correlation problem inherent in the two-file alternative (upload `report.jpg` and `report.json`
separately, then teach WF1 to wait for both and match them).

### 6.2 Layering, identical on both platforms

| Layer | iOS | Android | Contains |
|-------|-----|---------|----------|
| Domain (pure) | `ImageTransform`, `IntrinsicsGate` | same names | The K arithmetic. No platform types, unit-tested on both. |
| Capture | `ARCaptureSession`, `CaptureAssembler`, `PixelBufferRenderer` | `ArCameraController`, `CaptureAssembler`, `YuvConverter` | Frame acquisition and the pixel half of the transform |
| Data | `OutboxStore` (SwiftData), `CaptureAPIClient` | Room + DAO, Retrofit + `CaptureUploader` | Durable queue, HTTP, failure classification |
| Sync | `OutboxSynchronizer` (actor) | `UploadWorker` (WorkManager) | Draining the queue, backoff |
| Presentation | SwiftUI + `@Observable` MVVM | Compose + ViewModel/StateFlow MVVM | Unidirectional state, one screen per feature |

Both use constructor injection (a hand-rolled `AppContainer` on iOS, Hilt on Android), so the
domain and assembly layers are testable without a device.

### 6.3 Data model

The client's model is deliberately narrow — it is a queue, not a mirror of the server:

```
OutboxRecord / OutboxEntity
  capture_id        UUID, primary key, also the server's idempotency key
  created_at        shutter time
  metadata_json     the exact bytes that will be transmitted
  image_path        JPEG on disk (SQLite is a poor place for megabytes)
  thumbnail         small JPEG for the history list
  status            QUEUED → UPLOADING → DELIVERED | REJECTED
  attempt_count, next_attempt_at, last_error
  server_request_id, server_status
  intrinsics_source, intrinsics_trusted   (denormalised for the list UI)
```

Storing the serialised metadata rather than modelled columns means the document that was
validated is byte-for-byte the document that gets sent.

### 6.4 The invariant the whole design protects

```
camera.width  == image.width  == the JPEG's actual decoded width
camera.height == image.height == the JPEG's actual decoded height
0 ≤ target.pixel.x < image.width,  0 ≤ target.pixel.y < image.height
```

Enforced in four independent places: by construction in `CaptureAssembler`, by an explicit
assertion before the record is written, by the mock/real server on receipt, and visibly by the
review-screen marker. See `docs/INTRINSICS.md` for the transform derivations.

---

## 7. The wire contract

`POST /cbm/capture`, `multipart/form-data`, two parts: `image` (JPEG) and `metadata` (JSON).
Full schema in `contract/capture-metadata.schema.json`; OpenAPI in `contract/openapi.yaml`.

```json
{
  "schema_version": "1.0.0",
  "capture_id": "46ccbf7f-…",
  "building_id": "ROOM-POC",
  "reporter_email": "worker@example.com",
  "description": "Door handle detached, door will not latch.",
  "captured_at": "2026-08-27T18:32:14.482Z",
  "client":  { "platform": "IOS", "device_model": "iPhone15,2", … },
  "image":   { "width": 960, "height": 1280, "sha256": "…",
               "orientation_applied": 1, "source_width": 1920, "scale": 0.667 },
  "camera":  { "source": "ARKIT", "trusted": true,
               "fx": 954.63, "fy": 955.07, "cx": 480.19, "cy": 639.65,
               "width": 960, "height": 1280 },
  "target":  { "pixel": { "x": 512.0, "y": 706.5 },
               "source": "USER_TAP", "centrality": 0.104 },
  "pose":    { "source": "ARKIT", "position": {…}, "rotation": {…},
               "tracking_state": "NORMAL" }
}
```

Two notes on the shape:

- **Conventional CV notation.** `fx, fy, cx, cy` rather than the pipeline's current
  `fx, fy, px, py`. WF2 renames on the way into MultiSet and `resolve-ray`.
- **`pose` is advisory in v1.** The AR session's world frame has no relationship to the MultiSet
  map or `T_VPS_TO_IFC`; feeding it to the ray resolver would produce a confidently wrong
  GlobalId. It is transmitted so the ARKit pose and the VPS pose can be compared offline — the
  end-to-end consistency check sketched in § 7 of the calibration note.

---

## 8. Platform specifics

### iOS

Swift 6, SwiftUI, iOS 17+. ARKit via a RealityKit `ARView` that renders the passthrough feed;
nothing is added to the scene, because the app needs ARKit for `camera.intrinsics`, not for
rendering. `@Observable` MVVM, `async/await`, actors for the store and synchroniser, SwiftData
for the queue, Keychain for the token, Swift Testing for the suite.

`ARFrame.camera.intrinsics` is expressed in the coordinate system of `imageResolution`, which is
exactly the frame `capturedImage` occupies — the cleanest relationship of any platform, which is
why the calibration note recommends implementing iOS first.

### Android

Kotlin 2.1, Jetpack Compose, Material 3, minSdk 26. ARCore `Frame.camera.imageIntrinsics` is the
direct counterpart and is the primary source. Below it sit two fallbacks:

- **Camera2 `LENS_INTRINSIC_CALIBRATION`**, which returns `[fx, fy, cx, cy, s]` in the
  *pre-correction active array* coordinate system — neither the active array nor the saved JPEG.
  `Camera2IntrinsicsReader` performs the rescale explicitly and documents its two assumptions
  (no zoom, sensor-aspect output), both of which the plausibility gate catches if violated.
- **EXIF**, the § 4 estimate.

Hilt for DI, Room for the queue, WorkManager for delivery, DataStore + EncryptedSharedPreferences
for settings, Retrofit/OkHttp for HTTP.

---

## 9. Non-functional requirements

| | Requirement |
|---|---|
| **Performance** | Tap → review screen ≤ 1.5 s on a 4-year-old handset. JPEG encoding never on the main thread. |
| **Payload** | ≤ 1280 px long side, JPEG q85 — typically 200–400 KB, uploadable over a weak site connection. |
| **Storage** | Full-size JPEG deleted on delivery; only the thumbnail is retained for history. |
| **Reliability** | Queue survives force-quit and reboot; rows stuck in `UPLOADING` are recovered at launch. |
| **Security** | Token in the platform keystore; HTTPS expected, plain HTTP flagged in the UI, not silently accepted; app data excluded from cloud backup and device transfer. |
| **Privacy** | Photographs of a customer's building. Nothing leaves the device except to the configured endpoint. No analytics, no third-party SDKs. |
| **Accessibility** | Single large target; all controls labelled; Dynamic Type honoured on the review and reports screens. |

---

## 10. Server-side changes this app requires

The app is inert without these. They are **not** implemented in this delivery — they are the
n8n/Postgres half of the same design, listed here so the scope is explicit.

| Component | Change |
|-----------|--------|
| **WF1** | Replace the Drive trigger with a `Webhook` node at `POST /cbm/capture` (Header Auth credential). Add `Validate Capture Package` → `Intrinsics Valid?` → `Stage Request Idempotently` → upload image to Drive. |
| **WF1** | Add `GET /cbm/capture/health` returning `{ok, building_id, schema_version}`. |
| **Postgres** | `ALTER TABLE maintenance_requests ADD COLUMN camera_intrinsics jsonb;` — `jsonb` because iOS and Android carry slightly different fields. Stage with `source_system = 'mobile_app'` and `source_file_id = capture_id`, which the existing `UNIQUE (source_system, source_file_id)` index turns into idempotency for free. |
| **WF2** | `Prepare Image and Intrinsics` reads `$json.camera_intrinsics` instead of `$env.CAMERA_*`; map `cx→px`, `cy→py`. Delete the `CAMERA_*` environment fallback entirely. |
| **WF2** | Extend `Localization Trusted?` to also require trusted intrinsics, converting the fail-open unprojection into fail-closed. |
| **WF2** | Interpolate the real `width`/`height` into the vision prompt, or drop the bounding-box request altogether now that `target.pixel` is supplied. |
| **`.env`** | Remove `CAMERA_WIDTH/HEIGHT/FX/FY/PX/PY`. |

Until they exist, `mock-server/main.py` implements the same contract and the same seven checks.

---

## 11. Acceptance criteria

1. On an ARKit device, a capture produces `camera.source = ARKIT`, `trusted = true`, and
   `camera.{width,height}` equal to the decoded JPEG's dimensions.
2. The same on an ARCore device with `ARCORE`; on a non-ARCore device, `ANDROID_CAMERA2` or
   `EXIF`, with the source recorded truthfully.
3. Rotating the phone through all four orientations and capturing the same target yields a
   marker that lands on that target in every case.
4. Feeding K for a 1920×1440 frame with a 4032×3024 image is rejected as `FRAME_MISMATCH`.
5. Airplane mode: three captures queue, all three deliver after connectivity returns.
6. Killing the app mid-upload loses nothing; the row returns to `QUEUED` at next launch.
7. Re-POSTing a delivered `capture_id` returns `duplicate: true` and creates no second request.
8. Both test suites pass; the rotation identity holds for all four quarter-turns on both platforms.

### The measurement this enables

With `camera.source` and `device_model` on every record, § 8 of the calibration note becomes a
query rather than a study: photograph known elements from marked positions with 2–3 phones,
record whether the resolved `GlobalId` is correct and how far the ray hit lands from the true
centroid, and group by source. That yields the hardcoded-K vs EXIF-K vs factory-K ablation in
publishable form.

---

## 12. Risks

| Risk | Mitigation |
|------|------------|
| Camera2 intrinsics absent or wrong on a given handset | Three-level fallback; the plausibility gate catches a bad rescale; source recorded so bad devices are identifiable in the data |
| Worker taps a surface, not the defect | Review screen shows the marker before sending; retake is one tap |
| Ultra-wide distortion invalidates the pinhole model | Centrality warning at 0.6; distortion coefficients carried for a future correction |
| AR session fails to start (poor light, unsupported device) | Android degrades to the Camera2/EXIF path; iOS requires ARKit and declares it in `UIRequiredDeviceCapabilities` |
| PoC bearer token shared across handsets | Acceptable for a PoC and stated as such; per-device tokens are a v2 change with no client-side redesign |
| Two codebases drift | The domain layer is duplicated deliberately and its test suites assert identical identities, so drift fails a build rather than corrupting a GlobalId |

---

## 13. What ships in this delivery

- `ios/` — complete SwiftUI + ARKit implementation, 19 Swift sources + a test suite,
  plus `project.yml` so `xcodegen generate` produces the Xcode project deterministically
- `android/` — complete Compose + ARCore implementation, 25 Kotlin sources + a test suite,
  Gradle build with a version catalogue
- `contract/` — JSON Schema, OpenAPI 3.1, worked example
- `docs/INTRINSICS.md` — normative transform spec with derivations
- `mock-server/` — FastAPI stand-in implementing the contract and all seven checks
- `tools/env.ps1`, `tools/env.sh` — put the installed toolchains on PATH
- `.github/workflows/build.yml` — Android, iOS (macOS runner), and contract jobs
- this PRD

### Verification status

| Component | State |
|-----------|-------|
| **Android app** | **Built and tested.** 25 sources compile to 221 classes; KSP, Hilt and Room annotation processing clean; **16/16 unit tests pass**; a 21.79 MB `app-debug.apk` assembles and inspects correctly (`ai.cbm.capture.debug`, minSdk 26, targetSdk 35, ARCore declared optional). |
| **Mock server** | **Running and exercised.** 19/19 contract assertions pass, covering the happy path, idempotent replay, checksum mismatch, frame mismatch, out-of-frame target, untrusted intrinsics, and schema-version gating. |
| **Contract** | **Validated.** The worked example validates against the JSON Schema and satisfies the frame invariant. |
| **Transform arithmetic** | **Verified twice.** Independently in Python, then by the Kotlin suite: ray preservation under downscale, the ray rotating by (x,y)→(−y,x) per quarter-turn, the four-turn round trip, and the gate rejecting the real defect. |
| **iOS app** | **Built and tested.** Compiles under Xcode 16.4 with `SWIFT_STRICT_CONCURRENCY: complete`; **17/17 tests pass** on the simulator, via a GitHub-hosted `macos-15` runner. A physical iPhone is still needed to *run* the AR session — the Simulator does not track. |

CI: <https://github.com/Coob-hash/cbm-capture/actions>

### What compiling actually caught

Thirteen defects, none of which review had found. Three on Android — a missing
`gradle.properties` (no `android.useAndroidX`), `android:authority` where the manifest needs
`authorities`, and a missing launcher icon — two more in CI configuration (a pinned Xcode 16
whose simulator runtime the image lacks, and a `-destination "id=…"` missing its `platform=`
qualifier), and eight on iOS:

| Defect | Why it mattered |
|---|---|
| `xcodebuild \| xcbeautify` without `pipefail` | **The CI reported a green tick over `** BUILD FAILED **`.** GitHub runs steps with `bash -e`, not `-eo pipefail`, so the pipeline's status was the formatter's. The worst defect of the nine: it made every other result untrustworthy. |
| `static let` of an `ISO8601DateFormatter` | Non-`Sendable` class as shared mutable state — rejected outright under Swift 6. |
| `#Unique` in the SwiftData model | Requires iOS 18; the deployment target is 17. |
| `CIContext` in a `Sendable` struct | Thread-safe by documentation, but not annotated — needs `@unchecked`. |
| `try?` assumed to nest optionals | It flattens. The outbox drain loop bound a non-optional and unwrapped it again. |
| `UIDevice.current.systemVersion` | Main-actor isolated in Swift 6, read from a nonisolated context. |
| `PRODUCT_NAME` with a space | Built `CBM Capture.app/CBM Capture` while `TEST_HOST` looked for `CBMCapture.app/CBMCapture` — the app built, every test run failed. |
| `Info.plist` without `CFBundleExecutable` | A hand-written plist is used verbatim; the app built and linked, then failed at install. |

Most of these are invisible to inspection and surface only at one specific stage — annotation
processing, resource linking, app install, or test-host resolution. That is the argument for a
real toolchain over careful reading, and it is why the `pipefail` defect was the serious one:
it disabled the only mechanism that could find the others.
