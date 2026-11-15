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
compiles the instrumented tests, and uploads the debug APK and check reports.
To run the instrumented tests on a connected device or emulator:

```sh
./gradlew connectedDebugAndroidTest
```

The service-registration regression test checks that Android can resolve the
actual `MeshService` class. Instrumented tests are compiled, but not run, by CI.
Real BLE discovery, background operation, and multi-hop delivery need testing on
physical phones; an APK build alone does not verify these features.

---

## Key Features

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
