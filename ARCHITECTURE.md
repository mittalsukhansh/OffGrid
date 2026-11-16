# Mesh component architecture

## Ownership

| Component | Owns | Does not own |
| --- | --- | --- |
| `MeshService` | Foreground notification, Android lifecycle, binding and existing UI API | Radio callbacks, relay policy, packet acceptance, Room queries |
| `MeshNode` | Worker orchestration, recovery, maintenance, announcements and component events | GATT implementation, cryptographic primitives, SQL |
| `MeshTransport` | Byte-oriented transport and send-callback contract | Message types or delivery receipts |
| `BleTransport` | BLE discovery/advertising, GATT server, radio receiver, native resource lifetime | Conversation state, TTL policy, delivery confirmation |
| `CombinedTransport` | Namespaced BLE/Wi-Fi peer dispatch, aggregate radio availability | Contact identity or delivery guarantees |
| `LanTransport` / `LanFrames` | Opt-in Wi-Fi network callbacks, DNS-SD, network-bound IPv4 TCP, bounded length framing and native socket lifetime | Message authentication, encryption, Room or delivery receipts |
| `GattOutboundSession` | One client connection, MTU negotiation, service discovery, framed writes and close deadlines | Peer selection, retries across sessions, database mutations |
| `FrameCodec` | Packet fragmentation/reassembly, per-peer buffers, size/capacity/deadline rules | JSON, UTF-8 text decoding, signatures, Android |
| `RelayScheduler` | Peer table, signal ordering, freshness, backoff, batches, concurrency, pending cache and forwarding eligibility | Native Bluetooth objects, Handler, UI or Room DAO |
| `MeshProtocol` | Outgoing preparation; incoming parse/authentication/replay decisions; decryption, ACKs, local delivery and relay TTL | BLE frames, peer selection, Android lifecycle |
| `MeshStorage` / `RoomMeshStorage` | Persistence use cases and encrypted record adaptation | Routing or Bluetooth |
| `MessageStore` / Room / `StorageCipher` | Atomic history/outbox/replay/status transactions, encrypted fields and schema migrations | Transport or service state |

```mermaid
flowchart TD
  UI[MainActivity] --> Service[MeshService]
  Service --> Node[MeshNode]
  Node --> Protocol[MeshProtocol]
  Node --> Relay[RelayScheduler]
  Node --> Combined[CombinedTransport]
  Combined --> BLE[BleTransport]
  Combined --> LAN[LanTransport]
  LAN --> LANCodec[LanFrames]
  Relay --> Transport[MeshTransport interface]
  BLE -. implements .-> Transport
  BLE --> GATT[GattOutboundSession]
  BLE --> Codec[FrameCodec]
  Protocol --> Storage[MeshStorage interface]
  Relay --> Storage
  RoomAdapter[RoomMeshStorage] -. implements .-> Storage
  RoomAdapter --> Store[MessageStore / Room / StorageCipher]
```

`MeshService` remains `com.example.offgrid.MeshService`; its source now matches that
package instead of living in a misleading `mesh/` filesystem directory. Manifest
registration, binders, send statuses, service actions and advertised UUIDs stay
compatible. The packet version and Room schema are unchanged by this extraction.
`MeshConfig` centralizes the radio UUIDs and shared maintenance/queue timing.

## Thread contract

The service creates one `offgrid-mesh` HandlerThread. Node lifecycle commands,
transport listener callbacks, protocol acceptance, relay mutation and mesh storage
operations run there. BLE platform callbacks marshal onto that worker. GATT write
admission applies the synchronized frame budget before cloning/posting bytes.
`FrameCodec` and relay state are confined to that worker and need no locks.

Preparing an outgoing message may run on the caller's thread, as before; the
service posts its persistence/queue work. The immediate result is ACCEPTED, not
persisted or Delivered. UI events/notifications are marshalled to the main thread.
The existing history executor and Room observation remain separate from the mesh
worker and are owned by `MessageStore`/the activity.

`RelayScheduler.Timer` injects monotonic/wall clocks and scheduling. Its packet
encoder is injected separately, so JVM scheduling tests need neither Android JSON
nor a real radio. A fake `MeshTransport` implements the same callback contract as
BLE, including session completion and pre-send cancellation checks.

## Acceptance and recovery

1. BLE reassembles bytes through `FrameCodec` before decoding any JSON/text.
2. `MeshProtocol` applies packet budgets, parsing, signed namespace/time/identity
   checks and sender budgets. It checks the durable clock/replay state before any
   delivery or relay mutation.
3. The protocol calls a storage use case that commits the packet's replay record,
   history and pending relay packet in one transaction. A private recipient saves
   the message and its ACK outbox entry atomically.
4. Only after the transaction succeeds does the node notify listeners and load
   the persisted pending packet into the relay scheduler.
5. An authenticated ACK confirms the exact saved outgoing packet and atomically
   records replay evidence, marks Delivered and deletes that pending packet. The
   scheduler removes its in-memory copy and skips canceled packets still present
   in session batch snapshots.

On start, recovery expires old records, authenticates saved packets and restores
only eligible records with the original remaining lifetime and forward count.
An unreadable encrypted record fails recovery without deleting the original data.
Legacy/invalid pending packets are explicitly rejected as before. Routing pauses
when all transports are unavailable. Each radio closes its own native sessions on
loss; completing canceled Bluetooth sessions releases relay slots even when Wi-Fi
keeps the combined transport active. Durable pending messages remain available
for retry. Service destruction closes active **and delayed-closing**
GATT sessions before the worker thread exits.

## Validation boundaries

- `FrameCodecTest`: byte round trips, MTU boundaries, UTF-8 split boundaries,
  malformed/oversized frames, interleaved peers, transfer limits and deadlines.
- `RelaySchedulerTest`: fake clock/radio ordering, slot/batch/fan-out eligibility,
  backoff, stale peers, deadline recovery, queue eviction and stale callbacks.
- `MeshProtocolIntegrationTest`: production protocol + encrypted Room storage
  across three logical nodes, TTL, duplicate/restart behavior, authenticated
  receipts, malformed/tampered input and transaction failure.
- Existing crypto, replay, QR trust, storage and migration tests stay in place.

The logical-node tests bypass Bluetooth: they cannot validate actual discovery,
GATT vendor behavior, radio interference, physical range or manufacturer battery
restrictions. Record physical results using TESTING.md before updating README's
physical-device status.

## Changes made while extracting

The wire framing is unchanged, but fragmentation now caps characteristic values
at 512 bytes even when Android negotiates MTU 517. The previous MTU-only calculation
could produce 514-byte values that the receiver rejected. Tests cover the cap.
Malformed frames discard their peer's partial assembly, rather than leaving it
available for later continuations. Delayed GATT closes are tracked through teardown,
preventing stop from abandoning a handle that was already removed from an active
session map. These are bounded transport fixes, not evidence of device testing.

## Offline-sharing extensions in 0.2

Production composition supplies `PrivateActions` through `MeshProtocol.Extensions`.
It runs only after signature, endpoint, timestamp and flood admission. Private
withdrawals, type-5 media offers and type-4 encrypted control bodies use the same
worker and Room transactions. Saved deletion markers block delayed content before
normal history/receipt handling. `MediaTransfers` owns bounded file windows,
receiver bitmaps, chunk retries and integrity/completion; `AttachmentPreparer`
bounds user-selected media and `AttachmentFiles` scopes explicit viewer access.

`MeshNode` reconciles the relay cache with persisted pending IDs after accepted
extension actions and maintenance. Acknowledged chunks and withdrawn originals
therefore stop being eligible even if older native batch snapshots still exist.
Rejected/flooded packets do not trigger outbox database scans. File completion,
receipt evidence and message status changes remain transactional. Existing
protocol-only tests may omit extensions; the production service always installs
them. Wire version 4 and Room schema 3 belong to this feature milestone, not the
prior component extraction.

## Conversations and local Wi-Fi in 0.3-dev

Room schema 4 adds `messages.read` with a default of true for legacy records;
new inbound display rows explicitly start unread. Aggregate summaries scan all
history, while the selected conversation observes up to 500 latest messages.
Read updates are limited to rendered IDs and the selected conversation, and are
skipped for incomplete media. Visible-conversation tracking is cleared on pause.
Notifications contain generic text and explicit conversation intents. Media
completion emits a display event only after the existing integrity/save transaction.

LAN discovery/routing state lives on the mesh worker. A maximum of seven IO
threads covers one accept loop, three inbound sessions and three outbound
sessions, with no queued IO tasks. Frames wait for worker admission before a
transfer confirmation; the socket thread never calls protocol/Room directly.
Connections have a five-second connect timeout, eight-second IO waits and a
25-second absolute session deadline. Cancellation/network loss closes sockets.
Late send completion must still release a relay session; generation checks reject
late payloads, discovery callbacks and forwarding updates from an old network.
DNS-SD has bounded service/resolve tables, one active resolve and a deadline.
Only peers in the selected Wi-Fi interface's IPv4 subnet are admitted.

`CombinedTransport` reports active if either transport is active. Wi-Fi endpoints
have priority over negative BLE RSSI for available relay sessions; the existing
forwarding budget still spans transports. Transport endpoint addresses are not
contact IDs. A phone discovered twice is counted twice in diagnostics, and signed
packet replay/duplicate checks still operate across both paths.

Diagnostics snapshots are constructed on the mesh worker, then published to the
main thread. The Network dialog requests a snapshot every two seconds while
visible. Connection failures are kept in a bounded diagnostic ring instead of
showing repeated connection-error toasts. Debug counters reset with the service.
