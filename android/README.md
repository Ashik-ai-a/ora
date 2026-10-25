# Airbox for Android

Receives from an iPhone (text straight onto the clipboard, photos and files into
Downloads/Airbox) and shares back, with the same AirDrop-style look as the iPhone app.

## Build

Open this folder in Android Studio (Ladybug or newer), let it sync, run on a phone.
Command line: `./gradlew :app:assembleDebug` (generate the wrapper once with `gradle wrapper`).

## Works on every Android phone

| | Android 7–7.1 | 8–9 | 10–12 | 13–15 | 16+ |
|---|---|---|---|---|---|
| Receive text → clipboard | ✅ | ✅ | ✅ | ✅ (system shows “Copied”) | ✅ |
| Receive files → Downloads/Airbox | ✅ (storage permission) | ✅ (storage permission) | ✅ MediaStore | ✅ | ✅ |
| Private hotspot when there's no Wi-Fi | ❌ (use the normal hotspot) | ✅ | ✅ | ✅ | ✅ |
| Notifications | ✅ | ✅ channels | ✅ | ✅ asks permission | ✅ |
| Status-bar chip (Live Update) | ongoing notification | ongoing notification | ongoing notification | ongoing notification | ✅ |
| Share sheet “Send to iPhone” | ✅ | ✅ | ✅ | ✅ | ✅ |

Phone brands with their own battery killers get one plain fix and a button straight to the
right screen (`platform/DeviceCompat.kt`): Samsung, Xiaomi/Redmi/POCO, Huawei/Honor,
OPPO/realme/OnePlus, vivo/iQOO, ASUS, Tecno/Infinix/itel, with a safe fallback to the
app's settings page on anything else. Those screen names change between OS versions,
so every one is checked before opening it.

Typography: Inter is bundled (SIL Open Font License, `third_party/Inter-LICENSE.txt`) so
the app looks the same, and close to SF Pro, on every phone.

## What has been verified, honestly

- `platform/` (service, clipboard, notifications, hotspot, brand fixes, QR, Downloads) is
  **type-checked against the real Android 16 framework** on every `gradle test` run in
  the repository root (`tools/android-typecheck`). That check already caught one wrong
  API name. Newer APIs are behind `Build.VERSION.SDK_INT` checks for Android 7+.
- `ui/` (Compose) has **not been compiled**: AndroidX isn't available in the environment
  this was written in. Expect small fixes on the first Android Studio sync.
- Nothing has run on a phone yet. Before release, test on at least a Pixel, a Samsung, a
  Xiaomi, an Android 7 or 8 phone, and an Android 16 phone (Live Updates).
- The Live Update promotion extra (`android.requestPromotedOngoing`) needs confirming
  against the final SDK. AndroidX's `NotificationCompat.Builder.setRequestPromotedOngoing`
  is the safer route once AndroidX is in use here.
