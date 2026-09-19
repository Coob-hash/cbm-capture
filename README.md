# CBM Capture

The mobile front end of the CBM maintenance pipeline: one app with a separate experience for each
role (reporter, technician, facility manager), and the **App API** it talks to. The n8n workflows
work on top of the same database. A reporter's photo travels **together with the camera's own
factory calibration**, so the pipeline can unproject the tap into an exact IFC `GlobalId` instead
of guessing with hardcoded intrinsics.

Android is the platform being developed (Kotlin Multiplatform, `android/`). The iOS app (`ios/`) is
set aside: it builds and is tested on CI, but the v2 role screens are not ported to it.

**Start with [`PRD.md`](PRD.md)** (v2: one app, role-based logins for reporter, technician and
facility manager, integrated with the current n8n workflows). The capture-specific requirements of
v1 remain normative in [`docs/PRD_v1_capture.md`](docs/PRD_v1_capture.md).
**[`docs/INTRINSICS.md`](docs/INTRINSICS.md)** is the normative spec for the K arithmetic — both
implementations are line-for-line ports of it, and both test suites assert its identities.

```
backend/        the App API (FastAPI), schema cbm_app, the WF1 app branch; the phone talks only to this
android/        Kotlin 2.1 · Kotlin Multiplatform (:shared) · Compose · ARCore · Room · Hilt
contract/       capture JSON Schema, OpenAPI, worked example
docs/           the intrinsics specification, PRD v1 (capture requirements)
ios/            Swift 6 · SwiftUI · ARKit — set aside
PRD.md          product requirements (v2)
```

---

## Backend

See [`backend/README.md`](backend/README.md): endpoints, where the access rules live, tests, and
installing into the n8n deployment. The tests need only Docker:

```bash
backend/tests/run-tests.sh
```

---

## iOS

**Requirements:** Xcode 16+, iOS 17+, and a **physical device** — ARKit does not run in the
Simulator, and this app is entirely about what the camera hardware reports.

There is no `.xcodeproj` in the repository — it is generated from `ios/project.yml`, so every
machine and the CI runner build an identical project and there is no huge binary file to merge.

```bash
brew install xcodegen
cd ios
xcodegen generate
open CBMCapture.xcodeproj
```

Set your signing team in the target (or in `project.yml`'s `DEVELOPMENT_TEAM`) before running on
a device. The spec already wires up `Info.plist`, iOS 17 deployment, Swift 6 with
`SWIFT_STRICT_CONCURRENCY: complete`, the test bundle, and the ARKit / RealityKit / SwiftData
frameworks.

```
ios/CBMCapture/
  App/CBMCaptureApp.swift        composition root — every dependency is built here
  Core/
    Models/CaptureContract.swift wire types, mirroring the JSON Schema
    Imaging/ImageTransform.swift the K arithmetic (pure, unit-tested)
    Imaging/PixelBufferRenderer  the pixel half of the same transform
    Intrinsics/IntrinsicsGate    plausibility gate; decides `trusted`
    Capture/ARCaptureSession     ARSession wrapper, frame snapshots
    Capture/CaptureAssembler     builds the package; enforces the frame invariant
    Storage/, Networking/, Security/, Settings/, Sync/
  Features/Capture, Review, Reports, Settings
```

---

## Android

**Already set up on this machine.** Portable toolchains live in `C:\Users\USER\toolchains`
(Temurin JDK 17, Android SDK 35 + build-tools 35.0.0 + platform-tools, Gradle 8.11.1). Nothing
was written to the system PATH or registry — activate them per session:

```powershell
. .\tools\env.ps1          # PowerShell   (or: source tools/env.sh   in Git Bash)
cd android
.\gradlew.bat testDebugUnitTest         # 16 tests (in :shared), no device needed
.\gradlew.bat :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:installDebug         # ARCore-supported device over USB
```

To remove every toolchain again, delete `C:\Users\USER\toolchains`.

**The API address is a build setting**, kept out of Git. Put it in `android/local.properties`:

```properties
cbm.apiBaseUrl=https://<your-ngrok-domain>/
```

Without it the app builds against `https://example.invalid/` and cannot log in. A site's QR code
carries `cbmapp://join?site=<site code>`: the phone's camera app opens it in the installed app, on
the sign-up screen for that site.

An **ARCore-supported physical device** is needed for the primary capture path; the
Camera2/EXIF fallback works on others. The standard emulator is not useful here — its virtual
camera does not produce meaningful intrinsics.

Two Gradle modules. `:shared` is Kotlin Multiplatform with Compose Multiplatform — Android is its
only target for now (iOS is set aside, PRD § 11), and a build check rejects `android.*`/`java.*` in
`commonMain` so the code stays portable. New role screens go in `:shared`. `:app` is the Android
application: everything that needs Android APIs, Hilt, Room, ARCore or WorkManager.

```
android/shared/src/
  commonMain/kotlin/ai/cbm/capture/
    domain/imaging/ImageTransform.kt     Kotlin twin of the Swift file, same identities
    domain/intrinsics/IntrinsicsGate.kt
    domain/model/CaptureContract.kt
    ui/theme, ui/common                  Compose Multiplatform theme and components
  androidMain/                           actual implementations (dynamic colour)
  commonTest/                            the 16 transform/gate tests (kotlin.test)

android/app/src/main/java/ai/cbm/capture/
  app/            Application (Hilt), MainActivity, navigation
  domain/
    repository/CaptureRepository   the door between capture, storage, and network
  data/
    capture/  ArCameraController, BackgroundRenderer, YuvConverter,
              CaptureAssembler, Camera2IntrinsicsReader, ExifIntrinsicsReader
    local/    Room entity + DAO
    remote/   Retrofit API + CaptureUploader (failure classification)
    settings/ DataStore + EncryptedSharedPreferences
  work/UploadWorker.kt             WorkManager delivery
  ui/             capture, review, reports, settings
```

---

## The one idea to take away

Every pixel-space number in a capture package — `fx, fy, cx, cy`, and `target.pixel` — is
expressed in the coordinate system of the JPEG travelling beside it. Never the sensor's native
frame, never the pre-resize frame. The apps rotate and downscale the image, K, and the tap **in a
single operation** so they cannot drift apart, assert the result before persisting it, and mark
the package untrusted rather than repairing a calibration they do not believe.

That is the whole product. Everything else is a queue and four screens.

---

## Status

CI: <https://github.com/Coob-hash/cbm-capture/actions> — Android, iOS, backend, contract.

| | |
|---|---|
| **Android** | Kotlin Multiplatform layout (`:shared` + `:app`); **16/16 unit tests pass**; debug APK builds. Role screens in progress. |
| **iOS** | Compiles under Xcode 16.4 with `SWIFT_STRICT_CONCURRENCY: complete`, **17/17 tests pass** on the simulator. |
| **Backend** | Running on the deployment. SQL suite + 22 API tests pass against the live workflow schema, through the restricted database login. |
| **Contract** | Example validates against the schema; frame invariant holds. |

The iOS app is built on a GitHub-hosted `macos-15` runner, because Xcode is macOS-only — there
is no Windows build of it, and ARKit, SwiftUI, UIKit and SwiftData ship only in Apple's SDKs.
To build it yourself you need either that CI job, any Mac with Xcode 16+, or a rented Mac
(MacStadium, EC2 `mac`, MacinCloud, Codemagic).

A **physical iPhone is still required to *run* it**: ARKit does not track in the Simulator, so
CI proves the code compiles and its logic is correct, not that the camera path works.
