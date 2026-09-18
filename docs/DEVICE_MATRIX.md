# Device reliability matrix

Use this checklist before claiming MVP AC-02–AC-07 or a release candidate. Record device model, API level, build (`versionName`/`versionCode`), date, and pass/fail for each row.

## Core smoke (required)

On each target: select BloomWalk GPS as the mock location app, then run Start → verify → Stop and confirm cleanup.

| Check | API 26 emu | API 34 emu | API 36 emu | OEM device |
|---|---|---|---|---|
| Static Start injects GPS + FLP within ~3s (±10 m) | | | | |
| Notification shows coordinates + Stop | | | | |
| Notification Stop clears service + providers | | | | |
| Lock screen keeps mock active | | | | |
| Swipe away Activity keeps mock active | | | | |
| Force-stop leaves no residual mock / next Start recovers | | | | |
| 20× Start/Stop without crash or leftover notification | | | | |

## Route session

| Check | API 26 | API 34 | API 36 | OEM |
|---|---|---|---|---|
| Route Start / Pause / Resume / Stop | | | | |
| Notification Pause/Resume/Stop | | | | |

## Optional soak

| Check | Device | Result |
|---|---|---|
| Static mock ≥ 1 hour | | |
| Static mock 8 hours (Milestone 6) | | |
| OEM battery optimization: exclude app, overnight session | | |

## Commands

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.bloss0m.bloomwalk/com.sora.mockgps.MainActivity
adb shell dumpsys activity services com.bloss0m.bloomwalk/.service.MockLocationForegroundService
adb shell appops set com.bloss0m.bloomwalk android:mock_location allow
```

Independent clients: read location from a second LocationManager / Fused Location sample app while BloomWalk is Active.

## Notes

- Sony XQ-BC72 (Android 13) already covers a large subset of core flows; still fill API 26/34/36 and at least one additional OEM when possible.
- Public OpenFreeMap / Nominatim / FOSSGIS outages are expected; record network failures separately from mock-engine failures.
