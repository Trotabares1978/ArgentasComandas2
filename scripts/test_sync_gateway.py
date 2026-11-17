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

checks = {
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
    "GET cursor regex uses single JavaScript escapes": r"const isoCursor = /^\d{4}-\d{2}" in EDGE,
    "GET excludes inactive and revoked senders": '.eq("active", true)' in EDGE and '.is("revoked_at", null)' in EDGE and '.in("sender_device_id", allowedSenders)' in EDGE,
    "GET uses a composite timestamp/id cursor": "after_id" in EDGE and "created_at.gt." in EDGE,
    "GET rejects malformed cursor IDs": "invalid_after_id" in EDGE and "/^\\d+$/" in EDGE,
    "GET has deterministic tie-breaking order": '.order("created_at", { ascending: true })' in EDGE and '.order("id", { ascending: true })' in EDGE,
    "GET returns the next durable cursor": "nextCursor: last ? { after: last.created_at, after_id: last.id } : null" in EDGE,
    "composite cursor index exists": "(created_at asc, id asc)" in SQL,
    "rollback and rollout documented": "Rollback" in GUIDE and "native gateway bridge" in GUIDE and "Android Keystore" in GUIDE,
    "no production migration claimed": "has NOT been deployed to Supabase production" in GUIDE,
    "Android gateway storage uses Android Keystore": '"AndroidKeyStore"' in SECURE and "KeyGenParameterSpec" in SECURE,
    "Android gateway storage uses AES-GCM authenticated encryption": '"AES/GCM/NoPadding"' in SECURE and "GCMParameterSpec(128, iv)" in SECURE,
    "secure storage persists only encrypted Base64 values": "Base64.encodeToString(packed, Base64.NO_WRAP)" in SECURE and 'putString(name, value)' not in SECURE,
    "gateway tokens are restricted to HTTPS Supabase hosts": 'host.endsWith(".supabase.co")' in WORKER and 'uri.scheme == "https"' in WORKER,
    "gateway bearer tokens are high-entropy format checked": 'Regex("[A-Za-z0-9_-]{32,256}")' in WORKER,
    "outbox is persisted before scheduling network work": 'writeArray(context, OUTBOX, queue)' in WORKER and 'enqueueUniqueWork(' in WORKER,
    "outbox is idempotent by delivery ID": 'optString("deliveryId") == deliveryId' in WORKER,
    "inbox page capacity is checked before persistence": "old.length() + additions.size > MAX_QUEUE" in WORKER,
    "inbox is persisted before cursor advances": WORKER.index("addInboxPage(applicationContext, messages)") < WORKER.index("saveCursor(applicationContext, next)"),
    "gateway GET cursor values are URL encoded": 'URLEncoder.encode(cursor.optString("after"), "UTF-8")' in WORKER,
    "gateway redirects are disabled to protect bearer token": "instanceFollowRedirects = false" in WORKER,
    "WorkManager has connected-network constraints": "NetworkType.CONNECTED" in WORKER and "PeriodicWorkRequestBuilder" in WORKER,
    "native bridge exposes gateway queue without exposing token getter": "enqueueGatewayMessage" in ACTIVITY and "gatewayInbox" in ACTIVITY and "fun getGatewayToken" not in ACTIVITY,
    "native bridge exposes configuration and ACK methods": "configureGateway" in ACTIVITY and "acknowledgeGatewayInbox" in ACTIVITY,
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
