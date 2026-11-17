# Argentas sync gateway — physical-device test plan

## Scope and safety
Run this plan only against the isolated `ARGENTAS-Staging` project and a debug APK. Keep the existing Supabase/Pusher transport enabled as the fallback. Do not use production credentials or production data. Use two independent devices, each with its own device ID and bearer token.

## Preflight
- [ ] Install the same debug APK build on Device A and Device B.
- [ ] Confirm the legacy transport still sends and receives before enabling the gateway.
- [ ] Generate one token per device with `scripts/provision_sync_device.py`; execute only the generated hash SQL in ARGENTAS-Staging.
- [ ] Enter the matching endpoint and token in each device's debug-only staging panel.
- [ ] Enable staging dual-write on both devices and verify each reports configured.
- [ ] Record starting counts for devices, messages, and receipts. Do not copy or share raw tokens.

## Test cases

| ID | Scenario | Procedure | Expected result |
|---|---|---|---|
| P01 | A to B | Create one new comanda/event on A | B receives it once; one server message exists |
| P02 | B to A | Repeat in the reverse direction | A receives it once |
| P03 | Duplicate outbound retry | Toggle network during a send, then reconnect | Same delivery ID creates one server row and one business effect |
| P04 | Offline recipient | Disconnect B, send several updates from A, reconnect B | B receives every update; no cursor jump or missing event |
| P05 | Process killed before apply | Force-stop B while messages are pending, reopen | Pending messages are replayed and applied |
| P06 | Process killed after apply, before ACK | Kill B at the apply/ACK boundary if reproducible | Replay must not duplicate the business effect; handler idempotency must hold |
| P07 | Lost remote ACK | Interrupt B's network after local apply but before server ACK completes | B retries ACK; server eventually records one receipt |
| P08 | Simultaneous changes | Change separate comandas from A and B at nearly the same time | Both changes arrive; no message is silently skipped |
| P09 | Late commit | Use the staging SQL concurrency harness to commit an earlier-started transaction after a later one | Both messages remain pending until acknowledged |
| P10 | Inbox full | Fill B's local inbox to its configured cap, then send a new page | B does not ACK the page; server keeps it pending; no silent deletion |
| P11 | Sender revoked | Revoke A in staging, then attempt a new request and check B's pending feed | A is rejected; queued messages from revoked A are not delivered |
| P12 | Invalid credential | Temporarily configure an invalid token on a test device | Gateway returns unauthorized; legacy transport remains available |
| P13 | Payload ceiling | Send a body larger than 256 KB through a controlled test client | Gateway rejects with HTTP 413 |
| P14 | Rate limit | Send 121 writes or 61 reads in one minute from one test device | Requests beyond the configured 120/60 caps receive HTTP 429 |
| P15 | Device isolation | Attempt to configure B with A's token, then restore B's own token | Device identity follows the token; the client cannot claim another sender ID |

## Evidence to record
- Device label and APK version (never the bearer token).
- Test ID, start/end time, action performed, expected result, observed result.
- Local outbox/inbox counts and last-success/last-error diagnostics.
- Staging message and receipt counts before and after each case.
- Screenshots may show status and counts, but must not show token fields or credential files.

## Pass criteria
- No missing accepted messages after offline/reconnect and late-commit tests.
- No duplicate business effect after retry or replay.
- A recipient's server receipt appears only after the app has successfully applied the message.
- A full inbox never advances remote ACK state.
- Revoked/invalid devices cannot use the gateway.
- Legacy transport remains functional throughout the staging test.

## Release blockers
Do not promote the gateway until P01–P15 pass, including a repeatable test for P06. If the apply/ACK crash window cannot be reproduced reliably, add an automated fault-injection/instrumentation test. Define an explicit inactive-device expiry and message-retention policy before enabling any server-side deletion; do not use age-only cleanup.
