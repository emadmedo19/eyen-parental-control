# Eyen — عين
Android-first parental safety project.

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
- `android/`: Kotlin/Gradle Android workspace (Parent and Child apps).
- `server/`: API and database starter.
- `docs/`: architecture and implementation plan.

## Build
Open `android/` in Android Studio (current stable version), allow Gradle sync, then build the
`parent` and `child` debug variants. This starter is a scaffold, not a production-ready app.
