# OffGrid security model

## Implemented protections

Live traffic uses protocol version 4. Every public message, private message,
identity announcement, and acknowledgement carries a P-256 SHA256withECDSA
signature and the sender's public key. The sender ID must equal the first 128 bits
of SHA-256 of that key. `PacketSecurity.signingBytes` length-delimits the protocol
version, type, IDs, public key, timestamp, and payload under a signature-specific
domain. TTL is intentionally excluded because relays decrement it. A signature
proves control of the claimed key, not the real-world name of its owner.

Public broadcasts remain readable by nearby participants. Signing authenticates
them; it does not encrypt them. Private messages and receipts retain the existing
ECDH/HKDF/AES-GCM encryption with separate receipt and chat purposes. The current
P-256 identity is used for agreement and signing; a future session implementation
should follow its reviewed protocol's key separation requirements.

Contact QR codes contain the same `offgrid:` public-key contact string used by
manual import. Neither scanning, import, nor discovery marks a contact verified.
The user must compare the complete 256-bit SHA-256 fingerprint with the other
person's phone or another trusted channel and select **Fingerprints match**.
The verified flag is pinned to that key-derived identity and persisted locally.
A new identity does not inherit it. Verification can be removed in the same dialog.
Incoming bubbles and the recipient selector distinguish verified and unverified
contacts. Historic unsigned public messages are labelled as legacy messages.

## Replay and flood admission

All incoming packets must have a valid signature, a sender-prefixed random ID,
and an authenticated timestamp no older than ten minutes or over two minutes in
the future. Sender-prefixed IDs prevent an unrelated authenticated identity from
occupying another sender's message ID. Unsigned and downgraded live packets are
rejected before they affect history, delivery state, or the relay queue.

Room version 2 adds a durable replay ledger and wall-clock high-water record with
a non-destructive migration from version 1. Accepting an inbound message commits
its replay entry in the same transaction as history and the outbox. Accepting an
ACK commits its replay entry in the same transaction as delivery status. A crash
cannot leave an accepted packet without its replay evidence. Evidence lasts thirty
minutes, beyond the permitted packet-age window, and is capped at 10,000 entries.
A full ledger rejects new work instead of evicting unexpired evidence.

Pruning advances the durable clock floor. A rollback beyond two minutes pauses
inbound acceptance and displays a system-time error; it does not erase replay
evidence. An accidental large forward clock jump can therefore pause reception
after the clock is moved back. This offline policy prefers replay rejection over
silent admission after an uncertain clock change. Ordinary clocks still need to
agree within the stated tolerance.

An exact duplicate private message addressed to this device may only reuse its
previously authenticated, cached receipt. It cannot create another chat entry,
change its content, reset the receipt's forwarding budget, or extend its lifetime.

`FloodGuard` uses monotonic token buckets and bounded maps:

| Admission point | Burst | Sustained allowance |
| --- | --- | --- |
| Global GATT frames | 2,400 | 1,200/second |
| Frames from one Bluetooth address | 240 | 120/second |
| Global complete packets, before crypto | 60 | 180/minute |
| Complete packets from one Bluetooth address | 30 | 90/minute |
| Packets from one verified signing identity | 12 | 30/minute |

Sender limits apply after signature verification, preventing forged sender IDs
from exhausting another identity's bucket. Peer maps have 200 entries and the
sender map has 1,000. Rate buckets reset on process restart; replay evidence does
not. GATT values are bounded to 512 bytes, reassembled packets to 2,048 bytes,
and partial transfers to sixteen concurrent buffers with twelve-second deadlines.
The existing relay queue, fan-out, connection, and backoff limits still apply.

These controls bound work and replay acceptance; they cannot prevent radio jamming
or all denial of service. New identities can be generated cheaply, so sender quotas
alone are not Sybil resistance. Global budgets limit that bypass at the cost of
possibly dropping legitimate traffic during a flood.

## Forward secrecy: assessed, not implemented

The private-message protocol still uses static ECDH. Signatures, contact verification,
and periodic long-term key replacement do **not** give it forward secrecy. An attacker
who obtains a static private key may decrypt previously recorded private traffic.
This release does not claim a ratchet or post-compromise recovery.

The recommended next protocol investigation is a maintained implementation of
Signal's authenticated asynchronous session establishment and Double Ratchet,
with the versioned prekey/ratchet configuration chosen from current reviewed
specifications. This is an integration project, not a replacement cipher call:

1. Define signed prekey and one-time prekey bundles that can travel through this
   offline mesh or be exchanged in person; bound bundle age and replay.
2. Pin session identity keys to the verified contact. Decide how existing P-256
   contacts migrate to the implementation's identity format and require users to
   reverify any new fingerprint.
3. Persist session advancement and the corresponding outgoing packet atomically.
   Consume one-time prekeys atomically, erase superseded chain/message keys, bound
   skipped-message storage, and handle out-of-order packets and restored databases.
4. Put delivery receipts into the authenticated session. Specify session reset,
   lost-device, prekey exhaustion, and identity-change behavior without silently
   falling back to static ECDH.
5. Run protocol vectors, restart/rollback tests and independent security review
   before changing the production default. Forward secrecy protects past network
   traffic after key compromise; it does not hide plaintext already retained by
   the endpoints, including encrypted local history if its storage key is compromised.

The current [libsignal](https://github.com/signalapp/libsignal) project implements
the reviewed protocol family, but its own README says use outside Signal is
unsupported and APIs may change. Its [license](https://github.com/signalapp/libsignal/blob/main/LICENSE)
is AGPL-3.0. Dependency support, Android/native footprint, updates, and licensing
must be evaluated before selecting it for OffGrid. No libsignal dependency or
custom ratchet was added in this release.

References: [Signal specifications](https://signal.org/docs/),
[Double Ratchet](https://signal.org/docs/specifications/doubleratchet/),
[PQXDH](https://signal.org/docs/specifications/pqxdh/),
[libsignal support statement](https://github.com/signalapp/libsignal#readme).

## Upgrade and verification

Every participating endpoint and relay must upgrade for version 4 traffic.
Version 2/3 history remains readable. Unsigned/legacy pending packets are removed
from the active relay queue and outgoing ones are marked Failed rather than
silently bypassing authentication; users can resend their content with the new app.
The database migration itself preserves encrypted history and queue contents.

JVM tests cover namespace/timestamp rules and flood budgets. Android emulator tests
cover signatures, key substitution, fingerprint verification persistence, QR image
round trips, replay transactions, clock handling, ledger capacity, and Room migration,
alongside the existing persistence and receipt tests. Physical camera scanning and
multi-hop Bluetooth operation still require device testing. These checks do not
constitute an independent security audit.

## Version 0.2 offline sharing

Local nicknames are labels pinned to contact IDs, not globally unique usernames or
proof of real-world identity. They are stored in app-private contact preferences.
Changing a name cannot grant verification or transfer it to another key.

Withdrawal/control packets have their own encryption purpose and signed envelopes.
Only the original sender can withdraw a private message, and only its target can
confirm the request. Durable author/target-bound tombstones prevent delayed message
or file resurrection. Withdrawal is not a guarantee that an offline peer received
the request, nor secure deletion of screenshots, external exports or disk remnants.

Attachment manifests and chunks are end-to-end encrypted and signed. Incoming file
size, part count/index, format and final SHA-256 digest are checked. JPEG decoding
is dimension bounded; short MP4s are size/duration checked and are not autoplayed.
Files/chunks are encrypted at rest; small image previews stay in a bounded memory
cache. Opening a file explicitly writes it to a FileProvider-scoped private cache
and grants an external viewer read access. Such viewers may retain their own copies.
No broad storage access, address-book access, phone login or server was added.

The small transfer window, storage/active-transfer caps and existing flood budgets
limit work; they do not guarantee that a long transfer survives movement or radio
jamming. Do not infer physical mesh reliability from the emulator sharing tests.

## Local Wi-Fi transport and conversation metadata (0.3-dev)

Wi-Fi sharing is opt-in and uses a Wi-Fi-interface-bound TCP listener plus local
DNS-SD. The socket is not TLS protected: private packets retain their existing
end-to-end encryption/signatures; public broadcasts remain readable. LAN observers
can see addresses, sizes, timing, framing and packet-routing metadata. Discovery
names are random instances, not verified user identities. No internet server,
cloud directory or account service is introduced by the INTERNET permission.

Admission requires the interface IPv4 subnet and bounded socket/worker resources,
but these controls cannot prevent network-level denial of service. Both transports
share the protocol's signature, time, replay and flood checks. Transfer-confirmation
bytes are unauthenticated and update Forwarded only; they cannot establish Delivered.
A malicious neighbor can still advertise fake peers, refuse traffic or suppress
multicast. Forward secrecy is still not implemented.

Unread flags, conversation IDs and timestamps are queryable metadata, like the
existing routing/status fields. Message bodies remain encrypted at rest.
Notifications deliberately exclude contact nicknames and message text. The
conversation list loads no plaintext previews. Diagnostics expose local transport
addresses; review those before sharing screenshots or logs.

## Negotiated bulk ranges and CoC (current development)

Large control envelopes are admitted only within 12 KiB plaintext / 32 KiB packet
bounds; ordinary text/private packet limits and GATT's 2,048-byte reassembly cap
stay in place. Range offsets/lengths and author/target binding are validated against
the saved manifest; whole-range replay/progress/part acceptance is atomic. Receiver
capability and progress are signed/encrypted, but neither is a final delivery receipt.

CoC uses Android's unpaired/insecure L2CAP API. It does not replace message signatures
or end-to-end encryption, confer peer trust, or hide public content/metadata. PSM
advertisements are untrusted discovery input with strict encoding/range checks.
Both TCP and CoC have bounded frames, sessions, worker admission and close deadlines.
Stream ingress has separate bounded global/per-peer packet quotas (40 burst/20 per
second global, 20 burst/10 per second per peer) and authenticated-control identity
quotas (20 burst/10 per second). Ordinary/GATT sender quotas remain unchanged.
These higher CPU budgets require physical resource/abuse testing before further
increases. Mutable TTL is only a performance hint, not cryptographic route evidence.

## Wi-Fi Direct off-grid path

Direct discovery/pairing does not authenticate an OffGrid contact. Service names,
device labels/MACs and the TCP hello are untrusted transport metadata; encrypted
message signatures/target binding and fingerprint verification retain their roles.
No P2P group password is read or logged. TCP binds only the actual private P2P
interface, with source/subnet checks, bounded endpoints/IO and existing stream
admission limits. Private content remains end-to-end encrypted; public content
and network metadata remain observable. No TLS, anonymity or forward secrecy is
introduced by Wi-Fi Direct.

Protected Android P2P/Location broadcasts trigger fresh platform queries; extras
are not trusted as group state. Generation/query tokens and a shared cleanup lease
limit stale callback effects. Groups unknown at startup are left alone. Restored
and passively joined groups do not gain automatic deletion authority; only a
matching group from a currently accepted OffGrid connect operation can be removed
automatically. Explicit user disconnect is limited to an adopted group. Physical
OEM behavior and cross-app/kill/restart cases still require device testing.
