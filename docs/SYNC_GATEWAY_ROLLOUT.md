# Argentas sync gateway: deployment and device provisioning

## Status
This is a staged dual-write path. The native gateway adapter is integrated into the Android build pipeline but remains inactive unless an administrator explicitly configures and enables it in a debug build. It has NOT been deployed to Supabase staging or production. The existing Supabase/Pusher transport remains authoritative and available as the fallback until the gateway has passed physical-device and soak testing.

## Security model
- Each physical device receives an independent cryptographically random bearer token (at least 256 bits).
- Only SHA-256(token) is stored in `public.argentas_sync_devices`; raw tokens must never be committed or inserted into SQL.
- The Edge Function validates the token and resolves its device ID. The client cannot choose a sender ID.
- The service-role key exists only in the Edge Function environment.
- Public/anonymous roles have no grants on the new tables.
- `(sender_device_id, delivery_id)` is unique. Retrying the same delivery is acknowledged as a duplicate without creating another row.
- A device can only read messages sent by other registered devices. The endpoint currently treats all active devices in this single Argentas project as one trusted installation group.
- GET currently uses a composite `(created_at, id)` cursor (`after` + `after_id`). This resolves ties for rows sharing a timestamp, but **it is not a lossless delivery guarantee under concurrent transactions**: PostgreSQL `now()` is the transaction start time, so a transaction that began earlier can commit after a later transaction. A client may advance its cursor past the later row and never see the late-committing earlier row. Treat cursor pagination as provisional only; do not approve production until recipient-level server ACKs and an unacked-message query replace cursor-only delivery.
- POST enforces the 256 KB ceiling while streaming the request body (including chunked requests), before JSON parsing; it accepts only the exact `application/json` media type and rejects invalid UTF-8.
- GET accepts only strict ISO-8601 cursor timestamps before building its PostgREST filter, preventing arbitrary filter syntax from entering the query.
- GET limits delivery to currently active, non-revoked sender devices; revoking a sender stops its queued messages from being delivered to other devices.
- Responses are not cached.
- CORS is permissive for WebView compatibility; it is not an authentication control. The bearer token is the actual credential.
- Per-device rate limiting and abuse throttling are not implemented yet. Safe retention/expiry is also missing; it remains a release blocker.

## Verified state of the Android gateway foundation
- The WebView still loads the canonical app assembled from `source/argentas-original-*.part`; the legacy Supabase/Pusher transport remains unchanged.
- `MainActivity.kt` now exposes a separate `ArgentasNativeGateway` bridge. It does not replace or pretend to implement the legacy Bluetooth methods.
- `GatewaySecureStore.kt` stores the endpoint, bearer token, outbox, inbox, cursor, and diagnostics using AES-256-GCM with a key held by Android Keystore. The secure preferences are excluded from cloud backup and device transfer because the Keystore key is device-bound.
- `ArgentasGatewayWorker.kt` uses WorkManager with connected-network constraints. It POSTs queued deliveries in bounded batches of 15, persists one pending GET page, and flushes a durable encrypted server-ACK queue before fetching the next page.
- While the app is foregrounded, `MainActivity` schedules a non-overlapping gateway poll every 15 seconds. WorkManager also retains a 15-minute periodic fallback while Android allows background execution.
- The queue rejects overflow instead of dropping messages. Inbox page persistence is all-or-nothing with respect to capacity, so the cursor cannot advance past a partially stored page.
- Gateway configuration accepts only HTTPS Supabase project hosts and validates the high-entropy device-token format. The token is never returned through the JavaScript bridge.
- `scripts/integrate_sync_gateway.py` wraps the existing `ArgentasSupabaseTransport.send` for staging-only dual-write and routes inbox messages through `window.onSupabaseMessage`. The router now returns an explicit handled result; local inbox ACK occurs only when it returns true. No staging or production token is provisioned, and no gateway traffic is being sent.
- The old Bluetooth controls remain nonfunctional in this revision; the new HTTP gateway is a separate staged path.


## Critical delivery guarantee still missing

The current `(created_at, id)` cursor is deterministic for equal timestamps but cannot guarantee commit-order completeness. PostgreSQL `now()` is fixed at transaction start; transaction A can start first, transaction B can start later and commit first, and A can commit after a device has advanced beyond B's timestamp. A then falls behind the saved cursor and can be skipped permanently. A larger overlap window only reduces the probability; it does not prove correctness.

The production-safe direction is recipient-based delivery receipts: each authenticated device repeatedly asks the server for messages without a receipt for that recipient, persists a complete page locally, applies each message, and only then submits an idempotent server ACK. The server must return unacknowledged rows again until ACKed. Retention must wait for all eligible active recipients or an explicitly documented device-expiry/revocation policy. The local encrypted inbox remains the crash-recovery buffer. The current implementation does not yet provide this guarantee.

## Supabase Data API exposure check

The migration creates tables in `public` and the Edge Function accesses them through PostgREST. Supabase's 2026-10-30 Data API change means new tables are not automatically exposed by default. In the dedicated staging project, verify the `public` schema is included in the Data API exposed schemas before testing. Keep `anon` and `authenticated` grants revoked and RLS enabled; exposing the schema does not mean granting public table access. Do not use the existing `Manos Verdes Pedidos` project as Argentas staging.


## Recipient-based delivery protocol (staging implementation)

The gateway no longer relies on a timestamp cursor. Every GET asks the database for the oldest messages that have no receipt for the authenticated recipient device. The query is repeated from the beginning of the pending set on each poll; a message that commits late remains eligible because it is not hidden behind a saved cursor.

After the app persists a complete page in its encrypted local inbox, the existing router applies each message. Only a successful router result is locally acknowledged. The Android bridge first persists the message IDs in a separate encrypted server-ACK queue, then removes them from the local inbox. WorkManager sends those receipt IDs to the gateway; the database inserts one receipt per (message, recipient) idempotently. Only after the server confirms the receipt does the worker remove it from the local ACK queue. This ordering tolerates process death and network retries without silently dropping an ACK.

The pending query is intentionally page-based: it keeps returning the oldest unacknowledged rows until the client applies and ACKs them, then the next page becomes visible. The worker fetches one page per run; the WebView applies it and schedules the next run through the ACK bridge. No automatic server-side message deletion is enabled. Retention and expiry/revocation policy remain a separate production gate.

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

The SQL migration is located at `supabase/migrations/20261117000100_argentas_sync_gateway.sql`, the standard Supabase CLI migration directory. Apply it to the dedicated staging project first; do not run it against production during this phase.

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
- Provision a dedicated staging Supabase project and per-device tokens; none are currently provisioned.
- Add a staging environment and run real gateway integration tests before any production rollout.
- Replace or repair the nonfunctional Bluetooth controls; do not advertise them as available until native methods exist and two physical devices pass testing.
- A guided pairing flow is not implemented; current provisioning is an admin-operated staging step.
- The legacy Pusher/Realtime channel and its broad policies remain active until rollout completion.
- No production migration or Edge Function deployment has been performed.


## Staging adapter status

The Android build injects a native gateway adapter into the packaged HTML. It dual-writes supported outbound synchronization messages to the encrypted native outbox only after staging has been explicitly enabled; the existing Supabase transport is still called unchanged. Incoming gateway messages pass through the app's existing message router and are locally acknowledged only when the router reports successful handling. Ephemeral hello messages are not queued.

Credential provisioning is intentionally restricted to debug builds. In a debug APK, a small floating “Gateway · staging” panel appears at the lower-right corner; an administrator can enter the Supabase Edge Function endpoint and that device's individual bearer token there. The token field is cleared immediately after the native call; the native layer validates the endpoint/token and stores them encrypted with Android Keystore. The web layer stores only the staging on/off flag, never the credential. Release builds cannot provision credentials through this bridge.

The gateway adapter bounds each serialized message to 240 KB before enqueueing (below the 256 KB server limit) and uses the app's delivery/message/request identifiers for idempotent retries. Staging is opt-in and does not disable or replace the existing Supabase/Pusher transport. Do not enable it until a staging Supabase project has the migration applied and each test device has its own provisioned token.

Still required before production: apply the SQL migration and deploy the Edge Function to a dedicated staging Supabase project; provision at least two separate device tokens; run physical two-device tests including offline/retry, duplicate delivery, simultaneous edits, queue-full behavior, and app restart; then perform a soak test. No production migration, Edge Function deployment, or production credential provisioning has been performed.


## Additional production gap

The gateway now uses recipient-level server receipts instead of client cursors. It still does not prune old message rows. Before production, add a retention policy that only prunes after all eligible active recipients have acknowledged, with a documented expiry policy for devices that remain offline for an extended period. Do not add a simple age-based delete; it could silently remove messages from an offline phone.


The native gateway JavaScript bridge is registered only in debuggable builds; release WebViews do not receive this bridge at all. Bounded worker runs chain a continuation when more than 15 outbound messages remain. Incoming delivery proceeds one page at a time: local application queues durable remote receipts, then the worker fetches the next page. The router does not acknowledge a VENDER cancellation or comanda-status message while its parent order is missing, and rejects malformed state/status payloads without advancing the local inbox ACK. This protects against out-of-order arrival; unresolved dependencies remain pending until a later message supplies the missing record.

The Android outbox and inbox are bounded by both message count and total encrypted storage size (4 MiB per queue). Gateway GET pages are limited to 10 messages. If the inbox is full, the server continues returning the same unacknowledged page; the app must apply and locally acknowledge queued messages before the worker can continue.


Each worker run is capped at 15 POSTs and five GET pages, with a 10-second connect timeout and 10-second read timeout per HTTP request. This leaves execution headroom below WorkManager's ten-minute worker limit, even when a run has to process its full bounded workload.
