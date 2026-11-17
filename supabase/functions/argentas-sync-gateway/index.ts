// Argentas device-authenticated, idempotent sync gateway.
// Deploy only after applying the companion SQL migration and provisioning per-device tokens.
// Required secrets: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY.
// POST JSON: { deliveryId: string, payload: object }
// GET query: ?after=<ISO timestamp>&after_id=<bigint>&limit=<1..500>
// verify_jwt=false is intentional: this endpoint validates its own high-entropy device token.
import { createClient } from "npm:@supabase/supabase-js@2";

const url = Deno.env.get("SUPABASE_URL");
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
if (!url || !serviceKey) throw new Error("Missing Supabase gateway environment configuration");
const db = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
const MAX_BODY_BYTES = 256_000;
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Content-Type": "application/json",
  "Cache-Control": "no-store",
};

function reply(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: cors });
}
async function sha256(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, "0")).join("");
}

// Read at most MAX_BODY_BYTES even when Content-Length is absent or dishonest.
async function readBoundedBody(req: Request): Promise<{ raw: string; tooLarge: boolean; invalidUtf8: boolean }> {
  if (!req.body) return { raw: "", tooLarge: false, invalidUtf8: false };
  const reader = req.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  try {
    while (true) {
      const { value, done } = await reader.read();
      if (done) break;
      if (!value) continue;
      total += value.byteLength;
      if (total > MAX_BODY_BYTES) {
        await reader.cancel();
        return { raw: "", tooLarge: true, invalidUtf8: false };
      }
      chunks.push(value);
    }
    const bytes = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      bytes.set(chunk, offset);
      offset += chunk.byteLength;
    }
    try {
      return { raw: new TextDecoder("utf-8", { fatal: true }).decode(bytes), tooLarge: false, invalidUtf8: false };
    } catch {
      return { raw: "", tooLarge: false, invalidUtf8: true };
    }
  } catch {
    return { raw: "", tooLarge: false, invalidUtf8: true };
  } finally {
    reader.releaseLock();
  }
}
Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response(null, { status: 204, headers: cors });
  if (req.method !== "GET" && req.method !== "POST") return reply(405, { error: "method_not_allowed" });

  const auth = req.headers.get("Authorization") || "";
  const match = /^Bearer ([A-Za-z0-9_-]{32,256})$/.exec(auth);
  if (!match) return reply(401, { error: "unauthorized" });

  const tokenHash = await sha256(match[1]);
  const { data: device, error: deviceError } = await db
    .from("argentas_sync_devices")
    .select("device_id")
    .eq("token_hash", tokenHash)
    .eq("active", true)
    .is("revoked_at", null)
    .maybeSingle();
  if (deviceError) return reply(503, { error: "device_lookup_failed" });
  if (!device) return reply(401, { error: "unauthorized" });

  if (req.method === "POST") {
    const contentType = (req.headers.get("Content-Type") || "").split(";", 1)[0].trim().toLowerCase();
    if (contentType !== "application/json") return reply(415, { error: "unsupported_media_type" });
    const contentLength = req.headers.get("Content-Length");
    if (contentLength && (!/^\d+$/.test(contentLength) || Number(contentLength) > MAX_BODY_BYTES)) {
      return reply(413, { error: "payload_too_large" });
    }
    const bodyRead = await readBoundedBody(req);
    if (bodyRead.tooLarge) return reply(413, { error: "payload_too_large" });
    if (bodyRead.invalidUtf8) return reply(400, { error: "invalid_json" });
    const rawBody = bodyRead.raw;
    let body: any;
    try { body = JSON.parse(rawBody); } catch { return reply(400, { error: "invalid_json" }); }
    const deliveryId = typeof body?.deliveryId === "string" ? body.deliveryId : "";
    const payload = body?.payload;
    if (!deliveryId.trim() || deliveryId.length > 200 || !payload || typeof payload !== "object" || Array.isArray(payload)) {
      return reply(400, { error: "invalid_message" });
    }
    const { data, error } = await db.from("argentas_sync_messages")
      .insert({ sender_device_id: device.device_id, delivery_id: deliveryId, payload })
      .select("id")
      .maybeSingle();
    if (error?.code === "23505") return reply(200, { ok: true, duplicate: true, deliveryId });
    if (error) return reply(500, { error: "write_failed" });
    return reply(201, { ok: true, duplicate: false, id: data?.id, deliveryId });
  }

  const params = new URL(req.url).searchParams;
  const after = params.get("after") || "1970-01-01T00:00:00.000Z";
  const afterIdRaw = params.get("after_id") || "0";
  const afterId = Number(afterIdRaw);
  const limitRaw = Number(params.get("limit") || 200);
  const limit = Number.isFinite(limitRaw) ? Math.min(500, Math.max(1, Math.floor(limitRaw))) : 200;
  // The timestamp is interpolated into a PostgREST .or() filter: accept only
  // an ISO-8601 timestamp, never arbitrary filter syntax.
  const isoCursor = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:\d{2})$/;
  if (!isoCursor.test(after) || !Number.isFinite(Date.parse(after))) return reply(400, { error: "invalid_after" });
  if (!/^\d+$/.test(afterIdRaw) || !Number.isSafeInteger(afterId) || afterId < 0) {
    return reply(400, { error: "invalid_after_id" });
  }
  // Revoked/inactive senders must not continue delivering queued messages.
  const { data: activeDevices, error: activeError } = await db.from("argentas_sync_devices")
    .select("device_id")
    .eq("active", true)
    .is("revoked_at", null);
  if (activeError) return reply(503, { error: "device_registry_failed" });
  const allowedSenders = (activeDevices || [])
    .map((item: { device_id: string }) => item.device_id)
    .filter((id: string) => id !== device.device_id);
  if (!allowedSenders.length) return reply(200, { messages: [], nextCursor: null });

  // Composite cursor prevents losing rows that share the same created_at timestamp.
  const { data, error } = await db.from("argentas_sync_messages")
    .select("id,sender_device_id,delivery_id,payload,created_at")
    .in("sender_device_id", allowedSenders)
    .or(`created_at.gt.${after},and(created_at.eq.${after},id.gt.${afterId})`)
    .order("created_at", { ascending: true })
    .order("id", { ascending: true })
    .limit(limit);
  if (error) return reply(500, { error: "read_failed" });
  const messages = data || [];
  const last = messages.length ? messages[messages.length - 1] : null;
  return reply(200, {
    messages,
    nextCursor: last ? { after: last.created_at, after_id: last.id } : null,
  });
});
