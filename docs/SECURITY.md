# Security and privacy review (Phase 1–2)

Status: internal design review. **This has not had an external audit.** Commission one
before public release, and focus it on §2.

## 1. Threat model

| Threat | Mitigation | Where |
|---|---|---|
| Passive eavesdropper on Wi-Fi | TLS 1.3, ephemeral ECDHE | `net/TlsTransport.kt` |
| Active MITM at first contact | QR link pins the receiver fingerprint and carries a one-time token. Without a QR code, 6-digit SAS with commit-before-reveal; each MITM attempt has a 1-in-10⁶ chance | `security/SasPairing.kt`, `connectPinned` |
| MITM claiming "I already trust you" to a sender that connected by address | The sender pairs unless it pinned the receiver's fingerprint itself | `SenderSession` (`receiverVerified`) |
| Impersonating a trusted device by name | Trust is keyed by certificate fingerprint. Names are display-only | `security/TrustStore.kt` |
| Strangers spamming transfers ("cyber-flashing") | Unknown senders must pair before an offer is shown. The receiver always has to accept. Per-address rate limit. One transfer at a time | `ReceiverSession`, `ReceiverServer` |
| Replaying a QR token | Single use, short TTL, lockout after 5 failures | `PairingTokenIssuer` |
| Path traversal / disguised names | Sanitizer strips paths, `..`, control and bidi characters, reserved names | `transfer/FileNames.kt` |
| Memory exhaustion via huge frames | Lengths checked before allocation. Bounded chunk size | `protocol/Frame.kt` |
| Disk-fill | Free-space check plus reserve before accepting. Total-size cap | `ReceiverSession` |
| Truncated or corrupted files shown as complete | Whole-file SHA-256 checked before an atomic publish. The state machine forbids COMPLETED without VERIFYING | `ReceiverSession`, `TransferState.kt` |
| Overwriting existing files | Atomic name reservation (`CREATE_NEW`) followed by a replace of our own placeholder | `DirectoryReceiveStorage.publish` |
| Tracking a device across places | No stable ID in discovery. Session IDs rotate. The certificate fingerprint is never advertised | `docs/PROTOCOL.md` §5 |
| Slow-loris on the TLS port | Handshake runs off the accept thread with a 10 s timeout. Read timeouts. Close is bounded | `ReceiverServer`, `TlsStream.close` |

## 2. Known gaps and risks

1. **Custom SAS construction.** It follows a well-studied pattern, but the composition
   (SAS over fingerprints inside TLS) is ours and needs review. Alternative: use the TLS
   exporter (RFC 8446 §7.5) once both platform stacks expose it.
2. **Display names are attacker-controlled.** A nearby device can call itself "Mum's
   iPhone". The UI must show trust status separately from the name.
3. **Identity key storage.** The CLI keeps an unencrypted PKCS#12 file in `~/.airbox`
   (mode depends on umask). The Android app must use AndroidKeyStore and the iOS app the
   Keychain (`kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`).
4. **DoS while waiting for the user.** While a person decides, that one session holds the
   receiver (by design, one at a time). An attacker who has already paired can still be
   annoying, and the user can remove them from trusted devices.
5. **Malicious content.** Integrity is not safety. Received files are not scanned.
   Platform sandboxes apply. Never auto-open received files.
6. **The resume key** includes the sender-supplied metadata. A peer can resume only its own
   `transferId`, and any tampering with the partial file is caught by the final hash.

## 3. Privacy

- No account, no cloud, no analytics in the core. File contents and names are never logged.
  Error codes are the only diagnostics.
- Permissions are requested only when needed: Local Network (iOS), Nearby Wi-Fi Devices /
  Bluetooth (Android 13+), Notifications (Android 13+), Camera (QR scanning only).
- Any future relay must be opt-in, disclosed, and only see end-to-end encrypted bytes.
