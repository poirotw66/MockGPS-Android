#!/usr/bin/env bash
# Run DEVICE_MATRIX core smoke on one emulator AVD.
# Usage: scripts/emu-matrix-smoke.sh <avd_name>
# Writes JSON-ish summary lines to stdout; detailed log to /tmp/emu-matrix-<avd>.log
set -euo pipefail

AVD="${1:?avd name required}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/emulator:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

PKG=com.bloss0m.bloomwalk
ACTIVITY="$PKG/com.sora.mockgps.MainActivity"
SERVICE="$PKG/com.sora.mockgps.service.MockLocationForegroundService"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
LOG="/tmp/emu-matrix-${AVD}.log"
RESULT="/tmp/emu-matrix-${AVD}.result"

exec > >(tee "$LOG") 2>&1

echo "=== matrix smoke AVD=$AVD $(date -u +%Y-%m-%dT%H:%M:%SZ) ==="

# Kill any existing emulator
adb devices | awk '/emulator-/{print $1}' | while read -r s; do
  adb -s "$s" emu kill >/dev/null 2>&1 || true
done
sleep 2

emulator -avd "$AVD" -no-snapshot-save -no-audio -no-boot-anim -gpu swiftshader_indirect \
  >/tmp/emu-${AVD}.stdout 2>&1 &
EMUPID=$!
echo "emulator pid=$EMUPID"

SERIAL=""
for _ in $(seq 1 90); do
  SERIAL="$(adb devices | awk '/emulator-.*device$/{print $1; exit}')"
  if [[ -n "$SERIAL" ]]; then
    break
  fi
  # also accept offline→device transition
  sleep 5
done
if [[ -z "$SERIAL" ]]; then
  # maybe still offline
  SERIAL="$(adb devices | awk '/emulator-/{print $1; exit}')"
fi
echo "SERIAL=$SERIAL"
if [[ -z "$SERIAL" ]]; then
  echo "RESULT=FAIL reason=no_emulator_serial"
  echo "FAIL no_emulator_serial" >"$RESULT"
  exit 1
fi

ADB=(adb -s "$SERIAL")
"${ADB[@]}" wait-for-device

boot_ok=0
for _ in $(seq 1 90); do
  boot="$("${ADB[@]}" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
  if [[ "$boot" == "1" ]]; then
    boot_ok=1
    break
  fi
  sleep 5
done
SDK="$("${ADB[@]}" shell getprop ro.build.version.sdk | tr -d '\r')"
MODEL="$("${ADB[@]}" shell getprop ro.product.model | tr -d '\r')"
echo "boot_ok=$boot_ok sdk=$SDK model=$MODEL"
if [[ "$boot_ok" != "1" ]]; then
  echo "RESULT=FAIL reason=boot_timeout"
  echo "FAIL boot_timeout sdk=$SDK" >"$RESULT"
  "${ADB[@]}" emu kill >/dev/null 2>&1 || kill "$EMUPID" 2>/dev/null || true
  exit 1
fi

# Disable animations for faster UI
"${ADB[@]}" shell settings put global window_animation_scale 0 || true
"${ADB[@]}" shell settings put global transition_animation_scale 0 || true
"${ADB[@]}" shell settings put global animator_duration_scale 0 || true

echo "== install =="
"${ADB[@]}" install -r -t "$APK"
"${ADB[@]}" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION || true
"${ADB[@]}" shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION || true
"${ADB[@]}" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS || true
"${ADB[@]}" shell appops set "$PKG" android:mock_location allow
"${ADB[@]}" shell settings put secure mock_location_app "$PKG" || true

pass_start=0
pass_client=0
pass_stop=0
pass_force=0
notes=()

echo "== launch + start mock =="
"${ADB[@]}" shell am force-stop "$PKG" || true
"${ADB[@]}" shell am start -n "$ACTIVITY"
sleep 3

# UI automation via python + this SERIAL
export ADB_SERIAL="$SERIAL"
python3 <<'PY'
import os, re, subprocess, time, xml.etree.ElementTree as ET

serial = os.environ["ADB_SERIAL"]
adb = ["adb", "-s", serial]

def sh(*args, check=True):
    return subprocess.run([*adb, *args], check=check, capture_output=True, text=True)

def dump():
    for _ in range(5):
        r = subprocess.run([*adb, "shell", "uiautomator", "dump", "/sdcard/ui.xml"], capture_output=True, text=True)
        if r.returncode == 0 and "ERROR" not in (r.stdout + r.stderr):
            subprocess.check_call([*adb, "pull", "/sdcard/ui.xml", "/tmp/emu-ui.xml"], stdout=subprocess.DEVNULL)
            try:
                return ET.parse("/tmp/emu-ui.xml").getroot()
            except Exception:
                pass
        time.sleep(1)
    raise SystemExit("ui dump failed")

def center(b):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    return (int(m.group(1)) + int(m.group(3))) // 2, (int(m.group(2)) + int(m.group(4))) // 2

def tap_text(root, text):
    for n in root.iter("node"):
        if n.attrib.get("text") == text:
            b = n.attrib.get("bounds", "")
            if b == "[0,0][0,0]":
                continue
            x, y = center(b)
            subprocess.check_call([*adb, "shell", "input", "tap", str(x), str(y)])
            print(f"tap text={text!r} {x},{y}")
            return True
    return False

def tap_desc(root, desc):
    for n in root.iter("node"):
        if n.attrib.get("content-desc") == desc:
            b = n.attrib.get("bounds", "")
            if b == "[0,0][0,0]":
                continue
            x, y = center(b)
            subprocess.check_call([*adb, "shell", "input", "tap", str(x), str(y)])
            print(f"tap desc={desc!r} {x},{y}")
            return True
    return False

def texts(root):
    return [n.attrib.get("text", "") for n in root.iter("node") if n.attrib.get("text")]

# Dismiss setup / chrome
root = dump()
print("texts0", texts(root)[:25])
for label in ("知道了", "Got it", "Close", "關閉", "OK", "Allow", "While using the app", "僅限這次使用時允許", "使用應用程式時"):
    if tap_text(root, label):
        time.sleep(1)
        root = dump()

# Close bottom sheet if open
if tap_desc(root, "關閉功能表") or tap_desc(root, "Close sheet"):
    time.sleep(0.8)
    root = dump()

# Stop if already active
if tap_text(root, "停止") or tap_text(root, "Stop"):
    time.sleep(2)
    root = dump()

started = tap_text(root, "開始模擬定位") or tap_text(root, "Start mock location")
if not started:
    # try English localized CTA variants
    for t in texts(root):
        if "Start" in t and "mock" in t.lower():
            tap_text(root, t)
            started = True
            break
print("started", started)
time.sleep(4)

# Read active coordinate from Search sheet
root = dump()
tap_desc(root, "搜尋") or tap_desc(root, "Search") or tap_text(root, "搜尋") or tap_text(root, "Search")
time.sleep(1.2)
root = dump()
lat = lon = None
for n in root.iter("node"):
    t = n.attrib.get("text") or ""
    if "模擬中的座標" in t or t.startswith("Active coordinate") or "Active:" in t or t.startswith("選定座標") or t.startswith("Selected"):
        print("coord line", t)
        # formats: 模擬中的座標：lat, lon  OR Active coordinate: lat, lon
        try:
            part = t.split("：", 1)[-1] if "：" in t else t.split(":", 1)[-1]
            a, b = [x.strip() for x in part.replace("，", ",").split(",")[:2]]
            lat, lon = float(a), float(b)
        except Exception as e:
            print("parse fail", e)
print("ACTIVE_COORDS", lat, lon)
open("/tmp/emu-expected.env", "w").write(
    f"EXPECTED_LAT={lat}\nEXPECTED_LON={lon}\n" if lat is not None else ""
)
# leave sheet open or close — close for cleanliness
tap_desc(root, "關閉功能表")
open("/tmp/emu-start.flag", "w").write("1" if started else "0")
PY

pass_start=0
if [[ -f /tmp/emu-start.flag && "$(cat /tmp/emu-start.flag)" == "1" ]]; then
  if "${ADB[@]}" shell dumpsys activity services "$PKG" | grep -q MockLocationForegroundService; then
    pass_start=1
    echo "PASS start+FGS"
  else
    notes+=("UI started but FGS missing")
    echo "FAIL FGS missing after start"
  fi
else
  notes+=("could not tap Start CTA")
  echo "FAIL start CTA"
fi

echo "== independent client =="
set +e
set -a
# shellcheck disable=SC1091
source /tmp/emu-expected.env 2>/dev/null || true
set +a
SERIAL="$SERIAL" PKG="$PKG" EXPECTED_LAT="${EXPECTED_LAT:-}" EXPECTED_LON="${EXPECTED_LON:-}" \
  bash -c '
    # Adapt verify script to use SERIAL via ANDROID_SERIAL
    export ANDROID_SERIAL="'"$SERIAL"'"
    '"$ROOT"'/scripts/verify-mock-clients.sh
  '
client_rc=$?
set -e
if [[ $client_rc -eq 0 ]]; then
  pass_client=1
  echo "PASS client verify"
else
  notes+=("verify-mock-clients rc=$client_rc")
  echo "FAIL client verify"
fi

echo "== in-app Stop =="
export ADB_SERIAL="$SERIAL"
python3 <<'PY'
import os, re, subprocess, time, xml.etree.ElementTree as ET
serial=os.environ["ADB_SERIAL"]; adb=["adb","-s",serial]
def dump():
    subprocess.check_call([*adb,"shell","uiautomator","dump","/sdcard/ui.xml"], stdout=subprocess.DEVNULL)
    subprocess.check_call([*adb,"pull","/sdcard/ui.xml","/tmp/emu-ui.xml"], stdout=subprocess.DEVNULL)
    return ET.parse("/tmp/emu-ui.xml").getroot()
def center(b):
    m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    return (int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2
root=dump()
for n in root.iter("node"):
    if n.attrib.get("content-desc") in ("關閉功能表","Close sheet"):
        b=n.attrib["bounds"]
        if b!="[0,0][0,0]":
            x,y=center(b); subprocess.check_call([*adb,"shell","input","tap",str(x),str(y)]); time.sleep(0.5); root=dump(); break
stopped=False
for label in ("停止","Stop"):
    for n in root.iter("node"):
        if n.attrib.get("text")==label:
            b=n.attrib["bounds"]
            if b=="[0,0][0,0]": continue
            x,y=center(b); subprocess.check_call([*adb,"shell","input","tap",str(x),str(y)]); stopped=True; print("tapped", label); break
    if stopped: break
time.sleep(2)
open("/tmp/emu-stop.flag","w").write("1" if stopped else "0")
PY
if [[ "$(cat /tmp/emu-stop.flag)" == "1" ]] && ! "${ADB[@]}" shell dumpsys activity services "$PKG" | grep -q MockLocationForegroundService; then
  pass_stop=1
  echo "PASS stop cleared service"
else
  # force clear for next step
  notes+=("in-app stop incomplete")
  echo "FAIL/incomplete stop"
fi

echo "== force-stop residual =="
# Start again then force-stop
"${ADB[@]}" shell am start -n "$ACTIVITY"
sleep 2
export ADB_SERIAL="$SERIAL"
python3 <<'PY'
import os, re, subprocess, time, xml.etree.ElementTree as ET
serial=os.environ["ADB_SERIAL"]; adb=["adb","-s",serial]
def dump():
    subprocess.check_call([*adb,"shell","uiautomator","dump","/sdcard/ui.xml"], stdout=subprocess.DEVNULL)
    subprocess.check_call([*adb,"pull","/sdcard/ui.xml","/tmp/emu-ui.xml"], stdout=subprocess.DEVNULL)
    return ET.parse("/tmp/emu-ui.xml").getroot()
def center(b):
    m=re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", b)
    return (int(m.group(1))+int(m.group(3)))//2, (int(m.group(2))+int(m.group(4)))//2
root=dump()
for label in ("知道了","Got it"):
    for n in root.iter("node"):
        if n.attrib.get("text")==label:
            b=n.attrib["bounds"]
            if b!="[0,0][0,0]":
                x,y=center(b); subprocess.check_call([*adb,"shell","input","tap",str(x),str(y)]); time.sleep(0.5); root=dump(); break
for n in root.iter("node"):
    if n.attrib.get("text") in ("開始模擬定位","Start mock location"):
        b=n.attrib["bounds"]
        if b=="[0,0][0,0]": continue
        x,y=center(b); subprocess.check_call([*adb,"shell","input","tap",str(x),str(y)]); print("restarted"); break
time.sleep(2)
PY
"${ADB[@]}" shell am force-stop "$PKG"
sleep 1
if ! "${ADB[@]}" shell dumpsys activity services "$PKG" | grep -q MockLocationForegroundService; then
  pass_force=1
  echo "PASS force-stop cleared service"
else
  notes+=("force-stop left service")
  echo "FAIL force-stop residual"
fi

# Shutdown emulator
"${ADB[@]}" emu kill >/dev/null 2>&1 || kill "$EMUPID" 2>/dev/null || true
sleep 3

summary="AVD=$AVD sdk=$SDK start=$pass_start client=$pass_client stop=$pass_stop force=$pass_force notes=${notes[*]-}"
echo "SUMMARY $summary"
echo "$summary" >"$RESULT"
# overall
if [[ $pass_start -eq 1 && $pass_client -eq 1 && $pass_stop -eq 1 && $pass_force -eq 1 ]]; then
  echo "OVERALL=PASS"
  exit 0
fi
echo "OVERALL=PARTIAL_OR_FAIL"
exit 2
