# Phase 1: Feasibility and platform reality check

Last checked: October 2026. Rows marked **verify** are things I couldn't confirm from
documentation alone. Each needs a short spike on real hardware before anyone builds on it.

## 1. The market moved, and that changes the product

Before the technical matrix, one finding matters more than everything else:

- **Google shipped AirDrop interoperability in Quick Share** on the Pixel 10 in late 2025.
  Google has said it is coming to more Android devices in 2026, with Samsung and OPPO
  named. A Pixel 10 user can already send to an iPhone through AirDrop, with no app on
  either phone.
- **LocalSend** (open source, free, on Android, iOS, Windows, macOS and Linux) already
  covers "cross-platform, local Wi-Fi, encrypted, no account" — the core of Airbox.
- **Apple added a Wi-Fi Aware framework in iOS 26** (iPhone 12 and later), reportedly
  because of EU interoperability requirements. For the first time, a third-party app can
  open a direct Wi-Fi link between an iPhone and an Android phone with no router and no
  hotspot. This is the one genuinely new technical opening.

So the AirDrop-like experience on its own is no longer a differentiator. On a growing share
of Android phones the OS gives it away for free. What's left for Airbox:

1. Android phones that won't get Quick Share–AirDrop interop (older, budget, or
   non-partner OEMs).
2. Direct iPhone ↔ Android transfers with **no shared network**, using Wi-Fi Aware.
   Whether this works in practice is still an open question (see §3).
3. Things the system share sheets don't do well: resumable multi-GB transfers, desktop
   peers, trusted-device auto-accept, and transfer history.

## 2. Feature compatibility matrix

Legend: ✅ supported · 🟡 conditional · ❌ not available to a normal App Store / Play app

| Capability | Android | iOS | Notes |
|---|---|---|---|
| Appear as a share target from any app | ✅ Sharesheet intent filters (`ACTION_SEND`, `ACTION_SEND_MULTIPLE`) | ✅ Share Extension | iOS extensions have tight memory limits. Hand off to the main app for big transfers. |
| Send without opening the full app UI | ✅ Lightweight activity over the Sharesheet | 🟡 The Share Extension can show the recipient picker; long transfers need the containing app | |
| Discover peers on the same Wi-Fi | ✅ NSD (mDNS/DNS-SD) | ✅ `NWBrowser` + Bonjour, needs the Local Network permission and `NSBonjourServices` | Implemented via the shared protocol (LAN path). |
| Direct Wi-Fi, no router, Android ↔ Android | ✅ Wi-Fi Aware (hardware-dependent) or Wi-Fi Direct | – | |
| Direct Wi-Fi, no router, iPhone ↔ Android | 🟡 Wi-Fi Aware on Android | 🟡 Wi-Fi Aware framework, iOS 26+, iPhone 12+ | **verify**: cross-vendor discovery and data paths are unproven. Apple may require its own pairing UI flow. Spike this first. |
| iPhone ↔ iPhone direct | – | ✅ Network framework peer-to-peer (`includePeerToPeer`) | Multipeer Connectivity still works but can't talk to Android. |
| Google Nearby Connections cross-platform | ✅ | 🟡 | **verify**: Google's page says Android ↔ iOS works. Its open-source iOS framework is marked "not an officially supported Google product". Transports on iOS are unclear. Don't build on it until a spike proves it. |
| BLE discovery | ✅ (needs `BLUETOOTH_SCAN`/`ADVERTISE`) | ✅ Core Bluetooth, foreground; heavily throttled in background | Discovery and token exchange only. Far too slow for files. |
| NFC tap to pair | 🟡 Host Card Emulation + reader mode (Android Beam is gone) | 🟡 Core NFC can read; acting as a card for third parties is restricted (entitlement, region, commercial agreement) | Android ↔ Android: feasible. iPhone reading an Android HCE token: **verify**. Android reading an iPhone: assume no. |
| QR / deep-link pairing | ✅ | ✅ | Implemented (the `airbox://pair` link). |
| Receive while the app is closed | 🟡 Foreground service with a visible notification (Android 14+ requires a declared type; `dataSync` has daily time limits from Android 15) | ❌ No long-lived listening socket in the background. Without a server and internet, nothing can wake the app. | On iOS the honest states are Ready (app open) or Offline. |
| Background transfer after the user leaves the app | ✅ Foreground service | 🟡 Short background task time. Background `URLSession` is built for HTTP to servers. **verify** whether it can target a LAN peer | |
| Hand content to / from Quick Share | 🟡 Only as an ordinary share target. No public API to drive Quick Share | – | Coexist, don't compete. Never use private APIs. |
| Turn radios on for the user | ❌ Apps can only ask (Android `ACTION_REQUEST_ENABLE` prompt for Bluetooth; settings panels for Wi-Fi) | ❌ | Shown as guidance (see `ConnectionPlanner`). |
| Save into Photos / gallery | ✅ MediaStore | ✅ PhotoKit with add-only permission | |
| Save arbitrary files | ✅ Downloads via MediaStore, or SAF | ✅ App's Documents folder (exposed in Files) or a document picker export | |

## 3. Architecture decision

**One shared wire protocol, two native apps, one transport-agnostic transfer engine.**

```
 ┌──────────── Android (Kotlin/Compose) ───────────┐   ┌──────────── iOS (Swift/SwiftUI) ────────────┐
 │ Sharesheet target · Foreground service · UI     │   │ Share Extension · App · UI                  │
 │ NSD · Wi-Fi Aware · BLE · NFC HCE · QR          │   │ NWBrowser · WiFiAware · CoreBluetooth · QR  │
 │                ↓ SecureStream                   │   │                ↓ SecureStream               │
 │      :core (this repo, Kotlin/JVM)              │   │   AirboxCore (Swift port of docs/PROTOCOL)  │
 └─────────────────────────────────────────────────┘   └─────────────────────────────────────────────┘
                     ⇅  TLS 1.3 (mutual, pinned self-signed certs) + Airbox framing v1  ⇅
```

- **Data paths** (preference order, chosen by `ConnectionPlanner`): same LAN → Wi-Fi Aware →
  Wi-Fi Direct (Android ↔ Android) → hotspot the user approves.
- **Introduction paths**: mDNS on the LAN, Wi-Fi Aware discovery, BLE advertisement, NFC tap,
  QR code, manual address. None of these carry file bytes.
- **Security** is the same on every path: TLS 1.3 with per-device self-signed certificates, then
  Airbox-level trust (trusted store, QR-pinned fingerprint plus one-time token, or a
  6-digit SAS comparison). See [SECURITY.md](SECURITY.md).
- **Why not Nearby Connections as the base?** Its iOS support is unverified, and it would put
  the protocol under a dependency that Google labels unsupported on iOS. We can add it later
  as one more transport behind `SecureStream` if a spike shows it works.
- **Why not a cross-platform UI framework?** Share extensions, foreground services and radio
  APIs are native on both sides anyway. A shared UI framework would save the least
  important code.

## 4. Recommended order (changed from the original plan)

1. **Two-week spike: iPhone ↔ Android over Wi-Fi Aware.** It's the only technically
   distinctive feature left. If it doesn't work reliably, the product is a LocalSend
   competitor and should be judged as one.
2. Android app on the LAN path using `:core`, with a Sharesheet target and a receive-mode
   foreground service.
3. iOS app: a Swift port of the protocol, plus the Share Extension → app handoff.
4. Everything else in the original prompt (NFC, BLE upgrade, Quick Share coexistence) only
   after 1–3 work on physical devices.
