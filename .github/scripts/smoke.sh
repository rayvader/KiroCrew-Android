#!/usr/bin/env bash
# Emulator smoke test: install the APK, add a server through the real UI,
# load a test page over HTTP and check the page bridge answers.
set -Eeuo pipefail

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
# Keep the log and what is on screen whenever a step fails, so a failed run explains itself.
trap 'adb logcat -d > "$OUT/logcat.txt" 2>/dev/null || true; adb logcat -d -b crash > "$OUT/crash.txt" 2>/dev/null || true; adb shell dumpsys activity activities > "$OUT/activities.txt" 2>/dev/null || true' ERR
# On a fresh emulator Google Play services restarts about half a minute after boot,
# and Android kills apps that use its font provider with it. Let that pass first.
sleep 60
adb install -r "$APK"
# Grant up front so Android's permission dialog does not cover the screens below.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
adb shell am start -W -n "$PKG/.MainActivity"
sleep 6
adb exec-out screencap -p > "$OUT/1-welcome.png"

tap "Add server"
tap "$PKG:id/url"
adb shell input text "http://10.0.2.2:8000"
sleep 1
adb exec-out screencap -p > "$OUT/2-add-server.png"
adb shell input keyevent 66 # Enter = Done, saves the server
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
grep -q "Notify OK granted" "$OUT/page-ui.xml" || { echo "Notification stand-in missing or permission not granted"; exit 1; }

# Background the app: the page raises a "chat finished" toast, which must become an Android notification.
adb shell input keyevent KEYCODE_HOME
sleep 5
adb shell dumpsys notification --noredact > "$OUT/notifications.txt" || true
adb shell cmd statusbar expand-notifications || true
sleep 2
adb exec-out screencap -p > "$OUT/4-notification.png"
adb shell cmd statusbar collapse || true
grep -q "Smoke chat finished" "$OUT/notifications.txt" || { echo "Page notification did not reach Android"; exit 1; }

# Deep link into another page must not crash the app.
adb shell am start -W -a android.intent.action.VIEW -d "kirocrew://open?path=/schedule" "$PKG" >/dev/null
sleep 3
adb shell pidof "$PKG" >/dev/null || { echo "Deep link crashed the app"; exit 1; }
echo "Smoke test passed"
