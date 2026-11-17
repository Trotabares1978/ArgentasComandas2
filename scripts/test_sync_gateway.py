#!/usr/bin/env python3
"""Static contract checks for the staged Argentas sync gateway."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SQL = (ROOT / "database/migrations/20261117000100_argentas_sync_gateway.sql").read_text()
EDGE = (ROOT / "supabase/functions/argentas-sync-gateway/index.ts").read_text()
GUIDE = (ROOT / "docs/SYNC_GATEWAY_ROLLOUT.md").read_text()
SECURE = (ROOT / "app/src/main/java/com/trotabares/argentascomandas/GatewaySecureStore.kt").read_text()
WORKER = (ROOT / "app/src/main/java/com/trotabares/argentascomandas/ArgentasGatewayWorker.kt").read_text()
ACTIVITY = (ROOT / "app/src/main/java/com/trotabares/argentascomandas/MainActivity.kt").read_text()
GRADLE = (ROOT / "app/build.gradle.kts").read_text()
MANIFEST = (ROOT / "app/src/main/AndroidManifest.xml").read_text()
BACKUP = (ROOT / "app/src/main/res/xml/backup_rules.xml").read_text()
EXTRACTION = (ROOT / "app/src/main/res/xml/data_extraction_rules.xml").read_text()

ASSET = (ROOT / "app/src/main/assets/index.html").read_text(encoding="utf-8")

checks = {
    "gateway adapter is injected into packaged HTML": 'id="argentas-gateway-adapter"' in ASSET,
    "router returns a delivery-handling result": "var handled=true;raw.trim().split" in ASSET and "return handled}" in ASSET,
    "router marks unsupported messages unhandled": "}else{handled=false}" in ASSET,
    "router marks parse/apply exceptions unhandled": "catch(e){handled=false;console.warn('Mensaje de sincronización inválido',e)}" in ASSET,
    "router keeps VENDER cancellation pending until its draft exists": "else{handled=false}" in ASSET and "sendVenderAck(m.deliveryId||('vender-cancel-draft:'" in ASSET,
    "router does not ACK comanda status before its order exists": "if(!remoteOrder||!validStatus){handled=false}" in ASSET and "validStatus=incomingTs>0" in ASSET,
    "router does not ACK malformed state payloads": "if(!m.db||typeof m.db!=='object'||!m.sessionId){handled=false;return}" in ASSET and "if(!sid||!sentAt){handled=false;return}" in ASSET,
    "gateway uses stable delivery identifiers": "if(message.deliveryId)return String(message.deliveryId)" in ASSET and "if(message.msgId)return" in ASSET,
    "gateway dual-writes the existing Supabase transport": "var original=transport.send" in ASSET and "return original.apply(this,arguments)" in ASSET,
    "gateway staging is opt-in and debug-only": "argentas_gateway_staging_enabled" in ASSET and "isDebugBuild" in ASSET,
    "gateway staging flag is ignored outside debug builds": 'function enabled(){return debugBuild()&&localStorage.getItem(ENABLE_KEY)==="1"}' in ASSET,
    "gateway inbox ACK requires successful existing router handling": "window.onSupabaseMessage(JSON.stringify(payload))===true" in ASSET,
    "gateway skips ephemeral hello messages": 'message.type==="hello"||message.type==="hello-ack"' in ASSET,
    "gateway credentials are not stored in web storage": "localStorage.setItem(ENABLE_KEY" in ASSET and "localStorage.setItem(\"device_token\"" not in ASSET and "localStorage.setItem(\"argentas_gateway_token\"" not in ASSET,
    "gateway message size is bounded before enqueue": "new TextEncoder().encode(payloadJson).length>240000" in ASSET,
    "device token hash stored, not raw token": "token_hash text not null unique" in SQL,
    "device registry RLS enabled": "alter table public.argentas_sync_devices enable row level security" in SQL,
    "messages RLS enabled": "alter table public.argentas_sync_messages enable row level security" in SQL,
    "public roles revoked": "revoke all on public.argentas_sync_messages from anon, authenticated" in SQL,
    "unique per-device delivery key": "unique (sender_device_id, delivery_id)" in SQL,
    "gateway validates strict bearer token": 'const match = /^Bearer ([A-Za-z0-9_-]{32,256})$/.exec(auth)' in EDGE,
    "gateway hashes token with SHA-256": 'crypto.subtle.digest("SHA-256"' in EDGE,
    "gateway resolves sender from registry": '.eq("token_hash", tokenHash)' in EDGE,
    "gateway rejects revoked devices": '.is("revoked_at", null)' in EDGE,
    "database lookup failures are not misreported as bad credentials": 'device_lookup_failed' in EDGE,
    "duplicate delivery returns success": 'error?.code === "23505"' in EDGE,
    "sender cannot read own messages": "filter((id: string) => id !== device.device_id)" in EDGE,
    "request body is streamed with a hard byte ceiling": "async function readBoundedBody" in EDGE and "total > MAX_BODY_BYTES" in EDGE,
    "oversized streams are cancelled before JSON parsing": "await reader.cancel()" in EDGE and "bodyRead.tooLarge" in EDGE,
    "content length is checked before reading": 'Number(contentLength) > MAX_BODY_BYTES' in EDGE,
    "POST requires exact JSON media type": 'contentType !== "application/json"' in EDGE and "unsupported_media_type" in EDGE,
    "invalid UTF-8 is rejected": 'new TextDecoder("utf-8", { fatal: true })' in EDGE,
    "GET cursor is strict ISO-8601 before PostgREST interpolation": "const isoCursor =" in EDGE and "!isoCursor.test(after)" in EDGE,
    "new devices start at the beginning of gateway history": 'params.get("after") || "1970-01-01T00:00:00.000Z"' in EDGE,
    "GET cursor regex uses single JavaScript escapes": r"const isoCursor = /^\d{4}-\d{2}" in EDGE,
    "GET excludes inactive and revoked senders": '.eq("active", true)' in EDGE and '.is("revoked_at", null)' in EDGE and '.in("sender_device_id", allowedSenders)' in EDGE,
    "GET uses a composite timestamp/id cursor": "after_id" in EDGE and "created_at.gt." in EDGE,
    "GET rejects malformed cursor IDs": "invalid_after_id" in EDGE and "/^\\d+$/" in EDGE,
    "GET has deterministic tie-breaking order": '.order("created_at", { ascending: true })' in EDGE and '.order("id", { ascending: true })' in EDGE,
    "GET returns the next durable cursor": "nextCursor: last ? { after: last.created_at, after_id: last.id } : null" in EDGE,
    "composite cursor index exists": "(created_at asc, id asc)" in SQL,
    "rollback and rollout documented": "Rollback" in GUIDE and "ArgentasNativeGateway" in GUIDE and "Android Keystore" in GUIDE,
    "no staging or production deployment claimed": "has NOT been deployed to Supabase staging or production" in GUIDE,
    "Android gateway storage uses Android Keystore": '"AndroidKeyStore"' in SECURE and "KeyGenParameterSpec" in SECURE,
    "Android gateway storage uses AES-GCM authenticated encryption": '"AES/GCM/NoPadding"' in SECURE and "GCMParameterSpec(128, iv)" in SECURE,
    "secure storage persists only encrypted Base64 values": "Base64.encodeToString(packed, Base64.NO_WRAP)" in SECURE and 'putString(name, value)' not in SECURE,
    "AES-GCM ciphertext is bound to each preference key": SECURE.count("cipher.updateAAD(name.toByteArray(Charsets.UTF_8))") == 2,
    "gateway endpoint is pinned to canonical Supabase HTTPS path and port": 'host.endsWith(".supabase.co")' in WORKER and 'uri.scheme == "https"' in WORKER and 'uri.path == "/functions/v1/argentas-sync-gateway"' in WORKER and 'uri.port == -1 || uri.port == 443' in WORKER,
    "gateway bearer tokens are high-entropy format checked": 'Regex("[A-Za-z0-9_-]{32,256}")' in WORKER,
    "outbox is persisted before scheduling network work": 'writeArray(context, OUTBOX, queue)' in WORKER and 'enqueueUniqueWork(' in WORKER,
    "outbox is idempotent by delivery ID": 'optString("deliveryId") == deliveryId' in WORKER,
    "malformed persisted outbox entries fail safely without being silently dropped": "Hay un mensaje dañado en la cola de envíos" in WORKER and "if (deliveryId.isBlank() || payload == null)" in WORKER and "return Result.failure()" in WORKER,
    "WorkManager POST batch is bounded": "const val MAX_POSTS_PER_RUN = 15" in WORKER and "minOf(outgoing.length(), MAX_POSTS_PER_RUN)" in WORKER,
    "bounded worker runs chain another run when outbound backlog remains": "scheduleContinuation(applicationContext)" in WORKER and "outgoing.length() > MAX_POSTS_PER_RUN" in WORKER and "ExistingWorkPolicy.APPEND_OR_REPLACE" in WORKER,
    "bounded worker runs continue paging when a full five-page batch is fetched": "pagesFetched == 5" in WORKER and "pagesFetched++" in WORKER,
    "outbox has a hard encrypted byte ceiling": "MAX_QUEUE_BYTES = 4 * 1024 * 1024" in WORKER and "queue.toString().toByteArray(Charsets.UTF_8).size > MAX_QUEUE_BYTES" in WORKER,
    "inbox page is byte-bounded before cursor advances": "merged.toString().toByteArray(Charsets.UTF_8).size > MAX_QUEUE_BYTES" in WORKER,
    "GET page size limits worst-case response memory": 'append("limit=10")' in WORKER,
    "inbox page capacity is checked before persistence": "old.length() + additions.size > MAX_QUEUE" in WORKER,
    "inbox is persisted before cursor advances": WORKER.index("addInboxPage(applicationContext, messages)") < WORKER.index("saveCursor(applicationContext, next)"),
    "gateway GET cursor values are URL encoded": 'URLEncoder.encode(cursor.optString("after"), "UTF-8")' in WORKER,
    "gateway redirects are disabled to protect bearer token": "instanceFollowRedirects = false" in WORKER,
    "WorkManager has connected-network constraints": "NetworkType.CONNECTED" in WORKER and "PeriodicWorkRequestBuilder" in WORKER,
    "worker catches secure-store failures before reading credentials": WORKER.index("return try {") < WORKER.index("ArgentasGatewayQueue.endpoint(applicationContext)"),
    "invalidated Keystore storage fails cleanly instead of retry-looping": "catch (_: IllegalStateException)" in WORKER and "Result.failure()" in WORKER,

    "native bridge exposes gateway queue without exposing token getter": "enqueueGatewayMessage" in ACTIVITY and "gatewayInbox" in ACTIVITY and "fun getGatewayToken" not in ACTIVITY,
    "native bridge exposes configuration and ACK methods": "configureGateway" in ACTIVITY and "acknowledgeGatewayInbox" in ACTIVITY,
    "gateway credential provisioning is debug-build-only": "fun isDebugBuild()" in ACTIVITY and "if (!isDebugBuild()) return false" in ACTIVITY,
    "gateway JavaScript bridge is not registered in release WebViews": "if (isDebuggable)" in ACTIVITY and 'webView.addJavascriptInterface(NativeBridge(), "ArgentasNativeGateway")' in ACTIVITY,
    "WebView blocks untrusted frames from JavaScript bridge": "if (!request.isForMainFrame) return true" in ACTIVITY and 'uri.path?.startsWith("/android_asset/") == true' in ACTIVITY,
    "foreground polling stops when Activity pauses": "gatewayPollHandler.postDelayed(gatewayPoll, 15_000)" in ACTIVITY and "gatewayPollHandler.removeCallbacks(gatewayPoll)" in ACTIVITY,
    "foreground polling does not cancel active worker": "ExistingWorkPolicy.KEEP" in WORKER,
    "WorkManager dependency is installed": 'androidx.work:work-runtime-ktx:2.10.0' in GRADLE,
    "Android backup excludes Keystore-bound preferences": 'android:fullBackupContent="@xml/backup_rules"' in MANIFEST and 'android:dataExtractionRules="@xml/data_extraction_rules"' in MANIFEST and "argentas_gateway_secure_v1.xml" in BACKUP and "argentas_gateway_secure_v1.xml" in EXTRACTION,
}
# Compile and exercise the exact JavaScript regex with Python's compatible subset.
match = re.search(r"const isoCursor = /(.*?)/;", EDGE)
regex_ok = bool(match)
if match:
    try:
        cursor_re = re.compile(match.group(1))
        regex_ok = bool(cursor_re.fullmatch("2026-11-16T20:15:30.123Z")) and not cursor_re.fullmatch("2026-11-16T20:15:30 OR id.gt.0")
    except re.error:
        regex_ok = False
checks["GET cursor regex compiles and rejects injected filter text"] = regex_ok

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " " + name)
print(f"{len(checks)-len(failed)}/{len(checks)} checks passed")
if failed:
    raise SystemExit(1)
