# Eyen architecture

## Components
1. Parent app: account, paired devices, location, reports, policies and visible session requests.
2. Child app: pairing, device status, policy application and explicit visible session controls.
3. API: authentication, pairing, policy storage, audit events and WebRTC signaling.
4. WebRTC: peer-to-peer media where possible; TURN relay only when required.

## First milestones
- M0: confirm exact OPPO/Infinix models and Android versions.
- M1: Android build + secure pairing.
- M2: location and usage summaries with user-visible permissions.
- M3: time limits and app restrictions using supported Android APIs.
- M4: visible camera/microphone sessions and MediaProjection screen sharing.
- M5: internet connectivity, TURN fallback, reconnect and security testing.

## Privacy and safety
Every camera, microphone or screen session must be visible on the child device, use Android's
required permissions/consent, be time-bounded, and be stoppable locally. No covert capture.
