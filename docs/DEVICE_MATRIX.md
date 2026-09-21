# Device reliability matrix

Use this checklist before claiming MVP AC-02–AC-07 or a release candidate. Record device model, API level, build (`versionName`/`versionCode`), date, and pass/fail for each row.

Procedures, Play / FGS, and provider strategy: [RELEASE_HARDENING.md](RELEASE_HARDENING.md).

Helpers:

```bash
./scripts/oem-smoke.sh record-header
./scripts/oem-smoke.sh install
EXPECTED_LAT=25.034 EXPECTED_LON=121.5645 ./scripts/verify-mock-clients.sh
./scripts/oem-smoke.sh soak-probe 300 12
```

## Status snapshot (2026-09-21)

| Target | Status |
|---|---|
| API 26 emu | **Blocked** — no system image / emulator installed on this host (see RELEASE_HARDENING §1) |
| API 34 emu | **Blocked** — same |
| API 36 emu | **Blocked** — `platforms;android-36` present; system image + emulator still required |
| OEM Sony XQ-BC72 (Android 13 / API 33) | **Partial pass** — core static Start/Stop, notification Stop, Apply, joystick enable, dumpsys client read; see notes below |

Build under test: `versionName=0.1.0` `versionCode=1` (`com.bloss0m.bloomwalk`).

## Core smoke (required)

On each target: select BloomWalk GPS as the mock location app, then run Start → verify → Stop and confirm cleanup.

| Check | API 26 emu | API 34 emu | API 36 emu | OEM Sony XQ-BC72 |
|---|---|---|---|---|
| Static Start injects GPS + FLP within ~3s (±10 m) | | | | Pass (2026-09-21; gps+fused dumpsys `mock` @ 27.045470,122.117607) |
| Notification shows coordinates + Stop | | | | Pass (title 模擬定位執行中 + Stop action) |
| Notification Stop clears service + providers | | | | Pass (in-app Stop; adb STOP intent flaky — prefer UI/notification) |
| Lock screen keeps mock active | | | | Pending |
| Swipe away Activity keeps mock active | | | | Pending |
| Force-stop leaves no residual mock / next Start recovers | | | | Pass (2026-09-21; `oem-smoke.sh force-stop`) |
| 20× Start/Stop without crash or leftover notification | | | | Pending (prior 2026-08-23 Gate noted Pass; re-confirm on 0.1.0) |

## Route session

| Check | API 26 | API 34 | API 36 | OEM Sony |
|---|---|---|---|---|
| Route Start / Pause / Resume / Stop | | | | Pending on 0.1.0 |
| Notification Pause/Resume/Stop | | | | Pending on 0.1.0 |

## Cross-app / independent client

| Check | API 26 | API 34 | API 36 | OEM Sony |
|---|---|---|---|---|
| `scripts/verify-mock-clients.sh` while Active | | | | Pass (2026-09-21; gps+fused last location tagged `mock`, matched expected) |
| Second app (Maps / FLP sample) pin matches | | | | Pending manual |

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
adb shell dumpsys activity services com.bloss0m.bloomwalk/.service.MockLocationForegroundService
adb shell appops set com.bloss0m.bloomwalk android:mock_location allow
adb shell settings put secure mock_location_app com.bloss0m.bloomwalk
```

Independent clients: `./scripts/verify-mock-clients.sh`, plus a second LocationManager / Fused Location sample app or Google Maps while BloomWalk is Active.

## Notes

- Sony XQ-BC72 (Android 13) covered a large subset of core flows in 2026-08 and again on 2026-09-21 for static mock + UI; still fill API 26/34/36 and remaining OEM soak / lock / force-stop rows on the current build.
- Public OpenFreeMap / Nominatim / FOSSGIS outages are expected; record network failures separately from mock-engine failures.
- Emulator matrix cannot be claimed until Google APIs system images and the `emulator` package are installed.
