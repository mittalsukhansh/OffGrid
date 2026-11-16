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
