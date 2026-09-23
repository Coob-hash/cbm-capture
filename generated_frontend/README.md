# CBM App — v2 front end

One app, one login screen, three role-specific experiences (PRD 2.0):
Reporter · Technician · Facility Manager, over the Community-Based
Maintenance pipeline.

## Stack

- Kotlin Multiplatform + Compose Multiplatform, **Android first** (PRD Q5, §11).
  All UI lives in `shared/src/commonMain`; an iOS target can be added later
  without restructuring. Capture stays native (PRD §11) — the Capture screen
  exposes the viewfinder area where the ARCore preview binds.
- No backend is re-implemented. `CbmApi` (`shared/.../data/CbmApi.kt`) mirrors
  the App API endpoints of PRD §9.2 one-to-one. `FakeCbmApi` is a demo
  implementation with realistic Maddaloni Office (ROOM-POC) data; replace it
  with an HTTPS client (Ktor) against `/v1/*` in production.

## Modules

- `shared/` — design system (`theme/`, `design/`), domain model (`domain/`),
  API + session + outbox (`data/`), navigation (`nav/`) and the three role
  experiences (`feature/`).
- `androidApp/` — thin Android shell (MainActivity, manifest, launcher icon).

## Run

Open in Android Studio (Ladybug+), let Gradle sync, run the `androidApp`
configuration on a device or emulator (minSdk 26).

Demo accounts (any password of 4+ characters):

- `user@cbm.site` — Reporter
- `tech@cbm.site` — Technician (starts with no trades → T-0 onboarding)
- `fm@cbm.site` — Facility Manager (split dashboard + WF3 chat)
- `multi@cbm.site` — two memberships → membership picker (PRD §3)

Signing up a new FM account shows the "waiting for approval" state (PRD §3).

## Design system

`theme/` defines the concrete/ink/steel palette, hi-vis signal colors, role
accents (PRD §4.2), the typography scale (grotesque sans + monospace technical
face) and squared geometry. `design/` holds the reusable instrument
components: CbmPanel (title-block card with status rail), CbmKpiTile,
StatusBadge / LedDot / FirstJobTag, CbmButtons, CbmInputs, CbmTopBar
(role-branded header with site code + session countdown), charts and feedback
states.
