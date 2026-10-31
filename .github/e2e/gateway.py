"""Fake Kiro Crew gateway for the emulator feature tests.

It speaks the parts of the dashboard API the app relies on:

- dashboard pages (a tiny single-page app that reports what the app gave it)
- login cookies, GET /api/notifications, GET /api/stream (SSE), POST /api/auth/refresh
- a normal file download
- the crewmate Froyo's thread, dashboard (open items) and POST /api/chat, for the widget

Every Froyo item here is made up for the tests.

Test-only controls live under /test/ (push a note, expire the login, read the
request log). The emulator reaches this host as 10.0.2.2.

Usage: python3 gateway.py --port 8000 --name Primary
"""
import argparse
import json
import queue
import secrets
import threading
import time
from datetime import date, datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

PAGE = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Test gateway __NAME__</title>
<style>
  body { font-family: sans-serif; background: #1b1530; color: #fff; padding: 16px; margin: 0; }
  h1 { font-size: 24px; margin: 0 0 8px; }
  p { font-size: 18px; margin: 6px 0; }
  a, button { display: inline-block; font-size: 18px; margin: 6px 6px 6px 0; padding: 10px 14px;
              color: #fff; background: #6b4fd8; border: 0; border-radius: 8px; text-decoration: none; }
</style>
</head>
<body>
<h1>Kiro Crew test gateway __NAME__</h1>
<p id="path">Path: ?</p>
<p id="bridge">Bridge missing</p>
<p id="notify">Notify missing</p>
<p id="ua">UA tail: ?</p>
<p id="mobile">Mobile UA: ?</p>
<p id="bg">Background: ?</p>
<p>
  <button id="blob" onclick="saveBlob()">Download blob</button>
  <a href="/files/report.txt">Download report</a>
</p>
<p>
  <a href="/logs">Go to logs</a>
  <a href="https://example.com/">External site</a>
  <button id="switch" onclick="switchSession()">Switch session</button>
</p>
<p><textarea data-composer-input id="composer" rows="2" cols="24" placeholder="Message"></textarea></p>
<p id="shared">Shared: none</p>
<script>
  // Like the dashboard's chat box: "mc:prefill-composer" adds text, a file dropped on it is attached.
  window.addEventListener('mc:prefill-composer', function (e) {
    var c = document.getElementById('composer'), t = e.detail && e.detail.text;
    if (!t) return;
    c.value = c.value ? c.value + '\\n' + t : t;
    document.getElementById('shared').textContent = 'Shared text: ' + c.value.replace(/\\n/g, ' | ');
  });
  document.body.addEventListener('drop', function (e) {
    if (!e.dataTransfer || !e.dataTransfer.files.length) return;
    e.preventDefault();
    var f = e.dataTransfer.files[0];
    f.text().then(function (t) {
      document.getElementById('shared').textContent = 'Shared file: ' + f.name + ' ' + t.trim();
    });
  });
</script>
<script>
  // Like the dashboard: each in-app page switch renames the tab a moment later, with no page load.
  function show() {
    document.getElementById('path').textContent = 'Path: ' + location.pathname;
    setTimeout(function () { document.title = 'Test ' + location.pathname.slice(1) + ' page'; }, 50);
  }
  show();
  window.addEventListener('popstate', show);
  var A = window.KiroCrewAndroid;
  document.getElementById('bridge').textContent = A ? 'Bridge OK ' + A.appVersion() : 'Bridge missing';
  var ua = navigator.userAgent, i = ua.indexOf('KiroCrewAndroid/');
  document.getElementById('ua').textContent = 'UA tail: ' + (i < 0 ? 'missing' : ua.slice(i));
  document.getElementById('mobile').textContent = 'Mobile UA: ' + (/ Mobile/.test(ua) ? 'yes' : 'no');
  if (typeof Notification === 'function' && window.KiroCrewNotify) {
    document.getElementById('notify').textContent = 'Notify OK ' + Notification.permission;
  }
  // Only write when it changes: a constantly changing page never looks idle to uiautomator.
  setInterval(function () {
    var el = document.getElementById('bg');
    var text = 'Background: ' + getComputedStyle(document.body).backgroundColor;
    if (el.textContent !== text) el.textContent = text;
  }, 500);
  // Like picking another session in the dashboard: the page changes its own URL, then
  // renames itself, then the unread count in front of the name changes on its own.
  function switchSession() {
    history.pushState({}, '', '/chat?session=b');
    show();
    setTimeout(function () { document.title = 'Session B page'; }, 30);
    setTimeout(function () { document.title = '(3) Session B page'; }, 1500);
  }
  function saveBlob() {
    var url = URL.createObjectURL(new Blob(['hello from the test gateway\\n'], { type: 'text/plain' }));
    var a = document.createElement('a');
    a.href = url; a.download = 'e2e-blob.txt';
    document.body.appendChild(a); a.click(); a.remove();
  }
  // Like the dashboard: a finished chat raises a browser toast while the app is in the background.
  // Only when a test armed one (/test/arm-toast), so a stray pop-up never covers other screens.
  document.addEventListener('visibilitychange', function () {
    if (!document.hidden || typeof Notification !== 'function') return;
    fetch('/test/take-toast').then(function (r) { return r.text(); }).then(function (t) {
      if (t !== '1') return;
      setTimeout(function () {
        new Notification('E2E chat finished', { body: 'The test chat is done', tag: 'kirocrew-chat-done:e2e' });
      }, 1000);
    });
  });
</script>
</body>
</html>
"""


class State:
    def __init__(self, name):
        self.name = name
        self.lock = threading.Lock()
        self.sessions = set()
        self.refresh_allowed = True
        self.toast_armed = False
        # What GET /api/update/check reports: no update until a test sets one.
        self.gw_update = {"latest": "", "status": "succeeded"}
        self.notes = []
        self.listeners = []
        self.log = []
        # Froyo's open items and the chat messages sent to its thread.
        self.froyo = froyo_items()
        self.chats = []

    def now_ts(self):
        # Strictly increasing timestamps, so two notes pushed back to back never tie.
        with self.lock:
            ts = datetime.now(timezone.utc)
            if self.notes:
                last = datetime.fromisoformat(self.notes[-1]["ts"])
                if ts <= last:
                    ts = last.replace(microsecond=min(last.microsecond + 1000, 999999))
            return ts.isoformat(timespec="milliseconds")

    def push(self, note):
        with self.lock:
            self.notes.append(note)
            for q in list(self.listeners):
                q.put(note)


FROYO_SLUG = "froyo-personal-butler"
FROYO_SLOT = "member-froyo-e2e"
FROYO_MEMBER = "Froyo E2E"


def froyo_items():
    """Made-up items, dated from today: one late, one today, two later, one with no date."""
    today = date.today()
    d = lambda n: (today + timedelta(days=n)).isoformat()
    return [
        {"n": 1, "title": "E2E test parcel", "due": d(-1), "kind": "delivery", "flag": None},
        {"n": 2, "title": "E2E test dinner", "due": d(0), "kind": "booking", "flag": None},
        {"n": 3, "title": "E2E test flight", "due": d(3) + " 14:10", "kind": "flight", "flag": None},
        {"n": 4, "title": "E2E test form", "due": d(10), "kind": "deadline", "flag": None},
        {"n": 5, "title": "E2E test renewal", "due": None, "kind": "bill", "flag": None},
    ]


def froyo_dashboard(items):
    """Like GET /api/members/{slug}/dashboard: the page's values sit in a JSON data island."""
    island = json.dumps({"fields": {"open_items": items, "items_as_of": "now"}, "agentic": ["open_items"]})
    island = island.replace("<", "\\u003c").replace(">", "\\u003e").replace("&", "\\u0026")
    html = ('<!doctype html><html><head><title>Froyo</title></head><body>'
            '<script type="application/json" id="kirocrew-dashboard-data">' + island + '</script>'
            '<div id="open"></div></body></html>')
    return json.dumps({"state": "live", "template": "e2e", "rendered_html": html})


def make_handler(state):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):
            pass

        # ---- helpers -------------------------------------------------------------------------

        def cookies(self):
            out = {}
            for part in (self.headers.get("Cookie") or "").split(";"):
                if "=" in part:
                    k, v = part.strip().split("=", 1)
                    out[k] = v
            return out

        def signed_in(self):
            return self.cookies().get("kc_session") in state.sessions

        def record(self, status):
            u = urlparse(self.path)
            if u.path.startswith("/test/"):
                return
            with state.lock:
                state.log.append({
                    "t": time.time(),
                    "method": self.command,
                    "path": u.path,
                    "status": status,
                    "cookie": "kc_session" in self.cookies(),
                    "ua": self.headers.get("User-Agent", ""),
                })

        def send(self, status, body=b"", ctype="text/plain", headers=None):
            if isinstance(body, str):
                body = body.encode()
            self.send_response(status)
            self.send_header("Content-Type", ctype)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            for k, v in (headers or []):
                self.send_header(k, v)
            self.end_headers()
            if self.command != "HEAD":
                self.wfile.write(body)
            self.record(status)

        def new_session_cookies(self):
            token = secrets.token_hex(8)
            with state.lock:
                state.sessions.add(token)
            return [
                ("Set-Cookie", f"kc_session={token}; Path=/; Max-Age=86400"),
                ("Set-Cookie", "kc_refresh=r1; Path=/; Max-Age=86400; HttpOnly"),
            ]

        # ---- routes --------------------------------------------------------------------------

        def do_HEAD(self):
            self.do_GET()

        def do_GET(self):
            u = urlparse(self.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            p = u.path
            if p == "/test/log":
                with state.lock:
                    return self.send(200, json.dumps(state.log), "application/json")
            if p == "/test/push":
                note = {
                    "ts": state.now_ts(),
                    "title": q.get("title", "Test note"),
                    "body": q.get("body", "**Hello** from the test gateway"),
                    "url": q.get("url"),
                    "priority": q.get("priority", "normal"),
                    "silenced": q.get("silenced") == "1",
                    "acked": False,
                }
                state.push(note)
                return self.send(200, json.dumps(note), "application/json")
            if p == "/test/arm-toast":
                with state.lock:
                    state.toast_armed = True
                return self.send(200, "armed")
            if p == "/test/take-toast":
                with state.lock:
                    armed, state.toast_armed = state.toast_armed, False
                return self.send(200, "1" if armed else "0")
            if p == "/test/refresh-policy":
                with state.lock:
                    state.refresh_allowed = q.get("allow", "1") == "1"
                return self.send(200, "ok")
            if p == "/test/expire":
                with state.lock:
                    state.sessions.clear()
                    state.refresh_allowed = q.get("refresh", "allow") == "allow"
                return self.send(200, "expired")
            if p == "/test/gw-update":
                # Set what /api/update/check answers: latest=VERSION, status=succeeded|failed|unchecked.
                with state.lock:
                    state.gw_update = {"latest": q.get("latest", ""), "status": q.get("status", "succeeded")}
                return self.send(200, "ok")
            if p == "/test/froyo":
                # mode=reset (the made-up list) or empty (nothing open).
                with state.lock:
                    state.froyo = [] if q.get("mode") == "empty" else froyo_items()
                    state.chats = []
                return self.send(200, "ok")
            if p == "/test/chats":
                with state.lock:
                    return self.send(200, json.dumps(state.chats), "application/json")
            if p == f"/api/members/{FROYO_SLUG}/dashboard":
                if not self.signed_in():
                    return self.send(401, "{}", "application/json")
                if q.get("member") != FROYO_MEMBER:
                    return self.send(400, json.dumps({"code": "missing_member"}), "application/json")
                with state.lock:
                    items = list(state.froyo)
                return self.send(200, froyo_dashboard(items), "application/json")
            if p == "/api/update/check":
                if not self.signed_in():
                    return self.send(401, "{}", "application/json")
                with state.lock:
                    gw = dict(state.gw_update)
                checked = gw["status"] == "succeeded"
                body = {
                    "check_status": gw["status"],
                    "update_available": (bool(gw["latest"]) if checked else None),
                    "latest_version": gw["latest"],
                    "latest_version_display": gw["latest"],
                    "current_version": "0.11.0",
                }
                return self.send(200, json.dumps(body), "application/json")
            if p == "/api/notifications":
                if not self.signed_in():
                    return self.send(401, json.dumps({"error": "auth"}), "application/json")
                with state.lock:
                    feed = list(reversed(state.notes))
                return self.send(200, json.dumps({"notifications": feed}), "application/json")
            if p == "/api/stream":
                return self.stream()
            if p == "/files/report.txt":
                return self.send(200, "Test report from the fake gateway\n", "text/plain",
                                 [("Content-Disposition", 'attachment; filename="report.txt"')])
            if p == "/favicon.ico":
                return self.send(404)
            # Any other path is a dashboard page. A missing or old login gets a fresh one.
            headers = [] if self.signed_in() else self.new_session_cookies()
            self.send(200, PAGE.replace("__NAME__", state.name), "text/html; charset=utf-8", headers)

        def do_POST(self):
            u = urlparse(self.path)
            length = int(self.headers.get("Content-Length") or 0)
            raw = self.rfile.read(length) if length else b""
            if u.path in (f"/api/members/{FROYO_SLUG}/thread", "/api/chat"):
                # Like the gateway's CSRF check: a POST without the dashboard's Origin is refused.
                if not (self.headers.get("Origin") or "").startswith("http://10.0.2.2:"):
                    return self.send(403, "CSRF check failed: request origin not allowed.")
                if not self.signed_in():
                    return self.send(401, "{}", "application/json")
            if u.path == f"/api/members/{FROYO_SLUG}/thread":
                body = {"slot_key": FROYO_SLOT, "slug": FROYO_SLUG, "member": FROYO_MEMBER}
                return self.send(200, json.dumps(body), "application/json")
            if u.path == "/api/chat":
                try:
                    msg = json.loads(raw or b"{}")
                except ValueError:
                    return self.send(400, "{}", "application/json")
                text, slot = str(msg.get("message", "")), msg.get("slot")
                with state.lock:
                    state.chats.append({"slot": slot, "message": text})
                    # Like Froyo's ack rules: "Done: <title>" acks it, "Undo done: <title>" brings it back.
                    if slot == FROYO_SLOT and text.startswith("Done: "):
                        state.froyo = [i for i in state.froyo if i["title"] != text[6:]]
                    elif slot == FROYO_SLOT and text.startswith("Undo done: "):
                        back = [i for i in froyo_items() if i["title"] == text[11:]]
                        state.froyo = [i for i in state.froyo if i["title"] != text[11:]] + back
                return self.send(200, "data: [DONE]\n\n", "text/event-stream")
            if u.path == "/api/auth/refresh":
                if state.refresh_allowed and self.cookies().get("kc_refresh") == "r1":
                    return self.send(200, "{}", "application/json", self.new_session_cookies()[:1])
                return self.send(401, "{}", "application/json")
            self.send(404)

        def stream(self):
            if not self.signed_in():
                return self.send(401, "{}", "application/json")
            q = queue.Queue()
            with state.lock:
                state.listeners.append(q)
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "close")
            self.end_headers()
            self.record(200)
            self.close_connection = True
            try:
                while True:
                    try:
                        note = q.get(timeout=5)
                        frame = "event: notification\ndata: " + json.dumps(note) + "\n\n"
                    except queue.Empty:
                        frame = 'event: status\ndata: {"ok":true}\n\n'
                    self.wfile.write(frame.encode())
                    self.wfile.flush()
            except (BrokenPipeError, ConnectionResetError, OSError):
                pass
            finally:
                with state.lock:
                    state.listeners.remove(q)

    return Handler


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8000)
    ap.add_argument("--name", default="Primary")
    args = ap.parse_args()
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(State(args.name)))
    server.daemon_threads = True
    print(f"fake gateway {args.name} on 127.0.0.1:{args.port}", flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
