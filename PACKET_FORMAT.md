# OffGrid packet format, version 5

Executable definitions: `MeshMessage`, `PacketSecurity`, `CryptoManager`, `SequenceWindow`, `InventoryExchange` and the transport codecs. This custom protocol is not Bluetooth SIG Mesh. [Version 4](docs/PROTOCOL_V4.md) documents the previous format, including unchanged media controls/framing details; its static encryption and clock admission sections do not apply to version 5.

## Envelope and signature

Compact UTF-8 JSON: `v`, `id`, `sid`, `typ`, optional `tid`, `txt`, `spk`, `apk`, `epoch`, `seq`, `sig`, `ttl`, `ts`.

| Field | Definition |
| --- | --- |
| `v` | Integer 5 required for live packets. Saved versions 2/3/4 remain readable. |
| `sid` | First 16 bytes of SHA-256(raw signing public key), 32 lowercase hex. Existing signing identity is retained on upgrade. |
| `id` | `sid` followed by 32 random UUID hex characters. |
| `spk`, `apk` | Distinct signing/agreement P-256 public points: uncompressed 65 bytes, Base64url, no padding (87 characters). |
| `epoch` | Signed 32-hex random installation/restore epoch. Persisted across ordinary process restarts. |
| `seq` | Positive signed 64-bit sender sequence, reserved durably in blocks of 64 before use; unused numbers may be skipped. |
| `sig` | DER SHA256withECDSA signature, Base64url. |
| `ttl` | Mutable 0–15, normally 7; signatures deliberately exclude this relay budget. |
| `ts` | Positive sender wall-clock milliseconds for display; not the replay authority. |

The canonical signature is Java `DataOutputStream`: `writeUTF("offgrid-signed-packet-v1")`, int version, int type, UTF id, UTF sender, UTF target (empty if absent), UTF signing public key, UTF epoch, long sequence, UTF agreement public key, long timestamp, UTF text. `writeUTF` is Java's length-prefixed modified UTF-8. All fields other than TTL are bound. Validate public curve points, self-derived sender ID, namespace, signature, ingress limits and replay policy before acceptance. Receipt fingerprints also bind version, epoch, sequence and agreement key.

| Type | Payload and target |
| --- | --- |
| 0 public | Signed plaintext, at most 280 UTF-16 units; no target. |
| 1 private | Encrypted chat addressed to a recipient ID. |
| 2 announcement | Signing public key in text; authenticated agreement key in envelope. |
| 3 receipt | Purpose-separated encrypted receipt for the exact immutable original packet. |
| 4 control | Encrypted withdrawal/media control JSON; maximum 12 KiB plaintext. |
| 5 media | Encrypted attachment manifest/offer, with resumable canonical 640-byte blocks. |
| 6 inventory | Signed plaintext request/response, TTL zero; never relayed, displayed, persisted in outbox or treated as a receipt. |

Ordinary packets including inventory remain bounded to 2,048 serialized bytes; controls allow 32,768. Ordinary encrypted payloads allow 1,800 Base64url characters; encrypted control ceiling is 16,512. Chat encryption is bounded to 1,120 plaintext UTF-8 bytes; actual UI text is also limited to 280 UTF-16 units. Untrusted JSON has at most 16 nesting levels and 32 KiB; transport byte limits apply before allocation/parsing. Larger media ranges use control packets on stream transports, never oversized GATT frames.

## Encryption

Each private/control/receipt encryption generates a fresh P-256 ephemeral pair. Compute ECDH(ephemeral private, recipient agreement public), then HKDF-SHA256 with zero salt and info `offgrid-e2ee-v5|sender>target` for chat/media, `offgrid-ack-v5|sender>target` for receipts or `offgrid-control-v5|sender>target` for controls. Produce a 32-byte AES key. Payload is Base64url(`ephemeral_public(65) || iv(12) || AES-256-GCM ciphertext || tag(16)`). AAD is UTF-8 `messageId|senderId|targetId|timestamp`. The outer signature authenticates the ephemeral point and ciphertext. Signing keys never perform live ECDH.

This ephemeral-static construction is **not full forward secrecy**: later compromise of the recipient agreement private key permits recorded-message decryption. There is no ratchet or post-compromise recovery. See [security](SECURITY.md).

## Durable replay protection

Room schema 6 stores `(senderId, epoch) → highest sequence + 4,096-bit sliding receive window` atomically with history, queue, media/control and delivery changes. Out-of-order packets within the window are accepted once; older sequences are rejected. Protected replay budgets are 4,096 verified and 2,048 manually imported sender-epochs, plus 2,048 unknown windows (total 8,192). Per-sender epoch limits are 8/4/2. Only unknown windows can be evicted in LRU order; owner contact trust is rechecked before eviction. Protection never demotes; protected capacity exhaustion fails closed. Unknown replay guarantees weaken after eviction and duplicate-hint expiry. Schema 5→6 preserves existing receive bits/high-water values and classifies contact protections lazily. Clearing application data removes evidence; identity backup excludes inbound replay windows.

The temporary ID ledger is only a duplicate-handling hint. Wall-clock rollback no longer pauses version 5 reception. Local queue expiry/display/deadline behavior still uses wall time, so clock changes can affect delivery timing. A first-seen old signed packet cannot be proven recent from sequence alone; timestamps do not constitute a freshness guarantee.

Legacy pending packets fail live-version validation and are marked Failed; existing history stays readable. Every endpoint/relay must upgrade, and old contacts must exchange a version-5 contact bundle before private sending. Reverification is required because the displayed fingerprint now covers both keys.

## Inventory and logical peers

Before ordinary forwarding to a newly seen endpoint, exchange signed request/response packets containing `{kind, nonce, ids}`. Request target equals its sender (unknown remote identity); response target equals the challenger. A response must echo the exact random 128-bit challenge issued to that endpoint within five monotonic seconds. Only that response associates an endpoint with its signer and supplies inventory hints. A request alone never establishes an endpoint identity.

Each page advertises up to 12 exact packet IDs from the current relay queue. Pages rotate; requests are bounded to one per endpoint per 15 seconds, responses one per second, and state to 200 endpoints. No Bloom false positives; the inventory is partial, not complete history synchronization. Failure/old peers fall back to ordinary bounded forwarding after five seconds. Inventory consumes the normal cryptographic ingress flood budget and can be visible to observers.

Known inventory/forwarded IDs are shared across authenticated aliases of the same identity. Peer counts coalesce those aliases; unverified endpoints count separately. Scheduler permits at most one concurrent data session per logical identity and prefers stream profiles. Endpoint proof means a signer is reachable through that path, not physical proximity or a verified human. A first inventory claim postpones forwarding for five seconds; repeated claims cannot extend the deadline. Successful forwards back off for 30/60/120 seconds before retry, within the six-forward and ten-minute global queue limits. Version 1.1 limits each remembered logical neighbor to three sends per packet and reserves the final global attempt for a neighbor not yet served, preventing retries from spending the entire budget before a new relay appears. Active queued-ID deadlines survive endpoint churn across authenticated aliases (up to 200 remembered identities); a full hint cache ignores new claims. Claims never mark anything Delivered or delete the global queue. Malicious relays can still drop packets and bounded attempts do not guarantee delivery.

## Byte transports

GATT uses FIRST/LAST flag fragments, ordered writes with response, 2,048-byte reassembly, 16 active assemblies, 12-second deadline and negotiated MTU 23–517 (characteristic values at most 512). See the historical framing specification for exact unchanged flags/UUIDs.

LAN/Direct/CoC use bounded OGL1 framing: magic `0x4f474c31`, batch count 1–20, then positive 32-bit lengths up to 32,768 and raw packets. Transport confirmations mean Forwarded. Direct uses OGP1 hello/version 1 for endpoint discovery; that hello is not authenticated identity exchange. Only signed nonce inventory establishes the logical-peer mapping. Wi-Fi Direct uses actual Android P2P group interfaces/addresses; no shared AP or internet is required. See [Wi-Fi Direct](WIFI_DIRECT.md) and [throughput](THROUGHPUT.md).


## Named QR contacts (app 1.1; wire protocol remains 5)

Legacy `offgrid5:<signing>.<agreement>.<signature>` imports remain supported. A device with a chosen display name exports `offgrid5:<signing>.<agreement>.<name>.<signature>`: name is unpadded Base64url of normalized UTF-8 (1–40 UTF-16 units, no controls/format characters), maximum full bundle 640 characters. The signature covers ASCII `offgrid-contact-name-v1|` followed by the three dot-separated fields. The domain differs from the legacy `offgrid-contact-v5|` signature over the two key fields. A tampered name fails verification. Identity ID and two-key fingerprint do not depend on the name; a name is self-selected, not proof of a human identity. Duplicates are allowed. Named imports prefill an empty local nickname and never overwrite an existing local alias. Older apps cannot scan the new named bundle: update all phones to 1.1 first. Transport/protocol-5 packet compatibility is unchanged.
