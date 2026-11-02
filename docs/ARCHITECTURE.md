# Eyen architecture

## Components
1. Parent app: account, paired devices, location, reports, policies and visible session requests.
2. Child app: pairing, device status, policy application and explicit visible session controls.
3. API: authentication, pairing, policy storage, audit events and WebRTC signaling.
4. WebRTC: peer-to-peer media where possible; TURN relay only when required.

## Implemented first slice
- Parent creates a short-lived, one-use six-digit pairing code.
- Child claims the code once and receives a device bearer token.
- Parent checks pairing status using a separate polling secret.
- API uses PostgreSQL; code and bearer secrets are stored as peppered SHA-256 digests.
- See `server/db/001_pairing.sql` and `server/src/server.js`.

## Next milestones
- M0: confirm exact OPPO/Infinix models and Android versions.
- M1: deploy API and validate pairing on two real devices over different networks.
- M2: add parent/child accounts, token rotation, revocation and audit history.
- M3: location and usage summaries with user-visible permissions.
- M4: time limits and app restrictions using supported Android APIs.
- M5: visible camera/microphone sessions and MediaProjection screen sharing.
- M6: WebRTC signaling, TURN fallback, reconnect and security testing.

## Privacy and safety
Every camera, microphone or screen session must be visible on the child device, use Android's
required permissions/consent, be time-bounded, and be stoppable locally. No covert capture.
