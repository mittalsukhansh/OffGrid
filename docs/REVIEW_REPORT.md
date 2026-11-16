# OffGrid repository review report — 1.0 preparation

Updated 16 November 2026 from the current source changes. This report separates implementation, automated evidence, physical evidence and external blockers. Consult [DECISIONS.md](../DECISIONS.md) for append-only validation/publication records.

## 1. About and differentiators

OffGrid is an experimental Android offline messenger bridging BLE GATT/L2CAP, Wi-Fi Direct and optional shared-LAN TCP. It adds authenticated recipient receipts, persistent encrypted chat/relay queues, best-effort private unsend and resumable encrypted images/short videos. No internet server, phone-number account or global username registry is required. Human-readable nicknames are local labels; identity is derived from a signing public key.

Briar, Bitchat and Meshtastic solve overlapping problems with different architectures. OffGrid's proposed focus is transport bridging plus explicit receipt semantics and resumable media on ordinary Android phones. This is a positioning statement, not a claim of superior throughput, security or field reliability.

## 2. Technology

| Area | Current choice |
| --- | --- |
| Android | Java 11 source, XML/AppCompat, min API 26, target/compile 37 |
| Build | Gradle 9.6, Android Gradle Plugin 9.4.1, JDK 25 |
| Data | Room 2.8.4/SQLite schema 5; explicit migrations 1→5; Keystore AES-GCM field encryption |
| Protocol | Signed JSON version 5; bounded TTL forwarding, receipts, controls and inventory |
| Crypto | Separate P-256 ECDSA/agreement identities; fresh ephemeral-static ECDH, HKDF-SHA256, AES-256-GCM |
| Backup | Bouncy Castle 1.83 SCrypt, fixed RFC 7914 parameters; AES-GCM encrypted identity/contact file |
| Contacts | ZXing QR exchange, local nicknames and explicit fingerprint verification |
| Radio/data | GATT fallback; API 29+ LE L2CAP CoC; native Android Wi-Fi Direct; same-LAN NSD/TCP |
| Checks | JUnit, AndroidX instrumentation, Room migrations, Espresso, seeded parser/framing fuzz smoke tests, deterministic JVM relay simulator |
| Release identity | `io.github.mittalsukhansh.offgrid`; separate `.debug` package; pinned persistent release certificate |

## 3. Implemented behavior

- Separate public/private conversations, persistent unread counts, generic private notifications and per-conversation drafts in memory.
- Encrypted history, relay queues, media bytes/parts and control records; honest Queued/Forwarded/Delivered/Expired/Failed states.
- Authenticated author-bound unsend with durable tombstones and acknowledgements, including unsend-before-original coverage. It cannot force deletion on offline/malicious phones or remove screenshots.
- JPEG up to 48 KiB and MP4 up to 256 KiB/10 seconds. Canonical 640-byte resume blocks; larger negotiated range controls on streams. No video transcoder or unrestricted file sharing.
- Transport-specific pacing/chunk policy and measurement/reset UI. Aggregate measurement export and a three-phone session metadata/APK hash harness.
- Native Wi-Fi Direct discovery/consent/group-bound sockets without a shared AP; same-LAN TCP remains optional. P2P groups are owner/client stars, not unrestricted multi-group mesh. Wi-Fi Aware is not implemented.
- Signed nonce inventory, rotating pages of 12 exact queued IDs and alias coalescing by reachable signer identity. Partial inventory reduces duplicate sends; it is not full history synchronization or proof of proximity.
- Clock-independent durable per-sender/epoch replay windows. A 4,096-number reorder window and global 4,096 sender-epoch cap bound storage; evidence is not time-evicted.
- Separate signing/agreement keys and new signed contact bundles. Existing signing ID/history survives migration; contacts need fresh bundles/fingerprint comparison, and old pending/in-progress transfers may require resending.
- Encrypted identity/contact export and validated restore on an empty, stopped installation. Export excludes chat history, media and inbound replay evidence. Restored sender gets a fresh random epoch. Identity clones are unsupported.
- Removed tracked IDE files, normalized DECISIONS.md, moved full agent/review docs, retained a root agent discovery pointer, audited signing files/reachable Git blobs and prepared repo metadata setup.

## 4. Architecture and files to review

| Component | Responsibility and review focus |
| --- | --- |
| `MeshService` / `MeshNode` | Lifecycle/worker ownership, transport coordination, inventory, diagnostic export |
| `mesh/relay/RelayScheduler` | Queue/fan-out/expiry/backoff, per-logical-peer concurrency, inventory hints |
| `mesh/transport/LanTransport`, `WifiDirectTransport`, `DirectTcpLink` | Socket/interface binding, discovery/consent, resource deadlines and owned-group cleanup |
| `mesh/protocol/MeshProtocol`, `InventoryExchange` | Authentication/admission, duplicate response paths, endpoint challenge matching |
| `MeshMessage`, `PacketSecurity`, `CryptoManager` | Parser/signature canonicalization, key separation, sequence reservation, ephemeral ciphertext and key binding |
| `MessageStore`, `SequenceWindow`, `SequenceRecord` | Atomic replay/application acceptance, schema migration and bounded persistent evidence |
| `PrivateActions`, `MediaTransfers` | Unsend ordering, author/target binding, retry/bitmap/range/media state transitions |
| `IdentityBackup`, `IdentityBackupUi` | Fixed KDF/size limits, authenticated format, pre-validation/atomic replace and stopped/empty restore gating |

## 5. Validation scope

Local build/lint/JVM tests and instrumentation compilation are recorded in the decision log for each source commit. GitHub emulator results and release upgrade results must be linked there after they actually complete. A staged signed baseline exists for an in-place package/signature upgrade test; staging alone is not a passing upgrade result.

The deterministic JVM simulator exercises the production relay scheduler and codec with seeded loss/reordering, an A–B–C partition and a relay restart preserving queue/count state. It does not simulate Android radio negotiation or independently validate cryptography. Android suites exercise actual Room/Keystore/crypto with logical peers, framing sockets, migrations, receipt/unsend/media and UI contracts. Fuzz tests are bounded seeded smoke tests, not exhaustive fuzzing or a security audit.

Physical A–B–C, screen-off/OEM behavior, real CoC negotiation, AP-free Direct pairing and throughput measurements are **NOT RUN** in this environment. Follow [TESTING.md](../TESTING.md); publish real numbers in [THROUGHPUT.md](../THROUGHPUT.md), retaining failures and exact device/build metadata.

## 6. External blockers

- Upstream licensing permission remains unconfirmed. GitHub rejected the authorized issue/request attempt with HTTP 403; no email connector is present. The request was **not delivered**. [UPSTREAM_PERMISSION.md](../UPSTREAM_PERMISSION.md) contains the text and next owner action. Do not apply an open-source license without the relevant copyright holders' consent.
- The integration cannot provision Actions secrets or modify repository description/topics (HTTP 403). Metadata values/script are prepared. Release signing can be done locally with the private persistent key, publishing only the public signed APK through a supported artifact path; this does not provision GitHub signing secrets.
- No physical phones are connected/available for real radio results. Emulator success does not remove this blocker.

## 7. Material risks

Recipient long-term agreement-key compromise still reveals recorded traffic. No full forward secrecy, ratchet, post-compromise recovery, private groups, SOS/location, iOS implementation or security audit is shipped. Sequence windows trade bounded storage/reordering for capacity exhaustion and rejection of very delayed unseen packets. Fresh installs/restores lack prior inbound replay evidence. Inventory is partial/observable and a peer can lie about its own possession. Identity/contact export is sensitive and does not back up chat storage. Review all protocol changes before relying on compatibility or stronger security claims.

## 8. What is left and recommended proposal

1. Owner sends the upstream request and gets written consent; resolve license before encouraging external reuse/contribution.
2. Complete three named-phone tests and radio benchmarks, publish evidence and investigate failures before a stable reliability claim.
3. Verify signed baseline→1.0 install-without-uninstall continuity on an emulator and then actual phones; preserve identity/history, document old package/debug incompatibility and backup scope.
4. Externally review protocol 5, backup/restore and replay/unsend transactions; expand fuzz campaigns and fault injection beyond bounded smoke tests.
5. Assess maintained reviewed session protocols/libraries for asynchronous mesh constraints; do not equate ephemeral-static encryption with forward secrecy.
6. Later work: private group key management, SOS/optional location, measured adaptive transport policy, Wi-Fi Aware where useful and a separately designed iOS transport/app.

## Review prompt

Read the actual files listed above and cite exact file/line references. Prioritize bugs that break authentication, replay atomicity, backup restore safety, unsend-before-original, bounded parsing, socket cancellation or upgrade continuity. Separate exploitable issues from design tradeoffs and missing physical evidence. Do not infer real radio throughput or security audit completion from tests/version labels.
