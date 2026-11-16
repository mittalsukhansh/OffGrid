# Identity persistence investigation

The first signed baseline→1.0 run failed an identity comparison after `adb install -r`. It did not capture stored public/wrapped records before first loading and did not reopen its seed before upgrading. The original transcript therefore cannot establish whether identity was lost before or during installation. The later fixture committed the seed and reopened the baseline; that stricter signed upgrade passed.

The old constructor saved identity preferences with `apply()` and returned before disk durability was guaranteed. Current code commits identity before use and preserves saved records on load errors. That makes an abrupt-death persistence race a concrete hypothesis; it does not, by itself, prove the original incident's exact cause.

## Controlled OS-process regression

`IdentityProcessDeathTest` uses fresh isolated preferences. Immediately after constructing an identity it sends only public ID/key/fingerprint/epoch/PID to the host and calls `Process.killProcess`. There is no fixture commit, packet signing, Activity-stop lifecycle or graceful app shutdown before the kill. Expected public identity stays outside the killed process.

The host requires the instrumentation crash report and disappearance of the creation PID. A separate instrumentation process must read the original persisted public/wrapped records, recover the same identity/fingerprint/epoch and produce a signature verified against its public key. Missing/partial proof or a reused PID fails the test. The script refuses non-emulator execution.

The CI experiment first builds untouched production source at `6fc4ee19f784b396d3f5e5bd746b564ba541afa5`, adding only this test-only fixture. It records ten historical attempts without relabelling observed loss as PASS. After upgrading to the current debug build, ten current attempts must all pass before the ordinary Android suite runs. Historical harness/transport failures remain failures rather than being labelled persistence loss. Raw public-only transcripts and summaries are uploaded.

No live packet, replay policy, inventory policy or backup format is changed by this test. Actual outcomes are recorded in DECISIONS.md and this report after CI. A legacy reproduction can show that process death causes loss independently of migration; it cannot supply evidence missing from the original upgrade run.
