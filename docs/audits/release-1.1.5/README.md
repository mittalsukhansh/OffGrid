# OffGrid 1.1.5 experimental transfer-control evidence

Published 19 November 2026 from source `cbbfab3e50eae095607c83a14ddfc1d3cef9475e`.
VersionName1.1.5-experimental, versionCode115; same application ID and pinned
certificate. Protocol5 is unchanged; Room migrates6→7.

- [Final-source CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/44537059460)
  passed build/lint,90 JVM tests, six host checks and Android runtime/identity suites.
  Includes migration preserving encrypted bytes/progress/forwarding counts; pause
  across restart, late status/completion, cancellation/fresh retry, transactional
  rollback, expiry/ownership/unsend guards and actual control dialogs across rotation.
  Initial feature source also passed [CI44536359627](https://github.com/mittalsukhansh/OffGrid/actions/runs/44536359627).
- [Signed update/publication](https://github.com/mittalsukhansh/OffGrid/actions/runs/44537878353)
  installed the actual published v1.1.4/code114 APK with its matching fixture,
  seeded/reopened data, then performed signed install-r to code115 without uninstalling.
  Identity/signing key, verified dual-key contact/fingerprint, nickname and encrypted
  history survived. This is API30 emulator evidence, not an OEM/radio test.
- Independently downloaded the [public APK](https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.1.5)
  and verified SHA-256 `f76a64d491e5d8e77032875f4b711251b0336b2ddf6941c8b59c4307a038db50` against staged/published sums, plus certificate pin,
  package, version/code and embedded source. Public signature report matched local
  verification. Pre-stage signing audit:698 reachable blobs, two public signing files,
  zero findings. Private keys/passwords were not staged or uploaded.

[Manifest](manifest.json), [checksum](SHA256SUMS), [signature](signature.txt),
[upgrade transcript](upgrade.txt), [control semantics](../../TRANSFER_CONTROLS.md).

Outgoing controls are local and best-effort; cancel is separate from unsend. Pausing
retains progress and deadlines; terminal Send again starts a new attachment. Old
pre-upgrade query/wrapper packets may lack a migration association; already relayed
bytes can arrive after a stop. Details are snapshots, never route/delivery proof.
Physical A–B–C/weak-link/CoC/Direct control timing remains unverified. Adaptive tuning,
security review and previous legal/admin/offline-backup items remain open.
