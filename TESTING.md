# OffGrid 0.2: physical mesh test

This is a debug-signed test build for Android 8.0 / API 26 or newer. Install the
same `OffGrid-v0.2-test.apk` on every phone. Protocol version 4 requires every
participating phone and relay to use the updated app. Forward secrecy is not
implemented; use test messages and test contacts.

## Install and prepare

1. Download the APK from the GitHub v0.2 prerelease and install it. Android may
   ask you to allow installation from the app used to download it. You can also
   use `adb install -r OffGrid-v0.2-test.apk`.
2. Upgrading an existing installation requires the same signing certificate.
   If Android reports a signature mismatch, do not uninstall until any needed
   test information has been saved: uninstalling deletes local history, keys,
   and identity. This test APK uses the release job's debug certificate,
   which can differ from earlier local or CI APKs. `APK-SIGNATURE.txt` records it.
   The version code is 3 so Android permits
   an upgrade from the earlier version-code-1/2 builds with the same certificate.
3. Label three phones A, B, and C. Record model, Android version, and battery
   optimization settings. Keep automatic date/time enabled; packet admission
   rejects timestamps over two minutes in the future or ten minutes old.
4. Grant Nearby devices/Bluetooth permissions, notifications, and Location where
   older Android requires it. Camera permission is only for scanning contact QR
   codes. Start the mesh on each phone.
5. Scan contacts between A and C, compare the full fingerprints in person, and
   confirm the match. Verify that imported contacts are initially Unverified and
   only change to Verified after the explicit comparison.

## Establish the relay topology

1. With B stopped, move A and C far enough apart (or behind sufficient obstacles)
   that direct delivery does not work. Wait at least 30 seconds for stale peer
   discovery to expire. Record the distance and layout; there is no fixed BLE range.
2. Turn B on between them, within range of both. Send uniquely numbered private
   messages A to C and C to A. Confirm each appears once and the sender reaches
   Delivered. Public broadcasts should appear once but never claim Delivered.
3. Stop B again and confirm fresh A/C messages no longer reach Delivered. Restart
   B within the ten-minute delivery window and check whether queued messages arrive.
   This control is essential: success without it does not prove multi-hop routing.

## Scenarios

| Scenario | Procedure | What to record |
| --- | --- | --- |
| Screen off | Turn B's screen off, wait 5 minutes, send both ways. Repeat after 15 minutes. | Delivery, delay, foreground notification, battery settings. |
| Radio recovery | Toggle B's Bluetooth off and back on. Send while disconnected and after reconnecting. | Discovery recovery, queued/forwarded/delivered transitions, duplicates. |
| Movement | Move B out of range, queue fresh messages, then bring B back within 10 minutes. | Whether the route recovers and messages appear once. |
| Recipient restart | Restart C's app/process after receiving a message. | History retained; duplicate originals do not create another bubble. |
| Sender restart | Queue a message on A, kill its process, reopen and restart the mesh. | Queue survives and delivers before its deadline. |
| Relay restart | Queue traffic through B, kill B's process, reopen and restart its mesh. | Valid pending packets recover; no unlimited duplicate forwarding. |
| Timeout | Keep C unreachable for more than 10 minutes. | Unconfirmed private message becomes Expired when status refreshes. |
| Late receipt | Restore connectivity after a message expired, where a valid receipt was already pending. | A valid receipt may confirm delivery; expiry itself does not prove non-receipt. |

For process-death tests, obtain the PID with `adb shell pidof com.example.offgrid`,
then use `adb shell run-as com.example.offgrid kill -9 PROCESS_ID` with this
debuggable APK. Record whether Android restarts the foreground service and whether
the PID changes. Swiping away the screen or using `am kill` may leave a foreground
service alive, so neither proves process death. Android's **Force stop** deliberately
blocks automatic restart until the user opens the app; test it separately.

## Capture a failure

For each failure, record the phone, message number, direction, time, screenshots
of status/history, and the steps that reproduce it. If ADB is available, start
log collection before reproducing:

```sh
adb -s PHONE_SERIAL logcat -v threadtime OffGridMesh:D OffGridCrypto:D AndroidRuntime:E '*:S' > phone-B.log
```

Use the appropriate device serial and filename for each phone. Logs can include
device identifiers and diagnostic information; review them before sharing publicly.

Automated CI covers parsing/signature checks, TTL-bound signing behavior,
duplicate/replay handling, persistence, crypto tampering, QR encoding, and migration.
It does not establish physical Bluetooth range, manufacturer-specific screen-off
behavior, or successful A-B-C routing. Current development adds dedicated FrameCodec fragmentation/reassembly and relay
scheduling tests, plus three-logical-node protocol/storage tests. Those tests do not
retroactively change the published v0.1 APK or establish physical mesh reliability.

## Offline sharing checks for 0.2

- Rename two contacts to the same nickname. Confirm the warning and that their IDs
  and verification remain distinct after restarting.
- Unsend a private text while connected; check Requested then confirmed Unsent.
  Repeat while the recipient is offline, including withdrawal arriving before the
  original. After ten minutes without confirmation check Unsend unconfirmed and
  retry. Confirm public chat has no unsend action.
- Send a photo; verify a resized JPEG preview, progress, and a final receipt only
  after the recipient has the complete image. Toggle the relay's radio mid-transfer
  and verify progress resumes rather than starting a second conversation entry.
- Try an MP4 below 256 KB / ten seconds, then one above each limit. The invalid clip
  must be rejected, not silently queued. There is no video transcoder in this build.
- Unsend a partially transferred and a completed attachment. Check that the app
  cannot open it afterward; exported files/screenshots are outside this guarantee.

All nodes must use the same version 4 APK. Updating from an APK signed with
another debug certificate may require uninstalling; that deletes local identity
and data. Preserve needed test information first. This is a test prerelease, not a
production signing or recovery system.

## Conversations, Wi-Fi and diagnostics (0.3-dev)

The v0.2 release APK predates these features. Install the same current development
APK on all phones, with the signing/data-loss precautions above.

1. Receive messages from two private contacts and public chat. Check separate
   conversations sorted by recent activity, unread counts after process restart,
   and notification routing. Opening one contact must not display or mark another
   contact's messages as read. Check rotation and drafts when switching chats.
2. Deny notifications, then enable them in Android settings. Messaging and unread
   counts must work in either case. In the background, notifications must not show
   private text or nicknames. Open a notification and confirm the intended chat.
3. Put phones on the same Wi-Fi access point (internet disconnected). Enable
   Network → Local Wi-Fi sharing on each and start their meshes. Confirm Wi-Fi
   endpoint discovery, exchange private text/images and verify authenticated
   Delivered receipts. A forwarding event alone must not show Delivered.
4. Turn Bluetooth off while Wi-Fi is active. Check continued messages, queues and
   released outbound session slots. Turn Wi-Fi off during a transfer and check BLE
   can resume eligible packets. Repeat stopping/restarting the service and process.
5. Move one phone to another network and back, change Wi-Fi address, deny/revoke
   Android 17 local-network permission and test a hotspot with phones as clients.
   Record Wi-Fi state, discovery/failure messages and successful recovery. Do not
   assume hotspot-host support or discovery on guest/client-isolated Wi-Fi.
6. Compare diagnostics against logs: BLE scanning/advertising, Wi-Fi endpoints,
   queue size, session count, failure/retry delays and packet counters. Confirm one
   dual-transport phone can count as two endpoints without becoming two contacts.
7. Record actual attachment transfer time, screen-off behavior and Wi-Fi/BLE
   fallbacks. Size limits and media pacing are unchanged; do not report improved
   throughput or battery use without measuring it.

Automated coverage adds real-loopback TCP framing/max-batch and malformed/truncated
frame tests, combined-transport state/routing tests, Room conversation/unread/read
race and schema 3→4 migration tests, and Espresso chat switching/diagnostic-screen
checks. These do not exercise physical NSD discovery or access-point behavior.

## Bulk throughput development

Follow THROUGHPUT.md for controlled GATT/CoC/Wi-Fi measurements, reset semantics,
legacy/unknown-route behavior, signed range resumption and native-socket limits.
Check API 26–28 GATT-only behavior and API 29+ CoC with actual phones. Capture PSM
changes, unsupported channels, Bluetooth-off while Wi-Fi remains active, socket
cancellation, malformed stream headers, stalled sockets and 45-second smaller-chunk
fallback. All paths must retain private encryption, whole-file integrity and final
authenticated receipts. Host loopback results are not radio benchmarks.

## Wi-Fi Direct without infrastructure

Follow WIFI_DIRECT.md: leave Wi-Fi radio on but disconnect access points, disable
mobile data, same-LAN sharing and Bluetooth; pair supported phones through Network
→ Wi-Fi Direct. Verify group-owner/client state, WIFI_DIRECT packet counts,
private file integrity and authenticated Delivered. Repeat role reversal, incoming
pairing, rejected/expired permissions, Location-mode requirements, busy/timeout,
rapid stop/start, process kill, screen off, unrelated existing groups and a
three-phone group. Separate actual physical results from the loopback socket,
pure ownership/hello, UI and logical encrypted-range tests. Wi-Fi Aware and
unrestricted multi-group Wi-Fi mesh are not implemented.

## Reproducible physical session capture (1.0 preparation)

Use `python3 scripts/physical-test-session.py --phone A=SERIAL_A --phone B=SERIAL_B --phone C=SERIAL_C --apk offgrid.apk --output work/session.json` on a computer with the three owner-authorized phones connected to adb. The command only reads model/API metadata and hashes the APK; it neither installs nor clears app data and omits device serials from the output. A successful command means setup captured, not tests passed.

On each phone, open **Network → Reset measurements**, perform one controlled test, then **Export measurements**. Android's document picker chooses the destination; no broad storage permission is required. The aggregate report includes model/API, app version, UTC time, elapsed time, transmitted/received wire bytes, packets, sessions and failures. It excludes contact identities, messages, transport addresses and error text. Model and timing information can still identify a test session; review before sharing.

Record A/B/C roles, exact APK hash, transport selection, distance/obstacles, payload bytes/hash, duration, receiver completion, loss/retries and screen state alongside each export. Isolate GATT (CoC disabled), CoC (confirm negotiated transport), same-LAN and Direct (no AP, Bluetooth off) runs. Repeat each run three times. Keep unsuccessful runs. Receiver unique-media goodput is payload bytes divided by completion seconds; the exported wire averages include retries and idle time.

| Required run | Actual phones | Result | Evidence |
| --- | --- | --- | --- |
| A–B–C, A/C outside direct range | Not available to automated environment | NOT RUN | Owner phone session needed |
| GATT vs CoC vs Direct throughput | Not available to automated environment | NOT RUN | Per-radio exports needed |
| Screen off, Bluetooth toggles, movement, process restart | Not available to automated environment | NOT RUN | Observations and timing needed |

Never substitute JVM simulation, loopback sockets or emulator networking for these radio results. Publish measured numbers in THROUGHPUT.md only after attaching actual evidence.

## Immediate identity process-death regression

The emulator CI now builds the immutable protocol-4 reference with a test-only crash fixture, records ten historical attempts, then installs current debug APKs and requires ten current SIGKILL/restart passes. See [investigation](docs/UPGRADE_IDENTITY_INVESTIGATION.md). The fixture reports only public expected state to the host and kills the process immediately after identity construction; it does not flush preferences, sign a packet or stop an Activity first. Reopening must use a different PID and the same signing/agreement fingerprint, with a verified private-key signing proof.

Standalone host command, after installing matching debug/test APKs on an emulator:

`python3 scripts/check-identity-process-death.py --runs 10 --expected-protocol 5 --output work/identity-process-death`

This is an OS-process/Keystore persistence test, not a radio test. Do not confuse a reference-code reproduction with proof of the exact first upgrade incident's cause. Phone work is gated by the [follow-up checklist](docs/REVIEW_FOLLOWUP.md); the owner's Android-7.1.2 phone is below minSdk 26.


## Same-protocol contact trust during reinstall

The opt-in upgrade fixture records whether the baseline contact bundle includes an agreement key and its full fingerprint. Protocol-5→protocol-5 continuity must preserve that verified binding. Protocol-4→5 migration must clear single-key verification; it is a different expectation.

After the ordinary emulator suite, CI seeds current debug state, reinstalls the exact same debug app/test APKs with `adb install -r`, then verifies identity, signing key, verified contact fingerprint/nickname and encrypted history. `scripts/check-same-protocol-reinstall.py` refuses real phones and uploads its transcripts/summary. It is a same-build reinstall regression, not proof of a new release version's upgrade or physical-radio behavior. The original signed version-transition evidence remains separate.


The same-protocol reinstall check passed on API-30 in [run 43940312162](https://github.com/mittalsukhansh/OffGrid/actions/runs/43940312162); [raw evidence](docs/audits/same-protocol-reinstall/README.md). This does not change the physical-device NOT RUN entries above.


## Historical v1.0 two-phone baseline

This preserves the original baseline procedure. For current testing, use the published v1.1 release and the 1.1 retest below.

Use [v1.0 → offgrid-1.0.apk](https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.0) on **both** phones. This is the historical experimental snapshot; 1.0.1-experimental was an unpublished intermediate candidate. Keep the APK unchanged during baseline comparisons. S23 FE is eligible; Redmi 4A on Android 7.1.2 is below the API-26 minimum. Borrow an Android 8+ second phone (Android 10+ on both is needed for BLE CoC).

1. Record model/Android version and APK SHA-256. Install, allow requested nearby-device/location permissions and enable Location services for discovery where Android requires it. Start mesh on both phones. Exchange QR contacts and compare fingerprints directly.
2. **BLE:** disable Wi-Fi and mobile data on both phones; keep Bluetooth on. Send private text in both directions, then a JPEG near the 48 KiB limit. Confirm the recipient sees it and the sender eventually says Delivered. Forwarded alone is insufficient.
3. Reset diagnostics before each run. With BLE bulk streams enabled, confirm whether CoC actually negotiated; then disable bulk streams for GATT comparison. Record the transport used, exact photo bytes, time, failures and diagnostic exports. Do not label a fallback GATT run CoC.
4. **Wi-Fi Direct:** turn Bluetooth off, leave Wi-Fi on, disconnect shared access points/hotspots. In Network → Wi-Fi Direct, enable/discover/connect and accept the system pairing prompt. Confirm group-owner/client state, then repeat text and the same photo. Record WIFI_DIRECT counters and delivery evidence.
5. After basic success, repeat with screens off for five minutes, then Bluetooth toggles and a process restart. Keep failed runs and record recovery. Repeat controlled transfers three times if possible.

Two phones validate a direct link only. Three-phone A–B–C relay testing still requires a third phone with A and C outside direct range. Do not report radio throughput or background reliability until these measurements actually exist.


## Version 1.1 relay and name retest

Owner report: BLE bulk works with v1.0; third-phone relaying did not. Exact models/logs and whether this involved text/media remain unspecified. This is not a measured throughput result or a confirmed root cause.

Install the signed 1.1 experimental update on all three compatible phones **without uninstalling**. Choose a name on each phone. Exchange A/C named QR contacts and compare fingerprints; check names appear in contact/conversation labels and changing a local nickname survives rescanning. B does not need to read A/C private messages or import their QR contacts to relay ciphertext.

Start mesh on A, B and C. Disable Wi-Fi/mobile data for an isolated BLE run. Establish that A/C cannot deliver directly with B stopped; keep them out of direct range. Start B within range of both. Send a new private text A→C, verify C's text and authenticated Delivered at A; repeat C→A. Inspect/export diagnostics on all three. Repeat by introducing C after several minutes of A/B connectivity, then repeat with B restarted and with screen-off operation. Keep within the ten-minute pending lifetime. Test a photo only after plain text succeeds; record GATT versus negotiated CoC. This distinguishes a topology, discovery or receipt failure from media throughput.


## Latency patch comparison (1.1.1)

Before upgrading, record a v1.1 baseline if practical. On each version, start both phones, reset diagnostics and time a short text to receiver appearance and sender Delivered. Repeat with a photo already transferring to check chat priority. Compare nearby versus farther apart; record models, Android version, separation/obstacles, actual transport, exported failures/retries and whether B is involved. Then compare a fixed-size JPEG on confirmed CoC versus paired no-router Wi-Fi Direct. Change one variable at a time and keep failures. The 1.1.1 patch removes inventory scheduling gaps and prioritizes the next batch; it does not establish a numerical speedup or improve radio range by itself.


## Three-second queue and unsend retest (1.1.2)

Update all phones without uninstalling. Queue text A, then text B while A is stalled. Check B gets its own opportunity after A's three-second send window, while A stays queued/retryable; do not interpret this window as proof of delivery or an automatic failure. Repeat by unsending the active A: the connection should cancel immediately and B should proceed when a peer is available. Try a single slow message without B to confirm it is not repeatedly aborted every three seconds. Repeat with CoC enabled/disabled and with a photo transferring; capture actual counters, timing and failures. Watch for late A reappearing after an authenticated unsend. Confirm another reachable peer/transport remains usable. Type the next text while history loads and verify the composer does not wait for a delivery receipt.
