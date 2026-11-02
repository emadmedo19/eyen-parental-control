# Eyen architecture

## Components
1. Parent app: paired-device dashboard, status refresh, and explicit parent actions.
2. Child app: pairing, foreground status heartbeat, and one-time location sharing initiated on the child device.
3. Supabase: dedicated PostgreSQL database and Edge Function API for pairing and device status.
4. Future WebRTC layer: peer-to-peer sessions where possible, TURN relay only when required.

## Implemented first slice — secure pairing
- Parent creates a short-lived, one-use six-digit pairing code.
- Child claims the code once and receives a device bearer token.
- Parent checks pairing status using a separate polling secret.
- Pairing codes and bearer secrets are stored as peppered SHA-256 digests.
- The active backend is the dedicated Eyen Supabase project. The Node.js server under `server/` is a retained prototype, not the active deployed API.

## Implemented second slice — device status and one-time location
- Parent dashboard can refresh a paired device's last-seen time, battery percentage and charging state.
- Child app sends a heartbeat once per minute only while the app is in the foreground.
- Child can explicitly share a one-time current location after an in-app explanation and Android runtime permission.
- The location is not collected in the background. Parent can open the last shared location in a maps app.
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
