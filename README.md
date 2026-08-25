# BloomWalk GPS

[繁體中文](README-tw.md)

Android mock-location app for developers and QA. Pick a coordinate on the map, run static mock GPS, simulate routes, plan automatic journeys, or move freely with an on-screen joystick.

## Current status

- **Stack:** Kotlin, Jetpack Compose, application ID `com.bloss0m.bloomwalk`
- **SDK:** min 26, compile/target 36
- **Core mock:** LocationManager GPS test provider + Fused Location mock mode, atomic coordinator, foreground service, ongoing notification with Stop
- **Map UI:** MapLibre + OpenFreeMap, crosshair selection, light/dark styles, current-location button, EN + zh-TW resources
- **Search:** Nominatim remote search, 91 offline landmarks, direct coordinate parsing (e.g. `25.033964, 121.564468`)
- **Favorites & recents:** Room-backed local storage, deduplicated to 6 decimal places, 50-entry cap
- **Route planning:** FOSSGIS OpenStreetMap routing, A/B + waypoints, road polyline preview
- **Route simulation:** walk / run / bicycle / drive / custom speed, smooth acceleration, stop / loop / return, optional GPS drift
- **Automatic journey:** Taiwan / Japan / Korea landmarks (including Jeju) or **current location**; **perfect shape** or **road-adapted** routing
- **Joystick (standalone):** dedicated dock tab, 6 speed presets (walk 5, run 10, bike 18, car 100, HSR 300, plane 1000 km/h)
- **Route library:** Room storage, reverse routes, GPX import/export, JSON backup/restore (favorites not included)
- **Tests:** JVM + instrumentation, `assembleDebug`, `lintDebug`, R8 release build
- **Device evidence:** Sony XQ-BC72 (Android 13) core flows verified; full API 26/34/36 matrix still pending

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`

## Documentation

- [MVP scope & acceptance criteria](docs/MVP.md)
- [Architecture & platform decisions](docs/ARCHITECTURE.md)
- [Milestones & task list](docs/TASKS.md)

## Product decisions

- Kotlin + Jetpack Compose, single Android `app` module
- `minSdk 26`, `compileSdk 36`, `targetSdk 36`
- MapLibre Compose + OpenFreeMap — no Google Cloud API key required
- Mock Location via LocationManager + Fused Location Provider coordinator
- Foreground service for ongoing mock; notification Stop (Pause/Resume on route sessions)
- Room for favorites, saved routes, and recent routes
- Official Android mock location only — does not hide `Location.isMock()`

## Release & manual verification

Google Play Console, Data Safety, foreground-service declaration, and privacy policy URL require account-owner actions. Before each release: bump `versionCode`/`versionName`, sign the AAB, upload to Internal Testing.

JSON backup includes saved/recent routes only — **not favorites**.

### Device matrix

On API 26, 34, 36 emulators and at least one OEM device: select BloomWalk GPS as the mock app → Start → verify coordinates from an independent LocationManager/FLP client → Stop and confirm full cleanup.

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.bloss0m.bloomwalk/com.sora.mockgps.MainActivity
adb shell dumpsys activity services com.bloss0m.bloomwalk/.service.MockLocationForegroundService
```

If BloomWalk GPS does not appear under **Select mock location app**:

```bash
adb shell dumpsys package com.bloss0m.bloomwalk | grep ACCESS_MOCK_LOCATION
adb shell appops set com.bloss0m.bloomwalk android:mock_location allow
```

Reliability checklist (record device, API, result): 8-hour static soak, lockscreen, swipe-away Activity, force-stop, 20× Start/Stop, notification Pause/Resume/Stop, OEM battery optimization.

## Open decisions

1. Place-search provider policy and rate limits
2. OpenFreeMap availability monitoring and self-host strategy
3. At least one test device with Developer Options → mock location app enabled

## Map provider

MapLibre Compose with OpenFreeMap vector tiles (Positron light / Dark). No account or API key. OpenFreeMap is a public service with no SLA.

## Routing provider

Default: FOSSGIS OpenStreetMap demo router, no API key. Coordinates are sent only when the user taps plan route. Swap via `RoutingProviderConfig` for a self-hosted endpoint.

## Build locally

Requires JDK 17, Android SDK Platform 36, and `sdk.dir` in `local.properties`.

```bash
./gradlew testDebugUnitTest assembleDebug lintDebug
./gradlew assembleRelease bundleRelease
```

Release signing from environment variables:

```bash
export MOCKGPS_KEYSTORE_FILE=/absolute/path/release.jks
export MOCKGPS_KEYSTORE_PASSWORD='...'
export MOCKGPS_KEY_ALIAS='...'
export MOCKGPS_KEY_PASSWORD='...'
./gradlew bundleRelease
```

Install on a connected device:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
