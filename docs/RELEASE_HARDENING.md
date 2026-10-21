# Release hardening: reliability, Play policy, provider strategy

Engineering checklist for Milestone 6 / RC. This is not a claim that Play Console forms have been submitted.

## 1. Device matrix and cross-app verification

Source of truth: [DEVICE_MATRIX.md](DEVICE_MATRIX.md).

### Emulator API 29 / 34 / 36

AVDs on this host (Google APIs arm64): `api34_ga`, `api36_ga` (optional `api29_ga`).

```bash
sdkmanager --install \
  "emulator" \
  "system-images;android-29;google_apis;arm64-v8a" \
  "system-images;android-34;google_apis;arm64-v8a" \
  "system-images;android-36;google_apis;arm64-v8a"

./scripts/emu-matrix-smoke.sh api34_ga
./scripts/emu-matrix-smoke.sh api36_ga
```

On each AVD: select BloomWalk as mock location app → fill every **Core smoke** and **Route session** row in `DEVICE_MATRIX.md`.

`minSdk` is **29** (Android 10+). API 26 is out of scope.

### Cross-app / independent client read

While mock is Active:

```bash
# System LocationManager / fused dumps (adb client)
EXPECTED_LAT=25.034 EXPECTED_LON=121.5645 ./scripts/verify-mock-clients.sh

# True second app (manual): open Google Maps or any FLP sample and confirm the pin
# matches BloomWalk's active coordinate (± ~10–50 m depending on map zoom).
```

`scripts/verify-mock-clients.sh` fails closed if the foreground service is missing. Coordinate matching is optional via `EXPECTED_LAT` / `EXPECTED_LON`.

### OEM connected device helper

```bash
./scripts/oem-smoke.sh record-header
./scripts/oem-smoke.sh install
./scripts/oem-smoke.sh launch
# UI: Start mock
EXPECTED_LAT=... EXPECTED_LON=... ./scripts/verify-mock-clients.sh
./scripts/oem-smoke.sh stop-service
```

## 2. Soak and OEM battery

| Target | Procedure | Pass criteria |
|---|---|---|
| ≥ 1 h static | Start static mock, screen off optional | FGS stays `isForeground=true`; Stop still clears |
| 8 h static (Milestone 6) | Start before overnight; use `./scripts/oem-smoke.sh soak-probe 300 96` for optional heartbeats | No crash/ANR; Stop cleans providers |
| OEM battery | Exclude BloomWalk from battery optimization; run overnight | Session not silently killed; record OEM UI path |

```bash
./scripts/oem-smoke.sh battery-ignore
# Still confirm in OEM Settings → Battery → BloomWalk → Unrestricted / Don't optimize
./scripts/oem-smoke.sh soak-probe 300 12   # 1 hour of 5-minute probes
```

Record results in `DEVICE_MATRIX.md` **Optional soak** table (device, start/end UTC, pass/fail, notes).

## 3. Play Console and foreground-service policy

### Manifest (already in tree)

- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_LOCATION`
- Service `android:foregroundServiceType="location"`
- Runtime fine/coarse location before start; notification channel for ongoing mock

### Console actions (account owner)

1. Create app listing for `com.bloss0m.bloomwalk` (or promote Internal Testing).
2. **App content → Foreground service**: declare **Location** type; short description that the FGS keeps injecting developer-selected mock coordinates while the user explicitly runs a session, with a persistent notification and Stop.
3. **Data Safety**: mirror [PRIVACY.md](../PRIVACY.md) — precise location (optional, feature), local favorites/routes/settings, user-initiated gateway search/routing, AdMob/UMP on the free tier, Play Billing, Cloudflare backend. Do not claim “no ads / no backend.”
4. Host a public Privacy Policy URL (current `PRIVACY.md` is inventory only).
5. Upload signed AAB to Internal Testing; complete target API 36 declarations.

### Policy risk notes

- Mock location apps are developer tools; listing copy must not claim to “hide” `Location.isMock()` (setup guide already discloses this).
- Location FGS is while-in-use constrained: do not start FGS from the background; only continue an already-started session (current design).
- If Play rejects FGS justification, fall back to Internal / sideload distribution until policy copy is revised — do not strip the notification Stop action.

## 4. Search and map tile provider strategy

| Service | Current default | SLA | RC policy |
|---|---|---|---|
| Map tiles / style | OpenFreeMap Bright/Dark via MapLibre | None | Keep for RC; attribute OSM + OpenFreeMap. If outage: show map error/retry; offline landmarks/coords still work for mock. |
| Place search | BloomWalk gateway → Geoapify | Free-tier quota | Explicit submit only. Production flags stay false until B01–B04. |
| Routing | BloomWalk gateway → Geoapify | Free-tier quota; B01 blocks production | No public FOSSGIS fallback. Local perfect-shape and saved routes stay offline. |

### Decision for 0.1.x RC

- **Do not** call public Nominatim or FOSSGIS demo endpoints in production composition.
- **Do not** embed API keys, Play service accounts, or receipt private keys in the APK or git.
- Production `routingEnabled` / `adsEnabled` / `purchasesEnabled` stay false until the matching owner gate is deployed. Route dispatch accepts only the owner-accepted 25-credit ledger reservation.
- Treat provider HTTP failures as **network**, not mock-engine failures.

### Monitoring (lightweight)

Before each Internal Testing upload: check OpenFreeMap style URL and the gateway `/v1/config` kill switches. Do not spend live Geoapify credits just to prove CI.

## 5. Exit criteria for claiming RC

- [x] API 29, 34, 36 emulator core smoke rows tracked in `DEVICE_MATRIX.md` (34/36 done; 29 pending)
- [ ] At least one OEM column filled (Sony XQ-BC72 or other)
- [ ] Independent client / dumpsys verify documented for Active session
- [ ] ≥ 1 hour soak recorded; 8 hour soak recorded or explicitly deferred with owner
- [ ] Play FGS + Data Safety draft text ready for account owner
- [ ] Provider strategy section reviewed; self-host ticket filed if public launch is intended

## 6. 16 KB page size (64-bit native libraries)

Play's 16 KB page-size requirement applies to **64-bit** native libraries, not 32-bit ABIs ([S14](https://developer.android.com/guide/practices/page-sizes)).

Evidence from a local **unsigned** AAB (not a signed Play artifact):

| ABI | `libmaplibre.so` ELF LOAD align | 16 KB |
|---|---|---|
| `arm64-v8a` | 16384 | OK |
| `x86_64` | 16384 | OK |
| `armeabi-v7a` | 4096 | N/A (32-bit) |
| `x86` | 4096 | N/A (32-bit) |

**Packaging decision:** release/Play builds ship **64-bit only** (`arm64-v8a`, `x86_64`) via `ndk.abiFilters` on the `release` build type. That keeps 16 KB-aligned 64-bit MapLibre and omits 32-bit MapLibre that cannot satisfy a 16 KB ELF align. Debug/instrumentation is unfiltered so local and CI `x86_64` emulators still work. `packaging.jniLibs.useLegacyPackaging = false` so native libs stay uncompressed, which Play requires for 16 KB extraction.

This does **not** close B10. Recheck ELF align on a signed Play AAB before upload.
