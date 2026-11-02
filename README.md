# Eyen — عين
Android-first parental safety project.

## Current milestone: device pairing and status dashboard
The Android parent and child apps are connected to a dedicated Supabase project.
- Parent app requests a one-use six-digit code valid for five minutes.
- Child app enters the code to claim the pairing session.
- Parent can confirm pairing and see the paired device name.
- Parent dashboard displays last-seen time, battery percentage and charging state.
- Child sends status heartbeats once per minute while the app is open.
- Child can share a one-time current location after an in-app explanation, an explicit tap and Android runtime permission. Location is not collected in the background.
- Parent can open the last shared location in a maps app.
- Pairing codes and bearer secrets are stored as digests on the API, not in plaintext.

## Scope
- Parent Android app and Child companion app.
- Visible, consent-based live sessions for camera, microphone and screen sharing.
- Optional location, app-usage summaries, screen-time rules and device status.
- Remote access over the internet; local Wi-Fi is not required.

## Important Android constraints
Camera and microphone capture must follow Android permission and foreground-service rules.
Screen sharing uses Android MediaProjection consent. Device Owner does not bypass these controls.
No hidden capture, stealth audio, or covert screen viewing is implemented.

## Project layout
- `android/parent/`: Parent Android app.
- `android/child/`: Child companion Android app.
- `supabase/`: Edge Function and database migrations for the dedicated Eyen project.
- `server/`: retained Node.js API prototype; not the active deployed backend.
- `docs/`: architecture and implementation notes.

## Build status
Device pairing has been tested successfully on a real Infinix child device and a parent device.
The device-status dashboard and one-time location-sharing update are in the next Android build.
This is still a development build, not a production-ready release. Camera, microphone, screen sharing,
app-usage reporting, background services, account management and production hardening remain future work.

See [docs/SUPABASE_BACKEND.md](docs/SUPABASE_BACKEND.md) for the active backend configuration.
