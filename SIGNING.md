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
distribution. Actions builds that sign from secrets remain blocked until the owner provisions
the three secrets. The public pre-signed artifact route below supports local signing. App signing does not resolve the separate
upstream licensing permission gap in LICENSE and UPSTREAM_PERMISSION.md.


## Public pre-signed candidates and experimental releases

When Actions-secret provisioning is unavailable, build locally with the same private release keystore. The signed APK embeds its exact source commit in `assets/offgrid-build.json`. Only public signed APK/test fixtures, checksums, public certificate and metadata go on a temporary `release-assets/v1.0` branch, as one commit whose parent is the exact tested source on main. Never stage a keystore, credential JSON, password, identity backup or private handoff ZIP.

The `publish-pre-signed.yml` workflow now accepts temporary `release-assets/**` branches and defaults to **candidate only**. It verifies APK hashes, package/version/code, pinned app/test certificates, signed source provenance and exact-source successful CI. An API-30 emulator seeds the actual signed baseline, reopens it, installs the candidate with `adb install -r` and verifies identity/contact/history continuity. Its installable-candidate artifact is uploaded only after success. Nothing is published to Releases unless the manifest explicitly sets boolean `publish: true`; new publication labels must end in `-experimental`. Keep baseline and candidate test fixtures synchronized with `UpgradeContinuityTest` fixture version 2 so unchanged dual-key contact verification is preserved, while legacy single-key upgrades require reverification.

Schema 2 manifest fields: `schema: 2`, `version`, `versionCode`, `sourceCommit`, `baselineVersion`, `baselineVersionCode`, `baselineSource`, optional boolean `publish` (default false), optional `tag` matching the version with or without its `-experimental` suffix, and `sha256` mapping exactly `offgrid-<version>.apk`, `baseline.apk`, `baseline-test.apk`, `upgrade-test.apk` to their SHA-256 values. The new version code must exceed the baseline. Both app APKs must embed the declared source commit; instrumentation APKs must use the pinned certificate and release test package. Stage the actual published baseline app rather than rebuilding it; only its compatible test fixture needs rebuilding. Source must be committed and tested on main before creating the one-commit staging branch. Never merge binary staging branches into main.

A successful candidate check is emulator upgrade evidence, not physical-phone validation, secret provisioning or a reproducible-build guarantee. Keep the private signing backup separately. The historical schema-1 v1.0 manifest remains readable but also defaults to no publication on reruns.


## Published 1.0 evidence

The [1.0 workflow](https://github.com/mittalsukhansh/OffGrid/actions/runs/43923646455) passed actual API-30 emulator baseline reopening and signed install-without-uninstall identity/contact/history assertions. The [release](https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.0) includes APK, pinned public certificate, signature output, checksums and upgrade report. Its immutable application source is `061e5fa8cdf27f8dddd87d83c3313333894c1886`. The initial failed attempt was not published. Physical-device upgrade validation, private-key owner backup/custody and optional Actions-secret provisioning remain separate owner responsibilities.


## Owner-machine custody gate

See [KEY_CUSTODY.md](KEY_CUSTODY.md) for the recipient-encrypted handoff and offline-copy verification. Environment possession and a matching archive have been verified. The owner subsequently confirmed the decrypted Windows archive hash and created a passphrase-encrypted copy; separate offline-media restore verification remains pending. The intermediate candidate used code 101/name 1.0.1-experimental. Published v1.1 now uses code 110/name 1.1-experimental; v1.0 assets remain unchanged.


## Signed 1.0.1 experimental candidate evidence

The generalized [candidate workflow](https://github.com/mittalsukhansh/OffGrid/actions/runs/44059024185) passed an actual signed v1.0/code-100→1.0.1-experimental/code-101 update on API 30, preserving the same identity, verified dual-key contact, nickname and encrypted history. Its publish job was skipped (`publish: false`). [Evidence and artifact link](docs/audits/security-followups/README.md). This is an Actions candidate, not a new GitHub Release or phone-radio result.


## Published 1.1 upgrade evidence

The [v1.1 publication workflow](https://github.com/mittalsukhansh/OffGrid/actions/runs/44063108150) passed a signed API-30 v1.0/code100→1.1-experimental/code110 update before publishing. The [public release APK](https://github.com/mittalsukhansh/OffGrid/releases/tag/v1.1) was downloaded again and checked against the staged hash, pinned certificate, release package/version/code and signed source 16d8592. [Archived evidence](docs/audits/release-1.1/README.md). No private signing material was uploaded; phone-radio reliability remains a separate test.


## Published 1.1.1 latency patch

[Upgrade/publication workflow](https://github.com/mittalsukhansh/OffGrid/actions/runs/44065763471) passed actual API-30 signed v1.1/code110→1.1.1-experimental/code111 continuity before publication. [Exact-source checks](https://github.com/mittalsukhansh/OffGrid/actions/runs/44065006584) passed for 2ba6336. The published APK was downloaded and independently checked for hash, pinned signer, package, version/code and signed source. [Public evidence](docs/audits/release-1.1.1/README.md). No phone throughput/range result is implied.


## Published 1.1.2 queue fairness patch

[Upgrade/publication](https://github.com/mittalsukhansh/OffGrid/actions/runs/44073685324) passed an actual API-30 signed v1.1.1/code111→1.1.2-experimental/code112 install-r, retaining identity, verified contact/fingerprint, nickname and encrypted history. [Exact-source CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/44072897339) passed for 001e9a3 (82 JVM/six host tests and Android runtime suites). The public APK was independently downloaded and checked for hash, pin, package, version/code and source. [Evidence](docs/audits/release-1.1.2/README.md). Real phone cancellation timing remains separate.


## Published merged-UI 1.1.3 update

[Source CI](https://github.com/mittalsukhansh/OffGrid/actions/runs/44102943274) passed for 26ea58b (82 JVM/six host tests, build/lint and Android runtime/UI suites). [Signed upgrade/publication](https://github.com/mittalsukhansh/OffGrid/actions/runs/44103792519) verified actual published v1.1.2/code112→1.1.3-experimental/code113 identity, verified-contact/nickname and encrypted-history continuity on API30 before publishing. The public APK was downloaded and independently checked for hash, pin, package, code/version and signed source. [Evidence](docs/audits/release-1.1.3/README.md). No physical radio result is implied.
