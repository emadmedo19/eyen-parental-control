# Deploying the Eyen TURN relay

The Eyen API now issues short-lived TURN credentials using the TURN REST API (HMAC-SHA1).
This directory contains a Coturn deployment template. A reachable VPS with a public IPv4
address is required; Supabase Edge Functions are not a TURN relay and cannot replace it.

## 1. VPS firewall

Allow inbound:
- UDP and TCP 3478
- UDP 49160-49200

Use a Linux VPS with Docker Engine and the Docker Compose plugin installed.

## 2. Configure and start Coturn

Copy `.env.example` to `.env`, set:
- `TURN_SHARED_SECRET`: a long random secret (at least 32 random bytes).
- `TURN_REALM`: your TURN hostname or a stable realm name.
- `TURN_PUBLIC_IP`: the VPS public IPv4 address.

Then run:

```sh
docker compose --env-file .env -f docker-compose.turn.yml up -d
docker compose -f docker-compose.turn.yml logs -f
```

The compose file uses host networking so Coturn can allocate its UDP relay ports directly.

## 3. Configure Supabase Edge Function secrets

Set these secrets for the Supabase project that hosts `eyen-api`:

```sh
supabase secrets set TURN_URLS=turn:<TURN_HOST_OR_IP>:3478?transport=udp,turn:<TURN_HOST_OR_IP>:3478?transport=tcp
supabase secrets set TURN_SHARED_SECRET=<same-secret-as-.env>
supabase secrets set TURN_TTL_SECONDS=900
```

Replace placeholders; never commit the actual secret to Git. The API returns time-limited
credentials only to a parent or child device authorized for that live session.

## 4. Verify

Open a session on two devices on different networks (for example, Wi-Fi and mobile data).
Check the API response from `GET /v1/remote-sessions/{sessionId}/ice-servers`:
- `turnConfigured` must be `true`.
- `iceServers` must include TURN URLs, username, and credential.

Then confirm the WebRTC ICE state reaches `connected` or `completed` and test camera,
microphone, and screen-share separately.

## Security notes

- Do not expose `TURN_SHARED_SECRET` in Android apps or client-visible config.
- Keep the VPS firewall limited to the TURN ports above.
- Use a strong secret and rotate it if it is exposed.
- For production, add TLS certificates and TURN over TLS on 5349 if required by restrictive networks.
