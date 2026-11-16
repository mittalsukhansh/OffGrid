# OffGrid work and decision log

Canonical file: **decissions.md** (the filename requested by the repository owner).
Created 16 November 2026. This is both a work history and a decision record.
No existing decision log was found when this file was created.

## How this log is maintained

Append an entry after each completed logical set of work, before committing/pushing
that work. Keep one canonical log. Use the next D-number and UTC dates. Include
what changed, why, actual validation, source commit/PR/release references when
available, and open/blocked/deferred items. For the commit being created, write
“this change” rather than inventing its future SHA. See AGENTS.md for the durable
repository instruction and the template at the end of this file.

The backfill below is based on retained Git commits, verified release/CI records
and the owner's session instructions. Commit dates are distinguished from release
publication dates; they are not exact work-session start/end times. Operational
substeps without retained timestamps are identified as such. **Implemented,
published, tested on an emulator and physically tested are separate claims.**
Historical decisions can be superseded: append the correction/resolution and cite
the earlier entry. Do not erase the old decision or upgrade old test claims.
Never log private keys, passwords, secret values or private correspondence.

## D001 — 2026-10-12 to 2026-11-05 (upstream commit dates) — Inherited project bootstrap and history

- **Status:** Inherited source; backfilled
- **Work:** Initial repository/application commits, upstream main merge and README update. Original authorship is retained in Git.
- **Decision / reason:** Continue the upstream Android project rather than rewrite commit authorship or claim sole ownership.
- **Validation / evidence:** These commits establish provenance; their titles are not evidence that the early app was device-tested.
- **Open items:** Upstream licensing permission remains unconfirmed; see D017.
- **Sources:** [`de57231`](https://github.com/mittalsukhansh/OffGrid/commit/de57231415eaf3d1d7bd821b1282e89c61dd1a53), [`df6c690`](https://github.com/mittalsukhansh/OffGrid/commit/df6c690ba3127ca1c1a78eb8dca4bf44c45f35f8), [`f0513b7`](https://github.com/mittalsukhansh/OffGrid/commit/f0513b768a4a6542405228196d36bb5fad14ad26), [`c1e4804`](https://github.com/mittalsukhansh/OffGrid/commit/c1e4804f563befb5b31a61f95d5a7f0ef23e2efd), [`79b0e29`](https://github.com/mittalsukhansh/OffGrid/commit/79b0e297b4af605da08e47a10fa48a8ba4679cfb)

## D002 — 2026-11-05 02:38 UTC — Upstream private messaging and encryption baseline

- **Status:** Inherited implementation; backfilled
- **Work:** Inherited target-addressed private messaging, ECDH encryption and the earlier mesh-service structure.
- **Decision / reason:** Contacts use public-key identities and private traffic uses message-layer encryption.
- **Validation / evidence:** Historical source baseline identified in NOTICE.md. Later security tests concern subsequent code, not an audit of this original snapshot.
- **Open items:** Static ECDH does not provide forward secrecy; see D006 and D016.
- **Sources:** [`ea860f6`](https://github.com/mittalsukhansh/OffGrid/commit/ea860f670d9be1a74c6d77756797e1caefd858be)

## D003 — 2026-11-15 17:46 UTC — Startup registration and repeatable Android build

- **Status:** Implemented
- **Work:** Corrected service registration and added Android build/check automation; established JDK/SDK build prerequisites.
- **Decision / reason:** Make startup/build failures reproducible before extending the messaging protocol.
- **Validation / evidence:** Build/lint/JVM and instrumentation-compilation checks were established; current tooling is documented in README and build files.
- **Open items:** Physical-device startup/background behavior still needs device evidence.
- **Sources:** [`fe975a5`](https://github.com/mittalsukhansh/OffGrid/commit/fe975a51747421b989b475bf32b12d9a84a32160)

## D004 — 2026-11-15 18:17 UTC — Durable encrypted messages and relay queues

- **Status:** Implemented
- **Work:** Added Room-backed chat history and pending delivery queues, encrypted sensitive fields, reload/recovery and database tests.
- **Decision / reason:** Process death must not discard the displayed history or queued outgoing work. Keep queryable metadata separate from encrypted bodies.
- **Validation / evidence:** SQLite/Android Keystore persistence tests and later restart/migration regressions; not whole-database encryption.
- **Open items:** User-facing encrypted backup/restore and retention controls remain unimplemented.
- **Sources:** [`9770a53`](https://github.com/mittalsukhansh/OffGrid/commit/9770a535db8a0a8f21ea29bb0765959946128fc7)

## D005 — 2026-11-15 18:27 UTC — Authenticated delivery receipts

- **Status:** Implemented
- **Work:** Added authenticated recipient receipts and persisted Queued, Forwarded, Delivered and timeout/failure outcomes.
- **Decision / reason:** Local acceptance or a successful transport write cannot prove recipient delivery. Delivered requires an acknowledgement bound to the original outgoing message.
- **Validation / evidence:** Automated receipt-binding, restart and stale-callback regressions. Delivered does not mean read by a human.
- **Open items:** Physical delivery reliability remains unverified.
- **Sources:** [`e0f1df8`](https://github.com/mittalsukhansh/OffGrid/commit/e0f1df861b5076d57a8837c044f5b72007eb1345)

## D006 — 2026-11-15 18:51 UTC — Sender authentication, contact verification and replay protection

- **Status:** Implemented, with explicit security limits
- **Work:** Added signed packet identity binding, QR contacts, fingerprint comparison/verified indicators, durable replay evidence, clock checks and flood budgets.
- **Decision / reason:** Authenticate the sender key, pin trust to that identity and reject replay/unsigned live traffic. Importing/scanning a QR alone does not verify a person.
- **Validation / evidence:** JVM admission tests and Android crypto, trust/QR, replay, clock and transaction tests. No independent audit or physical camera/radio validation is implied.
- **Open items:** Forward secrecy, post-compromise recovery and stronger anti-abuse product controls remain open.
- **Sources:** [`9f0c3d1`](https://github.com/mittalsukhansh/OffGrid/commit/9f0c3d1cd677b628d9006ecdbb4cf3b912095a55); [SECURITY.md](SECURITY.md)

## D007 — 2026-11-15 19:04 UTC — Prepare the v0.1 test milestone

- **Status:** Implemented release preparation
- **Work:** Set the v0.1 test version and wrote a physical three-phone test checklist.
- **Decision / reason:** Separate a test build from any claim of field-tested mesh reliability.
- **Validation / evidence:** Checklist recorded; actual A–B–C range/screen-off/movement results were not produced.
- **Open items:** Run and retain the physical checklist before making reliability claims.
- **Sources:** [`02e6cc9`](https://github.com/mittalsukhansh/OffGrid/commit/02e6cc9d866fdfb6ec3bb41aed7698db015d7895); [TESTING.md](TESTING.md)

## D008 — 2026-11-15 19:08 UTC — Verified test-release automation

- **Status:** Implemented
- **Work:** Added manual GitHub Actions publishing tied to an exact tested main commit, APK version checks and checksum/signature assets.
- **Decision / reason:** Build/upload from GitHub Actions because the connected integration could not upload release binaries directly.
- **Validation / evidence:** Workflow publication succeeded for historical releases. This initial workflow used debug signing and was superseded by the persistent release-signing setup in D017.
- **Open items:** Persistent-key publication still requires owner-provisioned GitHub secrets.
- **Sources:** [`f0bc32c`](https://github.com/mittalsukhansh/OffGrid/commit/f0bc32c3e90c31353e524ba2de657876b01eb94f); [.github/workflows/test-release.yml](.github/workflows/test-release.yml)

## D009 — 2026-11-15 19:12 UTC (publication record) — Publish v0.1 test APK

- **Status:** Published historical prerelease
- **Work:** Published the v0.1 APK and supporting verification/testing assets.
- **Decision / reason:** Keep this artifact as an immutable historical test snapshot.
- **Validation / evidence:** GitHub prerelease publication record; source tag points to the tested release-automation milestone.
- **Open items:** Debug-signing continuity was not established for these early builds; old package data has no automatic migration to the later app ID.
- **Sources:** [v0.1 release](https://github.com/mittalsukhansh/OffGrid/releases/tag/v0.1); [`f0bc32c`](https://github.com/mittalsukhansh/OffGrid/commit/f0bc32c3e90c31353e524ba2de657876b01eb94f)

## D010 — 2026-11-15 19:44 UTC — Split mesh responsibilities

- **Status:** Implemented
- **Work:** Separated Android service lifecycle, BLE transport, relay scheduling, protocol admission and Room storage adaptation; documented the packet format and validation boundaries.
- **Decision / reason:** Keep transport/routing/protocol/storage responsibilities independently reviewable and testable.
- **Validation / evidence:** Added component tests for framing, scheduling and logical-node protocol behavior. Corrected characteristic frame caps and native-session cleanup.
- **Open items:** Further focused cleanup of large UI/crypto/media classes remains useful; physical radio behavior is not proven by component tests.
- **Sources:** [`6d3a2e3`](https://github.com/mittalsukhansh/OffGrid/commit/6d3a2e351596ad38ffbcc11f0b59d9b3476f71d7); [ARCHITECTURE.md](ARCHITECTURE.md); [PACKET_FORMAT.md](PACKET_FORMAT.md)

## D011 — 2026-11-15 20:46 UTC — Offline nicknames, private unsend and resumable media

- **Status:** Implemented; v0.2 source milestone
- **Work:** Added local nicknames with duplicate warnings, authenticated best-effort private unsend, author/target-bound tombstones/retries, compressed JPEGs and short MP4 transfers. Added encrypted partial storage, bitmaps and whole-file validation. Packet version became 4; Room schema became 3.
- **Decision / reason:** No hosted VM, SMS login, phone-number registration or central username directory is required. Nicknames do not replace cryptographic identities. Unsend cannot recall screenshots, exports or guarantee deletion on offline phones.
- **Validation / evidence:** Build/lint/JVM and Android sharing tests for authorization, delayed originals, restarts, integrity and JPEG/MP4 limits. Initial media pumping was two 640-byte chunks per five seconds; see D018 for the later change.
- **Open items:** Photos remain limited to 48 KiB; MP4s to 256 KiB and 10 seconds. No built-in video transcoder, guaranteed recall or secure forensic erasure.
- **Sources:** [`2ec43f6`](https://github.com/mittalsukhansh/OffGrid/commit/2ec43f6f1fabbecda0360ce267c4d099cc514f21)

## D012 — 2026-11-15 to 2026-11-16 (session operations; exact substep times not retained) — Repository access and main consolidation

- **Status:** Completed repository operations
- **Work:** Validated access to mittalsukhansh/OffGrid, established main as the combined development baseline/default branch and removed fully merged short-lived branches after their work landed.
- **Decision / reason:** New pushes must authenticate as mittalsukhansh, never braflix. Preserve original upstream commit authors. Main holds the combined codebase.
- **Validation / evidence:** Authenticated GitHub identity and remote refs were checked during pushes. The owner completed the default-branch setting in GitHub after the integration was denied that setting change. No separate retained main commit is inferred for temporary access/test-push checks.
- **Open items:** Continue checking account identity before future pushes. Do not rewrite original authorship.
- **Sources:** Session instructions/verified repository state; [repository](https://github.com/mittalsukhansh/OffGrid)

## D013 — 2026-11-16 07:16 UTC (publication record) — Publish and verify v0.2 APK

- **Status:** Published historical prerelease
- **Work:** Published v0.2 from the combined main snapshot, with APK, checksums, public signature metadata and testing checklist.
- **Decision / reason:** Verify the downloaded artifact and keep the release distinct from later source changes.
- **Validation / evidence:** Publishing workflow succeeded; downloaded checksum, signature and version 0.2/code 3 were verified. The signing certificate differed from v0.1.
- **Open items:** Old APK upgrades can require uninstall/data loss. v0.2 does not contain conversations, Wi-Fi, later signing identity or L2CAP throughput changes.
- **Sources:** [v0.2 release](https://github.com/mittalsukhansh/OffGrid/releases/tag/v0.2); [publish run](https://github.com/mittalsukhansh/OffGrid/actions/runs/43822340758); [`2ec43f6`](https://github.com/mittalsukhansh/OffGrid/commit/2ec43f6f1fabbecda0360ce267c4d099cc514f21)

## D014 — 2026-11-16 08:09–08:13 UTC (commit dates) — Separate conversations, local Wi-Fi and diagnostics

- **Status:** Implemented and merged
- **Work:** Added per-contact/public conversation views, durable unread flags/counts, per-chat drafts across switching/rotation, generic notifications, chat back navigation, opt-in local IPv4 Wi-Fi TCP/NSD transport and live diagnostics. Added Wi-Fi-only permission/startup handling. Room schema became 4.
- **Decision / reason:** Keep messaging offline and notifications private. Shared Wi-Fi/hotspot clients are the first Wi-Fi transport; this is not Wi-Fi Direct. One phone may appear as multiple transport endpoints.
- **Validation / evidence:** Build/lint, 38 JVM tests and Android emulator conversation, migration, unread/race and UI checks passed. TCP framing was exercised on real host sockets; physical NSD/access-point behavior was not.
- **Open items:** Physical Wi-Fi, mixed-radio recovery, notifications/OEM behavior and throughput still need phones. Draft text is not a durable backup.
- **Sources:** [`ada8561`](https://github.com/mittalsukhansh/OffGrid/commit/ada856162e91e36de79f49dd4f538563de75d94d), [`968000c`](https://github.com/mittalsukhansh/OffGrid/commit/968000ca9d82b82b768ba6a30854cc624d238fa2); [main CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/43829937343)

## D015 — 2026-11-16 09:37 UTC — Repository report for independent review

- **Status:** Documented and pushed
- **Work:** Created REVIEW_REPORT.md with project overview, stack, architecture, implemented features, evidence, limitations, proposals and a copy-ready Claude review prompt; linked it from README.
- **Decision / reason:** Pin the reviewed application snapshot and distinguish subsequent updates from historical findings. Ask reviewers to verify claims against source rather than treat the report as an audit.
- **Validation / evidence:** Checked referenced repository paths and report links. This documentation change did not require rerunning application tests.
- **Open items:** An independent Claude/human/security review is not established merely by creating the report.
- **Sources:** [`0395eea`](https://github.com/mittalsukhansh/OffGrid/commit/0395eea01263d452481021011bb6edc75d1f8df0); [REVIEW_REPORT.md](REVIEW_REPORT.md)

## D016 — 2026-11-16 (owner-directed scope decisions) — Deferred features and offline identity policy

- **Status:** Recorded decisions; not implemented work
- **Work:** Confirmed that battery modes, private groups and SOS/location are deferred. Discussed a separate iOS app, hosting, usernames and phone login.
- **Decision / reason:** Current app uses key identities/local nicknames without hosting or SMS. iOS needs separate platform implementation/build/signing work; do not imply the Android APK supplies iOS support.
- **Validation / evidence:** Owner instructions and the explicit status/roadmap in REVIEW_REPORT.md. No code for those deferred features was added.
- **Open items:** Groups need reviewed membership/key management; SOS needs explicit consent and honest uncertainty; forward-secret sessions and encrypted backups are separate future projects.
- **Sources:** Owner instructions; [review roadmap](REVIEW_REPORT.md#8-what-is-left-and-recommended-proposal)

## D017 — 2026-11-16 09:55 UTC — Application ID, persistent release signing and upstream attribution

- **Status:** Implemented code/docs; owner actions and licensing remain blocked
- **Work:** Changed release ID to io.github.mittalsukhansh.offgrid; debug gets .debug, a separate label and version suffix. Generated a private PKCS12 release key outside Git, pinned only its public certificate, added a fail-closed signed release workflow/verifier and signing ignores. Added LICENSE status notice, NOTICE.md and a permission-request draft.
- **Decision / reason:** Stable application ID plus signing identity are required for upgrades. The internal Java/Room namespace stays com.example.offgrid. The owner confirmed no upstream relicensing permission; do not label the inherited code MIT-licensed.
- **Validation / evidence:** Local signed release/lint and repeat-key verification passed. Missing signing inputs, debug APKs and wrong-key APKs were rejected. Build/unit and Android emulator package/service/FileProvider tests passed. GitHub returned HTTP 403 for signing-secret access; the integration could not provision secrets.
- **Open items:** Owner must securely retain the private key and provision the three GitHub signing secrets. Obtain upstream permission before adopting a real open-source license. New package IDs do not import old app identity/history automatically; a real future signed upgrade retaining data still needs device testing.
- **Sources:** [`a21defa`](https://github.com/mittalsukhansh/OffGrid/commit/a21defa3fe324467bd3c63d8b1566a72f739e460); [SIGNING.md](SIGNING.md); [NOTICE.md](NOTICE.md); [LICENSE](LICENSE); [UPSTREAM_PERMISSION.md](UPSTREAM_PERMISSION.md); [CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/43844134563)

## D018 — 2026-11-16 10:33 UTC — Transport-specific bulk throughput and L2CAP CoC

- **Status:** Implemented and merged; physical performance unverified
- **Work:** Added negotiated encrypted media ranges, independent pacing, bounded API 29+ BLE L2CAP CoC with dynamic-PSM discovery/deadlines/GATT fallback, path-size filtering, separate bounded stream quotas, packet/session measurements and a baseline/reset toggle. Recorded host framing microbenchmarks and a phone-test protocol. Version code advanced to 6.
- **Decision / reason:** Retain 640-byte canonical resume blocks. Initial maxima/pacing: GATT 640 B/2500 ms, CoC 2560 B/500 ms, Wi-Fi 7680 B/200 ms. Larger chunks require recipient capability; unknown/legacy routes stay conservative. These are policy settings, not measured radio speeds.
- **Validation / evidence:** Build/lint, 43 JVM tests and Android emulator regressions passed, including range integrity/authorization, atomic rollback, legacy behavior and restart-to-GATT resumption. Host loopback before/after results exclude Android/radio/crypto and do not prove app speed improvement.
- **Open items:** Real CoC negotiation, vendor lifecycle, mixed-version paths and phone throughput/CPU/energy must be measured before further tuning. Persistent channel reuse remains a proposal, not implemented.
- **Sources:** [`6534451`](https://github.com/mittalsukhansh/OffGrid/commit/65344518b42d60e99f7b7bc5f3a9390a421438a3); [THROUGHPUT.md](THROUGHPUT.md); [feature CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/43849930373); [main CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/43850536689)

## D019 — 2026-11-16 (this change) — Create the canonical work and decision log

- **Status:** Documented
- **Work:** Checked for an existing decision/work log; none was found. Created decissions.md, backfilled every retained main-history commit into the milestone entries above, linked the log from README and added future-maintenance instructions in AGENTS.md.
- **Decision / reason:** Use the owner-requested filename as the canonical append-only log; avoid a second competing decisions/decision file. Add one entry after each completed logical set of work, including docs, fixes, releases, operations and blocked outcomes.
- **Validation / evidence:** Checked commit coverage, IDs/order, local document links and Git diff formatting. Application behavior is unchanged; application tests were not rerun for this documentation-only work.
- **Open items:** Future work must append its decisions, evidence and remaining actions. Resolve prior pending items by a new linked entry rather than silently rewriting historical status.
- **Sources:** This change; [AGENTS.md](AGENTS.md); [README.md](README.md)

## D020 — 2026-11-16 11:25 UTC — AP-free Wi-Fi Direct transport

- **Status:** Implemented and locally checked; Android runtime CI pending at this commit
- **Work:** Added optional WifiP2pManager DNS-SD discovery and explicit group pairing, P2P-interface-bound TCP with bidirectional hello discovery, a Direct pairing screen and Android discovery/local-network permission flow. Added independent Direct availability, stream admission, media profile and metrics. Version code advanced to 7. Documented router-free setup and hardware tests in WIFI_DIRECT.md; updated the README/review report.
- **Decision / reason:** Supersedes D014's same-access-point-only Wi-Fi scope. Prefer Wi-Fi Direct for broad AP-free Android pairing; Wi-Fi Aware is not added. Keep same-LAN Wi-Fi as an optional path. Do not hardcode group IPs or require Bluetooth/internet. Device names/transport pairing do not verify contact identities. Do not automatically remove unknown startup groups, passively joined groups or process-restored groups; guard accepted-connect cleanup with matching fingerprints and generation/lease checks.
- **Validation / evidence:** Local APK build, lint, 52 JVM tests and instrumentation compilation passed. Added ownership/hello/multiplexer tests, Android real-loopback endpoint discovery/full-stream/cancellation tests, Direct-prefixed encrypted-media regression and pairing-screen UI checks. Runtime CI results will be appended after completion; no physical Wi-Fi Direct negotiation or throughput has been measured.
- **Open items:** Run the two-phone no-router/Bluetooth-off checklist, permission/OEM/role-reversal/process-restart tests and three-phone group tests. Direct is an owner/client group, not unrestricted multi-group Wi-Fi mesh; private group messaging remains deferred. Existing licensing permission and signing-secret owner actions from D017 are still outstanding.
- **Sources:** This change; [WIFI_DIRECT.md](WIFI_DIRECT.md); [THROUGHPUT.md](THROUGHPUT.md); [TESTING.md](TESTING.md)

## D021 — 2026-11-16 11:31 UTC — Direct discovery permissions and pairing-screen resume

- **Status:** Initial Direct runtime CI passed; final compatibility changes locally validated
- **Work:** Separated the SDK-specific permission matrix from the Android Location-services discovery gate, required the discovery mode on newer Android too, added a Location settings shortcut, and kept dialog polling resumable while the activity is paused for system prompts. Added permission-contract tests for API 26–32, 33–36 and 37+.
- **Decision / reason:** Nearby Wi-Fi permission does not remove Android's separate discovery Location-mode requirement. Avoid asking modern Android for fine/coarse coordinate permission, and do not leave the pairing view frozen after Activity Result callbacks delivered before onResume. No GPS coordinates are read.
- **Validation / evidence:** The D020 source commit passed both GitHub build and Android emulator jobs, including actual loopback TCP hello/full-size transfer/cancellation, Direct-prefixed encrypted-media and pairing-screen tests. This compatibility update passed local build/lint, 54 JVM tests and instrumentation compilation; its final runtime CI result will be appended separately.
- **Open items:** Physical Wi-Fi Direct discovery/pairing, permission/OEM behavior and off-grid throughput remain unverified; the emulator socket tests are not a radio test.
- **Sources:** This change; [`89fcec2`](https://github.com/mittalsukhansh/OffGrid/commit/89fcec2f223c9c8751020a7a01d44d53f7d3e4c7); [initial Direct CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/43857686933); [WIFI_DIRECT.md](WIFI_DIRECT.md)

## Template for the next work set

```markdown
## D022 — YYYY-MM-DD HH:MM UTC — Short title

- **Status:** Implemented / documented / published / blocked / deferred (be specific)
- **Work:** Concrete changes and affected components.
- **Decision / reason:** Choice, rationale and important tradeoffs; cite any superseded D-entry.
- **Validation / evidence:** Checks actually run, results and physical-testing limits.
- **Open items:** Remaining risks, owner actions or follow-up work; use “None identified” if appropriate.
- **Sources:** Known commit/PR/CI/release/document links, or “This change” for the pending commit.
```
