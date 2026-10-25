# Phase report

Honest status as of this commit. "Tested" means automated tests in this repo on a Linux
JVM over loopback TLS. **Nothing has been tested on a physical phone yet.**

## Works and is tested

| Area | Evidence |
|---|---|
| Wire protocol v1: framing, JSON messages, forward-compatible decoding | `FramingTest` |
| TLS 1.3 mutual auth with self-signed device certs, fingerprint pinning, impostor refusal | `TransferEndToEndTest.pinnedConnectRefusesImpostor` |
| QR / deep-link pairing with single-use tokens, expiry, lockout | `PairingTest`, `reusedTokenIsRefused` |
| SAS pairing (same code both sides, either side can decline) | `sasPairingShowsSameCodeOnBothDevices`, `sasDeclinedByReceiverSendsNothing` |
| Trusted devices (remember, skip pairing next time) | `rememberedSenderSkipsPairingNextTime` |
| Sender still verifies a receiver it reached by address, even if the receiver trusts it | `senderStillVerifiesReceiverWhenConnectedByAddress` |
| Accept / reject before any bytes flow | `receiverRejectsOffer` |
| Multi-file and empty files; no overwrite, `name (1).ext` | `multipleFilesAndDuplicatesNeverOverwrite` |
| Hostile names sanitized | `FileNamesTest`, `hostileFileNameIsSanitized` |
| Streaming in chunks (no whole-file buffering), per-file SHA-256, atomic publish | all E2E tests |
| Resume after a dropped connection from the kept offset | `resumesAfterConnectionDrop` |
| Pause / resume; corrupted partial detected and re-sent from scratch | `corruptedPartialIsDetectedAndResentFromScratch` |
| Storage check before asking the user | `insufficientStorageIsRejectedUpFront` |
| Cancel cleans up partials | `senderCancelCleansUpPartials` |
| File changed while sending → refused, nothing published | `fileShrinkingDuringSendFailsSafely` |
| Malformed offer rejected before the user is asked | `unknownOfferEntriesAreValidated` |
| Transfer state machine (COMPLETED only after VERIFYING) | `StateMachineTest` |
| Transport selection incl. Bluetooth off, all radios off, permission denied | `ConnectionPlannerTest` |
| ANCS client codec + bridge (iPhone notifications, calls, answer/decline) against a simulated iPhone | `AncsCodecTest`, `AncsBridgeTest` |
| AMS codec (iPhone media control) | `AmsCodecTest` |
| Companion link between paired devices: notifications, reply text, clipboard, battery; feature negotiation; strangers refused; keepalives; transfers alongside | `CompanionLinkTest` |
| Notification privacy policy (opt-in, hidden content, OTP masking) | `NotificationPolicyTest` |
| No-app iPhone web bridge: QR sign-in, text and file upload, Android→iPhone text/files, Shortcut keys, CSRF / DNS-rebinding / lockout protection | `WebBridgeTest` (10 tests) |
| The same page driven in Chromium with iPhone 15 emulation: send text, upload a 3 MB photo, download, copy | Manual Playwright run, files byte-identical. **Not yet run in real iOS Safari.** |
| Desktop CLI sender/receiver works end to end | Manual: 20 MB + text file over TLS, byte-identical (`cmp`) |

Run them: `gradle test` (63 tests).

## Not built yet (and why)

| Item | Status |
|---|---|
| Android app (Compose UI, Sharesheet target, foreground receive service, NSD, MediaStore) | **Not started.** The Android SDK can't be downloaded in the environment this was built in, so nothing Android-specific could be compiled. `:core` is plain Kotlin/JVM targeting Java 17 bytecode, so an Android module can depend on it directly. |
| iOS app (SwiftUI, Share Extension, Bonjour, Swift port of the protocol) | **Not started.** It needs macOS/Xcode. `docs/PROTOCOL.md` is written so the port can be done against the CLI. |
| LAN discovery (mDNS) | Specified (PROTOCOL §5), not implemented. The CLI uses the QR link or a manual address. |
| Wi-Fi Aware, Wi-Fi Direct, hotspot, BLE, NFC transports | Planned in `ConnectionPlanner`; no radio code exists. Wi-Fi Aware iPhone ↔ Android is the top-priority spike. |
| Quick Share coexistence | Only possible as an ordinary share target; nothing to build beyond the Android Sharesheet target. |
| Optional compression | Not built. Originals are always sent byte-for-byte. |
| Adaptive chunk size / concurrency | The chunk size is negotiated; nothing adapts yet. It needs device benchmarks first. |
| Performance benchmarks | **None on devices.** Loopback CLI throughput (~35 MB/s on a shared cloud VM, TLS + SHA-256 on both ends) means nothing for phones. Don't quote it. |
| History screen, settings, onboarding, localisation | Not built. |
| BLE radio code for the link (ANCS/AMS GATT client, L2CAP), notification listener, iOS background BLE | Not built: needs the Android SDK / Xcode and real phones. The byte-level and protocol parts are done and tested. See `docs/COMPANION.md`. |

## Limits no app can remove

- iPhone can't receive while Airbox is closed or suspended, because there's no background
  listener without a server. Show "Offline" honestly.
- No app can switch Bluetooth or Wi-Fi on silently.
- Quick Share and AirDrop are system services; Airbox can coexist with them, not control them.
- NFC can't carry files and is mostly unavailable for iPhone ↔ Android.

## Real-device test plan (to run once the apps exist)

For each pair — iPhone→Android, Android→iPhone, Android→Android, iPhone→iPhone — run:
one photo; 300 photos; mixed photos/PDF/DOCX/VCF/link; a 4 GB video; walk out of range at
50% and come back (must resume, not restart); receiver rejects; receiver nearly full;
screen locked mid-transfer; sender app backgrounded; Bluetooth off; Wi-Fi off; same Wi-Fi
with no internet; different Wi-Fi networks; client-isolated hotel Wi-Fi; phone hotspot;
Local Network permission denied (iOS); QR pairing; SAS pairing; Share Extension and
Sharesheet entry points. For each, record: outcome, time to first byte, MB/s, battery %
used, and the exact message the user saw.
