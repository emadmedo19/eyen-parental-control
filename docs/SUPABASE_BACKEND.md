# Eyen backend — Supabase

The Eyen backend is hosted in a dedicated Supabase project and is separate from the Bunyan/El-Emara project.

- Project ref: `wreilawyquhhbvkkgmfy`
- Region: `eu-central-1` (Frankfurt)
- Edge Function: `eyen-api`
- Function URL: `https://wreilawyquhhbvkkgmfy.supabase.co/functions/v1/eyen-api`

## Pairing API
- `GET /health`
- `POST /v1/pairing-sessions`: creates a six-digit code valid for five minutes.
- `POST /v1/pairing-sessions/claim`: consumes a code once and returns the child-device token.
- `GET /v1/pairing-sessions/:id`: checks pairing state using the parent polling token.

The function uses the project's server-side secret key only inside the Edge Function. Android contains only the public publishable key. Pairing codes, polling tokens, child tokens and rate-limit identifiers are stored as peppered SHA-256 hashes. RLS is enabled and direct anon/authenticated table access is revoked; only the service role can access these tables. Rate limiting is enforced in PostgreSQL, not process memory.

## Deployment
The production function and database migrations are deployed to project ref `wreilawyquhhbvkkgmfy`. The migration SQL is retained in `supabase/migrations/`. The Android parent and child clients target the function URL above.

## Current scope and limitations
This milestone implements device pairing, foreground device-status heartbeats, and child-initiated one-time location sharing. It does not yet implement app-usage reporting, camera, microphone, screen-sharing, WebRTC signaling, token rotation/revocation, or full parent account authentication. Those capabilities must be added as separate, consent-based features with visible Android permissions and controls.


## Device status and one-time location
The `eyen-api` function also supports:
- `POST /v1/devices/:deviceId/heartbeat` — child-device bearer token; stores last-seen time, battery percentage and charging state.
- `GET /v1/devices/:deviceId/status` — parent polling token; returns the paired device's status and any location the child explicitly shared.

The child app sends battery/last-seen heartbeats while its screen is open. Location is **not tracked in the background**: the child must tap “مشاركة موقعي الآن”, accept the in-app explanation, and grant Android's location permission. Each action sends a one-time location fix. The parent can open the last shared fix in a maps app.

The status/location columns are added by migration `20261102154904_add_eyen_device_status_and_location.sql`.
