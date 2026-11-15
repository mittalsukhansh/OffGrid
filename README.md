# OffGrid

Version **0.1** is a test prerelease. Download the APK from
[GitHub Releases](https://github.com/mittalsukhansh/OffGrid/releases) and follow
[the three-phone test checklist](TESTING.md). The combined development codebase
is on `main`; new work uses short feature branches.

**OffGrid** is an offline-first, peer-to-peer (P2P) messaging network built for Android. It enables secure communication without requiring an active internet connection or cellular service by creating a decentralized Bluetooth Low Energy (BLE) mesh network.

---

## Build and checks

Requirements: JDK 25 (the Gradle daemon toolchain), Android SDK Platform 37,
and Android SDK command-line tools. The app supports Android 8.0 / API 26 and newer.
Java source compatibility remains Java 11.

Set `JAVA_HOME` to your JDK 25 installation and `ANDROID_HOME` to your Android SDK.
Install the platform with `sdkmanager "platforms;android-37.0" "build-tools;36.0.0"`, accepting the Android
SDK licenses when prompted. Alternatively, configure these tools in Android Studio.

```sh
./gradlew assembleDebug lintDebug testDebugUnitTest assembleDebugAndroidTest
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.
The Gradle wrapper pins Gradle 9.6.0 and verifies its distribution checksum;
the Android Gradle plugin is pinned in `gradle/libs.versions.toml`.

GitHub Actions runs the build, lint, and unit tests on pushes and pull requests,
runs instrumented tests on an API 30 emulator, and uploads the debug APK and check reports.
To run the instrumented tests on a connected device or emulator:

```sh
./gradlew connectedDebugAndroidTest
```

The service-registration regression test checks that Android can resolve the
actual `MeshService` class. Storage tests use the emulator's SQLite and Android Keystore.
Real BLE discovery, background operation, and multi-hop delivery need testing on
physical phones; an APK build alone does not verify these features.

---

## Key Features

See [SECURITY.md](SECURITY.md) for signed protocol version 3, QR contact verification,
durable replay rejection, flood limits, upgrade behavior, and the forward-secrecy
assessment. Forward secrecy is not implemented in this release.

### Persistent messages

Room stores conversation IDs, message status, and pending relay packets. The screen
loads the latest 500 messages from the database, including messages received while
the screen was closed. Older history stays in the database. Public messages belong
to the `public` conversation; private messages are grouped by the other device's ID.

Message bodies (including public messages), saved private display text, and relay
packets are encrypted at rest with AES-256-GCM and a separate Android Keystore key.
Table name and message ID are authenticated to prevent ciphertext substitution.
IDs, conversation membership, timestamps, forwarding counts, and status are queryable
metadata, not encrypted. This is field encryption, not full-database encryption.
Backup is disabled. Clearing app data or losing the Keystore key makes saved content
unrecoverable; decryption failures do not erase the original rows or use plaintext.

Outgoing history and pending packets commit in one transaction before forwarding.
The composer clears only after the saved row appears. Pending packets survive service
or process restarts and resume when the mesh service next starts, retaining the
existing 10-minute expiry and forwarding limit. Recovery uses the persisted wall-clock
expiry, so changing the device clock can affect expiration. Private relay packets
retain their end-to-end ciphertext; decrypted display copies are never transmitted.

Outgoing bubbles show **Queued**, **Forwarded**, **Delivered**, **Expired**, or **Failed**.
“Forwarded” means a GATT write to a nearby peer succeeded. For a private message,
“Delivered” requires an authenticated receipt from its intended recipient after
the recipient committed the message to Room. It does not mean the person read it.
The existing 200-packet queue limit
still applies, with key announcements evicted first. Restarting does not reset
persisted forwarding counts, but per-peer connection history remains in memory.

`MessageStoreTest` exercises database reopening, private-content encryption, deduplication,
forwarding progress, expiration, transactional rollback, and ciphertext authentication
on an Android device with `./gradlew connectedDebugAndroidTest`.

### Delivery acknowledgements

`sendPrivateMessage` returns `ACCEPTED` for an asynchronous persistence request;
only the committed database row confirms **Queued**. It never returns `SENT`.
Storage failures preserve the composer's draft and show an error. Queue eviction
records **Failed**. Transient Bluetooth failures keep retrying within the existing
relay scheduling limits rather than claiming delivery or immediately failing.

Private recipients generate an encrypted ACK (packet type 3) using ECDH,
HKDF-SHA256 with a separate `offgrid-ack-v1` purpose, and AES-GCM. The receipt
references the original message ID and SHA-256 fingerprint of its immutable wire
fields. The sender authenticates/decrypts the ACK, checks both endpoints and the
fingerprint against the saved outgoing message, then atomically marks it
**Delivered** and deletes its pending packet. Relays cannot forge receipts;
changing a private chat packet's type into an ACK fails authentication.

The recipient atomically saves the incoming message and receipt outbox entry.
Its encrypted history also caches the receipt so a duplicate original packet can
requeue the same receipt after a restart without duplicating the conversation,
resetting its expiry, or resetting its forwarding budget. ACKs are relayed as
ciphertext and never appear as chat bubbles. Delivery does not automatically imply
a read receipt.

Unconfirmed private messages become **Expired** after ten minutes, including ones
already forwarded or removed from the pending queue. A late valid receipt can
confirm an expired/failed message; a subsequent GATT callback, timeout, or duplicate
receipt cannot downgrade **Delivered**. Expiration means confirmation did not arrive
in time, not proof that the recipient never received the message. Public broadcasts
have no single intended recipient, so they never claim **Delivered** and remain
**Forwarded** after a successful peer write.

Live traffic now requires signed protocol version 3; upgrade both endpoints and
relays. Existing version 2 encrypted history remains readable. Room migrates to
version 2 to add the durable replay ledger without deleting history.
`DeliveryReceiptTest` runs on the JVM; `AuthenticatedDeliveryTest` exercises three
independent identities, real crypto, Room, replay protection, and terminal states
in the CI emulator. Real multi-hop Bluetooth behavior still requires physical phones.

* **Peer-to-Peer Mesh Networking**: Relays messages across multi-hop BLE connections to extend communication range beyond point-to-point hardware limits.
* **Store & Forward Architecture**: Buffers outgoing packets locally until a route or recipient becomes reachable.
* **End-to-End Encryption (E2EE)**: Secures message payloads using AES-GCM prior to network transmission.
* **Packet Deduplication**: Uses rolling hash caches and unique Time-To-Live (TTL) identifiers to prevent routing loops and flooding.
* **GATT Server/Client Hybrid**: Operates simultaneously as both a BLE GATT Server and Client for dual-direction packet relaying.

---

## Tech Stack

| Domain | Tech / Tools |
| :--- | :--- |
| **Platform** | Android |
| **Language** | Java |
| **Networking** | Bluetooth Low Energy (BLE), GATT API |
| **Cryptography** | AES-GCM (WebCrypto / Java Cryptography Architecture) |
| **Architecture** | Custom Mesh Protocol (TTL Routing, Packet Caching) |
| **IDE & Tools** | Android Studio, Git |

---

## System Architecture & Topology

### Mesh Relay Topology
Nodes automatically discover nearby peers and form dynamic links. If Node A wants to send a message to Node C, Node B acts as an intermediate relay hop.
