# OffGrid 1.1.4-experimental (code 114)

- Reserve forwarding capacity before concurrent transport attempts, preventing the
  last global forwarding slots from being oversubscribed. Keep valid reservations
  when acknowledgements arrive out of order; release them on failure or mesh pause.
- Preserve measurement accounting when reset occurs during an active transfer.
- Add privacy-safe aggregate relay queue/reservation/event statistics to measurement
  export v2. Resetting counters preserves queued messages and active transfers.
- Avoid marking intentionally cancelled CoC work as a connection failure.
- Document pinned session-library candidates and outstanding integration requirements;
  no new cryptographic protocol or forward-secrecy claim.

Protocol 5 and database schema 6 remain unchanged. Install over the previous signed
release to retain identity/history. Automated checks and signed upgrade evidence are
published separately; physical A–B–C/weak-link speed and radio behavior remain unverified.
Licensing permission, repository metadata permissions and owner offline backup remain
open. This is an experimental prerelease.
