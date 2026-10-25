# Airbox for iPhone (optional app)

Adds one-tap **Paste & Send** to Android and **Dynamic Island** Live Activities. Everything
else also works with no app at all; see `../docs/NO_APP_IPHONE.md`.

## Build (Mac with Xcode 15.4+)

```sh
brew install xcodegen
cd ios && xcodegen          # creates Airbox.xcodeproj from project.yml
open Airbox.xcodeproj       # set your Team on both targets, run on an iPhone
```

- iOS 17 or later (Live Activity buttons need it). The Dynamic Island needs an iPhone 14
  Pro or later; other iPhones show the same activity on the Lock Screen.
- To try it without an Android phone, run the desktop bridge: `airbox web` (see the root
  README), then scan its QR code from the app.

## Status

**Written but never compiled or run.** No Swift toolchain was available in the
environment where this was written. It passes a Swift syntax parse and a manual API
review. Expect small fixes on the first Xcode build. To verify on a device:

1. The Paste button pastes without the "Allow Paste?" alert.
2. Activities start and update, and the buttons (`IslandActionIntent`) work while the
   app is in the background.
3. Copy from the island writes to the clipboard from the intent.
4. The Local Network permission prompt appears once and pairing succeeds.
