# Argentas sync gateway: deployment and device provisioning

## Status
This is a staged dual-write path. The native gateway adapter is integrated into the Android build pipeline but remains inactive unless an administrator explicitly configures and enables it in a debug build. The isolated Supabase staging project is active, all four schema migrations are applied, and the Edge Function is deployed with custom bearer-token authentication. No device tokens have been provisioned, so no device can use the gateway yet. Nothing has been deployed to production. The existing Supabase/Pusher transport remains authoritative and available as the fallback until the gateway has passed physical-device and soak testing.

## Security model
- Each physical device receives an independent cryptographically random bearer token (at least 256 bits).
- Only SHA-256(token) is stored in `public.argentas_sync_devices`; raw tokens must never be committed or inserted into SQL.
- The Edge Function validates the token and resolves its device ID. The client cannot choose a sender ID.
- The service-role key exists only in the Edge Function environment.
- Public/anonymous roles have no grants on the new tables.
- `(sender_device_id, delivery_id)` is unique. Retrying the same delivery is acknowledged as a duplicate without creating another row.
- A device can only read messages sent by other registered devices. The endpoint currently treats all active devices in this single Argentas project as one trusted installation group.
- GET now uses recipient-specific pending delivery and durable server receipts; it does not rely on a timestamp cursor.
- POST enforces the 256 KB ceiling while streaming the request body (including chunked requests), before JSON parsing; it accepts only the exact `application/json` media type and rejects invalid UTF-8.
- GET limits delivery to currently active, non-revoked sender devices; revoking a sender stops its queued messages from being delivered to other devices.
- Responses are not cached.
- CORS is permissive for WebView compatibility; it is not an authentication control. The bearer token is the actual credential. Supabase security advisor reports the three RLS-enabled gateway tables have no policies; this is intentional because direct client access is denied and only the service-role Edge Function uses them. Do not add broad policies merely to silence this informational finding.
- Atomic per-device fixed-window throttling is enabled: at most 120 POST operations and 60 GET polls per minute; excess requests receive HTTP 429 with `Retry-After: 60`. The SQL counter update is atomic on the registered device row, so concurrent requests cannot bypass the cap.
- Automated abuse detection beyond these request ceilings is not implemented. Safe retention/expiry is also missing and remains a release blocker.

## Verified state of the Android gateway foundation
- The WebView still loads the canonical app assembled from `source/argentas-original-*.part`; the legacy Supabase/Pusher transport remains unchanged.
- `MainActivity.kt` now exposes a separate `ArgentasNativeGateway` bridge. It does not replace or pretend to implement the legacy Bluetooth methods.
- `GatewaySecureStore.kt` stores the endpoint, bearer token, outbox, inbox, durable remote-ACK queue, and diagnostics using AES-256-GCM with a key held by Android Keystore. The secure preferences are excluded from cloud backup and device transfer because the Keystore key is device-bound.
- `ArgentasGatewayWorker.kt` uses WorkManager with connected-network constraints. It POSTs queued deliveries in bounded batches of 15, persists one pending GET page, and flushes a durable encrypted server-ACK queue before fetching the next page.
- While the app is foregrounded, `MainActivity` schedules a non-overlapping gateway poll every 15 seconds. WorkManager also retains a 15-minute periodic fallback while Android allows background execution.
- The queue rejects overflow instead of dropping messages. Inbox page persistence is all-or-nothing with respect to capacity, so the server keeps returning the same pending page until the app can store and apply it.
- Gateway configuration accepts only HTTPS Supabase project hosts and validates the high-entropy device-token format. The token is never returned through the JavaScript bridge.
- `scripts/integrate_sync_gateway.py` wraps the existing `ArgentasSupabaseTransport.send` for staging-only dual-write and routes inbox messages through `window.onSupabaseMessage`. The router now returns an explicit handled result; local inbox ACK occurs only when it returns true. No staging or production token is provisioned, and no gateway traffic is being sent.
- The old Bluetooth controls remain nonfunctional in this revision; the new HTTP gateway is a separate staged path.


## Critical delivery guarantee still missing

The previous `(created_at, id)` cursor design was removed because it could not guarantee commit-order completeness: PostgreSQL `now()` is fixed at transaction start, so a late-committing transaction could fall behind an already-advanced cursor. The current recipient-receipt implementation addresses that delivery gap by repeatedly selecting unacknowledged messages. Physical-device replay and crash tests remain required.

## Supabase Data API exposure check

The migrations create tables and RPCs in `public`. The isolated staging project has the standard `public` schema available to the Data API; table grants remain restricted to `service_role`, RLS is enabled, and RPC execution is revoked from `public`, `anon` and `authenticated`. Supabase's 2026-10-30 Data API change means new tables are not automatically exposed by default; explicit service-role grants are included. Do not use the existing `Manos Verdes Pedidos` project as Argentas staging.


## Recipient-based delivery protocol (staging implementation)

The gateway no longer relies on a timestamp cursor. Every GET asks the database for the oldest messages that have no receipt for the authenticated recipient device. The query is repeated from the beginning of the pending set on each poll; a message that commits late remains eligible because it is not hidden behind a saved cursor.

After the app persists a complete page in its encrypted local inbox, the existing router applies each message. Only a successful router result is locally acknowledged. The Android bridge first persists the message IDs in a separate encrypted server-ACK queue, then removes them from the local inbox. WorkManager sends those receipt IDs to the gateway; the database inserts one receipt per (message, recipient) idempotently. Only after the server confirms the receipt does the worker remove it from the local ACK queue. This ordering tolerates process death and network retries without silently dropping an ACK.

The pending query is intentionally page-based: it keeps returning the oldest unacknowledged rows until the client applies and ACKs them, then the next page becomes visible. The worker fetches one page per run; the WebView applies it and schedules the next run through the ACK bridge. No automatic server-side message deletion is enabled. Retention and expiry/revocation policy remain a separate production gate. A simple age-based deletion is unsafe because it could remove messages needed by a device that was offline. A simple age-based deletion is unsafe because it could remove messages needed by a device that was offline.

## Release sequence (required)
1. JavaScript adapter, staging-only dual-write, router handled-result, and ACK-after-apply behavior are implemented in the build pipeline.
2. A debug-only provisioning panel is implemented. Provision one independent token per device in the staging registry; never put raw tokens in source, logs, screenshots, or chat.
3. Build and inspect the audit APK; confirm the legacy transport still works and that the gateway remains inactive when not configured.
4. Generate one token per device on a trusted admin workstation. Insert only SHA-256 token hashes into the staging device registry.
5. Apply the migration to a separate Supabase staging project and deploy the Edge Function there with `verify_jwt=false` and its service-role secret configured in Supabase secrets.
6. Verify: unauthorized token rejected; revoked token rejected; two registered devices exchange messages; same delivery ID retry yields one row; sender does not receive its own message; payload >256 KB rejected; device A cannot impersonate device B; pagination handles multiple messages with identical timestamps; offline outbox survives app/process restart.
7. Run parallel soak tests and reconcile message counts against the legacy channel.
8. Only after approval, schedule production rollout and keep legacy transport available for rollback.
9. Revoke legacy `anon`/public access to `argentas_conexion` only after every active client is migrated and verified.

## Applying the migration

The SQL migrations are `supabase/migrations/20261117000100_argentas_sync_gateway.sql`, `supabase/migrations/20261117000200_argentas_sync_recipient_receipts.sql`, `supabase/migrations/20261117000300_argentas_sync_rpc_search_path.sql`, and `supabase/migrations/20261117000400_argentas_sync_rate_limits.sql`. All four have been applied to the isolated `ARGENTAS-Staging` project only. Do not run them against production during this phase.

## Provisioning token hashes
Generate each token on a trusted machine using a cryptographically secure random generator (for example, 32 random bytes encoded as hex). Compute SHA-256 locally and insert only the hash:
```sql
insert into public.argentas_sync_devices (device_id, token_hash, label)
values ('<existing-device-id>', '<64-char-sha256-hex>', 'tablet principal');
```
Never store the raw token in the database or repository. Deliver it through a secure, direct channel and store it using Android Keystore. Revocation:
```sql
update public.argentas_sync_devices
set active = false, revoked_at = now()
where device_id = '<device-id>';
```

## Rollback
Do not drop the new tables during rollback. Revert Android clients to the legacy transport, disable the Edge Function, and leave the new schema/data intact for diagnosis. Do not alter or purge `argentas_conexion` during the rollout.

## Known remaining work
- Add Android instrumentation tests for queue recovery, encryption failure, and WorkManager retry behavior; static contract tests and CI compilation are in place.
- The dedicated `ARGENTAS-Staging` project is active, all four schema migrations are applied, and Edge Function version 2 is deployed; no device tokens are provisioned yet. Rate-limit SQL verification passed: 120/121 writes and 60/61 reads were allowed/blocked as expected inside a rolled-back test transaction.
- Add a staging environment and run real gateway integration tests before any production rollout.
- Replace or repair the nonfunctional Bluetooth controls; do not advertise them as available until native methods exist and two physical devices pass testing.
- A guided pairing flow is not implemented; current provisioning is an admin-operated staging step.
- The legacy Pusher/Realtime channel and its broad policies remain active until rollout completion.
- No production migration or Edge Function deployment has been performed.


## Staging adapter status

The Android build injects a native gateway adapter into the packaged HTML. It dual-writes supported outbound synchronization messages to the encrypted native outbox only after staging has been explicitly enabled; the existing Supabase transport is still called unchanged. Incoming gateway messages pass through the app's existing message router and are locally acknowledged only when the router reports successful handling. Ephemeral hello messages are not queued.

Credential provisioning is intentionally restricted to debug builds. In a debug APK, a small floating “Gateway · staging” panel appears at the lower-right corner; an administrator can enter the Supabase Edge Function endpoint and that device's individual bearer token there. The token field is cleared immediately after the native call; the native layer validates the endpoint/token and stores them encrypted with Android Keystore. The web layer stores only the staging on/off flag, never the credential. Release builds cannot provision credentials through this bridge.

The gateway adapter bounds each serialized message to 240 KB before enqueueing (below the 256 KB server limit) and uses the app's delivery/message/request identifiers for idempotent retries. Staging is opt-in and does not disable or replace the existing Supabase/Pusher transport. Do not enable it until a staging Supabase project has the migration applied and each test device has its own provisioned token.

The Edge Function is deployed to staging as `https://cybaeuzrwviucsgegawz.supabase.co/functions/v1/argentas-sync-gateway` with `verify_jwt=false`; the function authenticates each request with its own device bearer token. A staging SQL smoke test passed: device A's message appeared for B, disappeared for B after a receipt, B's own message was excluded, and repeated ACK was idempotent. Still required before production: provision at least two separate physical-device tokens; run two-device tests including offline/retry, duplicate delivery, simultaneous edits, queue-full behavior, late-commit ordering, and app restart; then perform a soak test. No production migration, Edge Function deployment, or production credential provisioning has been performed.


## Additional production gap

The gateway now uses recipient-level server receipts instead of client cursors. It still does not prune old message rows. Before production, add a retention policy that only prunes after all eligible active recipients have acknowledged, with a documented expiry policy for devices that remain offline for an extended period. Do not add a simple age-based delete; it could silently remove messages from an offline phone.


The native gateway JavaScript bridge is registered only in debuggable builds; release WebViews do not receive this bridge at all. Bounded worker runs chain a continuation when more than 15 outbound messages remain. Incoming delivery proceeds one page at a time: local application queues durable remote receipts, then the worker fetches the next page. The router does not acknowledge a VENDER cancellation or comanda-status message while its parent order is missing, and rejects malformed state/status payloads without advancing the local inbox ACK. This protects against out-of-order arrival; unresolved dependencies remain pending until a later message supplies the missing record.

The Android outbox and inbox are bounded by both message count and total encrypted storage size (4 MiB per queue). Gateway GET pages are limited to 10 messages. If the inbox is full, the server continues returning the same unacknowledged page; the app must apply and locally acknowledge queued messages before the worker can continue.


Each worker run is capped at 15 outbound messages and one inbound pending page, with a 10-second connect timeout and 10-second read timeout per HTTP request. This leaves execution headroom below WorkManager's ten-minute worker limit.
