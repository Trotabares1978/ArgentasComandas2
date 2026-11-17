# Argentas sync gateway: deployment and device provisioning

## Status
This is a staged replacement path. It is deliberately NOT wired into the current Android client and has NOT been deployed to Supabase production. The current `argentas_conexion` transport remains unchanged until the Android client, gateway, and migration can be released together.

## Security model
- Each physical device receives an independent cryptographically random bearer token (at least 256 bits).
- Only SHA-256(token) is stored in `public.argentas_sync_devices`; raw tokens must never be committed or inserted into SQL.
- The Edge Function validates the token and resolves its device ID. The client cannot choose a sender ID.
- The service-role key exists only in the Edge Function environment.
- Public/anonymous roles have no grants on the new tables.
- `(sender_device_id, delivery_id)` is unique. Retrying the same delivery is acknowledged as a duplicate without creating another row.
- A device can only read messages sent by other registered devices. The endpoint currently treats all active devices in this single Argentas project as one trusted installation group.
- GET pagination uses a composite `(created_at, id)` cursor (`after` + `after_id`) so messages sharing the same timestamp are not skipped between pages. The response returns `nextCursor`; clients must persist both fields atomically.
- POST enforces the 256 KB ceiling while streaming the request body (including chunked requests), before JSON parsing; it accepts only the exact `application/json` media type and rejects invalid UTF-8.
- GET accepts only strict ISO-8601 cursor timestamps before building its PostgREST filter, preventing arbitrary filter syntax from entering the query.
- GET limits delivery to currently active, non-revoked sender devices; revoking a sender stops its queued messages from being delivered to other devices.
- Responses are not cached.
- CORS is permissive for WebView compatibility; it is not an authentication control. The bearer token is the actual credential.
- Per-device rate limiting, abuse throttling, message retention/cleanup, and a formal server-side ACK endpoint are not implemented yet; add operational limits before production exposure.

## Verified state of the Android gateway foundation
- The WebView still loads the canonical app assembled from `source/argentas-original-*.part`; the legacy Supabase/Pusher transport remains unchanged.
- `MainActivity.kt` now exposes a separate `ArgentasNativeGateway` bridge. It does not replace or pretend to implement the legacy Bluetooth methods.
- `GatewaySecureStore.kt` stores the endpoint, bearer token, outbox, inbox, cursor, and diagnostics using AES-256-GCM with a key held by Android Keystore. The secure preferences are excluded from cloud backup and device transfer because the Keystore key is device-bound.
- `ArgentasGatewayWorker.kt` uses WorkManager with connected-network constraints. It POSTs queued deliveries in bounded batches of 20, polls up to five GET pages per run, persists inbox rows before advancing the composite cursor, and relies on server idempotency after interrupted/retried sends.
- While the app is foregrounded, `MainActivity` schedules a non-overlapping gateway poll every 15 seconds. WorkManager also retains a 15-minute periodic fallback while Android allows background execution.
- The queue rejects overflow instead of dropping messages. Inbox page persistence is all-or-nothing with respect to capacity, so the cursor cannot advance past a partially stored page.
- Gateway configuration accepts only HTTPS Supabase project hosts and validates the high-entropy device-token format. The token is never returned through the JavaScript bridge.
- The app-side bridge and worker are not yet connected to the existing `ArgentasSupabaseTransport` message router. No production or staging token is provisioned, and no gateway traffic is being sent.
- The old Bluetooth controls remain nonfunctional in this revision; the new HTTP gateway is a separate staged path.

## Release sequence (required)
1. Finish the JavaScript adapter that maps the existing app message router to `ArgentasNativeGateway`, with explicit staging-only enablement and ACK only after successful application.
2. Add an admin-only native provisioning path for the staging endpoint and per-device token; never put raw tokens in source, logs, screenshots, or chat.
3. Build and inspect the audit APK; confirm the legacy transport still works and that the gateway remains inactive when not configured.
4. Generate one token per device on a trusted admin workstation. Insert only SHA-256 token hashes into the staging device registry.
5. Apply the migration to a separate Supabase staging project and deploy the Edge Function there with `verify_jwt=false` and its service-role secret configured in Supabase secrets.
6. Verify: unauthorized token rejected; revoked token rejected; two registered devices exchange messages; same delivery ID retry yields one row; sender does not receive its own message; payload >256 KB rejected; device A cannot impersonate device B; pagination handles multiple messages with identical timestamps; offline outbox survives app/process restart.
7. Run parallel soak tests and reconcile message counts against the legacy channel.
8. Only after approval, schedule production rollout and keep legacy transport available for rollback.
9. Revoke legacy `anon`/public access to `argentas_conexion` only after every active client is migrated and verified.

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

The Android build now injects a native gateway adapter into the packaged HTML. It dual-writes the existing state snapshot to the encrypted native outbox only after staging has been explicitly enabled; the Bluetooth path remains untouched and active as before. Incoming gateway messages are accepted only when they contain a valid state snapshot, merged through the app's existing merge routine, and locally acknowledged only after that routine returns successfully.

Credential provisioning is intentionally restricted to debug builds. In a debug APK, a small floating “Gateway · staging” panel appears at the lower-right corner; an administrator can enter the Supabase Edge Function endpoint and that device's individual bearer token there. The token field is cleared immediately after the native call; the native layer validates the endpoint/token and stores them encrypted with Android Keystore. The web layer stores only the staging on/off flag, never the credential. Release builds cannot provision credentials through this bridge.

The gateway adapter bounds each serialized snapshot to 240 KB before enqueueing (below the 256 KB server limit) and derives an idempotency key from the snapshot content. Staging is opt-in and does not change the existing Bluetooth transport. Do not enable it until a staging Supabase project has the migration applied and each test device has its own provisioned token.

Still required before production: apply the SQL migration and deploy the Edge Function to a dedicated staging Supabase project; provision at least two separate device tokens; run physical two-device tests including offline/retry, duplicate delivery, simultaneous edits, queue-full behavior, and app restart; then perform a soak test. No production migration, Edge Function deployment, or production credential provisioning has been performed.


## Additional production gap

The gateway currently uses durable per-device cursors on the client, but it does not yet send server-side recipient acknowledgements or prune old message rows. This avoids deleting messages that an offline device has not received, but the message table can grow indefinitely. Before production, add recipient-level delivery receipts and a retention policy that only prunes after all active recipients have acknowledged (with a documented expiry policy for devices that remain offline for an extended period). Do not add a simple age-based delete while devices rely on their local cursor; that could silently remove messages from an offline phone.


The Android outbox and inbox are also bounded by both message count and total encrypted storage size (4 MiB per queue). Gateway GET pages are limited to 10 messages so a worst-case page remains manageable in memory. If the inbox is full, the cursor does not advance; the app must apply and locally acknowledge queued messages before the worker can continue.
