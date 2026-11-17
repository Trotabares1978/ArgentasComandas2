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
    const contentLength = req.headers.get("Content-Length");
    if (contentLength && (!/^\d+$/.test(contentLength) || Number(contentLength) > MAX_BODY_BYTES)) {
      return reply(413, { error: "payload_too_large" });
    }
    let rawBody: string;
    try { rawBody = await req.text(); } catch { return reply(400, { error: "invalid_json" }); }
    if (new TextEncoder().encode(rawBody).byteLength > MAX_BODY_BYTES) return reply(413, { error: "payload_too_large" });
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
  const after = params.get("after") || new Date(Date.now() - 7 * 86400000).toISOString();
  const afterIdRaw = params.get("after_id") || "0";
  const afterId = Number(afterIdRaw);
  const limitRaw = Number(params.get("limit") || 200);
  const limit = Number.isFinite(limitRaw) ? Math.min(500, Math.max(1, Math.floor(limitRaw))) : 200;
  if (Number.isNaN(Date.parse(after))) return reply(400, { error: "invalid_after" });
  if (!/^\d+$/.test(afterIdRaw) || !Number.isSafeInteger(afterId) || afterId < 0) {
    return reply(400, { error: "invalid_after_id" });
  }
  // Composite cursor prevents losing rows that share the same created_at timestamp.
  const { data, error } = await db.from("argentas_sync_messages")
    .select("id,sender_device_id,delivery_id,payload,created_at")
    .neq("sender_device_id", device.device_id)
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
