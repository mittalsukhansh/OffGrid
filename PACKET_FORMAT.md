# OffGrid packet format, version 3

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
{"v":3,"id":"<64 lowercase hex>","sid":"<32 lowercase hex>","typ":0,"txt":"Hello","spk":"<public key>","sig":"<signature>","ttl":7,"ts":1770000000000}
```

| Key | Emitted type | Meaning |
| --- | --- | --- |
| `v` | integer | `3` for live traffic. The data model can also read saved version 2 history. |
| `id` | string | 64 lowercase hex characters: sender ID followed by 32 random UUID hex characters. |
| `sid` | string | 32 lowercase hex characters: first 16 bytes of SHA-256 of the raw public key. |
| `typ` | integer | 0 public chat, 1 private chat, 2 key announcement, 3 ACK. |
| `tid` | string, optional | Recipient's 32-hex ID for types 1 and 3; omitted for 0 and 2. |
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

Admission requires a version 3 signature, a sender-prefixed ID and creation time
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
