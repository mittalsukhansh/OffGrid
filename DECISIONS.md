# OffGrid work and decision log

Canonical file: **DECISIONS.md** (the filename requested by the repository owner).
Created 16 November 2026. This is both a work history and a decision record.
No existing decision log was found when this file was created.

## How this log is maintained

Append an entry after each completed logical set of work, before committing/pushing
that work. Keep one canonical log. Use the next D-number and UTC dates. Include
what changed, why, actual validation, source commit/PR/release references when
available, and open/blocked/deferred items. For the commit being created, write
“this change” rather than inventing its future SHA. See docs/AGENTS.md for the durable
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
- **Sources:** [`0395eea`](https://github.com/mittalsukhansh/OffGrid/commit/0395eea01263d452481021011bb6edc75d1f8df0); [REVIEW_REPORT.md](docs/REVIEW_REPORT.md)

## D016 — 2026-11-16 (owner-directed scope decisions) — Deferred features and offline identity policy

- **Status:** Recorded decisions; not implemented work
- **Work:** Confirmed that battery modes, private groups and SOS/location are deferred. Discussed a separate iOS app, hosting, usernames and phone login.
- **Decision / reason:** Current app uses key identities/local nicknames without hosting or SMS. iOS needs separate platform implementation/build/signing work; do not imply the Android APK supplies iOS support.
- **Validation / evidence:** Owner instructions and the explicit status/roadmap in REVIEW_REPORT.md. No code for those deferred features was added.
- **Open items:** Groups need reviewed membership/key management; SOS needs explicit consent and honest uncertainty; forward-secret sessions and encrypted backups are separate future projects.
- **Sources:** Owner instructions; [review roadmap](docs/REVIEW_REPORT.md#8-what-is-left-and-recommended-proposal)

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

## D022 — 2026-11-16 11:35 UTC — Wi-Fi Direct runtime validation and integration

- **Status:** Final source checks passed; integration record
- **Work:** Recorded final validation for D020/D021 and prepared the tested Wi-Fi Direct source plus this evidence for consolidation into main. The implementation, compatibility fix and their log entries were pushed using mittalsukhansh.
- **Decision / reason:** Integrate only after both the build and Android emulator jobs pass. Preserve the earlier pending-validation entries; record the successful outcome here rather than rewriting them. This final evidence-only update needs document checks, not another local application-test run.
- **Validation / evidence:** Final application commit 2d6205d passed GitHub build/lint/54 JVM tests and the Android runtime suite, including ownership/hello/permission contracts, real loopback framed transfer/cancellation, Direct-prefixed encrypted media, migration and UI regressions. Checked document links, sequential decision IDs and Git diff formatting for this record.
- **Open items:** Physical AP-free pairing, group formation, permission/OEM interactions, radio throughput and rapid process/connection recovery still need phones under WIFI_DIRECT.md. Loopback and emulator tests do not establish those outcomes. Wi-Fi Aware and private group chats remain unimplemented.
- **Sources:** This change; [`2d6205d`](https://github.com/mittalsukhansh/OffGrid/commit/2d6205d3484c8eeb601700ce82a0059b4c07c462); [final Direct CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/43858481086); [WIFI_DIRECT.md](WIFI_DIRECT.md)

## D023 — 2026-11-16 11:48 UTC — 1.0 hygiene, provenance audit and blocked external setup

- **Status:** Repository cleanup implemented; external permission operations blocked
- **Work:** Removed tracked .idea files while preserving local IDE settings; ignored the directory. Renamed the canonical log to DECISIONS.md and moved the full agent guide/review report to docs. Kept a minimal root AGENTS.md discovery pointer. Added a signing/history audit, requested metadata values and an owner-operated metadata script; updated README positioning.
- **Decision / reason:** Supersedes D019's filename and D015's report location while preserving historical entries. Retain automatic agent discovery so future work keeps logging. Only public certificate/identity metadata belongs in signing/. Do not apply MIT without upstream consent or claim blocked messages were delivered.
- **Validation / evidence:** Audited 302 reachable Git blobs, current signing files and known local private signing values: no findings in that scope. The upstream issue request, repo description/topics and signing-secret access each returned GitHub HTTP 403. No email connector is available, so the prepared licensing request was not delivered. No physical-device results were generated.
- **Open items:** Owner must send/obtain the upstream written permission and apply admin-only metadata/secrets or grant suitable integration permissions. Continue the authorized 1.0 technical changes, automated signing/upgrade evidence and truthful release preparation. A later release version is not proof of physical reliability or licensing resolution.
- **Sources:** This change; [audit](docs/audits/signing-history.json); [permission request](UPSTREAM_PERMISSION.md); [agent guide](docs/AGENTS.md)

## D024 — 2026-11-16 11:49 UTC — Validate relocated documentation links

- **Status:** Corrected and validated
- **Work:** Fixed the historical roadmap link containing a fragment after the review report moved to docs, and checked root/moved Markdown references.
- **Decision / reason:** Preserve historical prose while keeping document destinations usable after the owner-requested renames.
- **Validation / evidence:** Relative document paths and script syntax checked; no application behavior changed. The initial path check caught this fragment reference before the follow-up correction.
- **Open items:** Continue 1.0 implementation and automated release evidence; external and physical blockers from D023 remain.
- **Sources:** This change; [review report](docs/REVIEW_REPORT.md)

## D025 — 2026-11-16 — Aggregate diagnostics export and physical session harness

- **Status:** Implemented; physical runs pending
- **Work:** Added Network measurement export through Android's document picker and a named three-phone metadata/APK-hash capture script. Added controlled radio runs and an explicit NOT RUN results table.
- **Decision / reason:** Export only aggregate metrics plus device/app metadata; exclude messages, contacts, addresses and error text. Capture setup separately from test outcomes and distinguish wire throughput from unique-media goodput.
- **Validation / evidence:** Debug build, lint, JVM suite and Android-test compilation passed locally. Script help/syntax and diff formatting checked. No physical phone results generated.
- **Open items:** Run A–B–C and three repetitions per radio on actual phones; attach exports and receiver completion measurements. Continue protocol/backup/release work.
- **Sources:** This change; [testing](TESTING.md)

## D026 — 2026-11-16 — Protocol 5 replay, key separation, identity backup and inventory

- **Status:** Implemented; local validation complete at commit; emulator validation pending
- **Work:** Added separate signing/agreement identities, ephemeral-static ECDH per encrypted packet, signed contact bundles, durable sender epochs/reserved sequences and atomic Room 5 replay windows. Added encrypted identity/contact export/restore with fixed scrypt/AES-GCM and empty/stopped restore gating. Added bounded signed nonce inventory, endpoint identity coalescing and duplicate-send suppression. Added parser nesting bounds, seeded parser/framing fuzz smoke tests, deterministic production-relay partition/loss/reordering/restart simulation and migration/crypto/backup regressions. Updated current specifications/review and preserved the old protocol reference.
- **Decision / reason:** Preserve signing IDs/history across upgrade; require new contact exchange and fingerprint comparison, and fail old pending wire versions closed. Replay evidence is independent of wall time and never silently evicted; cap/reordering tradeoffs are documented. Fresh restore epochs avoid sender sequence reuse from stale backups. Inventory hints are never delivery evidence. Ephemeral-static encryption does not provide full forward secrecy; reviewed session integration remains assessed/deferred.
- **Validation / evidence:** Local debug build/lint/64 passing JVM tests/instrumentation compilation, including seeded production parser/framing and relay tests. Android runtime/physical results are not yet claimed. Prepared an older signed code-7 baseline and opt-in upgrade fixture locally; this is not yet upgrade-success evidence.
- **Open items:** Run hosted Android runtime tests, signed in-place upgrade and release verification. Actual phones, upstream written consent and admin-only metadata/secrets remain open. Backup excludes history/media and inbound replay windows; concurrent identity clones unsupported. Audit custom protocol externally before security-critical use.
- **Sources:** This change; [protocol](PACKET_FORMAT.md); [security](SECURITY.md); [backup](IDENTITY_BACKUP.md); [review](docs/REVIEW_REPORT.md)

## D027 — 2026-11-16 — Runtime evidence, parser hardening and signed 1.0 release preparation

- **Status:** D026 source passed hosted checks; 1.0 publication and signed-upgrade execution pending
- **Work:** Extended shared JSON size/depth guards to decrypted controls, media manifests, inventory and identity backup imports. Restore now waits for all mesh worker threads to stop, including delayed Direct cleanup. Prepared version 1.0/code 100, signed source provenance, opt-in upgrade fixtures and a public pre-signed artifact verification/upgrade/publication workflow. Signing secrets stay local; only signed APKs/public certificate and evidence are staged. Added release notes and owner limitations.
- **Decision / reason:** Supersedes time-based live replay admission without claiming full forward secrecy. Provisioning GitHub signing secrets is still forbidden by integration permissions, so build with the persistent key locally and verify public artifacts in Actions. Publish as experimental until actual radio tests and upstream consent exist. Require exact source CI and signed in-place upgrade before release; do not overwrite earlier APKs or claim a successful upgrade from old package IDs/debug keys.
- **Validation / evidence:** D026 commit 2c4de86 passed both GitHub build and API-30 emulator jobs, including actual Room/Keystore key separation, backup round-trip, sequence rollback/restart, media/unsend, migration and UI suites. Local final 1.0 build/lint/JVM/instrumentation-compilation checks precede this commit. Prepared baseline release/test APKs signed with the pinned certificate; not yet an executed upgrade. Checked workflow/Python syntax and diff formatting.
- **Open items:** Execute final-source hosted checks, locally sign/verify exact-source APK, stage only public artifacts, run actual emulator upgrade, publish/verify v1.0 and integrate/delete merged branches. Physical runs and written licensing consent remain owner actions; repository metadata/signing secret admin access remains blocked.
- **Sources:** This change; [D026 CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/43918236795); [release notes](RELEASE_NOTES_1.0.md); [signing](SIGNING.md)

## D028 — 2026-11-16 — Reject lenient-JSON depth bypass before publication

- **Status:** Implemented and locally validated; final hosted/source artifact checks pending
- **Work:** Rejected nonstandard single quotes/comments and unbalanced nesting in the shared bounded JSON pre-parser. Added a crafted regression where single-quoted brackets hide actual recursive nesting. Updated stale source comments and bundled Bouncy Castle's own license text in APK assets.
- **Decision / reason:** Android JSONTokener accepts syntax beyond standard JSON; the pre-parser must reject syntax that can disagree with its quote/depth accounting. Preserve the dependency's notice while upstream application licensing remains unresolved.
- **Validation / evidence:** Local build/lint/65 JVM tests/instrumentation compilation passed for the guard regression; comment/license-asset-only delta checked with the final signed build. Previous preparation commit a3fd223 passed both GitHub build and Android emulator jobs and its locally signed 1.0 APK matched the pinned certificate. Rebuild the final source before publishing rather than distributing the earlier parser version.
- **Open items:** Complete final exact-source CI, signed in-place emulator upgrade and experimental v1.0 publication. Physical devices, licensing consent and admin-only setup remain open.
- **Sources:** This change; [preparation CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/43919119774)

## D029 — 2026-11-16 — Carry inventory knowledge into newly authenticated radio aliases

- **Status:** Implemented and locally validated
- **Work:** Unioned bounded known-packet sets when a second transport endpoint authenticates as an already known identity. Clear inherited endpoint hints if its authenticated identity changes. Strengthened the alias regression so Wi-Fi advertises an empty page while BLE already knows a queued ID.
- **Decision / reason:** An alias must inherit earlier inventory/forwarding knowledge even when its own rotating page differs; otherwise duplicate forwarding can consume relay budget despite logical identity coalescing.
- **Validation / evidence:** Local debug build/lint/65 JVM tests/instrumentation compilation passed, including the empty-page cross-radio regression and deterministic relay simulator. Final source will be signed and checked again before release.
- **Open items:** Final hosted checks, actual signed emulator upgrade and publication; physical devices and external permission/admin blockers remain.
- **Sources:** This change; [protocol inventory](PACKET_FORMAT.md)

## D030 — 2026-11-16 — Public signed artifact staging for 1.0

- **Status:** Prepared for verified publication; signed upgrade/publishing pending at staging
- **Work:** Staged only the public signed 1.0 APK and signed baseline/upgrade test APKs with SHA-256 manifest. Main APK version 1.0/code 100 embeds exact source 3668e621e59d43f14e1a8abdcaf9e7f675eb4fc6 and matches the pinned release certificate. No private keystore, passwords, credential JSON, identity backups or private handoff ZIPs are staged.
- **Decision / reason:** Use the authorized public-artifact Actions route from D027 because GitHub admin-secret provisioning is unavailable. Require exact-source CI and actual in-place emulator upgrade before publication. The staging commit is based on the exact application source and is not merged into main.
- **Validation / evidence:** Local signed release build, release lint, release-instrumentation build, app ID/version/non-debuggable/certificate and embedded-source checks passed. Public fixture hashes recorded in the manifest. Hosted source CI and upgrade outcome are recorded separately after completion; no physical result is claimed.
- **Open items:** Await exact-source checks, integrate tested source, execute publication workflow and verify release downloads, then remove temporary branches. External consent/admin/physical items remain open.
- **Sources:** This staging change; [source](https://github.com/mittalsukhansh/OffGrid/commit/3668e621e59d43f14e1a8abdcaf9e7f675eb4fc6); [release notes](RELEASE_NOTES_1.0.md)

## D031 — 2026-11-16 — Stop publication after signed-upgrade identity mismatch

- **Status:** Upgrade failure detected; diagnostic/durability changes implemented; root cause under investigation
- **Work:** The first real signed baseline→1.0 test installed both APKs but found a different loaded identity, so publication was automatically skipped. Made new identity preferences durable before use and preserved existing identity records on load errors instead of silently overwriting them. Added a corrupt-record preservation regression. The upgrade fixture now flushes seed preferences, verifies persisted public/wrapped records before loading and reopens the baseline in a separate instrumentation run before upgrading. Capture filtered crypto/runtime error logs for diagnosis. Allow fresh attempt branches rather than rewriting failed staging history.
- **Decision / reason:** Installer/signature success is insufficient evidence of data continuity. Keep v1.0 unpublished until identity/contact/history assertions actually pass. Distinguish fixture seed durability, baseline process-reopen behavior and true version-upgrade failures before assigning a cause.
- **Validation / evidence:** Earlier source 3668e62 passed build/lint/65 JVM tests and Android runtime checks, but the first signed upgrade run 43921168068 failed the identity assertion and skipped publish. Local checks run for this diagnostic/fail-closed change; new runtime/upgrade results will be recorded separately. The original failed APK is not a published release.
- **Open items:** Determine the key-loading failure from baseline-reopen/filtered logs, fix it and repeat exact-source CI and signed upgrade. No physical results or licensing permission exist; admin settings remain blocked.
- **Sources:** This change; [failed upgrade](https://github.com/mittalsukhansh/OffGrid/actions/runs/43921168068)

## D032 — 2026-11-16 — Stage durable baseline-reopen upgrade attempt

- **Status:** Candidate/test artifacts prepared; upgrade and publication pending
- **Work:** Staged fresh signed APKs for source 061e5fa and updated baseline fixture. Public manifest binds every APK hash. Upgrade now verifies saved identity records and a separate baseline reopening before installing the candidate, and retains filtered crypto-loading logs.
- **Decision / reason:** Keep the failed first attempt immutable and use a new temporary attempt branch. Do not publish until the stricter continuity assertions pass. No private key or password is staged.
- **Validation / evidence:** Local signed release/lint/release-instrumentation build and package/version/certificate/embedded-source verification passed. Exact-source hosted checks and signed upgrade are checked before publication; their outcomes are recorded separately.
- **Open items:** Resolve any reproduced key-loading failure, pass upgrade and verify publication; owner physical/legal/admin actions remain.
- **Sources:** This staging change; [source](https://github.com/mittalsukhansh/OffGrid/commit/061e5fa8cdf27f8dddd87d83c3313333894c1886)

## D033 — 2026-11-16 — Publish verified experimental 1.0 and consolidate main

- **Status:** Signed 1.0 published; automated/emulator evidence complete; physical/legal/admin items open
- **Work:** Integrated tested application source 061e5fa into main. Published v1.0/code 100 as an experimental prerelease with the pinned release certificate, immutable source provenance and upgrade evidence. Updated download/review/signing records and removed merged feature/temporary public artifact branches from GitHub after verifying the release download. Copied the staging record into the canonical main history; no APK fixtures are merged into main.
- **Decision / reason:** A successful installer is insufficient; require durable baseline identity before upgrade and verify identity/signing key/contact nickname/encrypted history after it. The first identity comparison failed and correctly blocked publication. The second fixture explicitly committed seed state and checked a separate baseline reopening; the candidate passed. Do not infer from the first comparison alone whether loss preceded the upgrade. Preserve failed-attempt evidence and retain the stronger fail-closed/durable production identity behavior.
- **Validation / evidence:** Exact source passed GitHub build/lint/65 JVM tests and the API-30 Android suite. Upgrade workflow 43923646455 passed three explicit tests (seed, baseline reopen, upgraded identity/contact/history), actual install -r and publication. Verified the downloaded public APK against its SHA-256, non-debuggable package/version, pinned certificate and embedded 061e5fa source; checked attached PASSED upgrade evidence. Re-audited reachable Git/public signing material and known local signing values in this completion record. Document links and diff formatting checked; no physical tests claimed.
- **Open items:** Owner must obtain written upstream consent (request was blocked/not delivered), perform actual three-phone/radio/upgrade testing, apply admin-only metadata/secrets as needed and preserve private signing-key custody. Full forward secrecy/ratchets remain unimplemented; the reviewed-library direction is documented. Identity backup excludes history/media and inbound replay evidence. Only main remains as a GitHub branch; local scratch worktrees retain intermediate build evidence.
- **Sources:** This change; [source CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/43923057763); [upgrade/publication](https://github.com/mittalsukhansh/OffGrid/actions/runs/43923646455); [release](https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.0); [first blocked attempt](https://github.com/mittalsukhansh/OffGrid/actions/runs/43921168068)

## D034 — 2026-11-16 UTC — Explicit experimental version and real process-death regression

- **Status:** Implemented/locally validated; hosted crash experiment and owner handoff pending
- **Work:** Set next source version to 1.0.1-experimental/code 101 while retaining the immutable v1.0 release. Added an emulator-only host-driven SIGKILL/new-PID identity/private-key persistence regression and an immutable protocol-4 reference experiment. Added minimum-passphrase API coverage and a host-only production KDF benchmark. Verified the private release key can sign a challenge matching the pin and that its local archive matches; prepared a recipient-encrypted Windows handoff without exposing secrets. Documented owner handset compatibility and open review findings.
- **Decision / reason:** Honour the owner's physical-test-first gate: do not change wire protocol, replay admission, inventory semantics or backup format before real radio evidence. Actual KDF cost is N=32768/r=8/p=1, not the RFC test vector; future explicit authenticated parameter fields remain planned. Use code 101 for later update ordering; versionName now states experimental. Distinguish a controlled legacy persistence reproduction from the unknown exact cause of the first upgrade incident.
- **Validation / evidence:** Local debug build/lint/JVM suite and instrumentation compilation; historical fixture compiled against untouched 6fc4ee1 production code. Private-key sign/verify, pinned certificate, matching backup/archive CRC checks passed without exposing key/password values. New hosted SIGKILL outcomes will be appended after execution. KDF probe calls the production backup API and explicitly reports host-only timings.
- **Open items:** Await hosted current/historical crash results and the owner's age1 public recipient, then transfer encrypted backup and confirm PC/offline custody. The S23 FE is eligible; the owner's Mi/Redmi 4A is Android 7.1.2/API25, below minSdk26, so a second compatible phone is needed. Actual radio tests, licensing permission and admin-only repo/signing setup remain open. No protocol-risk mitigation is claimed completed.
- **Sources:** This change; [follow-up](docs/REVIEW_FOLLOWUP.md); [investigation](docs/UPGRADE_IDENTITY_INVESTIGATION.md); [custody](KEY_CUSTODY.md); [host KDF probe](docs/audits/backup-kdf-host.json)

## D035 — 2026-11-16 UTC — Reproduced legacy identity loss and confirmed owner PC handoff

- **Status:** Hosted regression passed; private backup delivered/PC verified; offline-media restore confirmation pending
- **Work:** Consolidated the tested experimental-label/regression source into main with ten historical losses and ten current passes under real process death, public-only summaries, a sample failure and the updated investigation. The old persisted public key is absent before loading in every failed historical trial; no upgrade occurs between those trial phases. Encrypted a minimal verified signing archive to the owner's age public recipient; sent only recipient-encrypted ciphertext. The owner confirmed its decrypted Windows archive hash and created a locally passphrase-encrypted backup. Prepared offline-copy/restore instructions without requesting private keys/passwords.
- **Decision / reason:** This proves an abrupt-death persistence failure independent of migration; the first upgrade event's exact cause remains unproven because its pre-load state was not captured. Keep known replay/inventory/backup-format changes behind the owner's physical-test gate. Distinguish a passphrase-encrypted file on the PC from a verified disconnected-media backup.
- **Validation / evidence:** Source 3c8770e passed GitHub build/lint/66 JVM tests, the API-30 historical/current SIGKILL experiment and the ordinary Android suite. All ten old reopen traces report a missing saved public key before constructor loading; all ten current attempts use different PIDs and verify the recovered signing key. Private key/archive/certificate challenge checks passed locally; the owner reported the expected archive hash on Windows. No private key, password, transfer secret or handoff payload is committed to GitHub. No physical phone results claimed.
- **Open items:** Confirm restore from the separate offline drive and safe ejection. Obtain a second Android8+ handset (Android10+ for CoC comparison), then run the no-router photo test before further protocol changes. Upstream written permission, repo admin metadata and permanent signing-path choice remain owner actions; published v1.0 is unchanged.
- **Sources:** This change; [CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/43932121974); [evidence](docs/audits/identity-process-death/README.md); [custody](KEY_CUSTODY.md)

## D036 — 2026-11-16 UTC — Owner deferred offline backup; maintenance consolidated

- **Status:** Owner PC custody confirmed; offline backup explicitly deferred; tested changes consolidated
- **Work:** Recorded the owner's decision to make/verify the offline-media copy later. Retained the confirmed Windows archive hash and locally passphrase-encrypted backup status without claiming a disconnected-media copy exists. Consolidated the tested source/evidence into main and removed the merged maintenance branch.
- **Decision / reason:** Do not confuse an encrypted second file on one PC with an offline backup or create a reminder from the owner's stated plan. Preserve the earlier physical-test-before-protocol gate while clarifying the next work set requested by the owner.
- **Validation / evidence:** D034 source passed hosted build/lint/66 JVM tests, the ten-old/ten-current real-process experiment and the ordinary Android suite. D035 contains those results. This custody/cleanup record is documentation-only; relative links, decision numbering and diff formatting checked. Feature head verified as an ancestor of main before deletion; pushes use mittalsukhansh.
- **Open items:** Owner offline restore/ejection, compatible second test phone, pending protocol mitigations behind the physical gate unless the owner changes priority, upstream licensing and admin-only setup. No new release has been published or existing APK overwritten.
- **Sources:** This change; [custody](KEY_CUSTODY.md); [review follow-up](docs/REVIEW_FOLLOWUP.md)

## D037 — 2026-11-16 UTC — Preserve verified-contact expectations in same-protocol upgrade tests

- **Status:** Implemented and locally validated; hosted runtime validation pending
- **Work:** Corrected the upgrade fixture's unconditional protocol-5 trust-clearing assertion. Seed now records the baseline contact binding/fingerprint and fixture version. Verify requires unchanged dual-key contact trust to remain verified while legacy single-key migration requires reverification. Added an emulator-only actual APK reinstall check after the normal suite, preserving identity, verified contact fingerprint/nickname and encrypted history.
- **Decision / reason:** Continue independent release-test hardening while maintaining the owner's physical-test gate for protocol changes. Same-protocol updates must not be mistaken for the earlier key-separation migration. This test is explicitly a same-build reinstall, not a signed N-to-N+1 version-upgrade or physical-radio claim. App protocol/storage behavior is unchanged.
- **Validation / evidence:** Local debug build/lint/66 JVM tests and instrumentation compilation passed; hosted runtime results recorded separately once complete. The updated fixture also compiled against the immutable protocol-4 baseline. Host script rejects non-emulator execution and requires actual installer success and continuity assertions.
- **Open items:** Run hosted reinstall and ordinary suites, then consolidate. Owner offline backup is deferred; second compatible phone and protocol/security follow-ups remain open. No new APK release is published.
- **Sources:** This change; [testing](TESTING.md); [signing](SIGNING.md)

## D038 — 2026-11-16 UTC — Make reinstall harness setup and failures explicit

- **Status:** Host harness corrected/locally checked; emulator rerun pending
- **Work:** The added reinstall stage could not launch its seed instrumentation after the Gradle-managed suite. Made initial matching app/test installation explicit before seeding and retained ADB stdout/stderr on failure. Added host orchestration regressions for install ordering and diagnostic/no-false-PASS behavior.
- **Decision / reason:** Do not depend on Gradle/UTP leaving instrumentation packages installed. The first wrapper raised before recording the diagnostic, so the precise original setup cause was not captured. Treat it as a harness startup failure, not an application continuity failure or pass. App/mesh protocol behavior is unchanged.
- **Validation / evidence:** D037 build/lint/JVM checks passed, while its hosted reinstall seed launch returned exit 1 before any new continuity assertions completed. New standard-library host tests validate initial installation, actual reinstallation and retained error output; workflow syntax checked. Runtime outcome follows after rerun.
- **Open items:** Pass the actual emulator reinstall stage before merging this release-test work. Existing owner-deferred offline backup, phone testing and security follow-ups remain.
- **Sources:** This change; [first harness run](https://github.com/mittalsukhansh/OffGrid/actions/runs/43939144742)

## D039 — 2026-11-16 UTC — Verify and consolidate same-protocol reinstall checks

- **Status:** Hosted checks passed; release-test maintenance consolidated
- **Work:** Recorded actual seed/install-r/verify output proving same-protocol contact-trust continuity. Consolidated the corrected fixture and self-contained host runner into main and cleaned the merged feature branch. The runner now installs both app/test APKs before seeding and preserves ADB diagnostics. App/wire protocol behavior and published release assets are unchanged.
- **Decision / reason:** Keep ordinary verified dual-key updates distinct from legacy single-key migration. A missing instrumentation setup is not an app data-loss result. Report this precisely as a same-build reinstall regression, with the earlier signed release version-transition proof kept separate.
- **Validation / evidence:** Source 0bef999 passed both GitHub jobs: build/lint/66 JVM tests, two host harness tests, ten current SIGKILL trials, ordinary Android suites and actual same-protocol APK reinstallation. The raw seed and verification each report OK (1 test); both install-r operations report Success. Immutable old-code trials reproduced ten persistence losses again. Public-only reports, document links and diff formatting checked before this evidence commit.
- **Open items:** Owner offline-media backup remains deferred; real two-phone/radio tests and replay/inventory/backup-format follow-ups remain behind the previously requested gate unless the owner changes priority. Repo licensing/admin actions remain open. No 1.0.1 APK release has been published.
- **Sources:** This change; [CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/43940312162); [reinstall evidence](docs/audits/same-protocol-reinstall/README.md)

## D040 — 2026-11-17 UTC — Versioned backup costs and non-renewable inventory hints

- **Status:** Implemented/locally checked; final runtime integration pending
- **Work:** Owner explicitly requested proceeding with the remaining work, superseding the earlier physical-test-first ordering. Added OGB2 authenticated scrypt cost/KDF/version fields with pre-allocation bounds, retained OGB1 reading and the existing mobile default, and tested the stronger 2^17 profile. Replaced indefinite per-peer possession suppression with first-claim deadlines and bounded post-forward backoff shared across aliases; repeated claims cannot renew a known hint. Keep global queue lifetime/fan-out limits and authenticated delivery receipts.
- **Decision / reason:** Keep protocol-5 wire compatibility while improving local policy/storage formats. New backup files encode logN 15..17, r8/p1 and KDF id1 in authenticated header; default remains logN15 until phones provide memory/latency evidence. Known claims expire after five seconds; actual forwarding backs off 30/60/120 seconds and never means Delivered. Only queued IDs can populate inventory claims.
- **Validation / evidence:** KDF debug build/lint/JVM/instrumentation compilation passed; relay retry and alias tests passed in the JVM suite. Generated the legacy vector with the old production OGB1 writer; verified read compatibility, tampered/bad costs, minimum passphrase and logN17 round-trip. Combined runtime tests continue with later work. Physical radio results remain unverified.
- **Open items:** Protected replay capacity, reusable release tooling, final integrated checks and documentation. Owner tests the unchanged published v1.0 baseline on compatible phones; the Android7.1.2 device remains ineligible. No new release is claimed.
- **Sources:** This change; [backup](IDENTITY_BACKUP.md); [protocol](PACKET_FORMAT.md)

## D041 — 2026-11-17 UTC — Reserve replay capacity for owner contacts

- **Status:** Implemented; local checks completed, hosted Android runtime checks pending
- **Work:** Room schema 6 adds indexed sender/trust/order metadata while preserving receive windows. Reserved 4,096 verified, 2,048 manually imported and 2,048 unknown sender-epochs (8,192 total); per-identity epoch caps are 8/4/2. Only unknown windows are eligible for LRU eviction, with contact trust rechecked under the identity monitor and all acceptance/eviction changes in one Room transaction. Added saturation, promotion, protected-limit, rollback and migration Android regressions. Active inventory deadlines now also survive endpoint churn; initialized trust before publishing the storage singleton. Updated security/backup/protocol/review docs and host probe format label.
- **Decision / reason:** Unknown identities must not spend contact reservations. Protection is sticky; owner verification/import takes priority over eviction. Protected epoch limits intentionally fail closed rather than forget known evidence. Unknown replay guarantees weaken after eviction and duplicate-hint expiry. The owner explicitly lifted the previous work-order gate; physical testing remains required and unverified.
- **Validation / evidence:** Final debug assembly, lint, JVM tests and Android-test compilation passed, including the singleton publication adjustment. Hosted Android execution is recorded separately when available. D040 source 6ba8ee7 also passed both hosted jobs (run 44054814322). New schema exported. Prior KDF/relay tests pass; no physical-device result claimed.
- **Open items:** Hosted Android validation; reusable signed-upgrade candidate tooling; owner physical tests, offline-media restore check, licensing/admin actions. No new APK release published.
- **Sources:** This change; D040; SECURITY.md; PACKET_FORMAT.md; docs/REVIEW_FOLLOWUP.md.

## D042 — 2026-11-17 UTC — Reusable signed candidates without automatic publication

- **Status:** Implemented and locally checked; end-to-end candidate upgrade pending
- **Work:** Generalized pre-signed staging beyond hardcoded v1.0: explicit baseline/source/version codes, exact APK set/digests, pinned app and test certificates, signed provenance, advancing code checks and safe metadata. Candidate checks upload an installable artifact only after exact-source CI and a real emulator install-r continuity check. Publication defaults false; explicit new publications require experimental labels. Kept historical schema-1 manifests readable. Added manifest-admission host tests and corrected obsolete signing-route wording. Added owner-specific baseline phone steps to TESTING.md.
- **Decision / reason:** Make a future update concrete and reviewable without overwriting the existing baseline or treating untested radios as stable. Preserve local signing while Actions secret provisioning remains unavailable; never upload signing secrets or owner backup material. Source/artifact identity must be explicit.
- **Validation / evidence:** Six host orchestration/manifest tests pass; shell syntax, workflow job/output/publication-guard structure and diff formatting checked. End-to-end APK validation/emulator execution is not inferred from these host checks and remains pending.
- **Open items:** Signed v1.0→1.0.1-experimental candidate upgrade proof; physical phones; deferred offline-media check and licensing/admin actions. No new release published by this work set.
- **Sources:** This change; SIGNING.md; TESTING.md; scripts/verify-staged-release.py; .github/workflows/publish-pre-signed.yml.

## D043 — 2026-11-17 UTC — Stage public signed 1.0.1 upgrade candidate

- **Status:** Public signed candidate prepared; hosted install-r proof pending
- **Work:** Built locally signed code-101 1.0.1-experimental from exact source febba6b. Staged that APK, actual published signed v1.0/code-100 APK and separately built, matching fixture-2 test APKs on a one-commit artifact branch. Explicit publish=false; no release creation. No private key/credentials/owner backup material is staged.
- **Decision / reason:** Test the real published baseline rather than a rebuilt substitute. Reuse the pinned certificate and preserve the experimental baseline release. Artifact branches are temporary and never merged as binary commits into main.
- **Validation / evidence:** Source febba6b passed both hosted jobs (44058403649); 71 JVM tests and six host tests pass. Signed release assembly/lint/test-fixture compilation passed locally. Both app APKs passed certificate, application ID, version/code and embedded source checks. Preceding replay source 0bb1d55 passed Android migration/reservation/rollback tests (44057790344). Signed version-transition evidence remains pending until the candidate workflow actually passes.
- **Open items:** Candidate workflow result and archived evidence; real phone tests; owner offline-media verification and legal/admin actions. Nothing new published in Releases.
- **Sources:** This change; https://github.com/mittalsukhansh/OffGrid/actions/runs/44058403649; https://github.com/mittalsukhansh/OffGrid/actions/runs/44057790344.

## D044 — 2026-11-17 UTC — Archive verified signed candidate and consolidate main

- **Status:** Integrated, automated checks passed; candidate verified, not published as a release
- **Work:** Fast-forwarded the tested feature work into main. Archived actual signed upgrade seed/reopen/install-r/verify output, public hash and signature report. Downloaded the hosted installable artifact and independently rechecked APK hash, certificate, package, version/code and embedded source. Updated current README security claims and review/signing follow-up links while preserving the historical v1.0 report. Preserved D043 preparation log in main; binary staging commits are not merged. Cleaned temporary feature/artifact branches after evidence capture.
- **Decision / reason:** Separate implementation, emulator upgrade proof, an Actions candidate and a published release. Keep unchanged v1.0 for initial phone comparisons; a later candidate update must be installed without uninstalling. User handles deferred USB backup; do not treat it as verified.
- **Validation / evidence:** Source febba6b passed exact-source CI 44058403649 (71 JVM/six host tests and Android runtime checks). Signed candidate workflow 44059024185 passed API-30 code100→101 continuity with fixture2; publisher skipped as intended. Hosted APK SHA-256 be9fc87086d9b1bb138cce4fffa7b561878706e052dabfa748258d6098a602ef matches the manifest and pinned signer. Signing history scan: 496 blobs, two public current signing files, zero findings. Public evidence, local links and diff formatting checked; documentation-only evidence commit avoids redundant Android reruns.
- **Open items:** Physical BLE/CoC/Direct and three-phone relay results; owner offline-media restore verification, upstream written license consent, admin-only metadata/optional Actions secrets. Unknown replay protection weakens after eviction; protected epoch limits fail closed; full forward secrecy remains absent. No new release was published.
- **Sources:** This change; D040–D043; https://github.com/mittalsukhansh/OffGrid/actions/runs/44059024185; docs/audits/security-followups/README.md.

## D045 — 2026-11-17 UTC — Version 1.1 relay fairness and named QR contacts

- **Status:** Implemented and locally checked; hosted Android and signed release checks pending
- **Work:** Owner reports BLE bulk working on published v1.0 but a third phone not relaying; exact models/logs and text versus media were not supplied. Added a long-partition relay regression, limited retries to three per remembered logical neighbor and reserved the final global forwarding attempt for a neighbor not yet served. Diagnostics explain automatic relay/direct-neighbor counts. Added durable device display-name prompt/editor, signed named QR export/import, normalized bounded names, tamper rejection, legacy contact read support and local-alias preservation. Identity backup now includes the own name. Added QR decode/crypto/name persistence/backup and first-launch UI regressions. Version code 110/name 1.1-experimental, protocol 5 unchanged. Prepared release notes and explicit v1.1 tag support in the reusable verifier.
- **Decision / reason:** A new relay needs forwarding budget after a long partition; retries still cannot imply Delivered or become unbounded. This identifies a regression risk in newer retry logic, not a proven root cause of the user's v1.0 radio failure. Names are self-selected labels signed with the contact binding, not hosted/global usernames, and do not change identity/fingerprint or overwrite owner aliases. All phones must update before scanning new named QRs. Release 1.1 is explicitly authorized, but no publication is claimed before checks.
- **Validation / evidence:** Debug assembly, lint, all 72 JVM tests and Android-test compilation passed. Six host harness/manifest tests pass, including matching optional experimental tag and invalid-tag rejection. The new fake-transport fixture was corrected to advertise CoC capability for CoC endpoint tests; no real-phone behavior inferred. Hosted Android runtime and signed v1.0→1.1 install-r validation remain pending.
- **Open items:** Execute hosted checks, signed build/upgrade and v1.1 publication; consolidate temporary branches without merging historical binary artifact commits. User must retest the actual three-phone topology. Physical CoC/Direct benchmarks, owner offline-media backup and legal/admin items remain open.
- **Sources:** This change; user v1.0 BLE/relay report in this session; TESTING.md; RELEASE_NOTES_1.1.md; PACKET_FORMAT.md.

## D046 — 2026-11-17 UTC — Keep named QR dialog usable on small screens

- **Status:** Fixed locally; hosted rerun pending
- **Work:** Hosted source dafd83c passed build/lint/JVM/host checks and ordinary crypto/protocol tests, but the new first-launch name UI test found My contact's Close button clipped after name entry on the API-30 emulator. Put the full contact details, QR and profile actions inside a scroll container with dialog actions outside it. Explicitly dismiss the name-entry keyboard after saving.
- **Decision / reason:** Fix the actual small-screen/keyboard flow rather than weakening the UI assertion or publishing a failed source. Retain the test's real Close-button click.
- **Validation / evidence:** Final debug assembly/lint, all 72 JVM tests and Android-test compilation pass locally. Failed hosted run 44061318744 is retained; raw result shows one actual UI failure plus four intentionally opt-in assumption skips, not five application regressions. No release was published from that source. Hosted rerun and freshly signed exact-source APK remain pending.
- **Open items:** Passing runtime rerun and signed v1.0→1.1 upgrade/publication, branch consolidation, physical relay retest. Legal/admin and owner offline backup remain separate.
- **Sources:** This change; https://github.com/mittalsukhansh/OffGrid/actions/runs/44061318744; D045.

## D047 — 2026-11-17 UTC — Consolidate 1.1 and stage authorized signed release

- **Status:** Source integrated and checks passed; signed upgrade/publication pending
- **Work:** Merged tested source 16d8592 into main. Detached preserved historical v1.0 artifact worktrees and deleted their obsolete local branch names; their public snapshots/untracked generated reports remain intact. No unmerged production features existed on those branches, and APK commits were not merged into main. Prepared exact-source signed code-110 APK and pinned fixture2 APKs with the actual published v1.0/code-100 baseline on one temporary release-assets/v1.1 commit. Explicit publish=true/tag v1.1 follows the owner's release instruction; application label remains 1.1-experimental.
- **Decision / reason:** Keep one development baseline and preserve update identity while distinguishing experimental status from a stable version promise. Publication must depend on successful real emulator install-r continuity. Release notes document named-QR compatibility and the still-open physical relay report.
- **Validation / evidence:** Corrected source passed both hosted jobs in run 44062366988, including the retained small-screen Close-button regression, QR signature/tamper/legacy/name/backup tests, production three-logical-node protocol/receipt tests, 72 JVM and six host tests. Signed release build/lint/test-fixture assembly and local APK certificate/package/code/version/source verification passed. Signing scan: 521 reachable blobs, two current public signing files, zero findings. No field relay result is inferred.
- **Open items:** Actual signed v1.0→1.1 upgrade, release publication and independent download verification; final feature/artifact branch cleanup/evidence archive. Owner three-phone radio retest and previously documented backup/legal/admin work remain open.
- **Sources:** This change; https://github.com/mittalsukhansh/OffGrid/actions/runs/44062366988; RELEASE_NOTES_1.1.md; D045–D046.

## D048 — 2026-11-17 UTC — Publish verified 1.1 and finish branch consolidation

- **Status:** Published experimental v1.1; source consolidated on main; public download verified
- **Work:** Successful workflow 44063108150 installed the actual v1.0/code100 app, seeded/reopened fixture2, performed signed install-r to code110/1.1-experimental and verified identity, verified dual-key contact/fingerprint, nickname and encrypted history. Published tag v1.1 from exact source 16d8592 with APK, public certificate, checksum/signature and upgrade report. Updated release notes using explicitly targeted mittalsukhansh/OffGrid. Downloaded the release APK and independently checked it. Archived only public manifest/upgrade/hash/signature evidence and D047 preparation log in main. Updated README/current test/review/signing/backup docs. Deleted merged feature and temporary public artifact branches; only main remains on origin, with old local artifact snapshots preserved detached and no binary artifact commits merged.
- **Decision / reason:** Fulfill the owner's 1.1 request while retaining explicit experimental labeling and the existing signing identity. Named QRs are self-selected labels with signatures, not unique hosted usernames or automatic human verification. Explain direct-neighbor counts and retest automatic relay with mesh running on the middle phone. Do not claim the reported physical failure is fixed by logical simulations.
- **Validation / evidence:** Source 16d8592 passed CI 44062366988: 72 JVM/six host tests and Android runtime suites, including the unchanged failing UI click assertion after the scroll/keyboard fix. Signed upgrade/publication passed 44063108150. Downloaded APK SHA-256 87cf629ea29c73448756c2a136478e61501d73255cd9ed36f1397552f40226cb matches staged and published checksums; pinned signer, package, code/name and embedded source verified. Public evidence/local-document links and diff formatting checked. This final change is documentation/evidence only and skips redundant Android CI.
- **Open items:** Actual 1.1 A–B–C BLE bulk relay, photos, screen-off/restarts and Direct benchmarks need owner phone results. No quantitative phone benchmark or confirmed field fix is claimed. Offline-media restore, written upstream licensing consent and admin-only metadata/optional Actions secrets remain owner work; full forward secrecy and existing bounded replay/forwarding limits remain documented.
- **Sources:** This change; D045–D047; https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.1; https://github.com/mittalsukhansh/OffGrid/actions/runs/44063108150; docs/audits/release-1.1/README.md.

## D049 — 2026-11-17 UTC — Remove recurring inventory stalls and prioritize chat

- **Status:** Implemented and locally checked; hosted/runtime/release checks pending
- **Work:** Owner reports both text and media slowing with distance, without timings and without confirmed third-phone routing. Identified periodic inventory refreshes reapplying a five-second data gate. Gate bulk only during the initial exchange; keep forwarding enabled after that initial deadline or a successful authentication, while retaining fresh nonce validation for identity/hint updates. Let text/receipt packets proceed during initial inventory; select receipts, then private/public chat, then bulk/control, then announcements, preserving FIFO within each class. Sort once per connect pass. Prepared code111/1.1.1-experimental patch and measurement notes.
- **Decision / reason:** Remove avoidable scheduling delay before speculative radio tuning. Inventory is an optimization, not packet authentication; signatures/replay/flood admission and authenticated delivery remain unchanged. Priority affects the next batch and cannot preempt an active transmission. Weak-link retries/range remain physical limits; no speed multiplier is claimed.
- **Validation / evidence:** Debug assembly/lint, all 74 JVM tests and Android-test compilation pass. Regressions cover chat/receipt bypass with bulk still gated, priority order, authenticated refresh without a pause, unanswered refresh after initial timeout, lifecycle reset and stale nonce rejection. No phone timing or measured radio speedup is inferred.
- **Open items:** Hosted Android checks, exact-source signed patch upgrade/publication and independent download verification; owner controlled nearby/far and CoC/Direct comparisons. Earlier physical relay, backup/legal/admin items remain open.
- **Sources:** This change; owner distance/latency report in this session; PACKET_FORMAT.md; THROUGHPUT.md; TESTING.md; RELEASE_NOTES_1.1.1.md.

## Template for the next work set

```markdown
## D050 — YYYY-MM-DD HH:MM UTC — Short title

- **Status:** Implemented / documented / published / blocked / deferred (be specific)
- **Work:** Concrete changes and affected components.
- **Decision / reason:** Choice, rationale and important tradeoffs; cite any superseded D-entry.
- **Validation / evidence:** Checks actually run, results and physical-testing limits.
- **Open items:** Remaining risks, owner actions or follow-up work; use “None identified” if appropriate.
- **Sources:** Known commit/PR/CI/release/document links, or “This change” for the pending commit.
```
