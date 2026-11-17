# Transport throughput and measurement

## Implemented in current development source

The five-second, two-chunk media pump has been replaced by transport-specific,
monotonic pacing. The file/bitmap format still uses **640-byte canonical blocks**.
New recipients advertise `ranges: true` inside their authenticated encrypted
media-status reply. Once that capability is received, a sender can bundle adjacent
blocks into one signed encrypted `media_range` control packet:

| Path | Maximum file payload per chunk | Minimum interval across outgoing transfers | In-flight canonical blocks |
| --- | --- | --- | --- |
| GATT / legacy peer / unknown route | 640 B | 2,500 ms | 4 per transfer |
| BLE L2CAP CoC | 2,560 B (4 blocks) | 500 ms | 16 per transfer |
| Local Wi-Fi TCP | 7,680 B (12 blocks) | 200 ms | 48 per transfer |
| Wi-Fi Direct TCP | 7,680 B (12 blocks) | 200 ms | 48 per transfer |

These are conservative initial limits, **not measured radio speeds**. GATT keeps
the previous nominal preparation ceiling of 256 payload B/s. The two stream paths
have nominal policy ceilings of 5,120 and 38,400 payload B/s respectively, before
crypto, connection setup, acknowledgements, retransmission, flow control or other
traffic. Do not present those ceilings as observed app throughput.

Outgoing transfers rotate fairly through a shared per-profile pacing gate. The
mesh maintenance interval remains five seconds; the media task wakes independently
at its active profile's interval and is woken after relevant accepted messages.
Idle media polling returns to five seconds. Pacing uses monotonic elapsed time;
persisted part retries/deadlines keep their existing wall-clock semantics.

## Route selection and fallback

The sender uses a recent authenticated packet from the contact arriving at the
original default TTL as a direct-ingress **performance hint**, with a 15-second
expiry. It also checks that the corresponding transport is currently usable.
TTL is mutable and is not a proof of a direct route or identity. Signature/key
checks, recipient authorization and the capability reply remain required.
Unknown, stale or evidently relayed routes use the conservative GATT profile.

The manifest and saved bitmap stay block-based. Switching Wi-Fi → CoC → GATT
therefore does not reset a file or reinterpret existing part indexes. Existing
unconfirmed stream ranges may wait for the 45-second retry deadline before their
missing blocks are regenerated into smaller chunks. An old peer that does not
advertise `ranges` always receives the old `media_chunk` form at GATT pacing.

GATT retains its **2,048-byte packet/reassembly cap**. The relay skips oversized
packets for GATT candidates, preventing large stream chunks from blocking ordinary
chat behind them. Stream framing accepts at most 32 KiB per packet and 20 packets
per session; only encrypted control envelopes have the larger model limit. Normal
public/private/ACK/media-offer packets retain the 2,048-byte limit. Ordinary private
chat keeps its existing plaintext/ciphertext bounds. All signed encryption domains
and normal authenticated Delivered receipts are preserved.

Old relays cannot forward larger control envelopes. Opportunistic stream ranges
are intended for upgraded direct peers; multi-hop/unknown hints stay conservative.
Mixed-version networks must be tested explicitly rather than assuming a path can
carry the largest packet. File completion still requires full length/hash/format
validation and encrypted save. Progress bitmaps and stream transfer-confirmation
bytes do not mark a message Delivered.

## L2CAP credit-based channels

On Android 10/API 29+, enabled BLE transport attempts
`listenUsingInsecureL2capChannel()` and advertises its dynamic LE PSM in a scan
response. Primary advertisements retain the existing service UUID/filter. Service
data is four bytes: `0x4f`, extension version `1`, big-endian PSM. The accepted
LE dynamic PSM range is 128–255.

The client calls `createInsecureL2capChannel(psm)`. The link requires no Bluetooth
bonding; **insecure** is the Android socket API's link-security mode, not an
alternative message crypto protocol. Packet signatures and private end-to-end
AES-GCM remain responsible for message authenticity/confidentiality. Public chat
and routing metadata remain observable.

The byte stream uses the same bounded OGL1 length/batch framing and byte transfer
confirmations as local Wi-Fi. Reads use `readFully`; neither Bluetooth SDU nor
InputStream read boundaries are treated as packet boundaries. There are at most
six CoC IO threads: one listener, two inbound handlers, and up to the relay's three
outbound sessions. No IO task backlog is allowed. A 25-second deadline closes
blocked connects/reads/writes; payload admission occurs on the mesh worker.

API 26–28, unavailable channels, unsupported advertisements and connection failures
continue to use GATT. A failing CoC endpoint has a 60-second cooldown. Remaining
small packets can fall back immediately; oversized file ranges are regenerated
into GATT-size blocks by the source retry path. Stop/radio change closes native
sockets, stale-generation payloads are rejected, and completion still releases
relay session slots. Each batch currently opens/closes a stream; persistent channel
reuse should be evaluated only after phone measurements identify its value.

## Flood/resource limits

The original GATT/normal-message budgets stay in place. Fast-stream ingress has
separate bounded pre-crypto limits: global burst 40 / 20 packets per second and
per endpoint burst 20 / 10 per second. Signed control traffic on stream ingress
has a per-identity burst 20 / 10 per second allowance. Ordinary signed chat still
uses the original identity budget. Both classes retain signatures, namespaces,
time/replay checks, bounded queues and storage/window limits.

These limits can constrain nominal policy ceilings. They are an explicit CPU/abuse
budget, not a promise of denial-of-service or Sybil resistance. Further increases
must be justified by CPU/memory/latency measurements and physical tests.

## Measurements available in the app

Wi-Fi Direct now supplies the AP-free Wi-Fi path; see WIFI_DIRECT.md. Same-LAN
Wi-Fi remains optional. Each path has its own counters/profile; all stream paths
share bounded pre-crypto and authenticated-control budgets.

Open **Network** while the mesh runs:

- Per GATT/CoC/same-LAN/WIFI_DIRECT serialized TX/RX packet bytes and packet counts.
- TX wall-time average since reset, session completions/failures and last-session
  duration, including connect/transfer/confirmation time.
- Active media's receiver-confirmed block progress and wall-age payload average.
- Existing queue size, peer freshness, failures and retry/cooldown information.
- **Reset measurements**, for controlled trials; reset while idle before sending.
- **BLE bulk streams (L2CAP)** toggle. Turn it off, and turn Wi-Fi off, to measure
  the GATT baseline. On API 26–28 this toggle is unavailable.

Serialized packet counts include JSON/encryption envelopes, relays and retries,
but exclude TCP/radio framing. They are not unique media goodput or evidence of
Delivered. Reset discards in-flight outbound session measurements; counters reset
with the service. Media wall-age averages include initial waiting/retries and are
not an instantaneous link rate. Active transfer diagnostics omit plaintext content.

## Baseline and repeatable benchmarks

Before the transport changes, a host loopback framing baseline was recorded in
[benchmarks/host-framing-before.json](benchmarks/host-framing-before.json).
It sends 2 MiB over localhost with length framing and one-byte confirmations for
640/2,560/7,680-byte payloads. It excludes Android, encryption, mesh scheduling,
real Wi-Fi and BLE. A post-change run is in
[benchmarks/host-framing-after.json](benchmarks/host-framing-after.json).
Run it again with:

```sh
python3 scripts/benchmark-stream-framing.py --output benchmarks/my-host-run.json
```

Those runs are a reproducible host microbenchmark, not evidence of real mesh speed
or an improvement from the Android changes. Use physical phones for the following
protocol before tuning the initial limits:

1. Record APK commit/version, both phones/Android versions, file hash/size, distance,
   signal, Wi-Fi access point, power/battery settings and whether screens are on.
2. Use the same non-sensitive compressed image or MP4 within existing size limits.
   Exchange contacts first; wait for idle queues; reset measurements on both phones.
3. **GATT baseline:** disconnect Direct groups, disable Wi-Fi Direct, same-LAN
   sharing and BLE bulk streams on both. Send the file.
   Record time from accepted send to authenticated Delivered, confirmed file bytes,
   session/error/retry counters, receiver hash and any failures.
4. **CoC:** keep Wi-Fi off, enable BLE bulk on both API 29+ phones, verify discovery
   and CoC counters actually advance. Repeat the same file and measurements.
5. **Same-LAN Wi-Fi:** keep Direct disabled and enable LAN sharing on both phones
   on the same access point, verify Wi-Fi
   counters advance, and repeat. Keep media limits and measurement definitions fixed.
6. **Wi-Fi Direct:** turn same-LAN sharing off, disconnect infrastructure Wi-Fi,
   enable Direct and pair supported phones with mobile data/Bluetooth off. Verify
   WIFI_DIRECT counters move and authenticated Delivered is received. Record
   Android's negotiated group roles and repeat the same file/measurements.
7. Run at least three trials per path, alternating order to reduce warm-up bias.
   Separate discovery/setup time, completion latency and sustained file goodput.
   Report median and range, including failures; do not silently discard timeouts.
8. Repeat with screen off, bidirectional files, Bluetooth/Wi-Fi toggles, movement,
   service/process restart, and a three-phone A–B–C route. Verify file hashes,
   cancellation/unsend, authenticated receipts and progress across fallback.
9. Capture CPU, memory and energy evidence before increasing quotas/windows or
   introducing persistent channels. Label unsupported/failed CoC trials as GATT
   fallback, not CoC results.

No physical-phone throughput result has been produced by this change. Automated
checks cover framing, advertisement encoding, pacing/metrics, relay eligibility,
large signed/encrypted range authorization/integrity/transaction rollback, and
resumption using the canonical blocks. Android API availability and compilation
are not a substitute for real CoC negotiation, hardware flow control or vendor
socket lifecycle testing.


## App latency follow-up (1.1.1)

The owner reports text and media feel slower with increased distance, without timings or confirmed third-phone routing. Distance-related loss/reconnection must be measured on phones. This patch does not increase chunk sizes, radio duty, send rates or range.

Queued delivery receipts go first, then private/public text, then bulk/control/media, then key announcements; FIFO is preserved within each priority. Chat/receipts do not wait for the first inventory exchange. Bulk may wait once, for at most five seconds, to allow inventory optimization. Refreshes after that initial deadline or a successful challenge no longer create repeated five-second forwarding gaps, including when refresh replies are lost. Endpoint identity and inventory hints still require a valid current signed nonce response. Packet signatures, replay/flood admission and receipt authentication are unchanged.

Priority takes effect when forming the next batch; it cannot preempt a batch already being transmitted. Heavy chat can reduce bulk throughput. Existing retries, expiry and queue limits remain. Host regressions prove scheduling/deadline behavior, not a measured end-to-end speedup.

For larger files on two nearby supported phones, try Network → Wi-Fi Direct with no router: pair both, start mesh, verify WIFI_DIRECT counters and compare the same file against confirmed CoC. Reset/export metrics and record payload bytes, completion time and failures. These measured results are needed before changing radio pacing or connection lifetime.
