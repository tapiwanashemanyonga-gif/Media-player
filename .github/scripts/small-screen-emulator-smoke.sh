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

adb shell input tap "$LIBRARY_X" "$NAV_Y"
sleep 3
adb shell uiautomator dump /sdcard/a1-library.xml >/dev/null
adb pull /sdcard/a1-library.xml "$OUT_DIR/library.xml" >/dev/null
grep -Fq 'Your library' "$OUT_DIR/library.xml"
grep -Fq 'Hide your library' "$OUT_DIR/library.xml"

adb shell input tap "$VIDEOS_X" "$NAV_Y"
sleep 3
adb shell uiautomator dump /sdcard/a1-videos.xml >/dev/null
adb pull /sdcard/a1-videos.xml "$OUT_DIR/videos.xml" >/dev/null
grep -Fq 'Open Videos to scan your device.' "$OUT_DIR/videos.xml"

adb shell input tap "$HOME_X" "$NAV_Y"
sleep 3
adb shell uiautomator dump /sdcard/a1-home.xml >/dev/null
adb pull /sdcard/a1-home.xml "$OUT_DIR/home.xml" >/dev/null
grep -Fq 'Home' "$OUT_DIR/home.xml"
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
  NAV_Y=$((SCREEN_HEIGHT - 58))
  adb shell input tap "$((SCREEN_WIDTH * 5 / 8))" "$NAV_Y"
  sleep 2
  adb shell uiautomator dump /sdcard/a1-small-library.xml >/dev/null
  adb pull /sdcard/a1-small-library.xml "$OUT_DIR/very-small-library.xml" >/dev/null
  grep -Fq 'Your library' "$OUT_DIR/very-small-library.xml"
fi

CRASH_LOG="$OUT_DIR/android-runtime.log"
adb logcat -d -v brief AndroidRuntime:E '*:S' > "$CRASH_LOG"
if grep -E 'FATAL EXCEPTION|Process: com\.a1family\.media' "$CRASH_LOG"; then
  echo "Android runtime crash detected on API $API_LEVEL at ${SCREEN_WIDTH}x${SCREEN_HEIGHT}."
  exit 1
fi

echo "PASS: API $API_LEVEL compact-phone smoke test (${SCREEN_WIDTH}x${SCREEN_HEIGHT}); launch, library, videos, navigation and rotation checked."
