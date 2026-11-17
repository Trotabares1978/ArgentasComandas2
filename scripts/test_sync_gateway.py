#!/usr/bin/env python3
"""Static contract checks for the staged Argentas sync gateway."""
from pathlib import Path
import hashlib
import os
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SQL = (ROOT / "supabase/migrations/20261117000100_argentas_sync_gateway.sql").read_text()
RECEIPTS_SQL = (ROOT / "supabase/migrations/20261117000200_argentas_sync_recipient_receipts.sql").read_text()
EDGE = (ROOT / "supabase/functions/argentas-sync-gateway/index.ts").read_text()
GUIDE = (ROOT / "docs/SYNC_GATEWAY_ROLLOUT.md").read_text()
SECURE = (ROOT / "app/src/main/java/com/trotabares/argentascomandas/GatewaySecureStore.kt").read_text()
WORKER = (ROOT / "app/src/main/java/com/trotabares/argentascomandas/ArgentasGatewayWorker.kt").read_text()
ACTIVITY = (ROOT / "app/src/main/java/com/trotabares/argentascomandas/MainActivity.kt").read_text()
ASSET = (ROOT / "app/src/main/assets/index.html").read_text(encoding="utf-8")

checks = {
    "gateway adapter is injected": 'id="argentas-gateway-adapter"' in ASSET,
    "router returns handled result": "var handled=true;raw.trim().split" in ASSET and "return handled}" in ASSET,
    "router rejects unsupported messages": "}else{handled=false}" in ASSET,
    "router only ACKs successfully applied inbox messages": "window.onSupabaseMessage(JSON.stringify(payload))===true" in ASSET,
    "gateway is staging opt-in and debug-only": "argentas_gateway_staging_enabled" in ASSET and "isDebugBuild" in ASSET,
    "outbound dual-write preserves legacy transport": "var original=transport.send" in ASSET and "return original.apply(this,arguments)" in ASSET,
    "message size bounded before enqueue": "new TextEncoder().encode(payloadJson).length>240000" in ASSET,
    "obsolete timestamp cursor indexes are removed": "drop index if exists public.argentas_sync_messages_created_idx" in (ROOT / "supabase/migrations/20261117000500_argentas_sync_remove_cursor_indexes.sql").read_text() and "drop index if exists public.argentas_sync_messages_created_id_idx" in (ROOT / "supabase/migrations/20261117000500_argentas_sync_remove_cursor_indexes.sql").read_text(),
    "per-device rate limit migration uses atomic row counters": "argentas_sync_consume_rate_limit" in (ROOT / "supabase/migrations/20261117000400_argentas_sync_rate_limits.sql").read_text(),
    "rate limit counters are bounded and service-role only": "v_limit := 120" in (ROOT / "supabase/migrations/20261117000400_argentas_sync_rate_limits.sql").read_text() and "v_limit := 60" in (ROOT / "supabase/migrations/20261117000400_argentas_sync_rate_limits.sql").read_text() and "grant execute on function public.argentas_sync_consume_rate_limit" in (ROOT / "supabase/migrations/20261117000400_argentas_sync_rate_limits.sql").read_text(),
    "edge function enforces read/write limits before processing": 'db.rpc("argentas_sync_consume_rate_limit"' in EDGE and 'status: 429' in EDGE and '"Retry-After": "60"' in EDGE,
    "device token hash stored, not raw token": "token_hash text not null unique" in SQL,
    "device and message tables use RLS": "alter table public.argentas_sync_devices enable row level security" in SQL and "alter table public.argentas_sync_messages enable row level security" in SQL,
    "public roles revoked on base tables": "revoke all on public.argentas_sync_messages from anon, authenticated" in SQL,
    "delivery IDs unique per sender": "unique (sender_device_id, delivery_id)" in SQL,
    "strict high-entropy bearer token validated": 'const match = /^Bearer ([A-Za-z0-9_-]{32,256})$/.exec(auth)' in EDGE,
    "token hashed with SHA-256": 'crypto.subtle.digest("SHA-256"' in EDGE,
    "revoked devices rejected": '.is("revoked_at", null)' in EDGE,
    "duplicate POST delivery idempotent": 'error?.code === "23505"' in EDGE,
    "request body streamed with hard size ceiling": "async function readBoundedBody" in EDGE and "total > MAX_BODY_BYTES" in EDGE,
    "oversized body cancelled": "await reader.cancel()" in EDGE,
    "invalid UTF-8 rejected": 'new TextDecoder("utf-8", { fatal: true })' in EDGE,
    "ACK batches bounded and validated": "MAX_ACK_IDS = 100" in EDGE and 'body?.action === "ack"' in EDGE,
    "gateway reads pending messages through receipt RPC": 'db.rpc("argentas_sync_pending_messages"' in EDGE and "nextCursor" not in EDGE and "isoCursor" not in EDGE,
    "gateway writes server ACK through receipt RPC": 'db.rpc("argentas_sync_ack_messages"' in EDGE,
    "receipt table unique per recipient": "primary key (message_id, recipient_device_id)" in RECEIPTS_SQL,
    "receipt table RLS enabled and public access revoked": "alter table public.argentas_sync_receipts enable row level security" in RECEIPTS_SQL and "revoke all on public.argentas_sync_receipts from anon, authenticated" in RECEIPTS_SQL,
    "pending query selects only unacknowledged messages": "not exists (" in RECEIPTS_SQL and "r.recipient_device_id = p_recipient_device_id" in RECEIPTS_SQL,
    "pending query stable ID ordering and bounded limit": "order by m.id asc" in RECEIPTS_SQL and "least(coalesce(p_limit, 10), 100)" in RECEIPTS_SQL,
    "ACK RPC verifies active recipient and excludes own messages": "recipient_device_inactive" in RECEIPTS_SQL and "m.sender_device_id <> p_recipient_device_id" in RECEIPTS_SQL,
    "receipt RPCs service-role only": "grant execute on function public.argentas_sync_pending_messages" in RECEIPTS_SQL and "grant execute on function public.argentas_sync_ack_messages" in RECEIPTS_SQL,
    "receipt RPCs pin empty search_path": "set search_path = '';" in (ROOT / "supabase/migrations/20261117000300_argentas_sync_rpc_search_path.sql").read_text(),
    "Android secure storage uses Android Keystore": '"AndroidKeyStore"' in SECURE and "KeyGenParameterSpec" in SECURE,
    "Android secure storage uses AES-GCM": '"AES/GCM/NoPadding"' in SECURE and "GCMParameterSpec(128, iv)" in SECURE,
    "encrypted preferences bind ciphertext to key": SECURE.count("cipher.updateAAD(name.toByteArray(Charsets.UTF_8))") == 2,
    "endpoint restricted to HTTPS Supabase function": 'host.endsWith(".supabase.co")' in WORKER and 'uri.scheme == "https"' in WORKER and 'uri.path == "/functions/v1/argentas-sync-gateway"' in WORKER,
    "outbox idempotent by delivery ID": 'optString("deliveryId") == deliveryId' in WORKER,
    "outbox batch bounded": "const val MAX_POSTS_PER_RUN = 15" in WORKER,
    "outbox continuation retained": "scheduleContinuation(applicationContext)" in WORKER and "ExistingWorkPolicy.APPEND_OR_REPLACE" in WORKER,
    "remote ACK queue is persisted before local inbox removal": WORKER.index("writeArray(context, SERVER_ACKS, newAcks)") < WORKER.index("writeArray(context, INBOX, kept)"),
    "native ACK only accepts IDs already persisted in local inbox": "if (present.contains(id)) acknowledged.add(id)" in WORKER,
    "worker sends remote ACKs before fetching next pending page": WORKER.index('put("action", "ack")') < WORKER.index('request("$endpoint?limit=10", token, "GET", null)'),
    "worker fetches without unsafe timestamp cursor": 'request("$endpoint?limit=10", token, "GET", null)' in WORKER and "saveCursor" not in WORKER,
    "inbox is persisted before app router sees it": "addInboxPage(applicationContext, messages)" in WORKER,
    "queue capacity is bounded by bytes": "MAX_QUEUE_BYTES = 4 * 1024 * 1024" in WORKER,
    "native gateway bridge debug-only": "if (isDebuggable)" in ACTIVITY and 'webView.addJavascriptInterface(NativeBridge(), "ArgentasNativeGateway")' in ACTIVITY,
    "untrusted WebView frames blocked": "if (!request.isForMainFrame) return true" in ACTIVITY,
    "rollout warns no production deployment": "No production migration or Edge Function deployment has been performed." in GUIDE,
}

# Exercise the one-time provisioner in a disposable directory. The secret must
# be written only to a private token file and never printed to stdout.
provisioner = ROOT / "scripts/provision_sync_device.py"
with tempfile.TemporaryDirectory() as tmp:
    result = subprocess.run(
        [sys.executable, str(provisioner), "--device-id", "tablet-test-01", "--label", "tablet test", "--out-dir", tmp],
        check=False, capture_output=True, text=True,
    )
    token_file = Path(tmp) / "tablet-test-01.token"
    sql_file = Path(tmp) / "tablet-test-01.sql"
    token = token_file.read_text().strip() if token_file.exists() else ""
    sql = sql_file.read_text() if sql_file.exists() else ""
    checks["provisioner creates a 256-bit random-format token privately"] = (
        result.returncode == 0 and re.fullmatch(r"[A-Za-z0-9_-]{43}", token) is not None
        and token not in result.stdout and token not in result.stderr
    )
    checks["provisioner writes only token hash to SQL"] = (
        result.returncode == 0 and hashlib.sha256(token.encode()).hexdigest() in sql
        and token not in sql
    )
    checks["provisioner protects secret files and directory permissions"] = (
        os.name != "posix" or (
            (token_file.stat().st_mode & 0o777) == 0o600
            and (sql_file.stat().st_mode & 0o777) == 0o600
            and (Path(tmp).stat().st_mode & 0o777) == 0o700
        )
    )
    checks["credential output directory is ignored by Git"] = "argentas-device-secrets/" in (ROOT / ".gitignore").read_text()
    checks["provisioner refuses to overwrite existing credentials"] = (
        subprocess.run(
            [sys.executable, str(provisioner), "--device-id", "tablet-test-01", "--label", "tablet test", "--out-dir", tmp],
            check=False, capture_output=True, text=True,
        ).returncode != 0
    )

failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS " if ok else "FAIL ") + name)
print(f"{len(checks)-len(failed)}/{len(checks)} checks passed")
if failed:
    raise SystemExit(1)
