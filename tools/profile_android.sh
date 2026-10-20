#!/usr/bin/env bash
# Same seeded data and gestures for both APKs; fixtures exist only in the test APK.
set -euo pipefail
PKG=ru.nspu.schedule
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
TEST_APK="${2:-app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk}"
OUT="${3:-performance-results/current}"
VERIFY="${4:-true}"
mkdir -p "$OUT"
adb wait-for-device
adb install -r "$APK"
adb install -r "$TEST_APK"
adb shell am force-stop "$PKG"
adb logcat -c
{
  echo "Android: $(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "Device: $(adb shell getprop ro.product.model | tr -d '\r')"
  echo "APK bytes: $(stat -c%s "$APK")"
  echo "Fixture: 32 lessons/day at home; 48 teacher lessons/week; no HTTP required"
} > "$OUT/environment.txt"
adb shell am instrument -w -r -e verifyCards "$VERIFY" \
  -e class ru.nspu.schedule.ScheduleScrollTest \
  ru.nspu.schedule.test/androidx.test.runner.AndroidJUnitRunner | tee "$OUT/instrumentation.txt"
REMOTE="/sdcard/Android/data/$PKG/files/performance"
for name in home-frames.txt teacher-frames.txt home-memory.txt teacher-memory.txt home.png teacher.png room.png; do
  adb pull "$REMOTE/$name" "$OUT/$name" || true
done
adb logcat -d > "$OUT/logcat.txt"
grep -q '^OK (' "$OUT/instrumentation.txt"
! grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed' "$OUT/instrumentation.txt"
