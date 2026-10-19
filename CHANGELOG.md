# Changelog

All notable changes to this project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project
adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.7] - 2026-10-19

First public release. Earlier versions were internal pilot builds.

### Added

- One app for three roles (reporter, technician, facility manager), with a single sign-in and a
  home screen per role.
- Calibrated capture: ARCore intrinsics, with a CameraX fallback that uses the Camera2 factory
  calibration or the photo's EXIF data.
- Image, intrinsics and tap transformed in a single operation, followed by an intrinsics
  plausibility check.
- Offline outbox with background upload, kept per account and per site.
- Joining a site by scanning its QR code in the app.
- Technician: job offers matched to their trades, accept or decline, and a work report with an
  optional after photo.
- Facility manager: authorization and approval queues, notifications and a building assistant chat.
- Show/hide toggle on password fields.

[Unreleased]: https://github.com/Coob-hash/cbm-capture/compare/v1.0.7...HEAD
[1.0.7]: https://github.com/Coob-hash/cbm-capture/releases/tag/v1.0.7
