# OffGrid 0.1: physical mesh test

This is a debug-signed test build for Android 8.0 / API 26 or newer. Install the
same `OffGrid-v0.1-test.apk` on every phone. Protocol version 3 requires every
participating phone and relay to use the updated app. Forward secrecy is not
implemented; use test messages and test contacts.

## Install and prepare

1. Download the APK from the GitHub v0.1 prerelease and install it. Android may
   ask you to allow installation from the app used to download it. You can also
   use `adb install -r OffGrid-v0.1-test.apk`.
2. Upgrading an existing installation requires the same signing certificate.
   If Android reports a signature mismatch, do not uninstall until any needed
   test information has been saved: uninstalling deletes local history, keys,
   and identity. This test APK uses the development workspace's debug certificate,
   which can differ from earlier CI APKs. The version code is 2 so Android permits
   an upgrade from the earlier version-code-1 builds with the same certificate.
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
behavior, or successful A-B-C routing. Dedicated byte-level fragmentation and
reassembly tests remain a follow-up; packaging this APK does not imply they were added.
