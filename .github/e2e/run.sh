#!/usr/bin/env bash
# Feature tests on the emulator, one per feature in the README.
#
# Each feature runs through the real app UI against the fake gateway
# (.github/e2e/gateway.py on host ports 8000 and 8001, reached as 10.0.2.2),
# is screen-recorded, and ends PASS, FAIL or VIDEO (works, but the result is
# only visible on screen, so check the video).
#
# Output in e2e-out/: results.tsv, logs/, shots/, video/.
set -uo pipefail

PKG=io.github.rayvader.kirocrew
HERE=.github/e2e
OUT=e2e-out
GW=http://127.0.0.1:8000
GW2=http://127.0.0.1:8001
mkdir -p "$OUT/logs" "$OUT/shots" "$OUT/video"
RESULTS="$OUT/results.tsv"
: > "$RESULTS"

# ---- Helpers -----------------------------------------------------------------------------------

log() { echo "[$(date +%T)] $*"; }
fail() { echo "FAIL: $*"; dump >/dev/null 2>&1 && cp "$OUT/ui.xml" "$OUT/logs/${FEATURE_ID:-x}-fail-ui.xml"; return 1; }
check() { echo "CHECK: $*"; }
video_only() { echo "VIDEO: $*"; touch "$OUT/logs/$FEATURE_ID.video"; }

read -r W H < <(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | tr x ' ')

dump() {
  local i
  for i in 1 2 3 4; do
    if adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && adb pull /sdcard/ui.xml "$OUT/ui.xml" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

has_text() { dump && python3 "$HERE/ui.py" has "$OUT/ui.xml" "$1"; }

wait_text() {
  local end=$((SECONDS + ${2:-20}))
  while ((SECONDS < end)); do
    has_text "$1" && return 0
    dismiss_anr
    sleep 1
  done
  fail "not on screen after ${2:-20}s: $1"
}

# Wait until any of the given texts is on screen.
wait_any() {
  local end=$((SECONDS + 20)) t
  while ((SECONDS < end)); do
    if dump; then
      for t in "$@"; do python3 "$HERE/ui.py" has "$OUT/ui.xml" "$t" && return 0; done
    fi
    sleep 1
  done
  fail "none of these on screen: $*"
}

wait_no_text() {
  local end=$((SECONDS + ${2:-20}))
  while ((SECONDS < end)); do
    has_text "$1" || return 0
    sleep 1
  done
  fail "still on screen after ${2:-20}s: $1"
}

tap_xy() { adb shell input tap "$1" "$2"; sleep "${3:-1.5}"; }
scroll_down() { adb shell input swipe $((W / 2)) $((H * 3 / 4)) $((W / 2)) $((H / 3)) 400; sleep 1; }
scroll_up() { adb shell input swipe $((W / 2)) $((H / 3)) $((W / 2)) $((H * 3 / 4)) 400; sleep 1; }

# Tap a node by exact text, content-desc or resource id. Waits for it; no scrolling.
tap_now() {
  local xy i mode=${2:-find}
  for i in 1 2 3 4 5 6; do
    if dump && xy=$(python3 "$HERE/ui.py" "$mode" "$OUT/ui.xml" "$1"); then
      tap_xy $xy
      return 0
    fi
    dismiss_anr
    sleep 1
  done
  fail "cannot find to tap: $1"
}

# Tap a node, scrolling down and then back up to find it.
tap() {
  local xy i
  for i in $(seq 0 14); do
    if dump && xy=$(python3 "$HERE/ui.py" find "$OUT/ui.xml" "$1"); then
      tap_xy $xy
      return 0
    fi
    if ((i < 7)); then scroll_down; else scroll_up; fi
  done
  fail "cannot find to tap: $1"
}

# Set a settings switch to true or false.
set_switch() {
  local title=$1 want=$2 r x y c i
  for i in $(seq 0 14); do
    if dump && r=$(python3 "$HERE/ui.py" switch "$OUT/ui.xml" "$title"); then
      read -r x y c <<<"$r"
      [[ $c == "$want" ]] || tap_xy "$x" "$y"
      dump
      r=$(python3 "$HERE/ui.py" switch "$OUT/ui.xml" "$title") || fail "switch row gone: $title"
      read -r x y c <<<"$r"
      [[ $c == "$want" ]] || fail "switch '$title' is $c, wanted $want"
      return 0
    fi
    if ((i < 7)); then scroll_down; else scroll_up; fi
  done
  fail "no switch titled: $title"
}

# Pick an option in a list setting.
choose() { tap "$1" && tap_now "$2"; }

# Type into the focused field. Spaces become %s, which input text turns back into spaces.
type_text() { adb shell "input text '${1// /%s}'"; sleep 1; }

ok_button() { tap_now "android:id/button1"; }
back() { adb shell input keyevent KEYCODE_BACK; sleep 1.5; }
home() { adb shell input keyevent KEYCODE_HOME; sleep 2; }
# A slow CI emulator sometimes shows "<app> isn't responding" for a system app,
# which covers every screen. Answer "Wait" so the run can go on.
dismiss_anr() {
  local xy
  if has_text "isn't responding" && xy=$(python3 "$HERE/ui.py" find "$OUT/ui.xml" "Wait"); then
    echo "Closed a system 'isn't responding' dialog"
    tap_xy $xy
  fi
}

launch() {
  adb shell cmd statusbar collapse >/dev/null 2>&1 || true
  dismiss_anr
  adb shell am start -W -n "$PKG/.MainActivity" >/dev/null
  sleep 3
}
overflow() { tap_now "More options" && tap_now "$1" menu; }
open_settings() { overflow "Settings" && wait_text "Background alerts" 10; }
page_path() { wait_text "Path: $1" "${2:-20}"; }
app_on_top() { adb shell dumpsys activity activities | grep -m1 -E "topResumedActivity|mResumedActivity" | grep -q "$PKG"; }
app_running() { adb shell pidof "$PKG" >/dev/null; }

gw_mark() { python3 "$HERE/gwlog.py" "${2:-$GW}" count; }
gw_since() { python3 "$HERE/gwlog.py" "$1" since "$2" "${@:3}"; }

notifs() { adb shell dumpsys notification --noredact > "$OUT/notif.txt"; }
has_notif() { notifs && grep -qF "$1" "$OUT/notif.txt"; }
wait_notif() {
  local end=$((SECONDS + ${2:-20}))
  while ((SECONDS < end)); do
    has_notif "$1" && return 0
    sleep 1
  done
  cp "$OUT/notif.txt" "$OUT/logs/$FEATURE_ID-notif.txt"
  fail "no notification after ${2:-20}s: $1"
}
wait_no_notif() {
  local end=$((SECONDS + ${2:-20}))
  while ((SECONDS < end)); do
    has_notif "$1" || return 0
    sleep 1
  done
  fail "notification still there after ${2:-20}s: $1"
}

# Move the emulator clock forward (needs adb root, set up below).
advance_clock() {
  local now
  now=$(adb shell date +%s | tr -d '\r')
  adb shell date -u "$(date -u -d "@$((now + $1))" +%m%d%H%M%Y.%S)" >/dev/null
}

# Run the app's background checks now. WorkManager skips a periodic job that
# starts before its slot, so first move the clock past the 15-minute period.
run_jobs() {
  local ids id
  advance_clock 960
  adb shell dumpsys jobscheduler > "$OUT/jobs.txt"
  ids=$(python3 "$HERE/ui.py" jobs "$OUT/jobs.txt" "$PKG")
  [[ -n $ids ]] || fail "the app has no background jobs scheduled"
  # A job can be rescheduled under a new id between the dump and the run; skip those.
  for id in $ids; do adb shell cmd jobscheduler run -f "$PKG" "$id" >/dev/null || true; done
  sleep 3
}

push_note() { curl -s -G "${GW_TARGET:-$GW}/test/push" "$@" >/dev/null; }

rec_start() {
  adb shell rm -f /sdcard/rec.mp4
  adb shell screenrecord --bit-rate 6000000 --time-limit 175 /sdcard/rec.mp4 >/dev/null 2>&1 &
  REC_PID=$!
  sleep 1
}

rec_stop() {
  adb shell pkill -INT screenrecord >/dev/null 2>&1 || true
  wait "$REC_PID" 2>/dev/null || true
  sleep 1
  adb pull /sdcard/rec.mp4 "$1" >/dev/null 2>&1 || true
}

N=0
FAILED=0
feature() {
  local title=$1 fn=$2 rc status slug
  N=$((N + 1))
  FEATURE_ID=$(printf %02d "$N")
  export FEATURE_ID
  slug=$(echo "$title" | tr 'A-Z' 'a-z' | tr -cs 'a-z0-9' '-' | sed 's/-$//')
  log "=== $FEATURE_ID $title"
  dismiss_anr
  rec_start
  (set -e; "$fn") > "$OUT/logs/$FEATURE_ID-$slug.log" 2>&1
  rc=$?
  adb exec-out screencap -p > "$OUT/shots/$FEATURE_ID-$slug.png" 2>/dev/null || true
  rec_stop "$OUT/video/$FEATURE_ID-$slug.mp4"
  if ((rc != 0)); then
    status=FAIL
    FAILED=$((FAILED + 1))
  elif [[ -f $OUT/logs/$FEATURE_ID.video ]]; then
    status=VIDEO
  else
    status=PASS
  fi
  sed 's/^/    /' "$OUT/logs/$FEATURE_ID-$slug.log"
  log "    -> $status"
  printf '%s\t%s\t%s\t%s\n' "$FEATURE_ID" "$status" "$title" "$slug" >> "$RESULTS"
}

# ---- Features ----------------------------------------------------------------------------------

f_add_server() {
  adb install -r "$(ls KiroCrew-*.apk | head -1)" >/dev/null
  # Granted up front so Android's dialog does not cover the screens below.
  adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
  launch
  wait_text "Connect to Kiro Crew"
  check "first start shows the welcome screen"
  tap_now "How to connect"
  wait_text "Connect your phone"
  wait_text "my-host.tailnet.ts.net:5476"
  check "the welcome screen opens the How to connect guide"
  tap_now "Got it"
  wait_no_text "Address examples" 10
  tap_now "Add server"
  tap_now "$PKG:id/url"
  # No scheme typed: host:port must become http://.
  type_text "10.0.2.2:8000"
  adb shell input keyevent KEYCODE_ENTER
  wait_text "Kiro Crew test gateway Primary" 30
  check "typed host:port is saved as http:// and the dashboard loads"
  page_path /chat
  check "opens the default start page (/chat)"
  wait_text "Bridge OK"
  check "page bridge window.KiroCrewAndroid answers"
  wait_text "UA tail: KiroCrewAndroid/"
  check "user agent carries KiroCrewAndroid/<version>"
  wait_text "Notify OK granted"
  check "page sees a working Notification API"
}

f_fast_nav() {
  launch
  local mark
  mark=$(gw_mark)
  tap_now "Schedule"
  page_path /schedule
  check "bottom bar Schedule opens /schedule"
  tap_now "Alerts"
  page_path /notifications
  check "bottom bar Alerts opens /notifications"
  tap_now "Chat"
  page_path /chat
  [[ -z $(gw_since "$GW" "$mark" GET page) ]] || fail "bottom bar reloaded the page from the server"
  check "fast page switching: no full page reload"
}

f_deep_link() {
  adb shell am start -W -a android.intent.action.VIEW -d "kirocrew://open?path=/artifacts" "$PKG" >/dev/null
  page_path /artifacts
  check "kirocrew://open?path=/artifacts opens that page"
}

f_back_and_relaunch() {
  launch
  tap "Go to logs"
  page_path /logs
  back
  page_path /artifacts
  check "back goes to the previous page"
  # Fresh start: one page in history, so back now asks to press again.
  adb shell am force-stop "$PKG"
  local mark
  mark=$(gw_mark)
  launch
  page_path /chat 30
  gw_since "$GW" "$mark" GET /chat | grep -q "cookie=1" || fail "login cookie was not sent after a restart"
  check "login cookie kept between launches"
  back
  app_on_top || fail "first back closed the app"
  check "first back on the last page stays (press back again to exit)"
  back
  sleep 1
  ! app_on_top || fail "second back did not leave the app"
  check "second back leaves the app"
}

f_rotate() {
  launch
  page_path /chat
  local mark
  mark=$(gw_mark)
  adb shell settings put system accelerometer_rotation 0
  adb shell settings put system user_rotation 1
  sleep 4
  wait_text "Kiro Crew test gateway Primary"
  adb shell settings put system user_rotation 0
  sleep 3
  [[ -z $(gw_since "$GW" "$mark" GET page) ]] || fail "turning the phone reloaded the page"
  check "turning the phone does not reload the page"
}

f_page_notification() {
  launch
  page_path /chat
  curl -s "$GW/test/arm-toast" >/dev/null
  home
  wait_notif "E2E chat finished" 20
  grep -qF "live:kirocrew-chat-done:e2e" "$OUT/notif.txt" || fail "chat toast was not posted as a live notification"
  check "a 'chat finished' toast from the page becomes an Android notification"
  launch
}

f_instant_alerts() {
  launch
  open_settings
  choose "Background alerts" "Instant (uses more battery)"
  back
  wait_notif "Instant alerts are on" 20
  wait_notif "Connected to 10.0.2.2" 40
  check "instant mode shows its 'Instant alerts are on' notice and connects"
  home
  push_note --data-urlencode "title=E2E instant alert" --data-urlencode "url=/schedule/e2e-job"
  wait_notif "E2E instant alert" 20
  check "a new bell note arrives within seconds over /api/stream"
  grep -qF "Hello from the test gateway" "$OUT/notif.txt" || fail "note body missing"
  ! grep -qF "**Hello**" "$OUT/notif.txt" || fail "markdown was not cleaned from the body"
  check "markdown in the note body is shown as plain text"
  adb shell cmd statusbar expand-notifications
  sleep 2
  tap_now "E2E instant alert"
  page_path /schedule/e2e-job 30
  check "tapping the notification opens the note's page"
  home
  push_note --data-urlencode "title=E2E muted note" --data-urlencode "silenced=1"
  push_note --data-urlencode "title=E2E passive note" --data-urlencode "priority=passive"
  push_note --data-urlencode "title=E2E second alert"
  wait_notif "E2E second alert" 20
  ! grep -qF "E2E muted note" "$OUT/notif.txt" || fail "muted note was shown"
  ! grep -qF "E2E passive note" "$OUT/notif.txt" || fail "passive note was shown"
  check "muted and passive notes are skipped, like the bell badge"
}

f_periodic_alerts() {
  launch
  open_settings
  choose "Background alerts" "Every 15 minutes"
  back
  wait_no_notif "Instant alerts are on" 20
  check "leaving instant mode removes its notice"
  home
  push_note --data-urlencode "title=E2E periodic alert"
  run_jobs
  wait_notif "E2E periodic alert" 30
  check "the 15-minute background check posts new notes with the app closed"
}

f_login_renewal() {
  home
  curl -s "$GW/test/expire?refresh=allow" >/dev/null
  push_note --data-urlencode "title=E2E after renewal"
  local mark
  mark=$(gw_mark)
  run_jobs
  wait_notif "E2E after renewal" 30
  gw_since "$GW" "$mark" POST /api/auth/refresh | grep -q " 200 " || fail "no successful POST /api/auth/refresh"
  check "an expired login is renewed through /api/auth/refresh, alerts keep coming"
  curl -s "$GW/test/expire?refresh=deny" >/dev/null
  run_jobs
  wait_notif "Sign in again for alerts" 30
  check "when renewal fails, 'Sign in again for alerts' is shown"
  curl -s "$GW/test/refresh-policy?allow=1" >/dev/null
  # Signing in again = loading a dashboard page, which hands out a new login cookie.
  launch
  tap_now "Reload"
  wait_text "Kiro Crew test gateway Primary" 30
  run_jobs
  wait_no_notif "Sign in again for alerts" 30
  check "the sign-in notice goes away after signing in again"
}

# Later features expect the first server, so switch back even when a check above failed.
back_to_primary() {
  launch
  if has_text "Kiro Crew test gateway Second"; then
    tap_now "Servers"
    tap_now "10.0.2.2"
    show_active "cleanup, after tapping 10.0.2.2"
    wait_text "Kiro Crew test gateway Primary" 30
  fi
}

f_servers() {
  trap 'back_to_primary 2>&1 | grep -E "^(--|FAIL)" || true' EXIT
  launch
  tap_now "Servers"
  tap_now "$PKG:id/add"
  tap_now "$PKG:id/name"
  type_text "Lab"
  tap_now "$PKG:id/url"
  type_text "10.0.2.2:8001"
  adb shell input keyevent KEYCODE_ENTER
  wait_text "Kiro Crew test gateway Second" 30
  wait_text "Lab"
  check "a second server can be added and opens"
  # The first background check for a new server only records where its feed is.
  run_jobs
  home
  GW_TARGET=$GW2 push_note --data-urlencode "title=E2E lab alert"
  run_jobs
  wait_notif "E2E lab alert" 30
  grep -qF "android.subText=String (Lab)" "$OUT/notif.txt" || fail "notification does not name the server"
  check "with several servers, a notification names its server"
  launch
  show_active "before switching"
  tap_now "Servers"
  tap_now "10.0.2.2"
  show_active "after tapping 10.0.2.2"
  wait_text "Kiro Crew test gateway Primary" 30
  check "switching servers from the Servers screen"
}

# Diagnostics: which server the app has saved as active (root can read app data).
show_active() {
  echo "-- $1: $(adb shell cat "/data/data/$PKG/shared_prefs/${PKG}_preferences.xml" | grep -oE 'name="active_profile">[^<]*' | tr -d '\r')"
}

f_bottom_bar() {
  launch
  open_settings
  tap "Bottom bar pages"
  tap_now "Artifacts"
  tap_now "Apps"
  ok_button
  back
  dump
  python3 "$HERE/ui.py" find "$OUT/ui.xml" "Apps" >/dev/null || fail "Apps missing from the bottom bar"
  ! python3 "$HERE/ui.py" find "$OUT/ui.xml" "Artifacts" >/dev/null || fail "Artifacts still in the bottom bar"
  check "bottom bar pages can be changed"
  tap_now "Apps"
  page_path /apps
  check "the new bottom bar page opens"
  adb shell dumpsys shortcut > "$OUT/shortcuts.txt"
  grep -q "nav_apps" "$OUT/shortcuts.txt" || fail "launcher shortcut for Apps missing"
  ! grep -q "nav_artifacts" "$OUT/shortcuts.txt" || fail "old launcher shortcut still there"
  check "long-press launcher shortcuts follow the bottom bar"
  open_settings
  tap "Bottom bar pages"
  tap_now "Developer"
  ok_button
  back
  dump
  ! python3 "$HERE/ui.py" find "$OUT/ui.xml" "Developer" >/dev/null || fail "a sixth page was accepted"
  check "at most 5 bottom bar pages"
}

f_start_page() {
  launch
  open_settings
  choose "Start page" "Schedule"
  back
  overflow "Start page"
  page_path /schedule
  check "start page setting is used by the Start page menu item"
}

f_top_bar() {
  launch
  open_settings
  set_switch "Show top bar" false
  back
  dump
  python3 "$HERE/ui.py" find "$OUT/ui.xml" "$PKG:id/menu_fab" >/dev/null || fail "no floating menu button"
  ! python3 "$HERE/ui.py" find "$OUT/ui.xml" "More options" >/dev/null || fail "top bar still shown"
  check "top bar off shows a floating menu button instead"
  tap_now "$PKG:id/menu_fab"
  tap_now "Settings" menu
  set_switch "Show top bar" true
  back
  wait_text "More options"
  check "the floating menu opens Settings; top bar comes back"
}

f_pull_to_refresh() {
  launch
  open_settings
  set_switch "Pull to refresh" true
  back
  sleep 2
  local mark
  mark=$(gw_mark)
  adb shell input swipe $((W / 2)) $((H / 4)) $((W / 2)) $((H * 4 / 5)) 600
  sleep 5
  [[ -n $(gw_since "$GW" "$mark" GET page) ]] || fail "pulling down did not reload"
  check "pull to refresh reloads the page"
}

f_desktop_mode() {
  launch
  wait_text "Mobile UA: yes"
  open_settings
  set_switch "Desktop mode" true
  back
  wait_text "Mobile UA: no" 30
  check "desktop mode drops the mobile user agent and reloads"
  open_settings
  set_switch "Desktop mode" false
  back
  wait_text "Mobile UA: yes" 30
  check "turning it off brings the phone layout back"
}

f_css_and_agent() {
  launch
  open_settings
  tap "User agent suffix"
  type_text "E2E-Agent"
  ok_button
  tap "Custom CSS"
  type_text "body{background:#010203}"
  ok_button
  back
  wait_text "Background: rgb(1, 2, 3)" 20
  check "custom CSS is applied to the page"
  local mark
  mark=$(gw_mark)
  tap_now "Reload"
  wait_text "E2E-Agent" 30
  check "user agent suffix reaches the page"
  gw_since "$GW" "$mark" GET page | grep -q "E2E-Agent" || fail "server did not see the suffix"
  check "user agent suffix reaches the server"
}

f_downloads() {
  launch
  adb shell rm -f /sdcard/Download/e2e-blob.txt "/sdcard/Download/report*.txt"
  tap "Download blob"
  local end=$((SECONDS + 20))
  until adb shell cat /sdcard/Download/e2e-blob.txt 2>/dev/null | grep -q "hello from the test gateway"; do
    ((SECONDS < end)) || fail "blob: download was not saved"
    sleep 1
  done
  check "blob: downloads are saved to Downloads"
  local mark
  mark=$(gw_mark)
  tap "Download report"
  end=$((SECONDS + 40))
  until adb shell ls /sdcard/Download/ 2>/dev/null | grep -q "^report"; do
    ((SECONDS < end)) || fail "normal download was not saved"
    sleep 1
  done
  check "normal downloads go through Android's downloader"
  gw_since "$GW" "$mark" GET /files/report.txt | grep -q "cookie=1" || fail "download did not carry the login cookie"
  check "downloads carry the login cookie"
}

f_external_links() {
  launch
  tap "External site"
  sleep 4
  if app_on_top; then
    echo "No browser took the link (the app shows 'No app can open this')."
  else
    echo "Opened in: $(adb shell dumpsys activity activities | grep -m1 -E 'topResumedActivity|mResumedActivity')"
  fi
  launch
  wait_text "Kiro Crew test gateway Primary"
  ! has_text "Example Domain" || fail "the other site opened inside the app"
  check "links to other sites do not open inside the app"
}

f_privacy_and_screen() {
  launch
  open_settings
  set_switch "Privacy screen" true
  set_switch "Keep screen on" true
  back
  sleep 2
  adb shell dumpsys window windows > "$OUT/window.txt"
  python3 "$HERE/ui.py" window "$OUT/window.txt" SECURE || fail "privacy screen did not set FLAG_SECURE"
  check "privacy screen blocks screenshots (FLAG_SECURE)"
  python3 "$HERE/ui.py" window "$OUT/window.txt" KEEP_SCREEN_ON || fail "keep screen on not set"
  check "keep screen on sets FLAG_KEEP_SCREEN_ON"
  open_settings
  set_switch "Privacy screen" false
  set_switch "Keep screen on" false
  back
  sleep 2
  adb shell dumpsys window windows > "$OUT/window.txt"
  ! python3 "$HERE/ui.py" window "$OUT/window.txt" SECURE || fail "FLAG_SECURE stayed on"
  check "turning them off clears the flags"
}

f_theme() {
  launch
  open_settings
  choose "App theme" "Dark"
  sleep 2
  choose "Accent color" "Emerald"
  sleep 3
  # Settings comes back after the rebuild, still scrolled to the row just changed.
  wait_text "Emerald"
  back
  # The rebuilt WebView draws the page but often exposes no text to uiautomator,
  # so check the page title the app shows in the top bar instead.
  wait_text "Test gateway Primary"
  app_running || fail "app crashed on theme change"
  check "theme and accent change without a crash; the page is back"
  dump
  python3 "$HERE/ui.py" find "$OUT/ui.xml" "10.0.2.2" >/dev/null || fail "top bar lost the server name"
  check "the top bar still shows the server name"
  video_only "the new colors are only visible in the video"
}

f_update_check() {
  launch
  open_settings
  tap "Check now"
  sleep 4
  app_running || fail "update check crashed the app"
  has_text "Kiro Crew for Android 1.0." || fail "version row missing"
  check "Settings shows the app version"
  video_only "the result is a short toast ('You have the newest version'), see the video"
}

f_sign_out() {
  launch
  open_settings
  tap "Sign out"
  ok_button
  local mark
  mark=$(gw_mark)
  back
  sleep 5
  gw_since "$GW" "$mark" GET page | head -1 | grep -q "cookie=0" || fail "page was not reloaded without the login cookie"
  check "sign out clears the login cookie and reloads"
}

f_app_lock() {
  adb shell locksettings set-pin 1234 >/dev/null
  launch
  open_settings
  set_switch "App lock" true
  choose "Lock after" "Right away"
  back
  home
  sleep 2
  launch
  # The system unlock prompt sits on top of the app's own lock screen.
  wait_any "Unlock Kiro Crew" "Kiro Crew is locked"
  check "app lock asks for the screen lock after leaving the app"
  sleep 2
  type_text "1234"
  adb shell input keyevent KEYCODE_ENTER
  wait_no_text "Kiro Crew is locked" 20
  wait_text "Kiro Crew test gateway Primary"
  check "unlocking with the screen lock shows the dashboard again"
  open_settings
  set_switch "App lock" false
  back
  adb shell locksettings clear --old 1234 >/dev/null
}

# ---- Run ---------------------------------------------------------------------------------------

# Root is needed to set the clock (google_apis images allow it).
adb root >/dev/null
sleep 3
adb wait-for-device
[[ $(adb shell id -u | tr -d '\r') == 0 ]] || { echo "adb root is not available on this image"; exit 1; }
adb shell settings put global auto_time 0
# Hide system "isn't responding" pop-ups; a slow CI emulator shows them for the launcher.
adb shell settings put global hide_error_dialogs 1
adb shell svc power stayon true
# Same first-boot Play services restart as in smoke.sh.
sleep 60
adb shell settings put global window_animation_scale 0
adb shell settings put global transition_animation_scale 0
adb shell settings put global animator_duration_scale 0
adb logcat -c || true

feature "Add a server" f_add_server
feature "Bottom bar and fast page switching" f_fast_nav
feature "Deep link" f_deep_link
feature "Back button and staying signed in" f_back_and_relaunch
feature "Turning the phone" f_rotate
feature "Chat finished notification" f_page_notification
feature "Instant alerts" f_instant_alerts
feature "Background alerts every 15 minutes" f_periodic_alerts
feature "Login renewal and sign-in notice" f_login_renewal
feature "Several servers" f_servers
feature "Bottom bar pages and launcher shortcuts" f_bottom_bar
feature "Start page" f_start_page
feature "Top bar off" f_top_bar
feature "Pull to refresh" f_pull_to_refresh
feature "Desktop mode" f_desktop_mode
feature "Custom CSS and user agent" f_css_and_agent
feature "Downloads" f_downloads
feature "Links to other sites" f_external_links
feature "Privacy screen and keep screen on" f_privacy_and_screen
feature "Theme and accent color" f_theme
feature "Update check" f_update_check
feature "Sign out" f_sign_out
feature "App lock" f_app_lock

adb logcat -d > "$OUT/logcat.txt" || true
adb logcat -d -b crash > "$OUT/crash.txt" || true
log "$FAILED of $N features failed"
exit $((FAILED > 0))
