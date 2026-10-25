# Airbox

Cross-platform nearby file sharing (iPhone ⇄ Android ⇄ desktop) with no account, no
cloud and encrypted transfers.

**Current stage: Phase 1 (feasibility) done, Phase 2 (core engine) done for the shared
transfer engine only. Neither mobile app exists yet.** Read [docs/STATUS.md](docs/STATUS.md)
before assuming anything works on a phone.

## What's here

| Path | What it is |
|---|---|
| `core/` | Transport-agnostic transfer engine in Kotlin/JVM: protocol v1, TLS identity, pairing (QR token + SAS), sender/receiver sessions with resume, integrity checks, safe storage, transport planner. The Android app will depend on it directly. |
| `cli/` | Desktop reference client for interop testing and for the future Swift port. |
| `docs/FEASIBILITY.md` | Platform reality check, compatibility matrix, architecture decision. **Start here.** |
| `docs/PROTOCOL.md` | Wire spec both apps implement. |
| `docs/SECURITY.md` | Threat model and known gaps. |
| `docs/STATUS.md` | What works, what's tested, what isn't built, device test plan. |

## Build and test

Requires JDK 17+ and Gradle 8.x.

```sh
gradle test                 # 39 unit + end-to-end tests over real TLS
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
