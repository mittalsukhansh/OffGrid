# Same-protocol APK reinstall evidence

[API-30 CI run](https://github.com/mittalsukhansh/OffGrid/actions/runs/43940312162), application/test source `0bef999d5c4c98316cdc14c28a7e3161c577d368`.

The host explicitly installed matching current debug app/test APKs, seeded identity/contact/history state, reinstalled both APKs with `adb install -r`, and required the verification test to pass. The two-key contact binding remained verified, its fingerprint/nickname remained unchanged, and identity/signing-key/encrypted-history assertions passed. See the raw seed, installation and verify output and summary.json.

This is an emulator same-build/same-protocol reinstall regression. It does not substitute for a signed N-to-N+1 release upgrade or for physical-phone radio testing. The same run also passed build/lint/66 JVM tests, two host orchestration tests, ten current process-death trials and the ordinary Android suite; the immutable old-code crash experiment reproduced ten persistence losses again.
