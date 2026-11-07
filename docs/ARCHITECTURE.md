# Eyen architecture

## Components
The single `android/app` module produces one APK with a persisted role choice.
Parent and child credentials use separate preference stores. The existing
`app.eyen.child` application ID and Firebase registration are preserved.

1. Parent role: paired-device dashboard, status refresh, and explicit parent actions.
2. Child role: pairing, foreground status heartbeat, and consent-based location sharing through a visible Android location foreground service.
3. Supabase: dedicated PostgreSQL database and Edge Function API for pairing and device status.
4. Future WebRTC layer: peer-to-peer sessions where possible, TURN relay only when required.

## Implemented first slice — secure pairing
- Parent creates a short-lived, one-use six-digit pairing code.
- Child claims the code once and receives a device bearer token.
- Parent checks pairing status using a separate polling secret.
- Pairing codes and bearer secrets are stored as peppered SHA-256 digests.
- The active backend is the dedicated Eyen Supabase project. The Node.js server under `server/` is a retained prototype, not the active deployed API.

## Implemented second slice — device status and consent-based location
- Parent dashboard can refresh a paired device's last-seen time, battery percentage and charging state.
- Child app sends a heartbeat once per minute while the app is in the foreground.
- Child can explicitly enable continuous location sharing after an in-app disclosure and Android location permissions, including the separate background-location setting where required.
- While enabled, a visible location foreground service posts an ongoing notification and sends location-bearing heartbeats approximately every 60 seconds. The child can stop sharing from the child app.
- Android may defer updates due to Doze, battery optimization, disabled location services, connectivity, permission changes, or force-stop. A one-minute interval is best-effort, not guaranteed.
- Parent dashboard refreshes device status approximately once per minute while the parent app is foregrounded and displays the last location update time.
- Parent can open the last shared location in a maps app.
- Database changes are in `supabase/migrations/`; the active API is `supabase/functions/eyen-api/index.ts`.

## Next milestones
- M0: confirm exact OPPO/Infinix models and Android versions.
- M1: add parent/child accounts, token rotation, revocation and audit history.
- M2: optional app-usage summaries with a visible Usage Access settings flow and clear disclosure.
- M3: time limits and app restrictions using supported Android APIs.
- M4: visible camera/microphone sessions and MediaProjection screen sharing.
- M5: WebRTC signaling, TURN fallback, reconnect and security testing.

## Privacy and safety
Every camera, microphone or screen session must be visible on the child device, use Android's
required permissions/consent, be time-bounded, and be stoppable locally. No covert capture.
