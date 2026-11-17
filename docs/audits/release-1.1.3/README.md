# OffGrid 1.1.3 experimental merged-UI update evidence

Published 17 November 2026 from source `26ea58b221298d80a7a0b2fb431c8a26eeadf830`. VersionName `1.1.3-experimental`, versionCode113, unchanged pinned certificate, application ID, Room schema6 and protocol5.

- [Exact-source CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/44102943274) passed both jobs: debug build/lint, 82 JVM/six host tests and Android runtime suites. Includes conversation navigation, name/contact dialogs, diagnostics, QR-only payload round trips, persistence/rollback, socket cancellation, queue fairness and continuity/crash harnesses. Merged UI/resources were already integration-tested and preserve the durable-save composer path.
- [Signed upgrade/publication](https://github.com/mittalsukhansh/OffGrid/actions/runs/44103792519) installed the actual published v1.1.2/code112 APK with matching fixture2, seeded/reopened data, then performed signed install-r to code113 with matching new fixture. Same identity/signing key, verified dual-key contact/fingerprint, nickname and encrypted history survived; no uninstall occurred.
- Independently downloaded the [published APK](https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.1.3) and verified SHA-256 `6a2f6c0456d0f002068a59275b39a84241f82317d025725c5c0ec8fd80016f54` against staged/published checksums, plus certificate pin, release package, version/code and embedded source.
- Public signing/history audit before staging: 635 reachable blobs, two current public signing files, zero findings. Private signing material was not staged or released.

Archived [manifest](manifest.json), [upgrade transcript](upgrade.txt), [checksum](SHA256SUMS), [signature report](signature.txt).

This APK ships the previously merged dark chat/conversation interface and completed theme/icon/background resources, Android8/AppCompat compatibility repairs, honest TTL labels, bounded contact shortcuts and paused background status animation. Retains signed named contacts, queue/unsend and durable local save behavior. Illustrative README previews contain sample data; automated/emulator results do not establish physical radio speed/range, OEM behavior or independently audited security. Keep the documented licensing/admin/offline-backup and real-phone testing items open. Install over the signed release without uninstalling.
