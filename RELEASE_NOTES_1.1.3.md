# OffGrid 1.1.3 — experimental merged UI update

- Includes the merged dark chat/conversation interface, contact shortcuts, message badges, status indicators, copyable node ID and attachment/send icons. Added the previously missing resources and fixed Android 8/AppCompat tint compatibility.
- Preserves named signed QR contacts, local nicknames, encrypted persistent conversations/media, authenticated delivery states, three-second first send opportunities and immediate cancellation of active unsent work. Composer release still follows durable local save.
- Shows TTL as a mutable packet hint rather than claiming direct/hop provenance. Bounded contact shortcuts retain the selected contact/full picker; background status animation pauses. QR test decoding matches the real QR-only scanner.

Signed with the existing certificate and application ID; versionCode113/versionName1.1.3-experimental, unchanged Room schema6 and wire protocol5. Install over the previous signed release without uninstalling. The actual published 1.1.2 APK is the signed upgrade baseline. No new radio speed/range/physical-device reliability claim is made. This remains an experimental prerelease with the existing security/licensing/offline-backup limitations; see TESTING.md and DECISIONS.md for actual validation evidence.
