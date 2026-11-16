# Android application identity and release signing

## Permanent distribution identity

- Release application ID: `io.github.mittalsukhansh.offgrid`.
- Debug application ID: `io.github.mittalsukhansh.offgrid.debug`.
- Debug label: **OffGrid Debug**; debug version names end with `-debug`.
- Internal Java/Room namespace remains `com.example.offgrid`. It is not the
  installed application's ID; retaining it avoids an unrelated class/schema rename.
- Public signing metadata: [release-identity.json](signing/release-identity.json).
- Public certificate: [offgrid-release-cert.pem](signing/offgrid-release-cert.pem).

The application ID and release certificate together establish upgrade continuity.
Keep both fixed for future direct-APK releases and increase version codes. Do not
regenerate a key simply because a build fails or a workspace is replaced.

The new release is a separate app from earlier `com.example.offgrid` APKs. Android
cannot install it as an in-place upgrade of those builds or automatically give it
the old app's data/Keystore keys. Keep the old app installed to retain that data;
there is no implemented cross-app history/identity importer. Exchange the new
app's contact QR again. Debug and release also have separate data/identities.
Do not run both meshes concurrently on the same phone when testing radio behavior.

## Keystore custody and backup

A persistent PKCS12 key with alias `offgrid-release` has been generated. Its public
certificate/fingerprint are pinned in Git; its private key and passwords are not.
The private handoff directory in the current workspace is
`/workspace/work/offgrid-signing/`:

- `offgrid-release.p12`: password-protected private release keystore.
- `credentials.json`: store/key passwords and alias. Treat this as a secret.
- `OFFGRID_KEYSTORE_BASE64`, `OFFGRID_STORE_PASSWORD`, `OFFGRID_KEY_PASSWORD`:
  private files containing the exact GitHub secret values, with no trailing newline.

An owner-only backup bundle is available in this workspace at
`/workspace/work/offgrid-signing-owner-handoff.zip`. It contains the private key
**and passwords**; never publish that ZIP.

Back up the key and credentials to owner-controlled secure storage before this
workspace is discarded. Keep an offline backup and test recovery. A GitHub secret
is not a retrievable backup; losing the original key can block future direct-APK
updates. Anyone with the key and passwords can sign an APK as this publisher.

Never commit these files, put them in public artifacts, paste their contents into
issues, or print them in CI logs. `.gitignore` excludes common signing key formats
and local signing properties. Only the certificate and its digest are public.

## Local release build

Use JDK 25 and the Android SDK setup described in README. Set these environment
variables securely in your shell/session or password manager:

```text
OFFGRID_KEYSTORE_FILE=/absolute/private/path/offgrid-release.p12
OFFGRID_STORE_PASSWORD=<store password>
OFFGRID_KEY_PASSWORD=<key password>
```

The alias is fixed to `offgrid-release`. The file type is PKCS12.

```sh
./gradlew --no-daemon --no-configuration-cache assembleRelease
python3 scripts/verify-release-apk.py app/build/outputs/apk/release/app-release.apk
```

The verification script checks application ID, non-debuggable status, APK signature
and exact pinned signing certificate. Optional `--version 0.3-dev` checks the
version string; `--signature-output APK-SIGNATURE.txt` records public signature data.
Use it before distributing a local release, not just in CI. Gradle refuses a release
build when signing inputs are absent; it never falls back to a debug signing key.
Disable the configuration cache for secret-bearing release builds.

## GitHub Actions setup: owner action required

The connected integration returned HTTP 403, **Resource not accessible by
integration**, when attempting to access the repository's Actions secret public
key. It cannot provision repository signing secrets. This is a GitHub permission
restriction, not an automatic approval-review rejection.

As `mittalsukhansh`, open:
<https://github.com/mittalsukhansh/OffGrid/settings/secrets/actions>

Create these repository secrets from the corresponding private handoff files:

| Repository secret | Value |
| --- | --- |
| `OFFGRID_KEYSTORE_BASE64` | Contents of the private `OFFGRID_KEYSTORE_BASE64` file |
| `OFFGRID_STORE_PASSWORD` | Contents of the private `OFFGRID_STORE_PASSWORD` file |
| `OFFGRID_KEY_PASSWORD` | Contents of the private `OFFGRID_KEY_PASSWORD` file |

Alternatively, with your own GitHub CLI authentication that has repository Actions
secrets write access, run these commands locally. The integration's current
credential cannot execute them successfully:

```sh
gh secret set OFFGRID_KEYSTORE_BASE64 --repo mittalsukhansh/OffGrid < /secure/handoff/OFFGRID_KEYSTORE_BASE64
gh secret set OFFGRID_STORE_PASSWORD --repo mittalsukhansh/OffGrid < /secure/handoff/OFFGRID_STORE_PASSWORD
gh secret set OFFGRID_KEY_PASSWORD --repo mittalsukhansh/OffGrid < /secure/handoff/OFFGRID_KEY_PASSWORD
```

Do not create a fresh keystore on each runner or during every release. Only trusted
maintainers should be able to trigger publishing or modify signing workflows.
Protect the default branch and release workflow against unreviewed changes.

## What the release workflow does

The manually dispatched **Publish test APK** workflow requires a draft release and
an exact tested commit reachable from `main`. It restores the same private keystore
from GitHub secrets, builds `assembleRelease` with Gradle configuration caching
disabled, and verifies the non-debuggable package, tag/version agreement and pinned
certificate before upload. It publishes checksums/signature metadata and removes
the temporary keystore even when a step fails. Gradle cache reuse is disabled in
this signing job. Normal build/PR CI uses debug signing and never receives the key.

Missing secrets or a mismatched certificate fail the workflow; it does not publish
an unsigned/debug replacement or change the public pin automatically. Old v0.1/v0.2
assets remain historical snapshots. Do not overwrite them with the new package.

## Validation and remaining checks

Local build/verification can establish package/signing correctness and repeat-key
use. An actual signed vN→vN+1 installation with retained identity/history still
requires a connected Android device/emulator and should be recorded before broad
distribution. GitHub publication with the persistent key remains blocked until the
owner provisions the three secrets. App signing does not resolve the separate
upstream licensing permission gap in LICENSE and UPSTREAM_PERMISSION.md.
