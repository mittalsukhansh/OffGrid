# OffGrid

OffGrid is an Android offline-messaging prototype that bridges **BLE and Wi-Fi
Direct**, with **authenticated delivery receipts** and **resumable encrypted
media**. Phones exchange signed packets without a messaging server or shared
access point. It uses a custom store-and-forward protocol; field reliability and
radio throughput still require physical-device evidence.

[Download OffGrid 1.1.2 — experimental queue fairness patch](https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.1.2).
Install the same release APK on all test phones, **without uninstalling** the existing signed app, and follow the [1.1 relay/name retest](TESTING.md#version-11-relay-and-name-retest). Development continues from `main` on short feature branches. Current signed release source: `001e9a3`; published v1.0 assets remain unchanged.

The [signed v1.1.1→1.1.2 emulator upgrade evidence](docs/audits/release-1.1.2/README.md) confirms identity, verified contact and encrypted history continuity; the earlier [v1.0→1.1 evidence](docs/audits/release-1.1/README.md) remains available. Source build/lint, 82 JVM tests, six host tests and Android runtime checks passed. This is automated evidence, not a confirmed fix of the owner's reported phone-radio failure.

## Current release and physical testing

Version **1.1.2-experimental**, code **112**, gives stalled packets three-second first opportunities when untried chat/receipts are waiting, cancels active removed/unsent work immediately, and releases the composer on durable chat save. It also prioritizes chat/receipts and removes recurring inventory forwarding pauses. It includes a device-name prompt and signed named QRs, preserves custom local contact aliases, improves relay retry fairness and includes the post-1.0 replay/inventory/backup fixes. Update all phones before scanning new named QRs; names can repeat and fingerprints remain the identity check. Wire protocol remains 5. Start mesh on all three phones when testing A–B–C; nearby peer counts show direct neighbors.

The owner reports BLE bulk success and a third-phone relay failure on v1.0, without device models or diagnostic exports. Controlled three-phone/Direct/screen-off results and throughput measurements are still pending. See [review follow-up](docs/REVIEW_FOLLOWUP.md) and [private signing-key custody](KEY_CUSTODY.md). The owner confirmed the Windows signing backup; offline-media restore verification remains pending. Upstream licensing consent remains unresolved.

## Wi-Fi Direct without a router

Version 1.0 includes **Wi-Fi Direct** for the off-grid case.
Open **Network → Wi-Fi Direct · no access point** on both phones, enable it,
start the meshes, find nearby OffGrid phones and select one to pair. Accept
Android's connection prompt. Phones form their own group; no shared access point,
internet, mobile data or manual IP setup is required. Bluetooth is optional.

The existing shared-LAN option is a separate convenience path. Direct discovery
uses Android's Nearby Wi-Fi permission (or discovery Location on API 26–32);
Android 17 also requests local-network access. Android Location services must be
on for Direct discovery; OffGrid does not read GPS coordinates. Hardware/OEM support varies.
Device labels are unverified; verify contact keys for private messaging.
See [WIFI_DIRECT.md](WIFI_DIRECT.md) for pairing, group ownership, permission,
recovery and physical-test details. **Physical pairing and off-grid throughput
have not yet been verified on phones.** Wi-Fi Aware remains unimplemented.

## Transport throughput development

Current source adds **BLE L2CAP CoC on API 29+**, with GATT fallback, plus separate
media pacing/chunk policies for GATT, CoC and local Wi-Fi. Resume bitmaps remain
640-byte blocks; new capable peers can carry 2,560-byte CoC or 7,680-byte Wi-Fi
ranges in one signed encrypted control packet. Ordinary/GATT packets stay bounded
to 2,048 bytes. Unknown/legacy routes use the conservative profile.

**Network** now includes per-transport measurements, a reset button, and an L2CAP
toggle for measuring the GATT baseline. Profile limits are initial policy settings,
not measured radio speeds. See [THROUGHPUT.md](THROUGHPUT.md) for exact limits,
capability/fallback behavior, host benchmark results and the physical-phone
benchmark procedure. No physical CoC or Wi-Fi speed claim has been established.

## Application identity, signing and licensing

Current release builds use **`io.github.mittalsukhansh.offgrid`**; development/debug
builds use **`io.github.mittalsukhansh.offgrid.debug`** and the label OffGrid Debug.
These are separate apps from old `com.example.offgrid` APKs: there is no automatic
history/identity migration. Keep the old app installed if its data is needed.
Release signing now requires the persistent keystore and verifies a pinned public
certificate. See [SIGNING.md](SIGNING.md) for backup and GitHub-secret setup;
the connected integration cannot provision those secrets (GitHub HTTP 403).

**Licensing is pending upstream permission.** [LICENSE](LICENSE) records the current
status and does not grant an MIT license. [NOTICE.md](NOTICE.md) preserves upstream
attribution; [UPSTREAM_PERMISSION.md](UPSTREAM_PERMISSION.md) describes the remaining
permission decision and includes a draft request. Do not assume repository push
access or public availability grants relicensing rights.

## Conversations, Wi-Fi and diagnostics (1.0 source)

These additions are included in the signed 1.0 prerelease. The older v0.2 APK does
**not** include them. Development debug artifacts are also available from a successful
[Android build](https://github.com/mittalsukhansh/OffGrid/actions/workflows/android.yml).
Debug artifact signing certificates can differ between builds; published release
builds now pin a persistent certificate. Use [encrypted identity export](IDENTITY_BACKUP.md)
before changing incompatible builds; it preserves identity/contacts, not chat history or files.

- **Conversations** shows public chat and each private contact separately, sorted
  by latest activity, with persistent unread counts. Opening a chat loads only its
  history (up to 500 latest entries per conversation). Drafts are separate while
  switching chats and survive rotation; draft text is not a durable backup.
- New-message notifications open the correct conversation and hide message bodies
  and contact nicknames. Allow Android notification permission to receive them.
  Private attachments notify after completion, not just the initial offer.
- Open **Network → Local Wi-Fi sharing** on each phone, then start the mesh. Connect
  phones as clients of the same Wi-Fi network or hotspot; internet is unnecessary.
  Android 17+ requests local-network permission. This switch is the shared-network
  path. Use the separate Wi-Fi Direct pairing screen above when no access point
  exists. The app does not create a normal hotspot or support every hotspot host interface.
- Wi-Fi uses local DNS-SD discovery and bounded IPv4 TCP sessions. Guest/client
  isolation, blocked multicast, VPNs and IPv6-only networks can prevent discovery
  or connections. Bluetooth continues independently; Wi-Fi can remain active when
  Bluetooth is off. Startup can use Wi-Fi without requesting Bluetooth access.
- **Network diagnostics** refreshes Bluetooth scanning/advertising/server state,
  Wi-Fi state, endpoint counts, relay queue, outbound sessions, packet counters,
  recent failures and retry delays. Addresses are discovered transport endpoints,
  not authenticated contacts or proof of connectivity. A phone can appear twice
  when discovered over both transports.
- Wi-Fi avoids BLE fragmentation and now has its own bounded media profile;
  attachment size limits remain. Larger ranges require an authenticated recipient
  capability. No physical throughput claim is made yet.
  A TCP transfer confirmation changes forwarding progress; only an authenticated
  recipient acknowledgement establishes **Delivered**.

Battery modes, private groups and SOS remain deferred. No hosting or login is
needed. Device Wi-Fi discovery, network changes and mixed-radio behavior still
need physical testing using [TESTING.md](TESTING.md).

## Offline sharing in 0.2

No account, phone number, address-book permission, hosted VM or messaging server is
required. Contacts are public-key identities exchanged by QR or contact strings.
Use **To → Manage selected → Edit local nickname** to label them on your phone;
duplicate names warn but never merge identities or change verification.

Long-press your private message or attachment to request **Unsend**. Your app copy
is replaced with a deletion marker and an authenticated request is queued for the
contact. It shows **Unsend requested**, **Unsend unconfirmed** after the request
window, or **Unsent** when acknowledged. You can retry an unconfirmed request.
Offline phones may never receive it; screenshots, exported copies and forensic
remnants cannot be recalled. Saved tombstones prevent delayed originals from
reappearing. Public-message unsend is not supported.

Choose a private contact and tap **Attach**:

- Photos are converted to JPEG, resized and compressed to at most **48 KB**.
- MP4 clips must already be **256 KB or smaller and at most 10 seconds**. There is
  no built-in video transcoder. Unsupported/oversized media is rejected.
- Files and partial chunks are encrypted locally. A small persisted transfer window,
  integrity hashes, receiver bitmaps and retries allow resumption after reconnects
  and restarts. The recipient must validate/save the whole file before Delivered.
- The v0.2 release prepares only two chunks per five-second pump. Current source
  has transport-specific pacing/ranges described in THROUGHPUT.md. Actual transfer
  time still needs device measurement. Transfers expire after an hour; initial
  offer acceptance has a ten-minute window. Keep both meshes running.
- Up to two outgoing/four total active transfers, 64 retained attachments, and about
  16 MB of encrypted file storage are allowed in this test build.

Tap a ready attachment to open it. This explicitly creates a plaintext copy in app
cache for an external viewer. Unsend removes the app's cached copy and revokes its
URI grant, but cannot remove anything an external viewer already copied.

## Implemented versus verified

No physical three-phone results have been supplied yet. Passing emulator tests
or a simulated A-B-C protocol exchange does not establish real radio range,
screen-off reliability, or successful Bluetooth mesh delivery.

| Area | Implemented | Automated verification | Physical-device status |
| --- | --- | --- | --- |
| Discovery and transport | Filtered BLE scanning, advertising, GATT server/client, MTU negotiation and retries | Build/lint; framing tests exercise the byte boundary | Real discovery, GATT callback ordering and range unverified |
| Framing | Fragmentation/reassembly, independent peer buffers, size limits and deadlines | JVM round trips at MTU 23/247/517, UTF-8 boundaries, malformed/oversized frames and timeout/capacity tests | Large-MTU interoperability still needs phones |
| Relay scheduling | Three concurrent outbound sessions, 20-packet batches, six-forward eligibility limit, jitter, peer freshness and backoff | Fake-clock/fake-transport tests for ordering, limits, expiry, recovery and stale callbacks | A-B-C routing and movement unverified |
| Message protocol | JSON packet types, TTL, signatures, private encryption, authenticated acknowledgements and replay rejection | Android tests run the production protocol/storage components across three logical nodes; parser and security regressions | This simulation does not use Bluetooth |
| Persistence and delivery | Encrypted Room history/queues, atomic message/receipt/replay writes, restart recovery, honest delivery states | Emulator SQLite/Keystore, rollback, migration and restart tests | Manufacturer-specific process/service recovery unverified |
| Contact trust | QR exchange, full fingerprint comparison and a persistent Verified indicator | QR image round trip and contact-state tests | Physical camera scanning and the complete UI flow need device checks |
| Abuse protection | Packet signatures, durable sequence windows, reserved contact capacity and bounded flood budgets | JVM rules and emulator security tests | No independent security audit or radio-level denial-of-service guarantee |
| Background operation | Connected-device foreground service, radio-state handling and maintenance | Component tests and compilation; no physical screen-off evidence | Screen off, battery optimization and Bluetooth toggling await the checklist |
| Offline sharing | Local nicknames, private withdrawals, encrypted image/short-video transfers | Emulator regression tests for authorization, tombstones, integrity, resumption and size/duration bounds | Camera/gallery/player UI and real media throughput need phones |
| Conversations and unread notifications | Per-contact/public histories, durable unread flags, generic notification routing | SQLite filtering/read/migration regressions and Espresso conversation/navigation checks | Notification permissions and OEM background behavior need phones |
| Wi-Fi Direct | AP-free OffGrid DNS-SD discovery, explicit Android pairing, P2P-interface TCP and lifecycle recovery | Ownership/hello and transport tests; Android loopback socket and encrypted-media regressions | Physical pairing, permission/OEM behavior, group limits and throughput unverified |
| Local Wi-Fi and diagnostics | Opt-in DNS-SD/IPv4 TCP alongside BLE, bounded sessions, live queue/endpoint/error diagnostics | Real TCP framing tests and transport selection/radio-state regressions | Physical NSD, access-point compatibility, network changes and throughput unverified |
| Forward secrecy | **Not implemented**; per-message ephemeral ECDH with a static recipient agreement key | Session-protocol assessment in SECURITY.md | No ratchet or post-compromise recovery claim |

## Components and documentation

- [DECISIONS.md](DECISIONS.md): continuing work history, decisions, validation and
  unresolved actions; updated after each work set under [AGENTS.md](AGENTS.md).

- [REVIEW_REPORT.md](docs/REVIEW_REPORT.md): project status, stack, architecture, limitations,
  proposed roadmap and a copy-ready independent review request for Claude.

The Android service is a lifecycle/binding adapter; it no longer owns the GATT
callbacks, relay peer table, packet acceptance policy, or database queries.

- [ARCHITECTURE.md](ARCHITECTURE.md): component ownership, dependency boundaries,
  threading, persistence transactions and how to test changes.
- [PACKET_FORMAT.md](PACKET_FORMAT.md): protocol version 5 fields, canonical
  signatures, private encryption/ACKs, TTL and exact BLE framing.
- [SECURITY.md](SECURITY.md): trust model, flood/replay policy, upgrade behavior,
  limitations and the forward-secrecy assessment.
- [TESTING.md](TESTING.md): installation, the physical three-phone plan and logs.

The `MeshTransport` interface separates byte delivery from routing. `BleTransport`
and `GattOutboundSession` own Android Bluetooth resources; `FrameCodec` and
`RelayScheduler` can be tested without Android or radios. `MeshProtocol` handles
packet acceptance and receipts through `MeshStorage`; `RoomMeshStorage` adapts the
existing encrypted `MessageStore` and its atomic transactions.

## Delivery and stored data

Outgoing requests return **ACCEPTED** for asynchronous persistence. The composer
clears only after the message is saved. Public broadcasts are signed but readable;
private messages and ACKs use ECDH, HKDF-SHA256 and AES-GCM.

- **Queued:** saved locally and awaiting forwarding.
- **Forwarded:** written successfully to a nearby peer; this is not final delivery.
- **Delivered:** the intended private recipient authenticated/decrypted the message,
  saved it, and returned a valid receipt. It is not a read receipt. Broadcasts never
  claim this state.
- **Expired:** no confirmation arrived within ten minutes for text; accepted media transfers have a longer transfer deadline. A valid late receipt
  can still confirm a private message.
- **Failed:** the pending message could not remain eligible, for example after
  queue eviction or rejection of a legacy/invalid stored packet. Storage failures
  before acceptance retain the draft and report an error.

Room retains conversation IDs, history, outgoing status and pending packets across
restarts. The screen shows the latest 500 messages; older rows remain stored.
Message bodies and queued packets are encrypted with a separate Android Keystore
key. IDs, timestamps, conversation membership, counts and status are metadata in
cleartext. This is field encryption, not whole-database encryption. Clearing app
storage or losing its key can make history unrecoverable; backup is disabled.

The queue holds at most 200 packets and expires them after ten minutes; it does not
promise indefinite or guaranteed delivery. Recovery preserves deadlines and
forward counts. Live traffic requires version 5 signatures and durable sender sequences; wall-clock
rollback no longer pauses reception. Version 2/3/4 history is readable; legacy pending
packets must be resent using the updated app. Contacts need a new QR exchange because
signing/agreement keys are now separate; compare the new full fingerprint. Every participating endpoint and relay must upgrade.

## 1.0 additions

- Separate signing/agreement keys; fresh ephemeral ECDH per encrypted packet. Recipient-key compromise still exposes recorded traffic; full forward secrecy is not implemented.
- Durable sender epoch/sequence replay windows, atomic with Room acceptance. Schema 6 reserves verified/imported-contact capacity; only unknown windows can be evicted. Protected capacity fails closed; unknown replay guarantees weaken after eviction. See SECURITY.md.
- Signed nonce inventory exchange, rotating exact-ID pages and logical identity association across radios. Inventory is a non-renewable hint with bounded forwarding retries; it is not full history sync or a delivery receipt.
- [Encrypted identity backup/restore](IDENTITY_BACKUP.md), including contacts/nicknames/verification, without hosting. New OGB2 backups authenticate bounded scrypt cost fields and retain OGB1 read support. History/files remain local and are not exported.
- Network aggregate measurement export and a three-phone capture harness. Physical results remain **NOT RUN** until real phones provide evidence.
- Seeded parser/framing fuzz smoke tests and a deterministic production-relay simulator; existing Android unsend-before-original coverage remains.

See [review report](docs/REVIEW_REPORT.md) and [decisions](DECISIONS.md) for validation and outstanding owner actions. Version 1.0 does not resolve upstream permission, verify real radio performance, or constitute a security audit.

## Build and run checks

Requirements: JDK 25, Android SDK Platform 37, Build Tools 36.0.0 and Android
command-line tools. The minimum supported Android version is 8.0 / API 26.
Java source compatibility remains Java 11. Gradle 9.6.0 is pinned by the wrapper
and its distribution checksum; the Android plugin and dependencies are pinned in
`gradle/libs.versions.toml`.

Set `JAVA_HOME` and `ANDROID_HOME`, accept the SDK licenses, then run:

```sh
sdkmanager 'platforms;android-37.0' 'build-tools;36.0.0'
./gradlew assembleDebug lintDebug testDebugUnitTest assembleDebugAndroidTest
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.

For the Android integration tests, use a connected device or emulator:

```sh
./gradlew connectedDebugAndroidTest
```

GitHub Actions runs build/lint/JVM checks and an API 30 emulator test job. The
separate test-release workflow only publishes a draft prerelease from a tested
commit on `main`; it attaches an APK, checksum, certificate report and checklist.
CI test artifacts do not replace the physical validation table above.


### Version 1.1 phone follow-up

Version 1.1-experimental (code 110) adds a device name prompt and signed named QR exchange. Scanning prefills the contact label; custom local nicknames are preserved. Names are not globally unique accounts. Update every phone before scanning new named QRs; old contacts and protocol-5 messaging remain supported.

The owner reports BLE bulk working on v1.0 and a third-phone relay failure. Device models, exact transport negotiation, exports and three-phone reproduction details have not been supplied, so this is a reported direct-link result, not a completed benchmark. Version 1.1 includes the post-1.0 inventory/replay/backup fixes, bounds repeated sends to one neighbor and keeps forwarding budget for a newly appearing relay. Automated routing regressions cannot establish that the reported phone failure is fixed; retest A–B–C with mesh started on all three, A/C outside direct range and B able to reach both. Nearby peer counts show direct neighbors, not an end-to-end route map.


### Latency patch 1.1.1

Chat and delivery receipts can proceed during the initial inventory exchange and are selected before attachment/control traffic. Refreshing inventory no longer creates repeated five-second forwarding gaps. That next-batch-only behavior describes 1.1.1; 1.1.2 also adds the conditional connection cancellation below. Radio pacing/chunk sizes and range are unchanged. The owner reports text/media get slower farther apart, without timings or a confirmed relay path. Compare confirmed CoC versus paired no-router Wi-Fi Direct and export measurements; this patch is not a measured speed multiplier. See [latency testing](TESTING.md#latency-patch-comparison-111) and [policy details](THROUGHPUT.md#app-latency-follow-up-111).


### Three-second queue turns (1.1.2)

A stalled active packet yields after three seconds when an untried text/receipt is ready. The original stays queued for retry. Once every queued chat has had a first chance, retries may use normal native timeouts so two slow but working packets can finish. Newly queued chats still get a turn. Unsend/removal cancels an active original immediately; other connections remain available. Composer unlocking follows the durable database commit, independently of network delivery/history rendering. The three-second rule is a scheduling policy, not a delivery deadline or automatic failure. See [phone retest](TESTING.md#three-second-queue-and-unsend-retest-112) and [release evidence](docs/audits/release-1.1.2/README.md).
