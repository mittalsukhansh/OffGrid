# Identity backup and restore

Open **My contact → Identity backup / restore → Export encrypted backup**. Pick a destination with Android's document picker, enter a strong passphrase of at least 12 characters, and keep that passphrase separately. The `.ogb` file is encrypted using scrypt and AES-256-GCM; there is no password reset or hosted account.

The backup contains signing/encryption identities, contacts, nicknames and verification state. It does **not** contain chat history, media, unread state or received-packet replay evidence. Identity export is not a complete application backup.

To restore, install the new app on a fresh/empty installation, keep mesh stopped, open the same screen, choose Restore, select the file and enter the passphrase. Existing history/outbox causes restoration to fail without replacing identity. Confirm the displayed ID/fingerprint afterwards and test a private message. Stop using the previous installation; concurrent identity clones are unsupported.

Changing app ID/signing certificate still prevents in-place Android upgrades. Export before uninstalling when moving from older debug packages. If you uninstall without an identity backup, your keys/contacts may be lost; even with this backup, local history/files are not recovered. A backup of the app release-signing keystore is a separate owner responsibility and never belongs in a user identity backup.

New exports use OGB2 with authenticated, bounded scrypt parameters. This app still reads OGB1 backups, but the published v1.0 app cannot read new OGB2 files. A database upgraded to schema 6 also cannot be downgraded to v1.0 in place. Keep backups and finish updates before relying on restore.

See [SECURITY.md](SECURITY.md) for format, parameters, threat model, restore epochs and replay limitations.
