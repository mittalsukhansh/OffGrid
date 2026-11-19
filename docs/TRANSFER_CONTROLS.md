# Attachment controls and delivery details

In a conversation, tap **Transfer controls** beneath an outgoing attachment.
The details screen shows recipient-confirmed bytes/blocks and a snapshot of local
forwarding state. Tap **Controls** for the available actions. Outgoing text messages
have a **Delivery details** button; received attachments show validated local progress.
Reopen details to refresh the snapshot. Progress bars follow persisted block progress.

- **Pause sending:** retains the attachment, confirmed-block bitmap and pending
  packets on this phone. A Room flag survives rotation, process death and restart;
  paused packets are filtered from the relay outbox and the media pump stops creating
  new packets. Active local attempts are cancelled when the outbox is reconciled.
- **Resume sending:** keeps the same message and confirmed blocks, forwarding counts
  and deadlines. It does not grant a new relay budget. Pausing does not extend the
  ten-minute unaccepted-offer deadline or the one-hour transfer lifetime.
- **Cancel local sending:** removes associated queued offers, queries and chunks,
  stops active local attempts and retains the encrypted source file for another try.
  It is separate from unsend; relays and recipients may already hold bytes.
- **Send again as new attachment:** available after failure/cancellation. It queues a
  new signed message/sequence and starts progress at zero. A confirmation explains
  that the contact might receive a duplicate. Creating it and cancelling the old
  transfer share a transaction; failure cannot leave a half-created retry.

Controls apply to outgoing transfers only. Completed/unsent attachments cannot be
resumed or retried through these controls. An authenticated completion receipt that
arrives after pause/cancellation still establishes Delivered, clears remaining work
and takes precedence over the local sending state. Late ordinary status packets
cannot unpause, resurrect a cancelled transfer or downgrade Delivered. Unsend still
uses its separate author-bound request/tombstone mechanism and erases local bytes.

## Explanation limits

Details distinguish mesh stopped, no recently seen neighbor, a live attempt,
busy sending slots, retry cooldown, initial bulk inventory exchange, incompatible
packet size/transport, forwarding-budget exhaustion and expiry. Nearby peers are
direct neighbors, never proof of a route, hop count or recipient delivery. A cooldown
is an observed peer's timer, not an estimated delivery time. Snapshots and block
progress are not end-to-end completion receipts.

Pause/cancel is local and best-effort: packets already handed to a radio or another
relay may arrive. Room migration6→7 adds `media.paused` and a clear-metadata
`pending_messages.transferId` association, preserving encrypted contents/progress.
It backfills existing offers/chunks. Old queued query/offer-wrapper controls created
before this upgrade have no recoverable plaintext association in SQL and may still
leave the phone; the paused pump does not send new data or re-offers in response.
New controls carry a durable association and are filtered/cancelled with the transfer.
Protocol5 is unchanged. Do not downgrade an installation with schema7 to an older APK.

Automated tests cover local policy, real Room/crypto and UI lifecycle. Physical BLE,
CoC/Direct pause timing, radio cancellation and A–B–C behavior require phone tests.
