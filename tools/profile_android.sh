#!/usr/bin/env bash
# Controlled emulator/USB device smoke profiling. No permanent settings are changed.
set -euo pipefail
PKG=ru.nspu.schedule
APK="${1:-app/build/outputs/apk/debug/app-debug.apk}"
OUT="${2:-performance-results}"
mkdir -p "$OUT"
adb wait-for-device
adb install -r "$APK"
{
  echo "App: $PKG"
  echo "Android: $(adb shell getprop ro.build.version.release | tr -d '\r')"
  echo "Device: $(adb shell getprop ro.product.model | tr -d '\r')"
  echo "APK bytes: $(stat -c%s "$APK")"
} > "$OUT/environment.txt"

# Measure cold-ish startup of the same binary five times; avoid comparing emulator
# numbers to a physical phone or calling these comprehensive energy measurements.
for i in 1 2 3 4 5; do
  adb shell am force-stop "$PKG" || true
  adb shell am start -W -n "$PKG/.MainActivity" >> "$OUT/startup.txt"
  sleep 1
done

adb shell dumpsys meminfo "$PKG" > "$OUT/memory.txt"
adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
adb shell input swipe 500 1200 500 380 350 || true
adb shell input swipe 500 1200 500 380 350 || true
adb shell dumpsys gfxinfo "$PKG" > "$OUT/frames.txt"
adb shell dumpsys cpuinfo > "$OUT/cpu-snapshot.txt"
adb exec-out screencap -p > "$OUT/app-screen.png"
{
  echo "# Android emulator/device performance smoke test"
  echo
  echo "The measurements below are hardware- and thermal-dependent; they are not a battery-life benchmark."
  echo
  echo "## Startup samples (ms)"
  grep -E 'TotalTime:|WaitTime:' "$OUT/startup.txt" || true
  echo
  echo "## Memory (system PSS)"
  grep -E 'TOTAL PSS:|TOTAL RSS:|TOTAL ' "$OUT/memory.txt" | tail -n 8 || true
  echo
  echo "## UI frames"
  grep -E 'Total frames rendered:|Janky frames:|90th percentile:|95th percentile:' "$OUT/frames.txt" || true
} > "$OUT/summary.md"
cat "$OUT/summary.md"
