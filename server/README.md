# Eyen API — pairing milestone

## Requirements
- Node.js 20+
- PostgreSQL 14+
- HTTPS in front of the API in any non-local environment

## Run locally
1. Create a PostgreSQL database.
2. Apply `db/001_pairing.sql`.
3. Copy `.env.example` to `.env` and set `DATABASE_URL` and a random `TOKEN_PEPPER` (32+ characters).
4. Run `npm install`, then `npm start`.

The API currently implements short-lived, one-use six-digit pairing codes:
- `POST /v1/pairing-sessions` creates a code valid for five minutes and returns a separate polling secret.
- `POST /v1/pairing-sessions/claim` consumes a code once and returns a child-device bearer token.
- `GET /v1/pairing-sessions/:id` reports waiting, paired or expired to the parent using its polling secret.
- `GET /health` is a basic health check.

Pairing codes, polling secrets and child tokens are stored only as secret-peppered SHA-256 digests. Use HTTPS, rotate the pepper carefully, and never commit real secrets. This is a first pairing slice, not yet a complete account/authentication system. Expired rows should be periodically cleaned up.

## Android
Set the API base URL in both Android `MainActivity.kt` files before building. It must be an HTTPS URL, without a trailing slash. The apps currently refuse plain HTTP.
