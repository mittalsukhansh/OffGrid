# Security model and limitations

OffGrid is experimental software, with a custom protocol and no independent security audit. Do not infer production security or radio reliability from a version number. Public traffic and inventory are plaintext. Private content is encrypted; traffic metadata (identities, targets, timing, sizes and connectivity) remains observable.

## Identity, keys and contact trust

The signing identity is P-256 ECDSA, retained through upgrades. Version 5 creates a distinct P-256 agreement key. Both software private keys are wrapped with AES-256-GCM under Android Keystore before private preferences storage; this is not non-exportable hardware EC key material. A rooted/unlocked compromised process can expose key material. New identity records commit before use. If saved identity records or their wrapping key cannot be opened, loading fails while preserving the original records; it never silently creates a replacement identity or wrapping key. Preserve application data and existing backups when investigating a load failure.

Every live packet binds signing/agreement public keys, sender-derived ID, epoch, sequence, routing fields, timestamp and content with ECDSA. TTL is mutable. Public signatures prevent impersonation under another key; they do not prove a person's name or prevent spam from fresh identities.

Version-5 contact QR strings are `offgrid5:signingPublic.agreementPublic.bindingSignature`. The binding signs UTF-8 `offgrid-contact-v5|signingPublic.agreementPublic`. The displayed SHA-256 fingerprint covers the ASCII `signingPublic.agreementPublic`. Import/discovery never verifies a human: compare the full fingerprint over an independent channel. Agreement-key changes clear the verification flag. Legacy single-key contacts remain listed/history-readable, but need a new exchange and comparison before private sending.

Private messages, receipts and controls use fresh ephemeral P-256 ECDH per encrypted message and purpose-separated HKDF/AES-GCM. Recipient agreement-key compromise still decrypts recorded traffic. This is a stopgap, not forward secrecy, a ratchet or post-compromise security. Exact wire details are in [PACKET_FORMAT.md](PACKET_FORMAT.md).

## Replay, resource limits and durability

A durable 4,096-bit sliding sequence window per sender/epoch replaces wall-clock high-water admission. Acceptance and its sequence evidence commit atomically with application data. Reordering within 4,096 sequence numbers is supported; older messages can be rejected even if unseen. Evidence survives process restart and is never time-pruned. A global cap of 4,096 sender-epochs rejects new state when full rather than forgetting old evidence. This bounds storage at the cost of a Sybil-capacity denial of service; it is not solved identity admission.

Wall-clock rollback does not stall new protocol-5 packets. Local expiry/display still uses wall time; sequence protection does not prove that a first-seen packet was recently created. Random restore epochs prevent sequence reuse from stale sender backups. Concurrent copies of an exported identity are unsupported. Removing app data or restoring onto a fresh device loses that device's inbound replay evidence, so previously unseen/replayed network traffic may be admitted there.

Packet/frame, sender and endpoint flood budgets, bounded worker/socket counts, queues, media quotas and parse limits remain. They limit work; they do not defeat radio jamming or unlimited malicious identities. BLE/LAN/Direct connection setup is unauthenticated; application signatures and encrypted controls establish content authenticity. A signed nonce response authenticates reachable logical identity across transports, not physical proximity. Inventory is a forwarding hint, never a receipt.

Room field encryption covers message bodies, queued packets, attachment bytes/parts and sensitive control records with a separate Keystore AES key. IDs, conversation membership, status, timestamps and counts remain clear metadata; this is not whole-database encryption. Losing that local storage key makes existing history/files unrecoverable. Android automatic application backup is disabled.

Authenticated receipts mean the recipient validated and committed the referenced content, not that a person read it. Forwarded only means a peer transport confirmed transfer. Best-effort unsend uses authenticated author-bound withdrawal, acknowledgement and durable tombstones, including withdrawal-before-content handling. It cannot erase screenshots, exports, forensic remnants, offline phones or malicious recipient copies.

## Encrypted identity export

My contact → Identity backup exports both identity key pairs, contacts, nicknames and verification state. It excludes history, files, transport settings and inbound replay windows. Format OGB1 has a fixed authenticated header: magic(4), salt(16), IV(12), then AES-256-GCM ciphertext/tag. Key derivation is Bouncy Castle SCrypt (RFC 7914), N=32768, r=8, p=1, 32-byte key (about 32 MiB core memory). Parameters are fixed rather than attacker-controlled. Maximum file size is 1 MiB; passphrase length 12–1,024 characters. Use a high-entropy passphrase; minimum length alone does not ensure strength.

Encryption/KDF/document IO run off the UI thread. Plaintext key buffers and derived keys are cleared where practical, but Java/JSON/String copies cannot be reliably zeroized; no secure-memory guarantee is made. No plaintext temporary backup is written. Android's document picker controls the encrypted file destination, including cloud-backed providers if selected by the owner. No hosting or phone-number login is involved.

Restore validates key pairs and contact curve points/IDs before atomically replacing preferences, wraps imported keys under this phone's Keystore, and starts a new sender epoch. The UI requires mesh stopped and empty local history/outbox. Stop using the old installation after restore. Keep the passphrase separately. Anyone possessing both backup and passphrase can impersonate the identity. A lost passphrase cannot be recovered. Identity backup does not recover deleted chat history.

## Reviewed session-protocol direction

A full session protocol remains a separate reviewed integration. Candidate: the MIT-licensed [Noise specification](https://noiseprotocol.org/noise.html) with a maintained Android-compatible library, an authenticated XX/IK handshake, explicit identity binding and replay/nonce rules. A session key erased after use can protect earlier sessions, but asynchronous store-and-forward, offline recipient prekeys, group membership, restored/cloned identities and out-of-order mesh delivery need an explicit design; Noise alone does not solve all of these.

[noise-java](https://github.com/rweather/noise-java) is a concrete Java implementation to assess, not a shipped dependency. Before adopting any library: verify maintenance/security advisories and exact license at a pinned commit; test API 26+, interoperability and known-answer vectors; review session resumption, persistent nonce allocation, lost messages, crash recovery and rekey erasure with an external cryptographer. No custom ratchet or claim of audited forward secrecy is introduced in 1.0.

## Evidence and reporting

See [TESTING.md](TESTING.md), [THROUGHPUT.md](THROUGHPUT.md) and [decision log](DECISIONS.md) for actual check outcomes. JVM fuzzing is a seeded bounded smoke test, not exhaustive fuzz coverage. JVM relay simulation does not exercise Android radios/Keystore. Instrumentation uses real Android crypto/Room and simulated logical nodes. Physical A–B–C, CoC and Wi-Fi Direct field results are still pending.

Report suspected issues privately to the repository owner through an available GitHub security advisory channel. Do not publish identity backups, private signing keys, passphrases or private conversation data. Upstream licensing permission is independently unresolved; dependency licenses do not license inherited code.
