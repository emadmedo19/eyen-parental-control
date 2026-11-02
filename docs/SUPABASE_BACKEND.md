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
This milestone implements device pairing only. It does not yet implement location, app usage, camera, microphone, screen-sharing, WebRTC signaling, token rotation/revocation, or full parent account authentication. Those capabilities must be added as separate, consent-based features with visible Android permissions and controls.
