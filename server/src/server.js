import crypto from "node:crypto";
import express from "express";
import helmet from "helmet";
import { rateLimit } from "express-rate-limit";
import pg from "pg";

const { Pool } = pg;
const app = express();
const port = Number(process.env.PORT || 8080);
const pepper = process.env.TOKEN_PEPPER;
if (!process.env.DATABASE_URL || !pepper || pepper.length < 32) {
  throw new Error("DATABASE_URL and a TOKEN_PEPPER of at least 32 characters are required.");
}
const pool = new Pool({ connectionString: process.env.DATABASE_URL, ssl: process.env.PGSSLMODE === "require" ? { rejectUnauthorized: true } : undefined });

app.disable("x-powered-by");
app.use(helmet());
app.use(express.json({ limit: "16kb" }));
app.use("/v1/pairing-sessions", rateLimit({ windowMs: 60_000, limit: 20, standardHeaders: "draft-7", legacyHeaders: false }));

const digest = (value) => crypto.createHash("sha256").update(pepper).update(":").update(value).digest("hex");
const randomToken = () => crypto.randomBytes(32).toString("base64url");
const makeCode = () => String(crypto.randomInt(0, 1_000_000)).padStart(6, "0");
const asyncRoute = (handler) => (req, res, next) => Promise.resolve(handler(req, res, next)).catch(next);

app.get("/health", (_req, res) => res.json({ ok: true, service: "eyen-api" }));

app.post("/v1/pairing-sessions", asyncRoute(async (_req, res) => {
  const sessionId = crypto.randomUUID();
  const pollToken = randomToken();
  const expiresAt = new Date(Date.now() + 5 * 60_000);
  let pairingCode;
  for (let attempt = 0; attempt < 5; attempt++) {
    const candidate = makeCode();
    try {
      await pool.query(
        "INSERT INTO pairing_sessions (id, code_hash, poll_token_hash, expires_at) VALUES ($1,$2,$3,$4)",
        [sessionId, digest(candidate), digest(pollToken), expiresAt]
      );
      pairingCode = candidate;
      break;
    } catch (error) {
      if (error.code !== "23505" || attempt === 4) throw error;
    }
  }
  res.status(201).json({ sessionId, pairingCode, pollToken, expiresAt: expiresAt.toISOString() });
}));

app.post("/v1/pairing-sessions/claim", asyncRoute(async (req, res) => {
  const code = String(req.body?.code ?? "").trim();
  const deviceName = String(req.body?.deviceName ?? "Android device").trim().slice(0, 80);
  if (!/^\d{6}$/.test(code)) return res.status(400).json({ error: "invalid_code_format" });

  const childDeviceId = crypto.randomUUID();
  const childToken = randomToken();
  const result = await pool.query(
    `UPDATE pairing_sessions
       SET claimed_at = NOW(), child_device_id = $2, child_device_name = $3, child_token_hash = $4
     WHERE code_hash = $1 AND claimed_at IS NULL AND expires_at > NOW()
     RETURNING id`,
    [digest(code), childDeviceId, deviceName, digest(childToken)]
  );
  if (result.rowCount !== 1) return res.status(404).json({ error: "code_invalid_or_expired" });
  res.status(200).json({ paired: true, childDeviceId, childToken });
}));

app.get("/v1/pairing-sessions/:id", asyncRoute(async (req, res) => {
  const bearer = req.headers.authorization?.startsWith("Bearer ") ? req.headers.authorization.slice(7) : "";
  if (!bearer) return res.status(401).json({ error: "unauthorized" });
  const result = await pool.query(
    `SELECT claimed_at, child_device_id, child_device_name, expires_at
       FROM pairing_sessions WHERE id = $1 AND poll_token_hash = $2`,
    [req.params.id, digest(bearer)]
  );
  if (result.rowCount !== 1) return res.status(404).json({ error: "session_not_found" });
  const row = result.rows[0];
  res.json({
    status: row.claimed_at ? "paired" : (new Date(row.expires_at) <= new Date() ? "expired" : "waiting"),
    childDeviceId: row.child_device_id,
    childDeviceName: row.child_device_name
  });
}));

app.use((error, _req, res, _next) => {
  console.error("request_failed", error);
  res.status(500).json({ error: "internal_error" });
});

app.listen(port, () => console.log(`Eyen API listening on :${port}`));
