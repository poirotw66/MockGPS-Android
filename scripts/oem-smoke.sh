#!/usr/bin/env bash
# OEM / connected-device smoke helpers for DEVICE_MATRIX.md core checks.
# Does not claim emulator API 26/34/36 coverage — those need AVDs with Google APIs images.
set -euo pipefail

PKG="${PKG:-com.bloss0m.bloomwalk}"
ACTIVITY="${ACTIVITY:-$PKG/com.sora.mockgps.MainActivity}"
SERVICE="${SERVICE:-$PKG/com.sora.mockgps.service.MockLocationForegroundService}"
APK="${APK:-app/build/outputs/apk/debug/app-debug.apk}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"

service_dump() {
  local out
  out="$(adb shell dumpsys activity services "$SERVICE" 2>/dev/null || true)"
  if ! echo "$out" | grep -q MockLocationForegroundService; then
    out="$(adb shell dumpsys activity services "$PKG" 2>/dev/null || true)"
  fi
  printf '%s\n' "$out"
}

service_running() {
  service_dump | grep -q MockLocationForegroundService
}

require_device() {
  adb get-state 1>/dev/null 2>&1 || { echo "FAIL: no adb device" >&2; exit 1; }
}

cmd_install() {
  require_device
  if [[ ! -f "$ROOT/$APK" && ! -f "$APK" ]]; then
    echo "Building debug APK…"
    (cd "$ROOT" && ./gradlew assembleDebug)
  fi
  local apk_path="$APK"
  [[ -f "$ROOT/$APK" ]] && apk_path="$ROOT/$APK"
  adb install -r "$apk_path"
  adb shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION || true
  adb shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION || true
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
  adb shell appops set "$PKG" android:mock_location allow
  adb shell settings put secure mock_location_app "$PKG" || true
  echo "Installed and mock_location allowed for $PKG"
}

cmd_launch() {
  require_device
  adb shell am start -n "$ACTIVITY"
}

cmd_service() {
  require_device
  service_dump | head -40
}

cmd_stop_service() {
  require_device
  # Prefer explicit component + action used by the notification Stop PendingIntent.
  adb shell am start-foreground-service \
    -n "$SERVICE" \
    -a com.sora.mockgps.action.STOP >/dev/null 2>&1 || \
  adb shell am startservice \
    -n "$SERVICE" \
    -a com.sora.mockgps.action.STOP >/dev/null 2>&1 || true
  sleep 2
  if service_running; then
    echo "WARN: service still present after STOP intent — use in-app or notification Stop"
  else
    echo "PASS: service cleared after STOP"
  fi
}

cmd_force_stop() {
  require_device
  adb shell am force-stop "$PKG"
  sleep 1
  if service_running; then
    echo "FAIL: service survived force-stop" >&2
    exit 1
  fi
  echo "PASS: force-stop cleared service"
}

cmd_battery_ignore() {
  require_device
  # Request ignore battery optimizations (may show system UI on some OEMs).
  adb shell dumpsys deviceidle whitelist | grep -F "$PKG" || true
  adb shell cmd appops set "$PKG" RUN_ANY_IN_BACKGROUND allow || true
  adb shell dumpsys deviceidle whitelist +"$PKG" 2>/dev/null || \
    adb shell cmd deviceidle whitelist +"$PKG" 2>/dev/null || \
    echo "NOTE: could not add deviceidle whitelist via adb; exclude BloomWalk in OEM battery UI manually."
}

cmd_soak_probe() {
  # Lightweight heartbeat while a long static soak is running.
  require_device
  local interval="${1:-60}"
  local count="${2:-10}"
  echo "Probing every ${interval}s × ${count} (keep mock Active)…"
  for ((i = 1; i <= count; i++)); do
    local ts
    ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    if service_dump | grep -q 'isForeground=true'; then
      echo "[$ts] #$i PASS FGS alive"
    else
      echo "[$ts] #$i FAIL FGS missing" >&2
      exit 1
    fi
    "$ROOT/scripts/verify-mock-clients.sh" || echo "[$ts] #$i WARN client verify failed"
    sleep "$interval"
  done
}

cmd_record_header() {
  require_device
  echo "model=$(adb shell getprop ro.product.model | tr -d '\r')"
  echo "release=$(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "sdk=$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
  echo "pkg=$PKG"
  adb shell dumpsys package "$PKG" | grep -E 'versionName=|versionCode=' | head -4
  echo "date_utc=$(date -u +%Y-%m-%dT%H:%M:%SZ)"
}

usage() {
  cat <<'EOF'
Usage: scripts/oem-smoke.sh <command>

Commands:
  install          Install debug APK, grant location + mock_location
  launch           Start MainActivity
  service          Dump foreground service
  stop-service     Send STOP action and confirm cleanup
  force-stop       Force-stop app and confirm no residual service
  battery-ignore   Attempt OEM battery-optimization whitelist via adb
  soak-probe [interval_s] [count]
                   Heartbeat while a long static soak is Active
  record-header    Print device/build fields for DEVICE_MATRIX notes

Typical OEM core smoke (manual Start in UI between steps):
  ./scripts/oem-smoke.sh install
  ./scripts/oem-smoke.sh launch
  # In UI: Start mock → wait 3s
  EXPECTED_LAT=... EXPECTED_LON=... ./scripts/verify-mock-clients.sh
  ./scripts/oem-smoke.sh stop-service
  # In UI: Start again, then:
  ./scripts/oem-smoke.sh force-stop
EOF
}

main() {
  local cmd="${1:-}"
  shift || true
  case "$cmd" in
    install) cmd_install "$@" ;;
    launch) cmd_launch "$@" ;;
    service) cmd_service "$@" ;;
    stop-service) cmd_stop_service "$@" ;;
    force-stop) cmd_force_stop "$@" ;;
    battery-ignore) cmd_battery_ignore "$@" ;;
    soak-probe) cmd_soak_probe "$@" ;;
    record-header) cmd_record_header "$@" ;;
    -h|--help|"") usage ;;
    *) echo "Unknown command: $cmd" >&2; usage; exit 1 ;;
  esac
}

main "$@"
