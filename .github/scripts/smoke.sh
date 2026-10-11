#!/usr/bin/env bash
# Emulator smoke test: install the APK, add a server through the real UI,
# load a test page over HTTP and check the page bridge answers.
set -euo pipefail

PKG=io.github.rayvader.kirocrew
APK=$(ls KiroCrew-*.apk | head -1)
OUT=smoke
mkdir -p "$OUT"

dump_ui() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null
}

tap() {
  # $1 = visible text or resource id
  dump_ui
  read -r x y < <(python3 .github/scripts/find_bounds.py "$OUT/ui.xml" "$1")
  adb shell input tap "$x" "$y"
  sleep 2
}

adb logcat -c || true
adb install -r "$APK"
adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
adb exec-out screencap -p > "$OUT/1-welcome.png"

tap "Add server"
tap "$PKG:id/url"
adb shell input text "http://10.0.2.2:8000"
sleep 1
adb exec-out screencap -p > "$OUT/2-add-server.png"
tap "Save"
sleep 8
adb exec-out screencap -p > "$OUT/3-dashboard-page.png"

dump_ui
cp "$OUT/ui.xml" "$OUT/page-ui.xml"
adb logcat -d > "$OUT/logcat.txt" || true

if ! adb shell pidof "$PKG" >/dev/null; then
  echo "App is not running after the test"
  adb logcat -d -b crash || true
  exit 1
fi
grep -q "Kiro Crew smoke page" "$OUT/page-ui.xml" || { echo "Test page did not load in the WebView"; exit 1; }
grep -q "Bridge OK" "$OUT/page-ui.xml" || { echo "Page bridge did not answer"; exit 1; }

# Deep link into another page must not crash the app.
adb shell am start -W -a android.intent.action.VIEW -d "kirocrew://open?path=/schedule" "$PKG" >/dev/null
sleep 3
adb shell pidof "$PKG" >/dev/null || { echo "Deep link crashed the app"; exit 1; }
echo "Smoke test passed"
