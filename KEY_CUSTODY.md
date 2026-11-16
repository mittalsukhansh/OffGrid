# Release signing-key custody

The environment private release key and its local handoff archive have been checked: the key signs a challenge, the pinned public certificate verifies that signature, and the archive contains the matching key and credentials. The certificate SHA-256 is:

`cc5455152701c5ef521e5a08dc4cbc7795c6897cf09eff375740391e4da2cf27`

**Owner PC copy confirmed:** the owner reported the matching decrypted archive hash and successful creation of a passphrase-encrypted copy on Windows. A separate offline-media restore/ejection is still pending confirmation. Environment possession alone did not establish owner custody. The archive contains the private signing keystore and its passwords. Keep it out of repositories, public links, messages and Actions artifacts.

## Recipient-encrypted Windows handoff

1. Install official `age`/`age-keygen` on the owner's PC, or download the official portable Windows ZIP and verify its release checksum. Run `age-keygen` using its full executable path if PATH is not refreshed.
2. Generate a transfer key locally. Keep the file containing `AGE-SECRET-KEY-...` private on the PC. Share only the public recipient beginning `age1...` with this task.
3. The environment encrypts the verified archive to that public recipient using official `age`. Only recipient-encrypted ciphertext is transferred. No private transfer key, passphrase or release-key password is requested in chat.
4. Decrypt on the PC and verify the archive SHA-256 supplied with the handoff. Check the keystore certificate against the pin above; retain its credentials together in protected storage.
5. Create a passphrase-encrypted offline copy on the PC (`age -p` prompts locally), copy that encrypted file to offline media and keep its passphrase separately. Verify the offline copy can decrypt and yields the same archive hash. A second copy on the same disk is not an offline backup.
6. Confirm completion without sending private keys, credentials or transfer secrets. Until then, the custody status remains pending.

The designated chat-download directory is unavailable/read-only in this cloud environment. Do not claim a download link or local owner copy exists merely because a file exists in the cloud workspace. The current transfer preparation uses a public recipient generated on the owner's Windows PC.

## Release path

GitHub Actions-secret provisioning was rejected with HTTP 403. The documented local-signing/public-artifact-verification route in SIGNING.md remains available while that is unresolved. Private keystore/credentials stay off GitHub. A permanent signing-path choice and private-key custody are owner responsibilities; no working future-signing guarantee follows from the presence of a public certificate alone.
