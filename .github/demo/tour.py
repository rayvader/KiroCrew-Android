"""Demo tour of the Kiro Crew app against a live demo gateway.

Signs in with the DEMO_SIGNIN_URL link, then walks the main features with
pauses long enough to film. Prints one "SCENE <seconds> <name>" line per scene
(seconds since the recording started), so the captions can be timed later.
Never prints the sign-in link.
"""
import os
import re
import subprocess
import sys
import time
import urllib.parse
import xml.etree.ElementTree as ET

PKG = "io.github.rayvader.kirocrew"
HOST = os.environ["DEMO_HOST"]
T0 = time.time()


def log(msg):
    print(f"[tour {time.time() - T0:6.1f}] {msg}", flush=True)


def scene(name):
    print(f"SCENE {time.time() - T0:.1f} {name}", flush=True)


def sh(*args, timeout=60):
    return subprocess.run(["adb", "shell", *args], capture_output=True, text=True, timeout=timeout).stdout


def pause(s=1.5):
    time.sleep(s)


def nodes():
    for _ in range(3):
        sh("uiautomator", "dump", "/sdcard/ui.xml")
        raw = subprocess.run(["adb", "exec-out", "cat", "/sdcard/ui.xml"],
                             capture_output=True, text=True, timeout=30).stdout
        try:
            root = ET.fromstring(raw)
            break
        except ET.ParseError:
            time.sleep(1)
    else:
        return []
    out = []
    for n in root.iter("node"):
        m = re.findall(r"\d+", n.get("bounds", ""))
        if len(m) != 4:
            continue
        x1, y1, x2, y2 = map(int, m)
        out.append(dict(text=n.get("text", ""), desc=n.get("content-desc", ""),
                        cls=n.get("class", ""), hint=n.get("hint", ""),
                        x=(x1 + x2) // 2, y=(y1 + y2) // 2))
    return out


def find(label, exact=True, below=0):
    for n in nodes():
        if n["y"] < below:
            continue
        for v in (n["text"], n["desc"], n["hint"]):
            if v and ((v == label) if exact else (label.lower() in v.lower())):
                return n["x"], n["y"]
    return None


def tap_xy(x, y, wait=1.5):
    sh("input", "tap", str(x), str(y))
    pause(wait)


def tap(label, exact=True, wait=1.5, tries=4, below=0):
    for _ in range(tries):
        pos = find(label, exact, below)
        if pos:
            log(f"tap '{label}'")
            tap_xy(*pos, wait=wait)
            return True
        pause(1)
    log(f"MISSING '{label}'")
    return False


def type_text(s):
    sh("input", "text", "'" + s.replace(" ", "%s").replace("'", "") + "'")
    pause(0.8)


def size():
    return tuple(map(int, re.findall(r"(\d+)x(\d+)", sh("wm", "size"))[-1]))


def swipe(up=True, wait=1.5, frac=(0.7, 0.35)):
    w, h = size()
    a, b = int(h * frac[0]), int(h * frac[1])
    if not up:
        a, b = b, a
    sh("input", "swipe", str(w // 2), str(a), str(w // 2), str(b), "700")
    pause(wait)


def back(wait=1.5):
    sh("input", "keyevent", "4")
    pause(wait)


def bottom(label, wait=5):
    _, h = size()
    return tap(label, wait=wait, below=int(h * 0.8))


def open_settings():
    tap("More options", tries=3)
    tap("Settings", wait=2.5)


def scroll_to(label, tries=10):
    for _ in range(tries):
        if find(label):
            return True
        swipe(up=True, wait=1)
    return False


def sign_in():
    link = os.environ["DEMO_SIGNIN_URL"].strip()
    tok = urllib.parse.parse_qs(urllib.parse.urlparse(link).query)["token"][0]
    url = f"https://{HOST}/?token={urllib.parse.quote(tok, safe='')}"
    sh("am", "start", "-W", "-n", f"{PKG}/.MainActivity")
    for _ in range(20):
        if find("Later"):
            tap("Later", tries=1)
        if find("Add server") and not find("Later"):
            break
        pause(1)
    scene("welcome")
    pause(3)
    scene("add server")
    tap("Add server", tries=8)
    tap("Name", wait=0.6)
    type_text("My crew")
    tap("Dashboard address", wait=0.6)
    # With a port and no scheme the app picks plain http, so say https.
    type_text("https://" + HOST)
    sh("input", "keyevent", "111")
    tap("Save", wait=3)
    # Signing in is a one-time link: open it in the app, off camera.
    deep = "kirocrew://open?path=" + urllib.parse.quote(url, safe="")
    sh("am", "start", "-W", "-a", "android.intent.action.VIEW", "-d", f"'{deep}'")
    log("signed in, waiting for dashboard")
    pause(10)
    for label in ("Allow", "While using the app"):
        if find(label):
            tap(label, tries=1)


def main():
    sh("settings", "put", "system", "show_touches", "1")
    sh("settings", "put", "system", "accelerometer_rotation", "0")
    sh("settings", "put", "system", "user_rotation", "0")
    sign_in()

    scene("chat")
    pause(4)
    box = None
    for n in nodes():
        if n["cls"] == "android.widget.EditText" or "message" in (n["hint"] + n["text"]).lower():
            box = (n["x"], n["y"])
    if box:
        tap_xy(*box, wait=1.2)
        type_text("Give me three quick tips for a great app demo video")
        sh("input", "keyevent", "66")
        pause(3)
        sh("input", "keyevent", "111")
        pause(25)
        swipe(up=True, wait=3)
    else:
        log("MISSING chat box")

    for page in ("Schedule", "Artifacts", "Alerts"):
        scene(page.lower())
        if bottom(page, wait=5):
            swipe(up=True, wait=2.5)
            swipe(up=False, wait=2)

    scene("share")
    bottom("Chat", wait=4)
    sh("am", "start", "-W", "-a", "android.intent.action.SEND", "-t", "text/plain",
       "--es", "android.intent.extra.TEXT", "'Summarize this page: https://kiro.dev'",
       "-n", f"{PKG}/.MainActivity")
    pause(7)

    scene("servers")
    tap("Servers", wait=4)
    back(wait=2)

    scene("ghost")
    open_settings()
    if scroll_to("Show top bar"):
        tap("Show top bar", wait=1.5)
    back(wait=3)
    tap("Menu", wait=2.5)
    back(wait=1.5)
    open_settings()
    if scroll_to("Show top bar"):
        tap("Show top bar", wait=1.5)

    scene("theme")
    if scroll_to("Accent color"):
        tap("Accent color", wait=1.5)
        tap("Ocean blue", tries=2, wait=4) or back()
        if scroll_to("Accent color"):
            tap("Accent color", wait=1.5)
            tap("Kiro purple", tries=2, wait=3) or back()

    scene("about")
    if scroll_to("Past changes", tries=1) or scroll_to("About", tries=14):
        for n in nodes():
            if n["text"].startswith("Version "):
                tap_xy(n["x"], n["y"], wait=5)
                break
    pause(4)
    scene("past changes")
    tap("Past changes", wait=6)
    swipe(up=True, wait=3)
    back(wait=2)
    back(wait=2)
    back(wait=2)

    scene("rotate")
    sh("settings", "put", "system", "user_rotation", "1")
    pause(6)
    sh("settings", "put", "system", "user_rotation", "0")
    pause(4)

    scene("outro")
    bottom("Chat", wait=5)
    log("done")


if __name__ == "__main__":
    try:
        main()
    except Exception as e:
        log(f"ERROR {e!r}")
        sys.exit(1)
