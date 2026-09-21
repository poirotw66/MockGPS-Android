#!/usr/bin/env bash
# Read mock location from system LocationManager / dumpsys while BloomWalk is Active.
# This is the adb-side "independent client" check for DEVICE_MATRIX.md.
set -euo pipefail

PKG="${PKG:-com.bloss0m.bloomwalk}"
SERVICE="${SERVICE:-$PKG/com.sora.mockgps.service.MockLocationForegroundService}"
EXPECTED_LAT="${EXPECTED_LAT:-}"
EXPECTED_LON="${EXPECTED_LON:-}"
TOLERANCE_DEG="${TOLERANCE_DEG:-0.001}" # ~111 m at equator

usage() {
  cat <<'EOF'
Usage: scripts/verify-mock-clients.sh

Environment:
  PKG              App package (default: com.bloss0m.bloomwalk)
  EXPECTED_LAT     Optional expected latitude
  EXPECTED_LON     Optional expected longitude
  TOLERANCE_DEG    Match tolerance in degrees (default: 0.001)

Prerequisites:
  - Device connected and authorized
  - BloomWalk selected as mock location app
  - Mock session Active (static, route, or joystick)
EOF
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if ! adb get-state 1>/dev/null 2>&1; then
  echo "FAIL: no adb device" >&2
  exit 1
fi

echo "== device =="
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk

echo
echo "== mock_location appop =="
adb shell appops get "$PKG" android:mock_location || true

echo
echo "== foreground service =="
SVC_OUT="$(adb shell dumpsys activity services "$SERVICE" 2>/dev/null || true)"
if ! echo "$SVC_OUT" | grep -q "ServiceRecord"; then
  # Fallback: package-scoped dump (some adb builds ignore fully-qualified filters).
  SVC_OUT="$(adb shell dumpsys activity services "$PKG" 2>/dev/null || true)"
fi
if echo "$SVC_OUT" | grep -q "MockLocationForegroundService"; then
  echo "PASS: MockLocationForegroundService is running"
  echo "$SVC_OUT" | grep -E 'isForeground|startRequested|ServiceRecord' | head -10
else
  echo "FAIL: MockLocationForegroundService not found (is mock Active?)" >&2
  exit 1
fi

echo
echo "== notification =="
NOTI_OUT="$(adb shell dumpsys notification --noredact 2>/dev/null || true)"
if echo "$NOTI_OUT" | grep -q "$PKG"; then
  echo "$NOTI_OUT" | grep -E 'android.title=|模擬|Mock|Stop|停止' | head -20 || true
  echo "PASS: package has an active notification record"
else
  echo "WARN: could not confirm notification for $PKG"
fi

echo
echo "== dumpsys location (gps / fused last locations) =="
LOC_OUT="$(adb shell dumpsys location 2>/dev/null || true)"
# Prefer lines that look like Location[<provider> lat,lon ...]
echo "$LOC_OUT" | grep -E 'last location=Location\[(gps|fused|network)' | head -20 || true

extract_lat_lon() {
  # shellcheck disable=SC2001
  echo "$1" | sed -n 's/.*Location\[[a-z]* \([-0-9.][0-9.]*\),\([-0-9.][0-9.]*\).*/\1 \2/p' | head -1
}

GPS_LINE="$(echo "$LOC_OUT" | grep -E 'gps provider:|last location=Location\[gps' -A2 | grep 'last location=Location\[gps' | head -1 || true)"
FUSED_LINE="$(echo "$LOC_OUT" | grep -E 'fused provider:|last location=Location\[fused' -A2 | grep 'last location=Location\[fused' | head -1 || true)"

# Broader parse: any recent Location[gps / Location[fused in dump
if [[ -z "$GPS_LINE" ]]; then
  GPS_LINE="$(echo "$LOC_OUT" | grep -oE 'Location\[gps [-0-9.]+,[-0-9.]+[^]]*\]' | head -1 || true)"
fi
if [[ -z "$FUSED_LINE" ]]; then
  FUSED_LINE="$(echo "$LOC_OUT" | grep -oE 'Location\[fused [-0-9.]+,[-0-9.]+[^]]*\]' | head -1 || true)"
fi

echo "gps last:   ${GPS_LINE:-<none>}"
echo "fused last: ${FUSED_LINE:-<none>}"

within_tol() {
  python3 - "$1" "$2" "$3" "$4" "$5" <<'PY'
import sys
lat, lon, elat, elon, tol = map(float, sys.argv[1:])
ok = abs(lat - elat) <= tol and abs(lon - elon) <= tol
print("yes" if ok else "no")
sys.exit(0 if ok else 1)
PY
}

if [[ -n "$EXPECTED_LAT" && -n "$EXPECTED_LON" ]]; then
  echo
  echo "== expected coordinate check ($EXPECTED_LAT, $EXPECTED_LON ± $TOLERANCE_DEG) =="
  matched=0
  for label_line in "gps:$GPS_LINE" "fused:$FUSED_LINE"; do
    label="${label_line%%:*}"
    line="${label_line#*:}"
    coords="$(extract_lat_lon "$line" || true)"
    if [[ -z "$coords" ]]; then
      echo "SKIP $label: no parseable coordinates"
      continue
    fi
    read -r lat lon <<<"$coords"
    if within_tol "$lat" "$lon" "$EXPECTED_LAT" "$EXPECTED_LON" "$TOLERANCE_DEG"; then
      echo "PASS $label matches expected ($lat, $lon)"
      matched=1
    else
      echo "FAIL $label off expected ($lat, $lon)"
    fi
  done
  if [[ "$matched" -eq 0 ]]; then
    echo "FAIL: neither gps nor fused matched expected coordinate" >&2
    echo "Tip: some OEMs only expose fused; also confirm mock has been Active for a few seconds." >&2
    exit 1
  fi
fi

echo
echo "OK: service Active; inspect dumpsys lines above for independent-client evidence."
echo "For a true second-app check, open Google Maps / a FLP sample while mock stays Active."
