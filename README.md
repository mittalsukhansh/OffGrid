# OffGrid

**OffGrid** is an offline-first, peer-to-peer (P2P) messaging network built for Android. It enables secure communication without requiring an active internet connection or cellular service by creating a decentralized Bluetooth Low Energy (BLE) mesh network.

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
