# Session protocol assessment — 19 November 2026

This is a source/documentation assessment, not a cryptographic audit, Android
integration test or change to the live protocol. OffGrid still uses per-message
ephemeral-static ECDH; compromising the recipient agreement key exposes recorded
traffic. Forward secrecy and post-compromise security remain unimplemented.

| Candidate | Pinned evidence | Fit and constraints |
| --- | --- | --- |
| [noise-java](https://github.com/rweather/noise-java/tree/49377b6dfc6a1e75740bce2318118291a57c0d6e) | MIT; default-branch head commit dated 2 August 2022; repository not archived at assessment | Java/JCA implementation with primitive fallbacks. Its age requires maintenance/advisory and Android API 26 checks. Noise supplies handshake/transport patterns, not an asynchronous prekey service or a message ratchet. |
| [libsignal](https://github.com/signalapp/libsignal/tree/956e1cab1d68291c92f2fcca3c29ec6c3b367e28) | AGPL-3.0; head dated 18 November 2026; repository not archived | Double Ratchet implementation and Android JNI bindings. README explicitly says use outside Signal is unsupported and APIs can change without notice. Rust/native integration, JDK 21 and NDK requirements add operational cost. |

The pinned libsignal Android build specifies minSdk 23, compileSdk 34,
Java 21 and NDK 28.0.13004108. The declared minimum fits OffGrid's API 26 floor;
that is not proof of runtime, packaging or toolchain compatibility. See its
[README](https://github.com/signalapp/libsignal/blob/956e1cab1d68291c92f2fcca3c29ec6c3b367e28/README.md),
[Android build](https://github.com/signalapp/libsignal/blob/956e1cab1d68291c92f2fcca3c29ec6c3b367e28/java/android/build.gradle)
and [license](https://github.com/signalapp/libsignal/blob/956e1cab1d68291c92f2fcca3c29ec6c3b367e28/LICENSE).
Noise evidence is its pinned
[README](https://github.com/rweather/noise-java/blob/49377b6dfc6a1e75740bce2318118291a57c0d6e/README.md).
Neither candidate's license resolves permission for inherited OffGrid code.
AGPL obligations need an explicit compatibility/distribution review before adoption.

## Integration requirements

1. Define whether an initial conversation requires both endpoints online. Interactive
   handshakes must survive A–B–C relaying; asynchronous delivery needs authenticated
   offline prekeys, their QR/discovery distribution and bounded one-time consumption.
   No hosted service or SMS login is assumed.
2. Bind sessions to the existing signing identity and contact fingerprint. Specify
   agreement-key changes, contact re-verification and protocol-version negotiation.
3. Commit ratchet/session state and encrypted outbox atomically. Test crash/restart
   at every nonce allocation, key advance and enqueue boundary. Bound skipped keys
   for loss, duplicates, reordering, partitions and long offline periods.
4. Define backup/restore and clone behavior: stale session state must never reuse
   keys or nonces. Prefer invalidating sessions and authenticated rekey after identity
   restore. State the consequences of deleting old keys and losing history access.
5. Bind receipts, unsend controls and media manifests to the correct session,
   sender and purpose; retain authenticated delivery semantics across rekeys.
6. Test published vectors, cross-implementation interoperability and API 26+ JNI/JCA
   compatibility before an independently reviewed migration. Measure memory and
   battery costs on real devices. Java buffer clearing cannot guarantee erasure.

## Decision

Do not add either library to the live wire protocol in this maintenance release.
The concrete next artifact is a separate offline-prekey/session design and isolated
compatibility prototype, followed by external cryptographic review. No library
binary, protocol bump, forward-secrecy claim or dependency audit result is introduced
by this assessment. Physical BLE/Direct measurements remain the immediate field priority.
