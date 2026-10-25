# Linked devices: making Android ⇄ iPhone feel like iPhone ⇄ iPad

Goal: pair once, and from then on the two phones stay connected and share the everyday
things — notifications, messages, calls, clipboard, links, battery, media, find-my-phone.

**The honest summary.** iPhone ⇄ iPad Continuity (Handoff, iMessage/SMS on every device,
phone calls on the iPad, Universal Clipboard, AirDrop, Instant Hotspot, iPhone Mirroring)
runs on private Apple services tied to your Apple ID. No third-party app can plug into
them. What *can* be built uses the public doors Apple and Google leave open, mainly:

- **ANCS / AMS.** Bluetooth services every iPhone exposes to paired accessories like
  smartwatches and car stereos. The Android phone plays the role of that accessory.
- **Android's notification-listener permission** (the user grants it once).
- **A background Bluetooth LE connection to the Airbox iOS app.**

Realistically that covers about 60–70% of the iPad experience. The missing parts are
listed openly below.

## What each feature can and can't do

Legend: ✅ buildable with public APIs · 🟡 partial · ❌ not possible for a normal app

### iPhone → Android (you're holding the Android phone)

| Feature | Status | How / limit |
|---|---|---|
| See iPhone notifications (any app) | ✅ | ANCS. Android connects to the iPhone as a BLE accessory. Title, text, app name, time. |
| See incoming iMessage / SMS | 🟡 | Arrives as a notification with the text preview (ANCS). There's **no message history and no threads**. |
| **Reply** to iMessage / SMS from Android | ❌ | ANCS has no text reply. Microsoft Phone Link does iMessage on Windows through Bluetooth MAP, but Android blocks MAP for third-party apps. |
| Incoming call: see who's calling, answer / decline | ✅ | ANCS positive/negative actions. Answering picks up **on the iPhone**. |
| Call audio on the Android phone | ❌ | Needs the Bluetooth hands-free (HFP) client role, which Android doesn't expose to apps. |
| Dismiss an iPhone notification | 🟡 | Only if the notification offers a negative action. |
| Control iPhone music (play/pause/skip/volume), see the track | ✅ | AMS. |
| Clipboard from iPhone | 🟡 | You tap "Send clipboard" in the Airbox app or share sheet. iOS doesn't allow automatic background clipboard reads. |
| Photos / files / links | ✅ | The transfer engine (already built). |
| iPhone battery level on Android | 🟡 | The iOS app reports it while the link is up. |
| Mirror the iPhone screen / use iPhone apps | ❌ | iPhone Mirroring is Mac-only and private. |

### Android → iPhone (you're holding the iPhone)

| Feature | Status | How / limit |
|---|---|---|
| See Android notifications on iPhone | ✅ | Android's notification listener sends them over the link. The iOS app posts them as normal iOS notifications. |
| **Reply** to Android WhatsApp/SMS/Telegram from the iPhone notification | ✅ | iOS text-input notification action, which fires the Android notification's own reply action. |
| Answer / decline Android calls from iPhone | 🟡 | Decline is often possible via the notification action. Answering works on some dialers. Audio stays on Android. |
| Control Android music from iPhone | ✅ | The Android app reads and controls media sessions (granted by the notification-listener permission). |
| Clipboard to Android | 🟡 | Tap to send. Android 10+ blocks background clipboard reads too. |
| Ring my Android phone ("find device") | ✅ | Android plays a loud alarm-channel sound even in silent mode, once the user allows it. |
| Ring my iPhone from Android | 🟡 | A notification sound plays, but silent/Focus mode can mute it. The loud override (Critical Alerts) needs a special Apple entitlement that is rarely granted. |
| Android battery on iPhone | ✅ | |
| Share Android's hotspot | 🟡 | Airbox shows a Wi-Fi QR code; the iPhone camera joins it. Not automatic like Instant Hotspot. |

## Staying connected when the apps aren't on screen

| | Android | iPhone |
|---|---|---|
| Keep the link while in background | ✅ Foreground service (`connectedDevice` type) with an always-visible notification. | 🟡 `bluetooth-central` background mode keeps a BLE connection and can wake the app for incoming data. **If the user swipes the app away (force-quit), iOS won't relaunch it** until it's opened again. |
| Link transport | BLE L2CAP channel (Android 10+) carrying TLS, plus Wi-Fi when both are on the same network | `CBL2CAPChannel` carrying TLS |
| ANCS (iPhone notifications on Android) | Android is the GATT client; it needs a bonded (encrypted) BLE link. | Nothing to run, because iOS provides ANCS itself, even when Airbox is closed. |

**Verify on hardware first:**

1. That an iPhone exposes ANCS to an *Android phone* bonding as a BLE peripheral, as it
   does to watches.
2. L2CAP and TLS throughput and stability in iOS background mode.
3. Battery cost over 24 hours on both phones.
4. EU only: iOS 26.5 added official notification forwarding to third-party wearables. It
   allows one device at a time, and turning it on disables Apple Watch forwarding. It might
   eventually offer a better path than ANCS in the EU, but it's built for accessories, not
   phones.

## Pairing and privacy rules (enforced in code)

- A link only opens between devices that **paired both ways** (`CompanionLink` refuses
  strangers, and `connect` must use the fingerprint pinned at pairing).
- Each side advertises what it can do. Only shared features are used, and sending an
  unsupported one fails loudly instead of being faked (`LinkFeature`).
- Notification mirroring is **opt-in per app**. Content can be hidden per app. One-time
  passcodes are masked by default (`NotificationPolicy`).
- Nothing goes through a server. Notification content is never logged.

## Code in this repo

| File | What it does |
|---|---|
| `core/.../companion/Ancs.kt` | Full ANCS client codec: notification events, attribute requests, multi-packet reassembly, actions |
| `core/.../companion/AncsBridge.kt` | iPhone notifications → `MirroredNotification`, one request in flight, app-name lookup, answer/decline |
| `core/.../companion/Ams.kt` | Apple Media Service codec: commands, track/player updates |
| `core/.../protocol/LinkMessages.kt` | Link wire messages (notifications, actions with reply text, clipboard, links, battery, ring, media) |
| `core/.../companion/CompanionLink.kt` | Always-on, trusted-only link with feature negotiation and keepalives |
| `core/.../companion/NotificationPolicy.kt` | Opt-in, hide-content and OTP masking rules |
| `core/.../net/IncomingRouter.kt` | Lets a link and file transfers share one listener |

Tests: `CompanionTest.kt` (ANCS byte layouts, reassembly across 20-byte BLE packets,
fake-iPhone bridge, AMS, policy, and live links over TLS including keepalives and refusing
strangers).

## What the app layers still need

- **Android:** a BLE GATT client for ANCS/AMS (`BluetoothGatt`, bonding, CCCD writes,
  feeding `AncsBridge`), a `NotificationListenerService` → `NotificationPolicy` →
  `CompanionLink`, firing `RemoteInput` replies, `MediaSessionManager`, a foreground
  service, BLE advertising plus L2CAP server, and settings screens for per-app opt-in.
- **iOS:** a CoreBluetooth central plus L2CAP client with `bluetooth-central` background
  mode and state restoration, a Swift port of the link messages, local notifications with
  a text-input reply action, and the battery API.
