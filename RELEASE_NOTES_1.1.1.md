# OffGrid 1.1.1 — experimental latency patch

- Send chat and authenticated-delivery receipt packets without waiting for the initial inventory exchange.
- Prioritize queued receipts and text ahead of attachments/control traffic and announcements; preserve FIFO within each priority. An already active batch is not preempted.
- Stop periodic inventory refreshes from repeatedly pausing forwarding for up to five seconds. Valid current signed nonce responses are still required to change endpoint identity or possession hints.
- Keep radio chunk sizes/pacing, retry/expiry budgets, encryption, replay/flood checks, protocol 5 and the pinned signing identity unchanged. No numerical throughput or range improvement is claimed without phone measurements.

Install this signed code111/1.1.1-experimental APK over v1.1 without uninstalling. Retains named QR contacts and the 1.1 relay fixes. For larger photos/videos, paired Wi-Fi Direct is already available without a router. Compare actual CoC/Direct counters and completion timings; weak links and reconnections at distance still need physical testing. See THROUGHPUT.md and TESTING.md. Experimental prerelease; upstream licensing consent remains unresolved.
