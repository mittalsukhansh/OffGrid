# Wi-Fi Direct: offline Wi-Fi without an access point

Current development source adds Wi-Fi Direct alongside BLE GATT, BLE L2CAP and
optional same-LAN TCP. Two supported phones form their own Android Wi-Fi Direct
group; no router, shared access point, mobile data, internet, hosted service or
manually entered IP address is required. This is implemented source awaiting
physical-device validation, not a claim of tested off-grid reliability.

Wi-Fi Direct transport groups are not private group chats. Encrypted private-group
messaging remains a separate deferred feature.

## Pairing flow

1. Install the same current development APK on both phones. v0.2 and earlier
   development artifacts predate this feature.
2. Open **Network → Wi-Fi Direct · no access point** on both phones and enable it.
   Grant Android's requested discovery/local-network permissions. Keep the Wi-Fi
   radio on; you do not have to connect it to an access point.
3. Tap **Find nearby OffGrid phones**. It starts the mesh if necessary. Both phones
   must run their meshes and advertise the OffGrid Direct service. Bluetooth is
   optional for this flow, and same-LAN sharing can remain off.
4. Select an OffGrid phone, tap **Connect**, and accept Android's system pairing
   request on the other phone. Android negotiates group-owner/client roles.
5. Wait for group and P2P socket readiness in the pairing screen. The client probes
   the group owner, allowing both sides to learn packet endpoints. Exchange/verify
   OffGrid contact keys as usual, then send messages or attachments.
6. Inspect the **WIFI_DIRECT** counters and delivery receipts in Network. A formed
   group, device name or successful TCP write does not prove private-message
   delivery or a person's identity.

A device label/MAC is an untrusted discovery label, not a contact nickname or key
fingerprint. Explicit transport pairing is independent of contact verification.

## Permissions and device requirements

- Wi-Fi Direct hardware support is optional; unsupported phones retain BLE and
  same-LAN options. Wi-Fi Aware is not implemented by this change.
- API 26–32: fine/coarse Location permission and Android Location services are
  needed for Direct discovery. OffGrid does not read GPS coordinates for it.
- API 33+: Nearby Wi-Fi devices permission, declared `neverForLocation`; fine/coarse
  Location permission is not requested for these versions.
- Android Location **services/mode** must also be on for P2P discovery, including
  newer versions using Nearby Wi-Fi permission. This is a platform discovery gate,
  separate from permission to read coordinates; OffGrid does not read GPS. The
  pairing dialog provides a Location settings shortcut.
- API 37+: local-network access is also requested for this target SDK.
- Notification permission remains optional. Direct startup does not require
  Bluetooth permissions or enabling Bluetooth.
- OEM discovery restrictions, Location toggles, VPNs, concurrent Wi-Fi/P2P support,
  user rejection, busy radios and channel loss can prevent pairing. The UI reports
  those conditions and provides Android Wi-Fi settings; it does not silently join
  arbitrary networks or force-enable the Wi-Fi radio.

## Architecture

`WifiDirectTransport` owns Android WifiP2pManager channel/discovery/group lifecycle.
It advertises `OffGrid` / `_offgrid._tcp` using Wi-Fi P2P DNS-SD service discovery.
Only peers advertising that service enter the app picker, but advertisements are
not cryptographically trusted. Pairing uses WifiP2pConfig and the platform's
connection consent/negotiation, with a bounded timeout and retry status.

`DirectTcpLink` binds a listener to the actual group's private IPv4 interface on
port **39997**. Interface name, subnet and group-owner address come from Android;
no group IP such as 192.168.49.1 is hardcoded. A client explicitly binds its source
socket to its P2P address and connects to the reported owner address. There is no
ConnectivityManager dependency on an infrastructure Wi-Fi network and no global
process network binding. IPv6-only groups are not supported in this revision.

Clients send a small discovery hello every ten seconds: two big-endian 32-bit
integers, magic `0x4f475031` (`OGP1`) and version `1`. The owner validates/replies with
the same frame and learns the client's TCP source address. No contact identity,
password or delivery proof is carried in this hello. Peer endpoints age out after
30 seconds; at most 32 are retained. Payloads use the existing OGL1 batch/packet
framing, signatures, private encryption, replay rules and authenticated receipts.

The data link has at most eight IO threads with no task backlog: one accept loop,
three inbound handlers, the relay's three outbound sessions and one hello probe.
Connects time out after five seconds, data reads after eight, and sessions have a
25-second closing deadline. Hello probes have a twelve-second closing deadline.
Connections are admitted only from the actual P2P IPv4 subnet. Native socket IO
marshals payload admission and callbacks through the mesh worker.

The Direct path has its own `WIFI_DIRECT` metrics/profile: up to 7,680 file payload
bytes per negotiated range, a 200 ms profile pacing interval and 48 in-flight
canonical blocks per transfer. These are policy limits, not measured speeds.
Normal control/data encryption and the 640-byte resume bitmap remain in use.
Unknown or relayed end-to-end routes may retain conservative pacing; see
THROUGHPUT.md for direct-ingress hints and fallback/retry semantics.

## Group ownership and cleanup

- A previously unknown group already present when OffGrid initializes is left
  untouched. Disconnect it in Android settings before starting a new OffGrid pair.
- A group created/joined after an explicit OffGrid connect can be adopted. A new
  incoming group with a discovered OffGrid member can also carry data, subject to
  normal message authentication. Unidentified incoming groups are not adopted.
- Automatic cleanup only removes the exact matching group from a connect initiated
  by the current OffGrid controller. Passively joined groups and groups restored
  from a prior process are not automatically removed: exclusive ownership cannot
  be inferred after a restart. Use **Disconnect OffGrid group** to leave an adopted
  group explicitly.
- Saved group fingerprints allow data-link recovery after a process restart but do
  not prove peer identity. Group passwords are not read, saved or placed in logs.
- Generation/query tokens and a shared cleanup lease prevent stale callbacks from
  changing a newer session. The mesh worker has a bounded shutdown grace period
  for group-removal/channel callbacks. A force-killed process cannot guarantee OS
  group cleanup; the next launch/settings can recover or disconnect the group.
- Only this channel's service/request registrations are removed. Existing unrelated
  groups and other apps' service-discovery registrations are not cleared globally.

Android normally permits one active Direct group per phone. The group owner can
relay between clients, but this is a group/star topology, not an unrestricted
multi-group Wi-Fi mesh. BLE remains another possible relay path. Physical testing
must establish actual group/client limits, concurrency and range before claiming
multi-hop reliability or speed.

## Validation and phone checklist

Automated checks cover the pure group-ownership policy (including unknown/restored
and passively joined groups), hello format/bounds, independent combined transport
availability and Direct-prefixed encrypted media/receipt regressions. Android
socket tests exercise client hello discovery, full-size framed transfers, bound
source addresses and stalled-session cancellation on loopback with an injected
loopback subnet policy. Production always uses the private P2P-subnet gate.
Those tests do not invoke physical WifiP2pManager negotiation or prove radio use.

Before calling this device-tested:

1. Use two named Wi-Fi Direct-capable phone models/OS versions. Disable cellular
   data and same-LAN sharing, disconnect infrastructure Wi-Fi, and turn Bluetooth
   off. Leave Wi-Fi radio on. Record APK commit, permission choices and radio state.
2. Discover and pair. Verify group owner/client and actual P2P interface addresses,
   then exchange private messages and files with final authenticated Delivered
   receipts. Confirm WIFI_DIRECT counters move and other transports stay idle.
3. Repeat both possible owner/client roles; deny/regrant permissions, cancel the
   Android pairing prompt, disable/re-enable Wi-Fi, and recover from busy/channel
   failure and pairing timeout.
4. Restart either process/service during a transfer, including rapid stop/start.
   Confirm resume/hash/receipt correctness and no stale cleanup of the new session.
5. Begin with an unrelated Direct group. Confirm startup/disable leaves it intact.
   Test passive incoming adoption and explicit disconnect separately from automatic
   cleanup. Verify no group credentials appear in logs or screenshots.
6. Test three phones with two clients of one owner, screen off, movement, BLE
   fallback and simultaneous infrastructure Wi-Fi only when supported. Record
   client limits, transfer times, failures, CPU/memory and energy honestly.

No physical Wi-Fi Direct pairing, interoperability, throughput or off-grid field
result has been produced by the current automated work.
