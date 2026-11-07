# Eyen — عين

One Android application, installed on both phones. Each installation chooses
**Parent** or **Child**; the choice is remembered on subsequent launches.

## Pair two phones

1. Install the same Eyen APK on both phones.
2. On the parent's phone, choose «هذا هاتف ولي الأمر» and generate a pairing code.
3. On the child's phone, choose «هذا جهاز الطفل», enter the six-digit code, and
   tap «ربط الجهاز». Codes expire after five minutes and can be claimed once.
4. The parent screen automatically detects successful pairing and enables device
   status, battery, location requests and media-session requests.
5. On the child, enable optional location sharing or grant media permissions as
   needed. An open child dashboard checks for session requests automatically.

Camera and microphone sessions remain visible and stoppable on the child.
Screen sharing also requires Android's system capture dialog for each session.
Pairing does not bypass Android permissions or start capture automatically.

«تغيير نوع الجهاز» reopens the role chooser. Switching to Parent stops this
phone's continuous location sharing and clears previous media preapproval.
Pairing credentials for the two roles are stored separately.

## Build

Use Java 17, Gradle 8.9 and Android SDK 35:

```sh
cd android
gradle :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

The single output is `android/app/build/outputs/apk/debug/app-debug.apk`.
CI builds this same unified application, with a signed release artifact on
feature-branch pushes when signing secrets and GitHub Actions billing are available.

The application keeps `app.eyen.child` as its Android application ID to retain
the existing Firebase registration. A release signed with the existing key can
update an old child installation and retain its pairing. Old parent installations
use a different application ID; open the unified application and pair again.
On a previously paired child, use «ربط الجهاز بكود جديد» to confirm stopping
sharing and enter the new parent's code.
Debug APKs cannot update an installed release with a different signing key.

## Project layout

- `android/app/`: unified application, role chooser, and both dashboards.
- `android/app/src/main/java/app/eyen/parent/`: parent features.
- `android/app/src/main/java/app/eyen/child/`: child features and role selection.
- `supabase/`: active Edge Function API and database migrations.
- `server/`: retained Node.js prototype, not the active backend.
- `docs/`: architecture, backend setup and device-testing notes.

This is a development build. Both phones still need a real-device media test.
STUN-only connections may fail on restrictive networks without TURN. The current
backend expires signaling two minutes after a session request is created.
Screen mode currently shares video without an audio track.

See [device verification](docs/LIVE_SESSION_TESTING.md) and
[backend setup](docs/SUPABASE_BACKEND.md).
