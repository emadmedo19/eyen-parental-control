import { createClient } from "npm:@supabase/supabase-js@2.57.0";

const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY") ??
  (() => {
    const keys = JSON.parse(Deno.env.get("SUPABASE_SECRET_KEYS") ?? "{}");
    return keys.default;
  })();
if (!supabaseUrl || !serviceKey) throw new Error("Supabase server credentials are unavailable.");

const db = createClient(supabaseUrl, serviceKey, {
  auth: { persistSession: false, autoRefreshToken: false },
});
const pepper = serviceKey;
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Content-Type": "application/json; charset=utf-8",
};
function reply(status: number, data: unknown) {
  return new Response(JSON.stringify(data), { status, headers: cors });
}
function randomToken(bytes = 32) {
  const value = crypto.getRandomValues(new Uint8Array(bytes));
  return btoa(String.fromCharCode(...value)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}
function makeCode() {
  const value = new Uint32Array(1);
  crypto.getRandomValues(value);
  return String(value[0] % 1_000_000).padStart(6, "0");
}
async function digest(value: string) {
  const bytes = new TextEncoder().encode(pepper + ":" + value);
  const hash = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(hash)].map((b) => b.toString(16).padStart(2, "0")).join("");
}
async function limited(key: string, max: number) {
  const { data, error } = await db.rpc("consume_eyen_rate_limit", {
    p_key_hash: await digest("rate:" + key),
    p_max: max,
    p_window_seconds: 60,
  });
  if (error) throw error;
  return data !== true;
}
Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  const url = new URL(req.url);
  const marker = "/eyen-api";
  const markerAt = url.pathname.indexOf(marker);
  const path = markerAt >= 0 ? url.pathname.slice(markerAt + marker.length) : url.pathname;
  const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? "unknown";
  try {
    if (req.method === "GET" && path === "/health") return reply(200, { ok: true, service: "eyen-api" });
    if (req.method === "POST" && path === "/v1/pairing-sessions") {
      if (await limited("create:" + ip, 12)) return reply(429, { error: "rate_limited" });
      const sessionId = crypto.randomUUID();
      const pollToken = randomToken();
      const expiresAt = new Date(Date.now() + 5 * 60_000).toISOString();
      await db.from("pairing_sessions").delete().lt("expires_at", new Date(Date.now() - 86_400_000).toISOString());
      let pairingCode = "";
      let inserted = false;
      for (let i = 0; i < 8; i++) {
        const candidate = makeCode();
        const { error } = await db.from("pairing_sessions").insert({
          id: sessionId, code_hash: await digest(candidate),
          poll_token_hash: await digest(pollToken), expires_at: expiresAt,
        });
        if (!error) { pairingCode = candidate; inserted = true; break; }
        if (error.code !== "23505") throw error;
      }
      if (!inserted) throw new Error("Could not allocate a unique pairing code.");
      return reply(201, { sessionId, pairingCode, pollToken, expiresAt });
    }
    if (req.method === "POST" && path === "/v1/pairing-sessions/claim") {
      if (await limited("claim:" + ip, 5)) return reply(429, { error: "rate_limited" });
      const body = await req.json().catch(() => ({}));
      const code = String(body?.code ?? "").trim();
      const deviceName = String(body?.deviceName ?? "Android device").trim().slice(0, 80);
      if (!/^\d{6}$/.test(code)) return reply(400, { error: "invalid_code_format" });
      const childDeviceId = crypto.randomUUID();
      const childToken = randomToken();
      const { data, error } = await db.from("pairing_sessions").update({
        claimed_at: new Date().toISOString(), child_device_id: childDeviceId,
        child_device_name: deviceName, child_token_hash: await digest(childToken),
      }).eq("code_hash", await digest(code)).is("claimed_at", null)
        .gt("expires_at", new Date().toISOString()).select("id").maybeSingle();
      if (error) throw error;
      if (!data) return reply(404, { error: "code_invalid_or_expired" });
      return reply(200, { paired: true, childDeviceId, childToken });
    }
    const match = path.match(/^\/v1\/pairing-sessions\/([0-9a-f-]+)$/i);
    if (req.method === "GET" && match) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ")
        ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      const { data, error } = await db.from("pairing_sessions")
        .select("claimed_at, child_device_id, child_device_name, expires_at")
        .eq("id", match[1]).eq("poll_token_hash", await digest(bearer)).maybeSingle();
      if (error) throw error;
      if (!data) return reply(404, { error: "session_not_found" });
      return reply(200, {
        status: data.claimed_at ? "paired" :
          (new Date(data.expires_at).getTime() <= Date.now() ? "expired" : "waiting"),
        childDeviceId: data.child_device_id, childDeviceName: data.child_device_name,
      });
    }
    return reply(404, { error: "not_found" });
  } catch (error) {
    console.error("eyen_api_error", error);
    return reply(500, { error: "internal_error" });
  }
});
