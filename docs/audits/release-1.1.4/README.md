# OffGrid 1.1.4 experimental maintenance evidence

Published 19 November 2026 from source `7e92162b024658eec65778fdb8cb4d6ea6d689c7`.
VersionName `1.1.4-experimental`, versionCode114, unchanged pinned signing
certificate, application ID, Room schema6 and protocol5.

- [Exact-source CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/44529729343)
  passed build/lint, 88 JVM tests, six host harness checks and Android runtime,
  database, Keystore, identity crash-recovery and UI suites.
- [Signed upgrade/publication](https://github.com/mittalsukhansh/OffGrid/actions/runs/44530564163)
  installed the actual published v1.1.3/code113 APK, seeded/reopened data, then
  performed signed install-r to code114. Identity/signing key, verified dual-key
  contact/fingerprint, nickname and encrypted history survived without uninstalling.
- Independently downloaded the [public APK](https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.1.4)
  and verified SHA-256 `ca8e19fcc3a5e8ad10f84863409e69cf3ff93dbb8014f0e8005dcce668a61af6`
  against staged/published checksums; pinned certificate, package, version/code and
  embedded source also verified. Public signature report matches local verification.
- Pre-stage signing audit:659 reachable blobs, two current public signing files,
  zero findings. Private keys/passwords were not staged or uploaded.

[Manifest](manifest.json), [upgrade transcript](upgrade.txt),
[checksum](SHA256SUMS), [signature report](signature.txt).

This maintenance release reserves live global forwarding slots, corrects measurement
reset during active transfers and exports aggregate relay state/events. It includes
no new wire protocol or session crypto. Physical A–B–C, CoC/Direct, weak-link timing
and OEM behavior remain unverified; automated/emulator evidence does not establish
radio throughput or an independent security audit. Legal/admin/offline-backup owner
items remain open. Install over the existing signed release without uninstalling.
