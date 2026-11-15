# OffGrid

OffGrid is an Android prototype for offline messaging over a custom Bluetooth Low
Energy/GATT relay network. Phones discover peers, exchange signed packets, and
retain messages for later forwarding. It is not an implementation of the Bluetooth
SIG Mesh standard.

[Download the v0.1 test prerelease](https://github.com/mittalsukhansh/OffGrid/releases/tag/v0.1).
Install the same APK on all test phones and follow [TESTING.md](TESTING.md).
Development continues from `main` on short feature branches; the published v0.1 APK
is an immutable earlier snapshot, not every later commit.

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
| Abuse protection | Packet signatures, timestamp admission, persistent replay ledger and bounded flood budgets | JVM rules and emulator security tests | No independent security audit or radio-level denial-of-service guarantee |
| Background operation | Connected-device foreground service, radio-state handling and maintenance | Component tests and compilation; no physical screen-off evidence | Screen off, battery optimization and Bluetooth toggling await the checklist |
| Forward secrecy | **Not implemented**; private messages still use static ECDH | Session-protocol assessment in SECURITY.md | No ratchet or post-compromise recovery claim |

## Components and documentation

The Android service is a lifecycle/binding adapter; it no longer owns the GATT
callbacks, relay peer table, packet acceptance policy, or database queries.

- [ARCHITECTURE.md](ARCHITECTURE.md): component ownership, dependency boundaries,
  threading, persistence transactions and how to test changes.
- [PACKET_FORMAT.md](PACKET_FORMAT.md): protocol version 3 fields, canonical
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
- **Expired:** no confirmation arrived within ten minutes. A valid late receipt
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
forward counts. Live traffic requires version 3 signatures and reasonably accurate
clocks. Version 2 history is readable; old unsigned pending packets must be resent
using the updated app. Every participating endpoint and relay must upgrade.

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
