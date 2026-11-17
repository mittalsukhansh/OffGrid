# Post-1.0 review: security follow-ups and physical testing

This record distinguishes source changes, automated evidence and owner-only work. The published v1.0 APK remains an immutable experimental snapshot; the intermediate 1.0.1-experimental/code101 candidate was checked without publication. Current published v1.1 uses 1.1-experimental/code110, retaining the app ID/certificate and protocol 5. See the final release evidence below.

| Finding | Current status |
| --- | --- |
| Release key custody | Environment private key successfully signs a challenge verified by the pinned certificate; local handoff archive matches. The owner has confirmed the Windows archive hash and created a passphrase-encrypted backup on that PC. The owner deferred separate offline-media copy/restore verification; it remains pending. See KEY_CUSTODY.md. |
| Stable-looking versionName | Published v1.1 explicitly says `1.1-experimental`, code 110, and passed a signed update from v1.0/code100. Existing v1.0 assets are not overwritten. |
| Identity after sudden process death | Added a host-orchestrated SIGKILL test with public expected identity captured outside the killed process, a new PID on reopening and a reloaded-private-key signing proof. Hosted API-30 outcomes: ten current passes, zero losses; ten losses on the untouched old constructor. See the investigation and committed summaries. |
| Historical first-upgrade cause | Original transcript does not contain pre-load persisted-key state. The controlled reference reproduced missing persisted public keys before loading in all ten trials; current code passed all ten. Do not present that reproduction as retrospective proof of the exact first event. |
| Replay-cap exhaustion | Implemented reserved verified/manual-contact pools, unknown-only LRU eviction, per-sender epoch caps and schema 6 migration. Protected windows fail closed; unknown replay evidence may be forgotten after eviction. Hosted Android migration, contact reservations, unknown eviction and transactional rollback tests passed (source 0bb1d55, run 44057790344); final integrated source febba6b also passed run 44058403649. |
| Inventory suppression | Implemented non-renewable five-second inventory hints and bounded 30/60/120-second forwarding retries, shared across authenticated aliases and endpoint churn. Existing queue lifetime/fan-out limits still apply; only authenticated recipient acknowledgements mean Delivered. |
| Backup KDF | Verified actual production N=32768 (2^15), r=8, p=1, 32-byte key and API-enforced 12–1,024 UTF-16-unit passphrase length. About 32 MiB core scrypt memory plus overhead. RFC small test vectors are only test vectors. |
| KDF evolution | Implemented OGB2 authenticated KDF/cost fields with pre-derivation bounds (logN15–17, r8, p1), OGB1 read compatibility and unchanged mobile default. Older apps cannot read OGB2; strong-profile JVM tests are not phone memory measurements. |

The host-only KDF probe in `scripts/BackupKdfProbe.java` calls the production backup API. [Results](audits/backup-kdf-host.json) are JVM timings, **not Android measurements**, and do not justify increasing the default to N=2^17 on every supported phone. That profile needs about 128 MiB core memory plus overhead; measure latency/peak memory on supported phones before changing defaults.

## Physical testing still required

The owner has a Samsung S23 FE and a Mi/Redmi 4A running Android 7.1.2 (API 25). Current OffGrid requires Android 8.0/API 26, so that 4A cannot install this build. The second usable test phone must run Android 8+; use Android 10+ on both for a CoC comparison. Do not lower the SDK requirement or modify phone firmware merely to mark a test complete.

1. Obtain the second compatible phone and record both actual model/API/build identifiers.
2. Install the exact same experimental APK on both; exchange QR contacts and compare fingerprints.
3. With no router, send a JPEG near the 48 KiB limit over BLE, recording GATT/CoC negotiation and authenticated completion.
4. Turn Bluetooth off, pair native Wi-Fi Direct, repeat the photo transfer without a shared AP, and record authenticated completion.
5. Reset/export measurements for each run; record exact payload byte count/hash, duration, failures and screen state. Keep failures and retry observations. Follow TESTING.md and WIFI_DIRECT.md.

Status remains **NOT RUN** until actual phones supply results. The owner explicitly requested continuing software follow-ups on 17 November 2026, superseding the earlier ordering gate. This does not supply physical-device evidence. Use the unchanged published v1.0 APK on both phones for the first reproducible baseline.

Owner actions still pending: complete the deferred offline-media copy/restore verification (Windows handoff is confirmed); send/obtain upstream written licensing consent; choose/provision Actions signing secrets or retain the documented local-signing path; apply description/topics with admin-capable credentials. Push access does not grant these integration permissions.


## Integrated follow-up evidence

The signed code-100→101 experimental candidate passed the API-30 in-place update check, preserving identity, verified dual-key contact, nickname and encrypted history. It is an Actions candidate artifact, not a published release. See [evidence and download](audits/security-followups/README.md). Publication stayed disabled; v1.0 assets remain unchanged. Physical results remain NOT RUN.


## Published 1.1 follow-up

[Version 1.1](https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.1) is now an experimental prerelease. It adds named signed QR contacts and bounded per-neighbor relay retries with forwarding opportunity reserved for a newly appearing relay. The corrected small-screen contact dialog and QR-name tests passed hosted Android checks. [Signed update and independently verified public-download evidence](audits/release-1.1/README.md) covers v1.0→1.1 identity/contact/history continuity. The owner reports BLE bulk success but failed third-phone relay on v1.0; no exact models/logs or quantitative measurements were supplied. A field fix is not claimed until the 1.1 A–B–C retest succeeds. Use the 1.1 retest in TESTING.md, not the historical v1.0 baseline, for current debugging.


## Published 1.1.1 latency follow-up

[Experimental patch 1.1.1](https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.1.1), code111, prioritizes next-batch receipts/text and removes recurring inventory forwarding pauses. Chat can proceed during the initial inventory exchange; cryptographic/replay/flood admission is unchanged. Owner reports distance-related text/media delays without timings or a confirmed third-phone path. [Automated and signed-upgrade evidence](audits/release-1.1.1/README.md) does not establish measured radio speed/range. Compare the same payload/transport and record near/far completion times before tuning radio rates.


## Published 1.1.2 queue/unsend follow-up

[Patch 1.1.2](https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.1.2), code112, adds conditional three-second first send turns, shared alias deferral, targeted native connection cancellation and composer release after a durable save. Unsend/removal cancels active original work immediately; original undelivered messages remain queued on yield. Retried slow packets can complete once queued chats have had first opportunities. [Hosted/native-socket/signed-upgrade evidence](audits/release-1.1.2/README.md) is available; actual BLE/GATT/CoC timing, weak links and third-phone behavior still need owner testing.
