# OffGrid 1.1.2 — experimental queue fairness patch

- Give a stalled active packet a three-second scheduling turn when another text/receipt is ready. Cancel only that connection and let the next text try; keep the original queued for retry rather than falsely marking it failed/delivered.
- Unsend/removing a queued packet cancels its active attempt immediately, freeing its send slot. Late transport callbacks cannot resurrect forwarding status. Cancellation works through CoC/GATT fallback and Wi-Fi sockets; intentional yields do not count as network failures.
- Preserve the window across fallback for the same packet; reset it for a new packet. A sole slow message keeps its ordinary connection timeout.
- Release the composer immediately after a durable chat save, independently of network delivery or delayed history rendering. Nested/rolled-back transactions do not signal a saved chat.

Code112/versionName1.1.2-experimental, same pinned signing identity, database and protocol 5. Install over the previous signed release without uninstalling. Three seconds is a scheduling window, not a radio delivery guarantee. Discovery, reconnections and weak links still need phone tests; see TESTING.md and THROUGHPUT.md. Experimental prerelease; existing security/licensing/physical-test limitations remain.
