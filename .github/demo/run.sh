#!/usr/bin/env bash
# Runs on the emulator host: install the APK, record the screen, run the tour.
set -uo pipefail

PKG=io.github.rayvader.kirocrew
OUT=demo-out
mkdir -p "$OUT"

# Play services restarts about a minute after boot and takes apps with it. Let it pass.
sleep 60
adb install -r "$(ls KiroCrew-*.apk | head -1)"
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS

# Android stops a recording at 3 minutes, so record back-to-back pieces.
rm -f /tmp/stoprec
(
  for i in 1 2 3 4; do
    [[ -f /tmp/stoprec ]] && break
    adb shell screenrecord --bit-rate 8000000 --time-limit 175 "/sdcard/tour$i.mp4"
  done
) &
REC=$!
sleep 2

python3 .github/demo/tour.py
rc=$?

touch /tmp/stoprec
adb shell pkill -INT screenrecord || true
wait "$REC" 2>/dev/null || true
sleep 2
for i in 1 2 3 4; do adb pull "/sdcard/tour$i.mp4" "$OUT/tour$i.mp4" >/dev/null 2>&1 || true; done
# No logcat in the artifact: it can hold the sign-in link, and artifacts are public.
exit $rc
