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
    const heartbeatMatch = path.match(/^\/v1\/devices\/([0-9a-f-]+)\/heartbeat$/i);
    if (req.method === "POST" && heartbeatMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ")
        ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      if (await limited("heartbeat:" + heartbeatMatch[1], 60)) return reply(429, { error: "rate_limited" });
      const body = await req.json().catch(() => ({}));
      const update: Record<string, unknown> = { last_seen_at: new Date().toISOString() };
      if (Number.isInteger(body?.batteryPercent) && body.batteryPercent >= 0 && body.batteryPercent <= 100) {
        update.battery_percent = body.batteryPercent;
      }
      if (typeof body?.isCharging === "boolean") update.is_charging = body.isCharging;
      const hasLat = typeof body?.latitude === "number" && Number.isFinite(body.latitude) && body.latitude >= -90 && body.latitude <= 90;
      const hasLon = typeof body?.longitude === "number" && Number.isFinite(body.longitude) && body.longitude >= -180 && body.longitude <= 180;
      if (hasLat && hasLon) {
        update.location_lat = body.latitude;
        update.location_lon = body.longitude;
        if (typeof body?.accuracyMeters === "number" && Number.isFinite(body.accuracyMeters) && body.accuracyMeters >= 0) {
          update.location_accuracy_m = body.accuracyMeters;
        }
        update.location_updated_at = new Date().toISOString();
      }
      const { data, error } = await db.from("pairing_sessions").update(update)
        .eq("child_device_id", heartbeatMatch[1])
        .eq("child_token_hash", await digest(bearer))
        .not("claimed_at", "is", null)
        .select("child_device_id")
        .maybeSingle();
      if (error) throw error;
      if (!data) return reply(401, { error: "device_token_invalid" });
      return reply(200, { ok: true, lastSeenAt: update.last_seen_at, locationUpdated: hasLat && hasLon });
    }

    const deviceStatusMatch = path.match(/^\/v1\/devices\/([0-9a-f-]+)\/status$/i);
    if (req.method === "GET" && deviceStatusMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ")
        ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      const { data, error } = await db.from("pairing_sessions")
        .select("child_device_id, child_device_name, last_seen_at, battery_percent, is_charging, location_lat, location_lon, location_accuracy_m, location_updated_at")
        .eq("child_device_id", deviceStatusMatch[1])
        .eq("poll_token_hash", await digest(bearer))
        .not("claimed_at", "is", null)
        .maybeSingle();
      if (error) throw error;
      if (!data) return reply(404, { error: "device_not_found" });
      return reply(200, {
        deviceId: data.child_device_id,
        deviceName: data.child_device_name,
        lastSeenAt: data.last_seen_at,
        batteryPercent: data.battery_percent,
        isCharging: data.is_charging,
        location: data.location_lat !== null && data.location_lon !== null
          ? { latitude: data.location_lat, longitude: data.location_lon, accuracyMeters: data.location_accuracy_m, updatedAt: data.location_updated_at }
          : null,
      });
    }


    const locationRequestMatch = path.match(/^\/v1\/devices\/([0-9a-f-]+)\/location-requests$/i);
    if (req.method === "POST" && locationRequestMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ")
        ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      if (await limited("location-request:" + locationRequestMatch[1], 6)) return reply(429, { error: "rate_limited" });
      const { data: device, error: deviceError } = await db.from("pairing_sessions")
        .select("child_device_id")
        .eq("child_device_id", locationRequestMatch[1])
        .eq("poll_token_hash", await digest(bearer))
        .not("claimed_at", "is", null)
        .maybeSingle();
      if (deviceError) throw deviceError;
      if (!device) return reply(404, { error: "device_not_found" });
      await db.from("device_commands").update({ status: "expired" })
        .eq("child_device_id", locationRequestMatch[1]).eq("status", "pending")
        .lt("expires_at", new Date().toISOString());
      const { data: command, error } = await db.from("device_commands").insert({
        child_device_id: locationRequestMatch[1], command_type: "LOCATION_REQUEST",
        expires_at: new Date(Date.now() + 2 * 60_000).toISOString(),
      }).select("id, expires_at").single();
      if (error) throw error;
      return reply(201, { requestId: command.id, expiresAt: command.expires_at, status: "pending" });
    }

    const childCommandsMatch = path.match(/^\/v1\/devices\/([0-9a-f-]+)\/commands$/i);
    if (req.method === "GET" && childCommandsMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ")
        ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      const { data: device, error: deviceError } = await db.from("pairing_sessions")
        .select("child_device_id").eq("child_device_id", childCommandsMatch[1])
        .eq("child_token_hash", await digest(bearer)).not("claimed_at", "is", null).maybeSingle();
      if (deviceError) throw deviceError;
      if (!device) return reply(401, { error: "device_token_invalid" });
      const now = new Date().toISOString();
      await db.from("device_commands").update({ status: "expired" })
        .eq("child_device_id", childCommandsMatch[1]).eq("status", "pending").lt("expires_at", now);
      const { data: command, error } = await db.from("device_commands")
        .select("id, command_type, expires_at")
        .eq("child_device_id", childCommandsMatch[1]).eq("status", "pending")
        .gt("expires_at", now).order("created_at", { ascending: true }).limit(1).maybeSingle();
      if (error) throw error;
      if (!command) return reply(200, { command: null });
      const { error: claimError } = await db.from("device_commands")
        .update({ status: "claimed", claimed_at: now }).eq("id", command.id).eq("status", "pending");
      if (claimError) throw claimError;
      return reply(200, { command: { id: command.id, type: command.command_type, expiresAt: command.expires_at } });
    }

    const commandResultMatch = path.match(/^\/v1\/devices\/([0-9a-f-]+)\/commands\/([0-9a-f-]+)\/result$/i);
    if (req.method === "POST" && commandResultMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ")
        ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      const { data: device, error: deviceError } = await db.from("pairing_sessions")
        .select("child_device_id").eq("child_device_id", commandResultMatch[1])
        .eq("child_token_hash", await digest(bearer)).not("claimed_at", "is", null).maybeSingle();
      if (deviceError) throw deviceError;
      if (!device) return reply(401, { error: "device_token_invalid" });
      const body = await req.json().catch(() => ({}));
      const ok = body?.status === "completed" &&
        typeof body?.latitude === "number" && Number.isFinite(body.latitude) && body.latitude >= -90 && body.latitude <= 90 &&
        typeof body?.longitude === "number" && Number.isFinite(body.longitude) && body.longitude >= -180 && body.longitude <= 180;
      const result = ok ? {
        latitude: body.latitude, longitude: body.longitude,
        accuracyMeters: typeof body.accuracyMeters === "number" ? body.accuracyMeters : null,
        updatedAt: new Date().toISOString(),
      } : null;
      const { data: command, error } = await db.from("device_commands").update({
        status: ok ? "completed" : "failed", completed_at: new Date().toISOString(), result,
      }).eq("id", commandResultMatch[2]).eq("child_device_id", commandResultMatch[1])
        .eq("status", "claimed").select("id").maybeSingle();
      if (error) throw error;
      if (!command) return reply(404, { error: "command_not_found_or_expired" });
      if (ok) {
        const { error: locationError } = await db.from("pairing_sessions").update({
          location_lat: body.latitude, location_lon: body.longitude,
          location_accuracy_m: typeof body.accuracyMeters === "number" ? body.accuracyMeters : null,
          location_updated_at: new Date().toISOString(), last_seen_at: new Date().toISOString(),
        }).eq("child_device_id", commandResultMatch[1]).eq("child_token_hash", await digest(bearer));
        if (locationError) throw locationError;
      }
      return reply(200, { ok: true });
    }

    const parentCommandMatch = path.match(/^\/v1\/location-requests\/([0-9a-f-]+)$/i);
    if (req.method === "GET" && parentCommandMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ")
        ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      const { data: command, error } = await db.from("device_commands")
        .select("id, child_device_id, status, expires_at, result")
        .eq("id", parentCommandMatch[1]).maybeSingle();
      if (error) throw error;
      if (!command) return reply(404, { error: "request_not_found" });
      const { data: device, error: deviceError } = await db.from("pairing_sessions")
        .select("child_device_id").eq("child_device_id", command.child_device_id)
        .eq("poll_token_hash", await digest(bearer)).not("claimed_at", "is", null).maybeSingle();
      if (deviceError) throw deviceError;
      if (!device) return reply(404, { error: "request_not_found" });
      return reply(200, { requestId: command.id, status: command.status, expiresAt: command.expires_at, location: command.result });
    }

    return reply(404, { error: "not_found" });
  } catch (error) {
    console.error("eyen_api_error", error);
    return reply(500, { error: "internal_error" });
  }
});
