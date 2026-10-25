# Airbox

Cross-platform nearby file sharing (iPhone ⇄ Android ⇄ desktop) plus an always-on link
between paired phones (notifications, calls, replies, media, clipboard, find device). No
account, no cloud, and everything is encrypted.

**Current stage: Phase 1 (feasibility) done, Phase 2 (core engine) done for the shared
transfer engine only. Neither mobile app exists yet.** Read [docs/STATUS.md](docs/STATUS.md)
before assuming anything works on a phone.

## What's here

| Path | What it is |
|---|---|
| `core/` | Transport-agnostic transfer engine in Kotlin/JVM: protocol v1, TLS identity, pairing (QR token + SAS), sender/receiver sessions with resume, integrity checks, safe storage, transport planner. The Android app will depend on it directly. |
| `cli/` | Desktop reference client for interop testing and for the future Swift port. |
| `docs/FEASIBILITY.md` | Platform reality check, compatibility matrix, architecture decision. **Start here.** |
| `docs/COMPANION.md` | Linked-devices features (notifications, calls, replies, media, clipboard, find device): what's possible between Android and iPhone and what isn't. |
| `docs/NO_APP_IPHONE.md` | iPhone with **no app**: AirDrop → Quick Share, the Safari page, and Shortcuts/Back Tap for clipboard. |
| `docs/DYNAMIC_ISLAND.md` | Optional iPhone app: one-tap Paste & Send, and what goes on the Dynamic Island (and Android 16 Live Updates). |
| `ios/` | iPhone app source (SwiftUI + Live Activities). Written but **not yet compiled**; see `ios/README.md`. |
| `docs/PROTOCOL.md` | Wire spec both apps implement. |
| `docs/SECURITY.md` | Threat model and known gaps. |
| `docs/STATUS.md` | What works, what's tested, what isn't built, device test plan. |

## Build and test

Requires JDK 17+ and Gradle 8.x.

```sh
gradle test                 # 75 unit + end-to-end tests
gradle :cli:installDist     # builds cli/build/install/airbox/bin/airbox
```

## Try a transfer between two computers on the same network

```sh
# Computer A
airbox receive --dir ~/Downloads/Airbox
#   prints: airbox://pair?v=1&h=192.168.1.20&p=40123&fp=...&t=...

# Computer B
airbox send --link 'airbox://pair?...' photo.heic video.mov
# or, without the link (both sides confirm a 6-digit code):
airbox send --to 192.168.1.20:40123 report.pdf
```

## Try the no-app iPhone page

```sh
airbox web --share-text "hello from the computer"
# scan the printed QR code with the iPhone camera (same Wi-Fi)
```
