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
- POST rejects oversized request bodies before parsing JSON, validates bearer-token format, and does not cache responses.

## Verified state of the current Android app
- The WebView loads `app/src/main/assets/index.html` and exposes a JavaScript object named `ArgentasNativeBluetooth`.
- The HTML calls native methods `startServer`, `refresh`, `connect`, and `send`. The current `MainActivity.kt` bridge exposes only `openExternalUrl` and `shareText`; those Bluetooth methods are absent. Therefore the visible Bluetooth controls are not a working device transport in this revision.
- The current app has no gateway HTTP client, no per-device token enrollment, no Android Keystore token storage, and no durable gateway cursor/outbox. Do not use this branch as a two-device production build yet.

## Release sequence (required)
1. Implement the Android gateway HTTP client and secure token storage without replacing the legacy transport.
2. Build and test the Android transport adapter against a separate Supabase staging project.
3. Generate one token per device on a trusted admin workstation. Deliver tokens directly to the paired devices; never put them in source, logs, screenshots, or chat.
4. Apply the migration to staging and insert only token hashes into the device registry.
5. Deploy the Edge Function to staging with `verify_jwt=false` and its service-role secret configured in Supabase secrets.
6. Verify: unauthorized token rejected; revoked token rejected; two registered devices exchange messages; same delivery ID retry yields one row; sender does not receive its own message; payload >256 KB rejected; device A cannot impersonate device B; pagination handles multiple messages with identical timestamps.
7. Migrate the Android client to POST/GET gateway with durable delivery IDs, persisted composite cursor, retry handling, and secure token storage (Android Keystore).
8. Run parallel soak tests and reconcile message counts against the legacy channel.
9. Only after approval, schedule production rollout and keep legacy transport available for rollback.
10. Revoke legacy `anon`/public access to `argentas_conexion` only after every active client is migrated and verified.

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
- Implement the Android gateway HTTP client, persistent outbox, composite cursor, ACK/processing semantics, and Android Keystore enrollment.
- Replace or repair the nonfunctional Bluetooth controls; do not advertise them as available until native methods exist and two physical devices pass testing.
- Add staging environment and run real gateway integration tests before any production rollout.
- A guided pairing flow is not implemented; current provisioning is an admin-operated staging step.
- The legacy Pusher/Realtime channel and its broad policies remain active until rollout completion.
- No production migration or Edge Function deployment has been performed.
