OffGrid 1.0 is an **experimental prerelease**. Physical-phone relay/throughput results and upstream licensing consent remain pending; this is not a production reliability or audited-security claim.

Changes:

- Separate signing/agreement keys, per-message ephemeral ECDH and signed version-5 contact bundles.
- Durable sender/epoch sequence replay windows; wall-clock rollback no longer pauses reception.
- Signed nonce inventory exchange, partial packet-ID sync and logical peer association across BLE/Wi-Fi endpoints.
- Encrypted identity/contact/nickname/verification export and validated restore using scrypt/AES-GCM.
- Aggregate diagnostic export, three-phone session capture, seeded parser/framing fuzz smoke tests and deterministic JVM relay partition/loss/reordering/restart simulation.
- Repository cleanup, public signing/history audit, current review report and canonical DECISIONS.md.
- Existing BLE GATT/CoC, Wi-Fi Direct without an AP, optional LAN, separate conversations, authenticated receipts, best-effort unsend and resumable encrypted media remain available.

Install `offgrid-1.0.apk`. Installed ID: `io.github.mittalsukhansh.offgrid`. Version code 100. The release certificate is pinned in `signing/release-identity.json`; APK signature and SHA-256 evidence are attached. The signed APK embeds its exact source commit.

**Upgrade:** A matching package/certificate can upgrade in place. The attached emulator report tests code-7 `0.3-dev` → `1.0` without uninstalling and checks identity, signing key, contact nickname and encrypted-history continuity. This is emulator evidence, not a phone/radio result. Old `com.example.offgrid` v0.1/v0.2 packages and `.debug` packages/certificates cannot upgrade in place to this ID. Use identity export where supported before moving installations; it does not export message history or files.

**Protocol change:** All live peers/relays must run protocol 5. Old history remains readable, but legacy pending/in-progress messages may need resending. Exchange new contact QR bundles and compare the new two-key fingerprint; old verification is cleared. Full forward secrecy is not implemented: recipient agreement-key compromise can decrypt recorded traffic. Identity backup excludes inbound replay windows, and concurrent restored identity copies are unsupported.

**Remaining owner actions:** Obtain written upstream permission, apply repository metadata/admin secrets where desired, and run TESTING.md on three named physical phones. No physical throughput numbers have been invented. Private groups, SOS and iOS remain future work.
