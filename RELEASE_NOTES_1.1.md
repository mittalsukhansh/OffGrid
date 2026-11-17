# OffGrid 1.1 — experimental

- Choose a device display name at first launch or edit it in My contact. Signed QR contacts include it and prefill contact labels; existing local nicknames stay yours. Names can repeat; verify fingerprints.
- Improve relay forwarding fairness: bounded retries per logical neighbor and a reserved forwarding opportunity for newly appearing relays. Diagnostics clarify that peer counts are direct neighbors. Includes post-1.0 expiring inventory hints and bounded retries.
- Includes reserved replay capacity for verified/imported contacts, schema 6 migration, and OGB2 backups with authenticated bounded scrypt parameters and OGB1 read compatibility.
- Signed with the existing release key, same application ID, versionCode 110, versionName 1.1-experimental; wire protocol remains 5. Install over v1.0 or the 1.0.1 candidate without uninstalling. Older apps cannot read OGB2 backups or named QRs; update all phones before named QR exchange. Schema 6 cannot be downgraded to v1.0 in place.

The owner reports direct BLE bulk success and failed three-phone relaying on v1.0. No phone benchmark or confirmed field fix is claimed. Start mesh on all three phones; put B between A/C with A/C out of direct range. Retest text/Delivered first, then photos and screen-off/restarts. See TESTING.md.

This is an experimental prerelease, with no independent security audit or full forward secrecy. Upstream licensing permission remains unresolved. Automated source and signed-upgrade results are linked in DECISIONS.md after completion; they do not prove radio reliability.
