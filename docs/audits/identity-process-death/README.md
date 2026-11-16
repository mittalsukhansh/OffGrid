# Immediate process-death evidence

Source under test: `3c8770ec26aac6ba57e9328ebf8810d1928de616`.

[API-30 CI run](https://github.com/mittalsukhansh/OffGrid/actions/runs/43932121974), job `storage-tests`. The full raw public-only transcripts and Android reports are available in its `storage-device-test-reports` artifact.

- Untouched protocol-4 application code at `6fc4ee19f784b396d3f5e5bd746b564ba541afa5`, plus the test-only fixture: **10/10 persistence losses**. All ten reopen transcripts show the persisted public key absent **before** calling the identity loader. See historical-summary.json and the first raw reopen trace.
- Current protocol-5 application code: **10/10 passes**, zero losses. Each pass uses a fresh identity, a real SIGKILL, a new reopening PID, the same identity/public fingerprint/epoch and successful signing with the reloaded private key.
- Build/lint/66 JVM tests and the ordinary Android runtime suite passed in the same run. These are emulator persistence results, not real-radio or handset results.

This reproduces the old asynchronous identity write being lost before migration or key loading. It does not reconstruct missing evidence from the first failed signed-upgrade attempt. The source test and host driver deliberately perform no fixture preference commit before killing the creation process.
