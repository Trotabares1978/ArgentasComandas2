// Argentas device-authenticated, idempotent sync gateway.
// Deploy only after applying the companion SQL migration and provisioning per-device tokens.
// Required secrets: SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY.
// Requests: Authorization: Bearer <per-device-random-token>
// POST JSON: { deliveryId: string, payload: object }
// GET query: ?after=<ISO timestamp>&limit=<1..500>
// verify_jwt=false is intentional: this endpoint validates its own high-entropy device token.
import { createClient } from "npm:@supabase/supabase-js@2";

const url = Deno.env.get("SUPABASE_URL")!;
const serviceKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const db = createClient(url, serviceKey, { auth: { persistSession: false, autoRefreshToken: false } });
const cors = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
  "Content-Type": "application/json",
};

function reply(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: cors });
}
async function sha256(value: string): Promise<string> {
  const bytes = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(value));
  return Array.from(new Uint8Array(bytes), (b) => b.toString(16).padStart(2, "0")).join("");
}
Deno.serve(async (req: Request) => {
  if (req.method === "OPTIONS") return new Response("ok", { headers: cors });
  if (req.method !== "GET" && req.method !== "POST") return reply(405, { error: "method_not_allowed" });

  const auth = req.headers.get("Authorization") || "";
  const token = auth.startsWith("Bearer ") ? auth.slice(7).trim() : "";
  if (token.length < 32 || token.length > 256) return reply(401, { error: "unauthorized" });

  const tokenHash = await sha256(token);
  const { data: device, error: deviceError } = await db
    .from("argentas_sync_devices")
    .select("device_id")
    .eq("token_hash", tokenHash)
    .eq("active", true)
    .is("revoked_at", null)
    .maybeSingle();
  if (deviceError || !device) return reply(401, { error: "unauthorized" });

  if (req.method === "POST") {
    let body: any;
    try { body = await req.json(); } catch { return reply(400, { error: "invalid_json" }); }
    const deliveryId = String(body?.deliveryId || "");
    const payload = body?.payload;
    if (!deliveryId || deliveryId.length > 200 || !payload || typeof payload !== "object" || Array.isArray(payload)) {
      return reply(400, { error: "invalid_message" });
    }
    const encoded = JSON.stringify(payload);
    if (encoded.length > 256_000) return reply(413, { error: "payload_too_large" });

    const { data, error } = await db.from("argentas_sync_messages")
      .insert({ sender_device_id: device.device_id, delivery_id: deliveryId, payload })
      .select("id")
      .maybeSingle();
    if (error?.code === "23505") return reply(200, { ok: true, duplicate: true, deliveryId });
    if (error) return reply(500, { error: "write_failed" });
    return reply(201, { ok: true, duplicate: false, id: data?.id, deliveryId });
  }

  const after = new URL(req.url).searchParams.get("after") || new Date(Date.now() - 7 * 86400000).toISOString();
  const limitRaw = Number(new URL(req.url).searchParams.get("limit") || 200);
  const limit = Number.isFinite(limitRaw) ? Math.min(500, Math.max(1, Math.floor(limitRaw))) : 200;
  if (Number.isNaN(Date.parse(after))) return reply(400, { error: "invalid_after" });
  const { data, error } = await db.from("argentas_sync_messages")
    .select("id,sender_device_id,delivery_id,payload,created_at")
    .neq("sender_device_id", device.device_id)
    .gt("created_at", after)
    .order("created_at", { ascending: true })
    .limit(limit);
  if (error) return reply(500, { error: "read_failed" });
  return reply(200, { messages: data || [] });
});
