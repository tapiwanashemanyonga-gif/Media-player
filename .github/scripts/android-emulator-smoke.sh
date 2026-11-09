#!/usr/bin/env bash
set -uo pipefail

APK="artifacts/apk/app-debug.apk"
OUT="${A1_ARTIFACT_DIR:-artifacts/emulator}"
PACKAGE="com.a1family.media"
ACTIVITY="com.kivora.music.MainActivity"
mkdir -p "$OUT"
FAIL=0

adb wait-for-device
SDK="$(adb shell getprop ro.build.version.sdk | tr -d '\r')"
RELEASE="$(adb shell getprop ro.build.version.release | tr -d '\r')"
DEVICE="$(adb shell getprop ro.product.model | tr -d '\r')"
{
  echo "Android API: $SDK"
  echo "Android release: $RELEASE"
  echo "Emulator model: $DEVICE"
  echo "Package: $PACKAGE"
  echo "Activity: $ACTIVITY"
} > "$OUT/device-info.txt"

if [[ ! -s "$APK" ]]; then
  echo "Debug APK missing: $APK" | tee "$OUT/failure.txt"
  exit 1
fi

if ! adb install -r "$APK" > "$OUT/install.txt" 2>&1; then
  echo "APK installation failed" | tee "$OUT/failure.txt"
  adb logcat -d -v threadtime > "$OUT/logcat.txt" || true
  exit 1
fi

# Grant the media permissions needed to reach the library UI without a permission dialog.
if (( SDK <= 32 )); then
  adb shell pm grant "$PACKAGE" android.permission.READ_EXTERNAL_STORAGE >/dev/null 2>&1 || true
else
  adb shell pm grant "$PACKAGE" android.permission.READ_MEDIA_AUDIO >/dev/null 2>&1 || true
  adb shell pm grant "$PACKAGE" android.permission.READ_MEDIA_VIDEO >/dev/null 2>&1 || true
  if (( SDK >= 34 )); then
    adb shell pm grant "$PACKAGE" android.permission.READ_MEDIA_VISUAL_USER_SELECTED >/dev/null 2>&1 || true
  fi
fi

adb logcat -c
if ! adb shell am start -W -n "$PACKAGE/$ACTIVITY" > "$OUT/activity-start.txt" 2>&1; then
  echo "Activity launch command failed" | tee "$OUT/failure.txt"
  FAIL=1
fi

# Capture both an early frame and the settled screen to help spot startup/UI regressions.
sleep 3
adb exec-out screencap -p > "$OUT/launch-screen.png" 2> "$OUT/screenshot-error.txt" || FAIL=1
sleep 9
adb exec-out screencap -p > "$OUT/home-screen.png" 2>> "$OUT/screenshot-error.txt" || FAIL=1
adb shell dumpsys activity activities > "$OUT/activities.txt" 2>&1 || true
adb shell dumpsys window windows > "$OUT/windows.txt" 2>&1 || true
adb shell pidof "$PACKAGE" > "$OUT/app-pid.txt" 2>&1 || true
adb logcat -d -v threadtime > "$OUT/logcat.txt" 2>&1 || true

if ! grep -q "$PACKAGE" "$OUT/app-pid.txt"; then
  echo "App process is not alive after startup" | tee -a "$OUT/failure.txt"
  FAIL=1
fi

if grep -A6 "FATAL EXCEPTION" "$OUT/logcat.txt" | grep -q "Process: $PACKAGE"; then
  echo "Fatal application exception detected; see logcat.txt" | tee -a "$OUT/failure.txt"
  grep -A30 "FATAL EXCEPTION" "$OUT/logcat.txt" > "$OUT/fatal-exception.txt" || true
  FAIL=1
fi

if [[ ! -s "$OUT/home-screen.png" ]]; then
  echo "Screenshot was not produced" | tee -a "$OUT/failure.txt"
  FAIL=1
fi

if (( FAIL != 0 )); then
  echo "FAIL: Android API $SDK startup smoke test" | tee -a "$OUT/result.txt"
  exit 1
fi

echo "PASS: Android API $SDK app installed, launched, remained alive and produced a screenshot." | tee "$OUT/result.txt"
