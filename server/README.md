# Eyen server (planned)
Recommended first deployment:
- Ubuntu LTS VPS or a suitable free-tier VM.
- HTTPS reverse proxy (Caddy).
- PostgreSQL.
- Small REST/WebSocket API for pairing, device heartbeat, policy sync and session signaling.
- Coturn only as a fallback relay for WebRTC connectivity.

Do not put secrets in the Android apps or commit credentials.
The first milestone should use a development server and test two devices on different networks.
