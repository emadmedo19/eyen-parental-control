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

function base64Url(bytes: Uint8Array) {
  return btoa(String.fromCharCode(...bytes)).replaceAll("+", "-").replaceAll("/", "_").replaceAll("=", "");
}
function pemBytes(pem: string) {
  const raw = pem.replace(/-----BEGIN PRIVATE KEY-----|-----END PRIVATE KEY-----|\s/g, "");
  return Uint8Array.from(atob(raw), (ch) => ch.charCodeAt(0));
}
async function sendFcmData(token: string, data: Record<string, string>) {
  const raw = Deno.env.get("FCM_SERVICE_ACCOUNT_JSON");
  if (!raw) return { sent: false, reason: "credentials_not_configured" };
  const account = JSON.parse(raw);
  if (!account.project_id || !account.client_email || !account.private_key) {
    throw new Error("FCM service account JSON is missing required fields.");
  }
  const now = Math.floor(Date.now() / 1000);
  const header = base64Url(new TextEncoder().encode(JSON.stringify({ alg: "RS256", typ: "JWT" })));
  const claim = base64Url(new TextEncoder().encode(JSON.stringify({
    iss: account.client_email,
    scope: "https://www.googleapis.com/auth/firebase.messaging",
    aud: "https://oauth2.googleapis.com/token",
    iat: now,
    exp: now + 3600,
  })));
  const unsigned = header + "." + claim;
  const key = await crypto.subtle.importKey(
    "pkcs8", pemBytes(account.private_key),
    { name: "RSASSA-PKCS1-v1_5", hash: "SHA-256" }, false, ["sign"],
  );
  const signature = new Uint8Array(await crypto.subtle.sign(
    "RSASSA-PKCS1-v1_5", key, new TextEncoder().encode(unsigned),
  ));
  const assertion = unsigned + "." + base64Url(signature);
  const oauth = await fetch("https://oauth2.googleapis.com/token", {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "urn:ietf:params:oauth:grant-type:jwt-bearer",
      assertion,
    }),
  });
  const oauthBody = await oauth.json().catch(() => ({}));
  if (!oauth.ok || typeof oauthBody.access_token !== "string") {
    throw new Error("FCM OAuth token request failed.");
  }
  const push = await fetch(
    "https://fcm.googleapis.com/v1/projects/" + account.project_id + "/messages:send",
    {
      method: "POST",
      headers: {
        "Authorization": "Bearer " + oauthBody.access_token,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        message: {
          token,
          data,
          android: { priority: "high", ttl: "120s" },
        },
      }),
    },
  );
  if (!push.ok) {
    const detail = await push.text();
    console.error("FCM delivery failed:", push.status, detail.slice(0, 500));
    return { sent: false, reason: "fcm_delivery_failed" };
  }
  return { sent: true };
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

    const fcmTokenMatch = path.match(/^\/v1\/devices\/([0-9a-f-]+)\/fcm-token$/i);
    if (req.method === "POST" && fcmTokenMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ")
        ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      if (await limited("fcm-token:" + fcmTokenMatch[1], 10)) return reply(429, { error: "rate_limited" });
      const body = await req.json().catch(() => ({}));
      const token = typeof body?.token === "string" ? body.token.trim() : "";
      if (token.length < 40 || token.length > 4096) return reply(400, { error: "invalid_fcm_token" });
      const { data, error } = await db.from("pairing_sessions").update({ fcm_token: token })
        .eq("child_device_id", fcmTokenMatch[1])
        .eq("child_token_hash", await digest(bearer))
        .not("claimed_at", "is", null)
        .select("child_device_id").maybeSingle();
      if (error) throw error;
      if (!data) return reply(401, { error: "device_token_invalid" });
      return reply(200, { ok: true });
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
        .select("child_device_id, fcm_token")
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
      let push = { sent: false, reason: "device_has_no_fcm_token" };
      if (device.fcm_token) {
        push = await sendFcmData(device.fcm_token, {
          type: "LOCATION_REQUEST",
          requestId: command.id,
          deviceId: locationRequestMatch[1],
        });
      }
      return reply(201, {
        requestId: command.id, expiresAt: command.expires_at, status: "pending",
        push,
      });
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


    // Remote session control plane. Media is never captured or relayed by this API.
    const remoteCreateMatch = path.match(/^\/v1\/devices\/([0-9a-f-]+)\/remote-sessions$/i);
    if (req.method === "POST" && remoteCreateMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ") ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      const { data: device, error: deviceError } = await db.from("pairing_sessions")
        .select("id, child_device_id, fcm_token").eq("child_device_id", remoteCreateMatch[1])
        .eq("poll_token_hash", await digest(bearer)).not("claimed_at", "is", null).maybeSingle();
      if (deviceError) throw deviceError;
      if (!device) return reply(404, { error: "device_not_found" });
      const body = await req.json().catch(() => ({}));
      const mode = body?.mode;
      if (!["camera", "audio", "screen"].includes(mode)) return reply(400, { error: "invalid_mode" });
      await db.from("eyen_remote_sessions").update({ status: "expired", ended_at: new Date().toISOString(), end_reason: "expired" })
        .eq("child_device_id", remoteCreateMatch[1]).in("status", ["requested", "approved", "connecting"])
        .lt("expires_at", new Date().toISOString());
      const { data: active } = await db.from("eyen_remote_sessions").select("id")
        .eq("child_device_id", remoteCreateMatch[1]).in("status", ["requested", "approved", "connecting", "active"]).limit(1).maybeSingle();
      if (active) return reply(409, { error: "remote_session_already_active" });
      const { data: session, error } = await db.from("eyen_remote_sessions").insert({
        pairing_session_id: device.id, child_device_id: device.child_device_id,
        mode,
        expires_at: new Date(Date.now() + 2 * 60_000).toISOString(),
      }).select("id, mode, status, requested_at, expires_at").single();
      // Legacy pairing credentials do not identify an auth.users account. Keep the
      // session bound to the verified pairing row; user_id login is a separate rollout.
      if (error) {
        if (error.code === "23503") return reply(409, { error: "account_login_required_for_remote_sessions" });
        throw error;
      }
      let push = { sent: false, reason: "device_has_no_fcm_token" };
      if (device.fcm_token) push = await sendFcmData(device.fcm_token, {
        type: "REMOTE_SESSION_REQUEST", sessionId: session.id, deviceId: device.child_device_id, mode,
      });
      return reply(201, { session, push });
    }

    const childRemoteMatch = path.match(/^\/v1\/devices\/([0-9a-f-]+)\/remote-sessions\/pending$/i);
    if (req.method === "GET" && childRemoteMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ") ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      const { data: device, error: de } = await db.from("pairing_sessions").select("child_device_id")
        .eq("child_device_id", childRemoteMatch[1]).eq("child_token_hash", await digest(bearer))
        .not("claimed_at", "is", null).maybeSingle();
      if (de) throw de;
      if (!device) return reply(401, { error: "device_token_invalid" });
      const { data, error } = await db.from("eyen_remote_sessions")
        .select("id, mode, status, requested_at, expires_at")
        .eq("child_device_id", childRemoteMatch[1]).eq("status", "requested")
        .gt("expires_at", new Date().toISOString()).order("requested_at", { ascending: true }).limit(1).maybeSingle();
      if (error) throw error;
      return reply(200, { session: data ?? null });
    }

    const remoteStatusMatch = path.match(/^\/v1\/remote-sessions\/([0-9a-f-]+)$/i);
    if (req.method === "GET" && remoteStatusMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ")
        ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      const { data: session, error: sessionError } = await db.from("eyen_remote_sessions")
        .select("id, pairing_session_id, child_device_id, mode, status, requested_at, approved_at, started_at, ended_at, expires_at, end_reason")
        .eq("id", remoteStatusMatch[1]).maybeSingle();
      if (sessionError) throw sessionError;
      if (!session) return reply(404, { error: "session_not_found" });
      const { data: parent, error: parentError } = await db.from("pairing_sessions")
        .select("id").eq("id", session.pairing_session_id)
        .eq("poll_token_hash", await digest(bearer)).maybeSingle();
      if (parentError) throw parentError;
      if (!parent) return reply(404, { error: "session_not_found" });
      if (session.status === "requested" && new Date(session.expires_at).getTime() <= Date.now()) {
        const { data: expired, error: expireError } = await db.from("eyen_remote_sessions")
          .update({ status: "expired", ended_at: new Date().toISOString(), end_reason: "expired" })
          .eq("id", session.id).eq("status", "requested")
          .select("id, pairing_session_id, child_device_id, mode, status, requested_at, approved_at, started_at, ended_at, expires_at, end_reason")
          .maybeSingle();
        if (expireError) throw expireError;
        return reply(200, { session: expired ?? { ...session, status: "expired", end_reason: "expired" } });
      }
      const { pairing_session_id: _pairingSessionId, child_device_id: _childDeviceId, ...safeSession } = session;
      return reply(200, { session: safeSession });
    }

    const remoteDecisionMatch = path.match(/^\/v1\/remote-sessions\/([0-9a-f-]+)\/decision$/i);
    if (req.method === "POST" && remoteDecisionMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ") ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      const body = await req.json().catch(() => ({}));
      if (!["approved", "rejected"].includes(body?.decision)) return reply(400, { error: "invalid_decision" });
      const { data: session, error: se } = await db.from("eyen_remote_sessions").select("id, child_device_id, status, expires_at")
        .eq("id", remoteDecisionMatch[1]).maybeSingle();
      if (se) throw se;
      if (!session || session.status !== "requested" || new Date(session.expires_at).getTime() < Date.now()) return reply(404, { error: "session_not_pending" });
      const { data: device, error: de } = await db.from("pairing_sessions").select("child_device_id")
        .eq("child_device_id", session.child_device_id).eq("child_token_hash", await digest(bearer)).maybeSingle();
      if (de) throw de;
      if (!device) return reply(401, { error: "device_token_invalid" });
      const update = body.decision === "approved"
        ? { status: "approved", approved_at: new Date().toISOString() }
        : { status: "rejected", ended_at: new Date().toISOString(), end_reason: "child_rejected" };
      const { data, error } = await db.from("eyen_remote_sessions").update(update)
        .eq("id", session.id).eq("status", "requested").select("id, status, mode").maybeSingle();
      if (error) throw error;
      if (!data) return reply(409, { error: "session_state_changed" });
      return reply(200, { session: data });
    }

    const remoteEndMatch = path.match(/^\/v1\/remote-sessions\/([0-9a-f-]+)\/end$/i);
    if (req.method === "POST" && remoteEndMatch) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ") ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      const { data: session, error } = await db.from("eyen_remote_sessions")
        .select("id, child_device_id, status").eq("id", remoteEndMatch[1]).maybeSingle();
      if (error) throw error;
      if (!session) return reply(404, { error: "session_not_found" });
      const hash = await digest(bearer);
      const { data: parent } = await db.from("pairing_sessions").select("id")
        .eq("id", (await db.from("eyen_remote_sessions").select("pairing_session_id").eq("id", session.id).single()).data?.pairing_session_id)
        .eq("poll_token_hash", hash).maybeSingle();
      const { data: child } = await db.from("pairing_sessions").select("id")
        .eq("child_device_id", session.child_device_id).eq("child_token_hash", hash).maybeSingle();
      if (!parent && !child) return reply(401, { error: "device_token_invalid" });
      const { data, error: updateError } = await db.from("eyen_remote_sessions").update({
        status: "ended", ended_at: new Date().toISOString(), end_reason: "user_stopped",
      }).eq("id", session.id).in("status", ["requested", "approved", "connecting", "active"])
        .select("id, status").maybeSingle();
      if (updateError) throw updateError;
      return reply(200, { session: data ?? { id: session.id, status: "ended" } });
    }

    const signalMatch = path.match(/^\\/v1\\/remote-sessions\\/([0-9a-f-]+)\\/signals$/i);
    if (signalMatch && (req.method === "GET" || req.method === "POST")) {
      const bearer = req.headers.get("authorization")?.startsWith("Bearer ")
        ? req.headers.get("authorization")!.slice(7) : "";
      if (!bearer) return reply(401, { error: "unauthorized" });
      const { data: session, error: sessionError } = await db.from("eyen_remote_sessions")
        .select("id, pairing_session_id, child_device_id, status, expires_at")
        .eq("id", signalMatch[1]).maybeSingle();
      if (sessionError) throw sessionError;
      if (!session) return reply(404, { error: "session_not_found" });
      const tokenHash = await digest(bearer);
      const { data: parent, error: parentError } = await db.from("pairing_sessions")
        .select("id").eq("id", session.pairing_session_id).eq("poll_token_hash", tokenHash).maybeSingle();
      if (parentError) throw parentError;
      const { data: child, error: childError } = await db.from("pairing_sessions")
        .select("id").eq("id", session.pairing_session_id)
        .eq("child_device_id", session.child_device_id).eq("child_token_hash", tokenHash).maybeSingle();
      if (childError) throw childError;
      const senderRole = parent ? "parent" : child ? "child" : null;
      if (!senderRole) return reply(401, { error: "device_token_invalid" });
      if (session.status !== "approved" && session.status !== "connecting" && session.status !== "active") {
        return reply(409, { error: "session_not_approved" });
      }
      if (new Date(session.expires_at).getTime() <= Date.now()) return reply(410, { error: "session_expired" });
      if (req.method === "POST") {
        const body = await req.json().catch(() => ({}));
        const type = body?.type;
        const payload = body?.payload;
        if (!["offer", "answer", "ice", "hangup"].includes(type) || !payload || typeof payload !== "object") {
          return reply(400, { error: "invalid_signal" });
        }
        if (JSON.stringify(payload).length > 16_384) return reply(413, { error: "signal_too_large" });
        const { data, error } = await db.from("eyen_webrtc_signals").insert({
          session_id: session.id, sender_role: senderRole, signal_type: type, payload,
        }).select("id, sender_role, signal_type, payload, created_at").single();
        if (error) throw error;
        if (type === "hangup") {
          await db.from("eyen_remote_sessions").update({
            status: "ended", ended_at: new Date().toISOString(), end_reason: "peer_hangup",
          }).eq("id", session.id).in("status", ["approved", "connecting", "active"]);
        }
        return reply(201, { signal: data });
      }
      const after = Math.max(0, Number(new URL(req.url).searchParams.get("after") || 0));
      const { data, error } = await db.from("eyen_webrtc_signals")
        .select("id, sender_role, signal_type, payload, created_at")
        .eq("session_id", session.id).neq("sender_role", senderRole)
        .gt("id", after).order("id", { ascending: true }).limit(50);
      if (error) throw error;
      return reply(200, { signals: data ?? [] });
    }

    return reply(404, { error: "not_found" });
  } catch (error) {
    console.error("eyen_api_error", error);
    return reply(500, { error: "internal_error" });
  }
});
