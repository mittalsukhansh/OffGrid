# OffGrid

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

Outgoing bubbles show **Queued**, **Forwarded**, **Expired**, or **Queue full**.
“Forwarded” means a GATT write to a nearby peer succeeded; it is not confirmation
that the final recipient received the message. The existing 200-packet queue limit
still applies, with key announcements evicted first. Restarting does not reset
persisted forwarding counts, but per-peer connection history remains in memory.

`MessageStoreTest` exercises database reopening, private-content encryption, deduplication,
forwarding progress, expiration, transactional rollback, and ciphertext authentication
on an Android device with `./gradlew connectedDebugAndroidTest`.

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
