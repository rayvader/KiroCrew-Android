"""Read a uiautomator dump.

  ui.py find   DUMP TEXT       centre "x y" of the first node whose text, content-desc
                               or resource-id equals TEXT (case-insensitive)
  ui.py menu   DUMP TEXT       like find, but only popup and overflow menu items
  ui.py has    DUMP TEXT       exit 0 when any node's text or content-desc contains TEXT
  ui.py switch DUMP TITLE      "x y true|false" for the settings row titled TITLE
  ui.py window DUMPSYS FLAG    exit 0 when the app's main window has FLAG (from dumpsys window)
  ui.py jobs   DUMPSYS PKG     job ids the app has scheduled (from dumpsys jobscheduler)
"""
import re
import sys
import xml.etree.ElementTree as ET

BOUNDS = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def centre(node):
    m = BOUNDS.match(node.get("bounds", ""))
    if not m:
        return None
    x1, y1, x2, y2 = map(int, m.groups())
    if x2 <= x1 or y2 <= y1:
        return None
    return (x1 + x2) // 2, (y1 + y2) // 2


def nodes(path):
    return list(ET.parse(path).iter("node"))


def find(path, wanted):
    wanted = wanted.lower()
    for node in nodes(path):
        values = [node.get(k, "").lower() for k in ("text", "content-desc", "resource-id")]
        if wanted in values:
            c = centre(node)
            if c:
                print(*c)
                return 0
    return 1


def menu(path, wanted):
    """Like find, but only popup/overflow menu items (they carry an id/title resource id)."""
    for node in nodes(path):
        if node.get("resource-id", "").endswith(":id/title") and node.get("text", "").lower() == wanted.lower():
            c = centre(node)
            if c:
                print(*c)
                return 0
    return 1


def has(path, wanted):
    wanted = wanted.lower()
    for node in nodes(path):
        if wanted in node.get("text", "").lower() or wanted in node.get("content-desc", "").lower():
            return 0
    return 1


def switch(path, title):
    root = ET.parse(path).getroot()
    parent = {child: p for p in root.iter() for child in p}
    for node in root.iter("node"):
        if node.get("text", "").lower() != title.lower():
            continue
        row = node
        while row is not None and row.get("clickable") != "true":
            row = parent.get(row)
        if row is None:
            continue
        for d in row.iter("node"):
            if d.get("checkable") == "true":
                c = centre(row)
                if c:
                    print(c[0], c[1], d.get("checked", "false"))
                    return 0
    return 1


def window(path, flag):
    text = open(path, encoding="utf-8", errors="replace").read()
    for block in re.split(r"\n\s*Window #", text):
        if "io.github.rayvader.kirocrew.MainActivity" in block:
            m = re.search(r"\bfl=([A-Z_ ]+)", block)
            if m and flag in m.group(1).split():
                return 0
    return 1


def jobs(path, pkg):
    text = open(path, encoding="utf-8", errors="replace").read()
    ids = sorted(set(re.findall(r"JOB #u\d+a\d+/(\d+): \S+ " + re.escape(pkg) + "/", text)))
    print(" ".join(ids))
    return 0


if __name__ == "__main__":
    cmd, path, arg = sys.argv[1], sys.argv[2], sys.argv[3]
    sys.exit({"find": find, "menu": menu, "has": has, "switch": switch, "window": window, "jobs": jobs}[cmd](path, arg))
