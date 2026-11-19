# OffGrid 1.1.5-experimental (code115)

- Attachment progress bars and transfer controls: persistent pause/resume, cancel
  local sending and send again as a new attachment after failure/cancellation.
- Delivery details explain local relay waits, retry cooldown, active/busy sending,
  transport constraints, expiry and exhausted forwarding budgets.
- Retain confirmed blocks and deadlines when pausing; local cancellation cannot
  recall bytes already relayed. Authenticated completion overrides a late local stop.
- Room migration6→7 preserves encrypted data and links new pending media controls
  to their transfer. Protocol5 is unchanged. No adaptive radio tuning is introduced.

Install over the previous signed release without uninstalling. Avoid downgrading
schema7 app data to older APKs. See docs/TRANSFER_CONTROLS.md for controls, receipt
semantics and limitations for legacy queued controls. This remains an experimental
prerelease; physical-radio timing and multi-phone results still need testing.
