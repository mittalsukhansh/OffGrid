# Signing/history audit

`signing-history.json` records the 16 November 2026 scan before 1.0 changes.
Only the public release certificate and fingerprint/alias/application-ID metadata
are in `signing/`. The scan included reachable Git blobs and comparisons against
known local private signing values without printing those values. No matching
private material was found within that scope. This is not a universal secret scan.
Run `python3 scripts/audit-signing-history.py --output /private/audit.json` after
fetching the refs to audit. The audit does not rewrite history or rotate a key.

The integration cannot update repo description/topics (GitHub HTTP 403). The
intended values are in `.github/repository-metadata.json`. The owner can run
`python3 scripts/apply-repository-metadata.py` with their own authorized GitHub
login. Upstream licensing request delivery was also blocked; see
[UPSTREAM_PERMISSION.md](../../UPSTREAM_PERMISSION.md).
