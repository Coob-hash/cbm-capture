# CBM Capture

The mobile front end of the CBM end-of-day AI agent. Two native apps — iOS/ARKit and
Android/ARCore — that photograph building damage and send the photo **together with the camera's
own factory calibration** to n8n, so the pipeline can unproject the worker's tap into an exact
IFC `GlobalId` instead of guessing with hardcoded intrinsics.

**Start with [`PRD.md`](PRD.md)** for the why, the requirements, and the architecture.
**[`docs/INTRINSICS.md`](docs/INTRINSICS.md)** is the normative spec for the K arithmetic — both
implementations are line-for-line ports of it, and both test suites assert its identities.

```
contract/       JSON Schema, OpenAPI 3.1, worked example   ← the shared truth
docs/           the intrinsics specification
ios/            Swift 6 · SwiftUI · ARKit · SwiftData
android/        Kotlin 2.1 · Compose · ARCore · Room · Hilt · WorkManager
server/         the n8n and PostgreSQL half: migration + both workflows
mock-server/    FastAPI stand-in for the n8n webhook
PRD.md          product requirements
```

---

## Quick start: see it working without n8n

```bash
cd mock-server
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8099
```

Open `http://localhost:8099` in a browser to watch packages arrive. Then in either app's
**Settings**:

- **Server address:** `http://<your-machine-LAN-ip>:8099` — not `localhost`, which on a handset
  means the handset
- **Access token:** `dev-token`
- **Building ID:** `ROOM-POC`

The mock server enforces the same seven checks the real endpoint must: token, schema version,
SHA-256 of the bytes, decoded image dimensions, `camera.{width,height}` agreeing with them,
the target pixel lying inside them, and `camera.trusted`. It deliberately answers `422
UNTRUSTED_INTRINSICS` for an untrusted package so the client's permanent-failure path gets
exercised.

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
.\gradlew.bat :app:testDebugUnitTest    # 16 tests, no device needed
.\gradlew.bat :app:assembleDebug        # -> app/build/outputs/apk/debug/app-debug.apk
.\gradlew.bat :app:installDebug         # ARCore-supported device over USB
```

To remove every toolchain again, delete `C:\Users\USER\toolchains`.

An **ARCore-supported physical device** is needed for the primary capture path; the
Camera2/EXIF fallback works on others. The standard emulator is not useful here — its virtual
camera does not produce meaningful intrinsics.

```
android/app/src/main/java/ai/cbm/capture/
  app/            Application (Hilt), MainActivity, navigation
  domain/
    imaging/ImageTransform.kt      Kotlin twin of the Swift file, same identities
    intrinsics/IntrinsicsGate.kt
    model/CaptureContract.kt
    repository/CaptureRepository   the door between capture, storage, and network
  data/
    capture/  ArCameraController, BackgroundRenderer, YuvConverter,
              CaptureAssembler, Camera2IntrinsicsReader, ExifIntrinsicsReader
    local/    Room entity + DAO
    remote/   Retrofit API + CaptureUploader (failure classification)
    settings/ DataStore + EncryptedSharedPreferences
  work/UploadWorker.kt             WorkManager delivery
  ui/             capture, review, reports, settings, theme
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

All four CI jobs are green: <https://github.com/Coob-hash/cbm-capture/actions>

| | |
|---|---|
| **Android** | Compiles (221 classes), **16/16 unit tests pass**, `app-debug.apk` 21.79 MB. |
| **iOS** | Compiles under Xcode 16.4 with `SWIFT_STRICT_CONCURRENCY: complete`, **17/17 tests pass** on the simulator. |
| **Server** | Migration applies to a real PostgreSQL 16 and is idempotent; 15 SQL assertions and 30 Code-node assertions pass. |
| **Mock server** | Runs; 19/19 contract assertions across every rejection branch. |
| **Contract** | Example validates against the schema; frame invariant holds. |

The iOS app is built on a GitHub-hosted `macos-15` runner, because Xcode is macOS-only — there
is no Windows build of it, and ARKit, SwiftUI, UIKit and SwiftData ship only in Apple's SDKs.
To build it yourself you need either that CI job, any Mac with Xcode 16+, or a rented Mac
(MacStadium, EC2 `mac`, MacinCloud, Codemagic).

A **physical iPhone is still required to *run* it**: ARKit does not track in the Simulator, so
CI proves the code compiles and its logic is correct, not that the camera path works.

## Server

`server/` holds the other half: the migration that adds `camera_intrinsics`, the new
webhook-intake WF1, and the patched WF2 that reads K from the request instead of inventing it.
See [`server/README.md`](server/README.md) for what changes and how to deploy it.

`mock-server/` remains useful for app development — it implements the same contract without
needing n8n, Postgres, or a MultiSet account.
