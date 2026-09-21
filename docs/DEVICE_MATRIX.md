# Device reliability matrix

Use this checklist before claiming MVP AC-02–AC-07 or a release candidate. Record device model, API level, build (`versionName`/`versionCode`), date, and pass/fail for each row.

**Platform floor:** `minSdk 29` (Android 10+). API 26 support was dropped (2026-09-21).

Procedures, Play / FGS, and provider strategy: [RELEASE_HARDENING.md](RELEASE_HARDENING.md).

Helpers:

```bash
./scripts/oem-smoke.sh record-header
./scripts/oem-smoke.sh install
EXPECTED_LAT=25.034 EXPECTED_LON=121.5645 ./scripts/verify-mock-clients.sh
./scripts/oem-smoke.sh soak-probe 300 12

# Emulator matrix (one AVD at a time)
./scripts/emu-matrix-smoke.sh api29_ga   # create AVD if needed
./scripts/emu-matrix-smoke.sh api34_ga
./scripts/emu-matrix-smoke.sh api36_ga
```

## Status snapshot (2026-09-21)

| Target | Status |
|---|---|
| API 26 | **Out of scope** — `minSdk` raised to 29 |
| API 29 emu | Pending (CI matrix updated; local AVD optional) |
| API 34 emu (`api34_ga`) | **Pass** — Start / gps+fused mock client / Stop / force-stop |
| API 36 emu (`api36_ga`) | **Pass** — Start / gps+fused mock client / Stop / force-stop |
| OEM Sony XQ-BC72 (Android 13 / API 33) | **Partial pass** — core static Start/Stop, notification, force-stop, dumpsys client; lock/swipe-away/soak/route still pending |

Build under test: `versionName=0.1.0` `versionCode=1` (`com.bloss0m.bloomwalk`).  
Emulator runs: headless `-gpu swiftshader_indirect`, English locale on API 34/36.

## Core smoke (required)

On each target: select BloomWalk GPS as the mock location app, then run Start → verify → Stop and confirm cleanup.

| Check | API 29 emu | API 34 emu | API 36 emu | OEM Sony XQ-BC72 |
|---|---|---|---|---|
| Static Start injects GPS + FLP within ~3s (±10 m) | Pending | Pass (2026-09-21; gps+fused mock @ 25.033964,121.564468) | Pass (2026-09-21; same) | Pass (2026-09-21; gps+fused mock) |
| Notification shows coordinates + Stop | Pending | Pass (FGS notification + Stop action present) | Pass | Pass (title 模擬定位執行中 + Stop) |
| Notification / in-app Stop clears service + providers | Pending | Pass (in-app Stop) | Pass (in-app Stop) | Pass (in-app Stop) |
| Lock screen keeps mock active | | Pending | Pending | Pending |
| Swipe away Activity keeps mock active | | Pending | Pending | Pending |
| Force-stop leaves no residual mock / next Start recovers | Pending | Pass | Pass | Pass (`oem-smoke.sh force-stop`) |
| 20× Start/Stop without crash or leftover notification | | Pending | Pending | Pending (2026-08-23 Gate noted Pass; re-confirm) |

## Route session

| Check | API 29 | API 34 | API 36 | OEM Sony |
|---|---|---|---|---|
| Route Start / Pause / Resume / Stop | | Pending | Pending | Pending on 0.1.0 |
| Notification Pause/Resume/Stop | | Pending | Pending | Pending on 0.1.0 |

## Cross-app / independent client

| Check | API 29 | API 34 | API 36 | OEM Sony |
|---|---|---|---|---|
| `scripts/verify-mock-clients.sh` while Active | Pending | Pass (gps+fused tagged `mock`) | Pass | Pass |
| Second app (Maps / FLP sample) pin matches | | Pending manual | Pending manual | Pending manual |

## Optional soak

| Check | Device | Result |
|---|---|---|
| Static mock ≥ 1 hour | Sony XQ-BC72 | Pending — use `oem-smoke.sh soak-probe 300 12` |
| Static mock 8 hours (Milestone 6) | | Pending |
| OEM battery optimization: exclude app, overnight session | Sony XQ-BC72 | Pending — Settings → Battery; `oem-smoke.sh battery-ignore` assists |

## Commands

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.bloss0m.bloomwalk/com.sora.mockgps.MainActivity
adb shell dumpsys activity services com.bloss0m.bloomwalk/com.sora.mockgps.service.MockLocationForegroundService
adb shell appops set com.bloss0m.bloomwalk android:mock_location allow
adb shell settings put secure mock_location_app com.bloss0m.bloomwalk
```

Independent clients: `./scripts/verify-mock-clients.sh`, plus a second LocationManager / Fused Location sample app or Google Maps while BloomWalk is Active.

## Notes

- Sony XQ-BC72 (Android 13) covered a large subset of core flows in 2026-08 and again on 2026-09-21 for static mock + UI.
- API 26 was dropped from product scope after MapLibre Vulkan crashed on the API 26 Google APIs emulator; raising `minSdk` to 29 avoids that compatibility tax for a developer/QA tool.
- Public OpenFreeMap / Nominatim / FOSSGIS outages are expected; record network failures separately from mock-engine failures.
- Local AVDs: `api34_ga`, `api36_ga` (and optional `api29_ga`) under `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.
