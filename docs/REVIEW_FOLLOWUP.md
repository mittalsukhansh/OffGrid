# Post-1.0 review: first fixes and physical-test gate

This record distinguishes source changes, automated evidence and owner-only work. The published v1.0 APK remains an immutable experimental snapshot; the next source build is `1.0.1-experimental`, version code 101. It retains the app ID/certificate and protocol 5. No new release is implied by the source version update.

| Finding | Current status |
| --- | --- |
| Release key custody | Environment private key successfully signs a challenge verified by the pinned certificate; local handoff archive matches. The owner has confirmed the Windows archive hash and created a passphrase-encrypted backup on that PC. The owner deferred separate offline-media copy/restore verification; it remains pending. See KEY_CUSTODY.md. |
| Stable-looking versionName | Next build explicitly says `1.0.1-experimental`; code 101 allows a later update from published code 100. Existing v1.0 assets are not overwritten. |
| Identity after sudden process death | Added a host-orchestrated SIGKILL test with public expected identity captured outside the killed process, a new PID on reopening and a reloaded-private-key signing proof. Hosted API-30 outcomes: ten current passes, zero losses; ten losses on the untouched old constructor. See the investigation and committed summaries. |
| Historical first-upgrade cause | Original transcript does not contain pre-load persisted-key state. The controlled reference reproduced missing persisted public keys before loading in all ten trials; current code passed all ten. Do not present that reproduction as retrospective proof of the exact first event. |
| Replay-cap exhaustion | Still open. The 4,096 sender-epoch pool has no reserved verified/manual-contact capacity. Fresh malicious identities/epochs can exhaust it. Any unknown-sender eviction policy must explicitly account for weakened replay guarantees after eviction. |
| Inventory suppression | Still open. Claims suppress sends to that logical peer; they never mark Delivered or remove the global queue. Explicit hint expiry and bounded retry/backoff are still needed while preserving queue lifetime/fan-out limits. |
| Backup KDF | Verified actual production N=32768 (2^15), r=8, p=1, 32-byte key and API-enforced 12–1,024 UTF-16-unit passphrase length. About 32 MiB core scrypt memory plus overhead. RFC small test vectors are only test vectors. |
| KDF evolution | OGB1 already has an authenticated format magic/version, but cost parameters are implicit in that version. Future OGB2 should authenticate explicit KDF/parameter fields, enforce resource bounds before derivation and retain OGB1 read support. No format change is made before the physical test. |

The host-only KDF probe in `scripts/BackupKdfProbe.java` calls the production backup API. [Results](audits/backup-kdf-host.json) are JVM timings, **not Android measurements**, and do not justify increasing the default to N=2^17 on every supported phone. That profile needs about 128 MiB core memory plus overhead; measure latency/peak memory on supported phones before changing defaults.

## Physical test before further protocol changes

The owner has a Samsung S23 FE and a Mi/Redmi 4A running Android 7.1.2 (API 25). Current OffGrid requires Android 8.0/API 26, so that 4A cannot install this build. The second usable test phone must run Android 8+; use Android 10+ on both for a CoC comparison. Do not lower the SDK requirement or modify phone firmware merely to mark a test complete.

1. Obtain the second compatible phone and record both actual model/API/build identifiers.
2. Install the exact same experimental APK on both; exchange QR contacts and compare fingerprints.
3. With no router, send a JPEG near the 48 KiB limit over BLE, recording GATT/CoC negotiation and authenticated completion.
4. Turn Bluetooth off, pair native Wi-Fi Direct, repeat the photo transfer without a shared AP, and record authenticated completion.
5. Reset/export measurements for each run; record exact payload byte count/hash, duration, failures and screen state. Keep failures and retry observations. Follow TESTING.md and WIFI_DIRECT.md.

Status remains **NOT RUN** until actual phones supply results. Replay admission, inventory semantics and backup-format changes are deferred behind this gate. They are documented open risks, not claimed fixes.

Owner actions still pending: complete the encrypted release-key handoff and offline backup; send/obtain upstream written licensing consent; choose/provision Actions signing secrets or retain the documented local-signing path; apply description/topics with admin-capable credentials. Push access does not grant these integration permissions.
