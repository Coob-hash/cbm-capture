# Contributing to CBM Capture

Thank you for your interest in contributing. This guide explains how to set up the project and
what a pull request needs.

## Code of Conduct

This project follows the [Contributor Covenant](CODE_OF_CONDUCT.md). By participating, you are
expected to uphold it.

## Issues

- **Bugs and feature requests:** use the
  [issue templates](https://github.com/Coob-hash/cbm-capture/issues/new/choose), after searching
  the existing issues.
- **Security vulnerabilities:** do not open a public issue; follow [SECURITY.md](SECURITY.md).

## Development setup

1. Install [Android Studio](https://developer.android.com/studio) Ladybug (2024.2.1) or newer,
   with JDK 17 and the Android SDK for API 35.
2. Clone the repository and open it in Android Studio.
3. Point the app at a server by adding its base URL to `local.properties`:

   ```properties
   cbm.apiBaseUrl=https://api.example.com/
   ```

| Command                          | Purpose                                         |
|----------------------------------|-------------------------------------------------|
| `./gradlew testDebugUnitTest`    | Unit tests of `:app` and `:shared`              |
| `./gradlew :app:lintDebug`       | Android lint                                    |
| `./gradlew :app:assembleDebug`   | Build the debug APK                             |
| `./gradlew :app:installDebug`    | Install the debug build on a connected device   |
| `./gradlew :shared:screenshots`  | Render every screen to `docs/screenshots`       |

AR capture needs a physical [ARCore-supported device](https://developers.google.com/ar/devices);
on emulators and other devices the app falls back to the standard camera.

### Integration test

`ApiContractIntegrationTest` runs the app's own API client against a real server. It is skipped
unless the following environment variables are set:

| Variable               | Value                                                        |
|------------------------|--------------------------------------------------------------|
| `CBM_API_IT_URL`       | Base URL of a disposable test server, never a production one |
| `CBM_API_IT_SITE_CODE` | A site code that accepts sign-ups                            |
| `CBM_API_IT_SITE_ID`   | The site behind that code (default `API-TEST`)               |

## Coding guidelines

- Follow the [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html);
  the project uses the official code style.
- Keep `shared/src/commonMain` free of Android and Java APIs and put platform code in
  `androidMain`. The build enforces this.
- Write on-screen text for the person using the app, without internal vocabulary. The build
  enforces this too.
- Changes to the capture geometry must preserve the invariants in
  [docs/camera-intrinsics.md](docs/camera-intrinsics.md).
- Add or update unit tests for every change in behaviour.

## Commit messages

Use [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/): a type (`feat`, `fix`,
`docs`, `refactor`, `test`, `build`, `ci` or `chore`), an optional scope, and a short summary in
the imperative mood, for example:

```text
fix(capture): keep queued photos for their own site
```

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Make your change, with tests.
3. Check that `./gradlew testDebugUnitTest :app:lintDebug` passes.
4. For user-visible changes, add an entry under *Unreleased* in [CHANGELOG.md](CHANGELOG.md).
5. Open a pull request and fill in the template.

CI must pass before review. Pull requests are squash-merged to keep the history linear.

## License

By contributing, you agree that your contributions are licensed under the
[Apache License 2.0](LICENSE).
