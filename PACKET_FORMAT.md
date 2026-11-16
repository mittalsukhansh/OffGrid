# OffGrid packet format, version 4

This describes the current custom BLE/GATT protocol, not Bluetooth SIG Mesh.
See `MeshMessage`, `PacketSecurity`, `DeliveryReceipt`, `CryptoManager` and
`mesh/transport/FrameCodec` for executable definitions. No packet or schema version
change is introduced by the component refactor.

## Discovery and byte transport

- Advertised service UUID: `b8f1a2c4-6d3e-4f70-9a15-2e8c7d4b1f93`.
- Writable GATT characteristic: `b8f1a2c5-6d3e-4f70-9a15-2e8c7d4b1f93`.
- Advertisements discover peers; they do not contain chat messages.
- Nodes act as both GATT server and client. A client writes one packet at a time
  using writes with response, waiting for each write callback before the next
  frame. Replies/ACKs use the receiver's own outbound client session.

Each characteristic value is `flags:1 byte || raw packet slice`. Only two flag bits
are defined: `0x01` FIRST, `0x02` LAST. A one-frame packet uses `0x03`; an interior
frame uses `0x00`. Empty payloads, values under two bytes, reserved flag bits and
values over 512 bytes are rejected. There are no sequence numbers or packet IDs
in the frame header, so writes from one peer must not interleave different packets.

For negotiated ATT MTU `m`, value capacity is `min(m - 3, 512)`, and each slice
contains at most `min(m - 3, 512) - 1` bytes after the flag:

| MTU | Maximum characteristic value | Packet bytes per frame |
| --- | --- | --- |
| 23 (default) | 20 | 19 |
| 247 (requested) | 244 | 243 |
| 517 | 512 | 511 |

The fragmenter accepts MTUs 23–517 and packets 1–2,048 bytes. The receiver buffers
at most sixteen peer assemblies, bounded to 2,048 bytes each, for twelve seconds
from FIRST; continuations do not extend that deadline. A new FIRST replaces that
peer's incomplete packet. Continuations without FIRST are ignored. Invalid input,
oversize data or disconnect discards that peer's partial assembly. Expired entries
are reclaimed on frame admission/maintenance. Completed bytes are decoded as a
single UTF-8 JSON document, so split UTF-8 characters are never decoded per frame.

Framing does not authenticate anything or detect every lost interior frame.
Ordered GATT writes supply transport ordering; JSON validation, signatures and
AES-GCM reject malformed/altered completed packets.

## JSON envelope

The sender emits compact UTF-8 JSON, at most 2,048 bytes including its envelope.
This abbreviated public example is illustrative, **not** a valid signature vector:

```json
{"v":4,"id":"<64 lowercase hex>","sid":"<32 lowercase hex>","typ":0,"txt":"Hello","spk":"<public key>","sig":"<signature>","ttl":7,"ts":1770000000000}
```

| Key | Emitted type | Meaning |
| --- | --- | --- |
| `v` | integer | `4` for live traffic. The data model can also read saved version 2/3 history. |
| `id` | string | 64 lowercase hex characters: sender ID followed by 32 random UUID hex characters. |
| `sid` | string | 32 lowercase hex characters: first 16 bytes of SHA-256 of the raw public key. |
| `typ` | integer | 0 public chat, 1 private chat, 2 key announcement, 3 ACK, 4 private control, 5 private media offer. |
| `tid` | string, optional | Recipient's 32-hex ID for types 1, 3, 4 and 5; omitted for 0 and 2. |
| `txt` | string | Type-specific plaintext, encrypted payload or announcement public key. |
| `spk` | string | Sender's uncompressed P-256 public key, Base64url without padding. |
| `sig` | string | DER-encoded SHA256withECDSA signature, Base64url without padding. |
| `ttl` | integer | Mutable remaining hop budget, constrained to 0–15. |
| `ts` | positive integer | Sender wall-clock creation time, epoch milliseconds. |

The raw public key is 65 bytes: `0x04 || X(32) || Y(32)`, encoded as 87 Base64url
characters. `spk` and `sig` are mandatory for accepted live traffic, including
public chat and announcements. The parser's legacy allowances and default fields
are for data/model compatibility; successful parsing alone is not network admission.
Unknown versions/types, invalid structural constraints and oversize packets fail
parsing; signature/namespace/time checks are an additional acceptance stage.

Type 0 carries up to 280 Java UTF-16 code units of nonblank public text. It has no
target and is signed, not encrypted. Type 1 carries Base64url `IV || ciphertext ||
GCM tag`; its encoded payload is bounded to 1,600 characters. The plaintext is
bounded to 280 UTF-16 code units at the chat API and 1,120 UTF-8 bytes at encryption.
Type 2 carries the sender's 87-character public key in `txt` as well as `spk`.
Type 3 carries encrypted receipt plaintext and is never displayed as a chat bubble.

## Canonical signature bytes

Verify the key's hash against `sid`, decode its P-256 point, then verify the DER
signature over the following ordered bytes. JSON property order and whitespace
are not signed directly.

1. `writeUTF("offgrid-signed-packet-v1")`
2. `writeInt(v)`, `writeInt(typ)`
3. `writeUTF(id)`, `writeUTF(sid)`, `writeUTF(tid or "")`, `writeUTF(spk or "")`
4. `writeLong(ts)`
5. `writeUTF(txt)`

These are **Java DataOutputStream encodings**: signed 32/64-bit big-endian integers,
and a two-byte unsigned byte length followed by Java **modified UTF-8** for
`writeUTF`. Modified UTF-8 differs from ordinary UTF-8 for NUL and supplementary
characters. Implementations in another language must reproduce those bytes.
The signature itself and TTL are excluded. Relays may decrement TTL without
invalidating identity/content authentication; TTL remains a bounded mutable field.

## Private encryption and authenticated receipts

P-256 ECDH uses the static local private key and the recipient's public key.
HKDF-SHA256 (zero salt, one 32-byte output block) derives a direction-specific key:

- Chat info: `"offgrid-e2ee-v1|" + senderId + ">" + targetId`.
- ACK info: `"offgrid-ack-v1|" + senderId + ">" + targetId`.

The literal `>` occurs once between the two IDs. AES-256-GCM uses a random 12-byte IV and a 16-byte tag. AAD is
the ordinary UTF-8 encoding of `id|sid|tid|ts`. The wire `txt` is unpadded Base64url
of `IV || ciphertext || tag`. Separate HKDF purposes prevent retyping chat
ciphertext as a receipt. All packets are also signed at the envelope layer.

ACK plaintext is `offgrid-receipt-v1|<original-id>|<fingerprint>`; the fingerprint
is 64 lowercase SHA-256 hex characters. Split at the last `|` after the prefix.
Its hashed bytes are `writeInt(original typ)`, then `writeUTF(id)`, `writeUTF(sid)`,
`writeUTF(tid or "")`, `writeUTF(spk or "")`, `writeLong(ts)`, `writeUTF(txt)` using
the same DataOutputStream conventions. TTL/signature/version are not in this
receipt fingerprint. The ACK's own envelope signature still binds its version,
type, endpoints, time and ciphertext.

The sender only marks Delivered after authenticating/decrypting the ACK and
matching its sender/target, referenced original ID and fingerprint against a saved
outgoing private message. Receipt acceptance, replay evidence, status change and
original outbox deletion are atomic. The recipient atomically saves the incoming
message and its receipt outbox entry. A cached receipt can be reused for an exact
duplicate original without re-displaying it or resetting the ACK's deadline/count.
Decrypted private display copies refuse serialization; relays retain ciphertext.

Static ECDH does not provide forward secrecy. See SECURITY.md for the session
protocol assessment and trust limitations.

## TTL, time, replay and scheduling

A receiver may deliver an accepted packet locally, then computes `max(0, ttl - 1)`
and relays only if the result is greater than zero. TTL 0 and 1 therefore stop at
that receiver. Private recipients emit a fresh signed ACK with its own default
TTL 7 instead of continuing to flood the original. Announcements start with TTL 4;
normal outgoing chat starts with TTL 7. Maximum accepted TTL is 15.

Admission requires a version 4 signature, a sender-prefixed ID and creation time
within ten minutes past / two minutes future relative to the receiver. The durable
replay ledger records accepted IDs for thirty minutes with a 10,000-entry cap;
full capacity rejects new work rather than deleting unexpired evidence. A durable
wall-clock floor rejects significant clock rollback. Exact private duplicates
addressed to this device have only the cached-receipt exception described above.

Relay policy is separate from the wire format: 200 queued packets, original
ten-minute deadlines, six-forward eligibility, at most three concurrent outbound
sessions, 20 packets per session, bounded peer history, jitter and backoff. Reboot
recovery preserves remaining lifetime and forwarding count. A successful GATT
write means Forwarded, not Delivered. Radio errors can be retried; expiration is
lack of confirmation, not proof of non-receipt. Flood limits are in SECURITY.md.

## Version 4 offline actions

Type 4 is a signed encrypted private control packet. Its HKDF info is
`"offgrid-control-v1|" + senderId + ">" + targetId`; the IV, AES-GCM tag and AAD
layout match the existing private encryption, but the distinct purpose prevents
chat/receipt/control reflection. Its decrypted JSON has a `kind` and a `mid`
referencing a 64-hex original message/transfer ID:

| Kind | Additional fields / behavior |
| --- | --- |
| `withdraw` | `when`: original timestamp. Only the original author may withdraw a private message for that target. |
| `withdraw_ack` | `request`: withdrawal packet ID. Only the intended recipient may confirm it. |
| `media_query` | Ask for the persisted receiver bitmap, or a final receipt when complete. |
| `media_missing` | Ask the source to retransmit an unaccepted offer within its original admission window. |
| `media_offer` | `wire`: complete signed type-5 offer. The inner signature, endpoints, time and ID are revalidated; this permits recovery without resetting the original identity. |
| `media_status` | `bits`: unpadded Base64url of Java BitSet.toByteArray (least significant bit first per byte); `failed`: boolean. This is progress, never proof of Delivered. |
| `media_chunk` | `index`: zero-based part; `data`: unpadded Base64url of at most 640 file bytes. Exact last-part length is checked. |

Type 5 is an encrypted private media manifest using the existing chat HKDF purpose;
the envelope signature binds its distinct packet type. The plaintext JSON contains
`mime` (`image/jpeg` or `video/mp4`), `size`, `parts = ceil(size/640)`, and `sha256`
(the whole-file digest, 64 lowercase hex). It is saved as a conversation entry,
but its ordinary private ACK is withheld until all bytes pass length/hash and media
validation and the encrypted file commits to Room. That final normal ACK refers to
the exact type-5 original; a progress bitmap cannot mark it Delivered.

Outgoing parts have a four-part in-flight window, fresh signed packet IDs on retry
after 45 seconds, and persisted send times/receiver bitmaps. The worker prepares
at most two chunks per five-second pump across transfers, sharing the existing
flood budgets. Initial offer admission expires after ten minutes; an accepted
transfer can run for one hour. Restart recovery retains progress. Network delivery
is still best-effort, including withdrawals.

Withdrawal markers survive the temporary replay window. A valid deletion request
may arrive before the original; later copies cannot resurrect it. Existing
message content, partial files and app cache are removed at application level;
this is not guaranteed secure erasure of SQLite/WAL/storage remnants or external
copies. Acknowledgements to older withdrawal attempts do not confirm a newer
retry. Ordinary late message ACKs cannot undo an unsend.

Wire version 4 is required on every endpoint and relay. Saved history from versions
2 and 3 remains readable. Room schema 3 adds withdrawal/media/part tables plus
message kind/progress with a non-destructive 2→3 migration. Forward secrecy remains
unimplemented.

## Optional local Wi-Fi framing (0.3-dev)

The signed JSON packet and wire version 4 are unchanged. DNS-SD advertises
`_offgrid._tcp.` with a random service-instance name, a dynamic TCP port and
`framing=1`. Discovery does not establish contact trust. TCP is bound to the
selected local Wi-Fi IPv4 address/network, with same-subnet peer admission.

Each connection starts with two big-endian 32-bit integers: magic `0x4f474c31`
(`OGL1`), then a packet count from 1 through 20. Each packet is a big-endian 32-bit
length from 1 through 2048 followed by exactly that many signed-JSON bytes. The
receiver passes it through the normal protocol/flood/replay pipeline and writes
one byte `0x01` as a **transport** confirmation. This is not signed and proves
neither recipient identity nor message delivery. Delivered still requires the
existing encrypted authenticated message receipt, queued in the reverse direction.
Bad lengths, truncated frames, invalid headers and timed-out sessions are closed.
An unsent or canceled item can terminate a batch; remaining durable items can retry.
Public message content remains plaintext on Wi-Fi, just as it is over BLE.

## Negotiated range extension (current development)

Normal wire envelopes remain version 4. A new receiver advertises `ranges: true`
inside its signed encrypted `media_status` control body. Legacy senders ignore the
extra field; a new sender without that authenticated capability keeps `media_chunk`
at 640-byte/GATT pacing. The manifest and bitmap still describe 640-byte blocks.

`media_range` is a control kind with `mid`, a starting canonical `index` and Base64url
`data` for 1–12 adjacent blocks (at most 7,680 file bytes). Non-final ranges must be
block aligned; an ending partial block must match the exact remaining file length.
Only the original file author/target may send/accept it. All added blocks, replay
evidence and progress are committed atomically; failure rolls back the whole range.
Each range receives a progress response. Full length/hash/media validation and the
ordinary final authenticated receipt remain mandatory for Delivered.

Only control envelopes can now use up to 12 KiB plaintext / 32 KiB serialized
packet bounds. Ordinary packet limits stay 2,048 bytes. GATT framing stays capped
at 2,048; TCP and CoC OGL1 framing permit 32 KiB packets. Older relays cannot carry
these large envelopes. Direct-ingress hints select profiles; unknown/relayed paths
stay conservative, and missing range blocks can be regenerated as smaller packets
on retry. Details and limits are in THROUGHPUT.md. CoC service-data advertisements
are `4f 01 psmHi psmLo` in a scan response; PSM is a dynamic LE value 128–255.
