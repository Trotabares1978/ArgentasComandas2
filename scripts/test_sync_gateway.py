#!/usr/bin/env python3
"""Static contract checks for the staged Argentas sync gateway."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
SQL = (ROOT / "database/migrations/20261117000100_argentas_sync_gateway.sql").read_text()
EDGE = (ROOT / "supabase/functions/argentas-sync-gateway/index.ts").read_text()
GUIDE = (ROOT / "docs/SYNC_GATEWAY_ROLLOUT.md").read_text()

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
    "rollback and rollout documented": "Rollback" in GUIDE and "gateway HTTP client" in GUIDE and "Android Keystore" in GUIDE,
    "no production migration claimed": "has NOT been deployed to Supabase production" in GUIDE,
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
