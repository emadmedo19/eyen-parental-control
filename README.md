# Eyen — عين
Android-first parental safety project.

## Current milestone: device pairing and status dashboard
The `feature/device-pairing` branch contains the first pairing slice:
- Parent app requests a one-use six-digit code valid for five minutes.
- Child app enters the code to claim the pairing session.
- Parent can check whether the child device has paired.\n- Parent dashboard displays last-seen time, battery and charging state.\n- Child sends status heartbeats while the app is open.\n- Child can share a one-time location after explicit in-app action and Android permission; no background location tracking.
- Pairing codes and bearer secrets are stored as digests on the API, not in plaintext.

## Scope
- Parent Android app and Child companion app.
- Visible, consent-based live sessions for camera, microphone and screen sharing.
- Location, app-usage summaries, screen-time rules and device status.
- Remote access over the internet; local Wi-Fi is not required.

## Important Android constraints
Camera and microphone capture must follow Android permission and foreground-service rules.
Screen sharing uses Android MediaProjection consent. Device Owner does not bypass these controls.
No hidden capture, stealth audio, or covert screen viewing is implemented.

## Project layout
- `android/parent/`: Parent Android app.
- `android/child/`: Child companion Android app.
- `server/`: Node.js API and PostgreSQL schema.
- `docs/`: architecture and implementation plan.

## Build status
This is an early development scaffold, not a production-ready app or installable release yet.
Before testing the Android pairing screens, deploy the API with PostgreSQL and replace
`https://YOUR_API_HOST` in both Android `ApiClient.kt` files with the real HTTPS API origin.
See [server/README.md](server/README.md) for setup.
