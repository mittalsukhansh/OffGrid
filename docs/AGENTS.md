# Repository working instructions

## Work and decision logging

The owner requires a continuing work log. The canonical file is `DECISIONS.md`.
Read its latest entries before making changes. Do not create a parallel
`decissions.md`, `decision.md` or similarly named log.

After each completed logical set of work, append an entry to `DECISIONS.md` in
the same change before committing/pushing. This applies to feature work, bug fixes,
refactors, tests, documentation, configuration, releases and repository operations.
For attempted work that remains blocked, log the completed preparation, the exact
blocker and the remaining owner action; do not mark the blocked action completed.

Each entry must contain the next sequential D-number, UTC date, status, concrete
work, decision/rationale, validation actually performed, known source references,
and open items. Use the file's template. Use “This change” for a pending commit;
do not guess a SHA or create a circular self-reference. A documentation-only change
needs relevant document/link checks, not an unnecessary repeat of app tests.

Keep history append-only. If a choice changes or a blocker is resolved, append a
new entry citing the earlier D-number. Mark historical backfills and approximate
operational dates explicitly. Distinguish source implementation, artifact
publication, automated/emulator evidence and physical-device results. Never invent
a check result or record signing keys, passwords, secret values or private messages.

If the owner renames the canonical log, update these instructions and README links
in the same change; preserve the previous history.

## Push identity

The owner permits pushes only through GitHub account `mittalsukhansh`, never
`braflix`. Verify the authenticated GitHub login before pushing. Preserve original
upstream commit authorship; the account performing a push is not the author of all
historical commits. Push completed authorized work after appropriate checks.
