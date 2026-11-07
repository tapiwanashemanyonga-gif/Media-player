#!/usr/bin/env bash
set -euo pipefail

PACKAGE="com.a1family.media"
ACTIVITY="com.kivora.music/.MainActivity"
APK_PATH="${APK_PATH:-app-release.apk}"
SCREEN_WIDTH="${SCREEN_WIDTH:-360}"
SCREEN_HEIGHT="${SCREEN_HEIGHT:-800}"
API_LEVEL="${API_LEVEL:-0}"
OUT_DIR="small-screen-results/api-${API_LEVEL}"
mkdir -p "$OUT_DIR"

test -s "$APK_PATH"
adb wait-for-device
adb shell wm size "${SCREEN_WIDTH}x${SCREEN_HEIGHT}"
adb shell wm density 160
adb shell pm install -r "$APK_PATH"

if (( API_LEVEL >= 33 )); then
  adb shell pm grant "$PACKAGE" android.permission.READ_MEDIA_AUDIO
  adb shell pm grant "$PACKAGE" android.permission.READ_MEDIA_VIDEO
  if (( API_LEVEL >= 34 )); then
    adb shell pm grant "$PACKAGE" android.permission.READ_MEDIA_VISUAL_USER_SELECTED || true
  fi
else
  adb shell pm grant "$PACKAGE" android.permission.READ_EXTERNAL_STORAGE
fi

assert_activity_and_capture() {
  local label="$1"
  local xml="$OUT_DIR/$label.xml"
  adb shell am force-stop "$PACKAGE"
  adb shell am start -W -n "$ACTIVITY"
  sleep 8
  adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | grep -q "$PACKAGE"
  adb shell uiautomator dump /sdcard/a1-window.xml >/dev/null
  adb pull /sdcard/a1-window.xml "$xml" >/dev/null
  grep -Fq 'A1 FAMILY' "$xml"
  adb exec-out screencap -p > "$OUT_DIR/$label.png"
}

assert_activity_and_capture "portrait-${SCREEN_WIDTH}x${SCREEN_HEIGHT}"

# Exercise the bottom navigation on the compact viewport, not just process startup.
NAV_Y=$((SCREEN_HEIGHT - 58))
LIBRARY_X=$((SCREEN_WIDTH * 5 / 8))
VIDEOS_X=$((SCREEN_WIDTH * 7 / 8))
HOME_X=$((SCREEN_WIDTH / 8))

assert_selected_tab() {
  local xml="$1"
  local tab="$2"
  python3 - "$xml" "$tab" <<'PY'
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
tab = sys.argv[2]
if not any(
    node.attrib.get("text") == tab and node.attrib.get("selected") == "true"
    for node in root.iter("node")
):
    raise SystemExit(f"The {tab} navigation destination did not become selected.")
PY
}

adb shell input tap "$LIBRARY_X" "$NAV_Y"
sleep 3
adb shell uiautomator dump /sdcard/a1-library.xml >/dev/null
adb pull /sdcard/a1-library.xml "$OUT_DIR/library.xml" >/dev/null
assert_selected_tab "$OUT_DIR/library.xml" "Library"
grep -Fq 'Show your library' "$OUT_DIR/library.xml"

TOGGLE_CENTER=$(python3 - "$OUT_DIR/library.xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.attrib.get("content-desc") == "Hide or show library controls while keeping the song list visible":
        values = [int(value) for value in re.findall(r"-?\d+", node.attrib.get("bounds", ""))]
        if len(values) == 4:
            left, top, right, bottom = values
            print((left + right) // 2, (top + bottom) // 2)
            break
else:
    raise SystemExit("Library visibility toggle is missing from the phone viewport.")
PY
)
read -r TOGGLE_X TOGGLE_Y <<< "$TOGGLE_CENTER"
adb shell input tap "$TOGGLE_X" "$TOGGLE_Y"
sleep 2
adb shell uiautomator dump /sdcard/a1-library-expanded.xml >/dev/null
adb pull /sdcard/a1-library-expanded.xml "$OUT_DIR/library-expanded.xml" >/dev/null
grep -Fq 'Your library' "$OUT_DIR/library-expanded.xml"
grep -Fq 'Hide your library' "$OUT_DIR/library-expanded.xml"

adb shell input tap "$VIDEOS_X" "$NAV_Y"
sleep 4
adb shell uiautomator dump /sdcard/a1-videos.xml >/dev/null
adb pull /sdcard/a1-videos.xml "$OUT_DIR/videos.xml" >/dev/null
assert_selected_tab "$OUT_DIR/videos.xml" "Videos"

adb shell input tap "$HOME_X" "$NAV_Y"
sleep 3
adb shell uiautomator dump /sdcard/a1-home.xml >/dev/null
adb pull /sdcard/a1-home.xml "$OUT_DIR/home.xml" >/dev/null
assert_selected_tab "$OUT_DIR/home.xml" "Home"
grep -Fq 'Choose a song' "$OUT_DIR/home.xml"
grep -Fq 'Play or pause playback' "$OUT_DIR/home.xml"
python3 - "$OUT_DIR/home.xml" "$SCREEN_WIDTH" "$SCREEN_HEIGHT" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, width, height = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
root = ET.parse(path).getroot()
matches = [
    node for node in root.iter("node")
    if node.attrib.get("content-desc") == "Play or pause playback"
]
if not matches:
    raise SystemExit("Mini-player play control is missing from the accessibility hierarchy.")
bounds = matches[0].attrib.get("bounds", "")
numbers = [int(value) for value in re.findall(r"-?\d+", bounds)]
if len(numbers) != 4:
    raise SystemExit(f"Mini-player control has invalid bounds: {bounds}")
left, top, right, bottom = numbers
if left < 0 or top < 0 or right > width or bottom > height - 68:
    raise SystemExit(
        f"Mini-player control is outside the usable phone viewport: {bounds} on {width}x{height}."
    )
print(f"Mini-player bounds fit above bottom navigation: {bounds}")
PY
adb exec-out screencap -p > "$OUT_DIR/home.png"

# Rotation forces a compact-height configuration; returning to portrait checks recreation again.
adb shell settings put system accelerometer_rotation 0
adb shell settings put system user_rotation 1
sleep 3
assert_activity_and_capture "landscape"
adb shell settings put system user_rotation 0
sleep 3
assert_activity_and_capture "portrait-after-rotation"

# Android 16 additionally exercises a very narrow, short 320dp-class phone viewport.
if (( API_LEVEL == 36 )); then
  SCREEN_WIDTH=320
  SCREEN_HEIGHT=640
  adb shell wm size "${SCREEN_WIDTH}x${SCREEN_HEIGHT}"
  sleep 2
  assert_activity_and_capture "very-small-320x640"
  grep -Fq 'Play or pause playback' "$OUT_DIR/very-small-320x640.xml"
  python3 - "$OUT_DIR/very-small-320x640.xml" "$SCREEN_WIDTH" "$SCREEN_HEIGHT" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, width, height = sys.argv[1], int(sys.argv[2]), int(sys.argv[3])
root = ET.parse(path).getroot()
node = next(
    (item for item in root.iter("node")
     if item.attrib.get("content-desc") == "Play or pause playback"),
    None
)
if node is None:
    raise SystemExit("Mini-player is missing on the 320x640 phone viewport.")
bounds = node.attrib.get("bounds", "")
values = [int(value) for value in re.findall(r"-?\d+", bounds)]
if len(values) != 4:
    raise SystemExit(f"Mini-player has invalid bounds: {bounds}")
left, top, right, bottom = values
if left < 0 or top < 0 or right > width or bottom > height - 68:
    raise SystemExit(f"Mini-player overlaps the bottom navigation on 320x640: {bounds}")
print(f"320x640 mini-player bounds are valid: {bounds}")
PY
  NAV_Y=$((SCREEN_HEIGHT - 58))
  adb shell input tap "$((SCREEN_WIDTH * 5 / 8))" "$NAV_Y"
  sleep 2
  adb shell uiautomator dump /sdcard/a1-small-library.xml >/dev/null
  adb pull /sdcard/a1-small-library.xml "$OUT_DIR/very-small-library.xml" >/dev/null
  assert_selected_tab "$OUT_DIR/very-small-library.xml" "Library"
  grep -Fq 'Show your library' "$OUT_DIR/very-small-library.xml"
  TOGGLE_CENTER=$(python3 - "$OUT_DIR/very-small-library.xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

root = ET.parse(sys.argv[1]).getroot()
for node in root.iter("node"):
    if node.attrib.get("content-desc") == "Hide or show library controls while keeping the song list visible":
        values = [int(value) for value in re.findall(r"-?\d+", node.attrib.get("bounds", ""))]
        if len(values) == 4:
            left, top, right, bottom = values
            print((left + right) // 2, (top + bottom) // 2)
            break
else:
    raise SystemExit("Library toggle is missing on the 320x640 phone viewport.")
PY
  )
  read -r TOGGLE_X TOGGLE_Y <<< "$TOGGLE_CENTER"
  adb shell input tap "$TOGGLE_X" "$TOGGLE_Y"
  sleep 2
  adb shell uiautomator dump /sdcard/a1-small-library-expanded.xml >/dev/null
  adb pull /sdcard/a1-small-library-expanded.xml "$OUT_DIR/very-small-library-expanded.xml" >/dev/null
  grep -Fq 'Your library' "$OUT_DIR/very-small-library-expanded.xml"
fi

CRASH_LOG="$OUT_DIR/android-runtime.log"
adb logcat -d -v brief AndroidRuntime:E '*:S' > "$CRASH_LOG"
if grep -E 'FATAL EXCEPTION|Process: com\.a1family\.media' "$CRASH_LOG"; then
  echo "Android runtime crash detected on API $API_LEVEL at ${SCREEN_WIDTH}x${SCREEN_HEIGHT}."
  exit 1
fi

echo "PASS: API $API_LEVEL compact-phone smoke test (${SCREEN_WIDTH}x${SCREEN_HEIGHT}); launch, library, videos, navigation and rotation checked."
