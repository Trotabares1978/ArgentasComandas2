#!/usr/bin/env python3
"""Static contract checks for the staged Argentas sync gateway."""
from pathlib import Path

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
    "gateway validates bearer token": 'auth.startsWith("Bearer ")' in EDGE,
    "gateway hashes token with SHA-256": 'crypto.subtle.digest("SHA-256"' in EDGE,
    "gateway resolves sender from registry": '.eq("token_hash", tokenHash)' in EDGE,
    "gateway rejects revoked devices": '.is("revoked_at", null)' in EDGE,
    "duplicate delivery returns success": 'error?.code === "23505"' in EDGE,
    "sender cannot read own messages": '.neq("sender_device_id", device.device_id)' in EDGE,
    "payload size is bounded in UTF-8 bytes before JSON parsing": 'new TextEncoder().encode(rawBody).byteLength > 256_000' in EDGE,
    "GET uses a composite timestamp/id cursor": "after_id" in EDGE and ".or(`created_at.gt." in EDGE,
    "GET rejects malformed cursor IDs": "if (!/^[0-9]+$/.test(afterIdRaw)" in EDGE,
    "GET has deterministic tie-breaking order": '.order("created_at", { ascending: true })' in EDGE and '.order("id", { ascending: true })' in EDGE,
    "GET returns the next durable cursor": "nextCursor: last ? { after: last.created_at, after_id: last.id } : null" in EDGE,
    "rollback and rollout documented": "Rollback" in GUIDE and "gateway HTTP client" in GUIDE and "Android Keystore" in GUIDE,
    "no production migration claimed": "has NOT been deployed to Supabase production" in GUIDE,
}
failed = [name for name, ok in checks.items() if not ok]
for name, ok in checks.items():
    print(("PASS" if ok else "FAIL") + " " + name)
print(f"{len(checks)-len(failed)}/{len(checks)} checks passed")
if failed:
    raise SystemExit(1)
