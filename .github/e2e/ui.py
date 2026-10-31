"""Read a uiautomator dump.

  ui.py find   DUMP TEXT       centre "x y" of the first node whose text, content-desc
                               or resource-id equals TEXT (case-insensitive)
  ui.py menu   DUMP TEXT       like find, but only popup and overflow menu items
  ui.py has    DUMP TEXT       exit 0 when any node's text or content-desc contains TEXT
  ui.py switch DUMP TITLE      "x y true|false" for the settings row titled TITLE
  ui.py window DUMPSYS FLAG    exit 0 when the app's main window has FLAG (from dumpsys window)
  ui.py jobs   DUMPSYS PKG     job ids the app has scheduled (from dumpsys jobscheduler)
  ui.py widget DUMP -          Froyo widget rows shown; exit 1 when rows overlap or stick out
  ui.py top    DUMP TEXT       top y of the node whose text equals TEXT
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


def box(node):
    m = BOUNDS.match(node.get("bounds", ""))
    return tuple(map(int, m.groups())) if m else None


def widget(path, _arg):
    """The Froyo widget's rows: prints how many show; exit 1 when one overlaps another,
    the header, or sticks out of the widget."""
    found = {}
    for node in nodes(path):
        rid = node.get("resource-id", "")
        for key in ("froyo_root", "froyo_header", "froyo_row1", "froyo_row2", "froyo_row3", "froyo_row4"):
            if rid.endswith(":id/" + key) and key not in found:
                found[key] = box(node)
    root = found.get("froyo_root")
    if not root:
        print("no widget on screen")
        return 1
    rows = [found[k] for k in ("froyo_row1", "froyo_row2", "froyo_row3", "froyo_row4") if found.get(k)]
    above = found.get("froyo_header")
    for r in rows:
        if r[1] < root[1] or r[3] > root[3]:
            print("row outside the widget", r, root)
            return 1
        if above and r[1] < above[3]:
            print("row overlaps the one above", r, above)
            return 1
        above = r
    print(len(rows))
    return 0


def top(path, wanted):
    """Top y of the first node whose text equals TEXT."""
    for node in nodes(path):
        if node.get("text", "").lower() == wanted.lower():
            b = box(node)
            if b:
                print(b[1])
                return 0
    return 1


def jobs(path, pkg):
    text = open(path, encoding="utf-8", errors="replace").read()
    ids = sorted(set(re.findall(r"JOB #u\d+a\d+/(\d+): \S+ " + re.escape(pkg) + "/", text)))
    print(" ".join(ids))
    return 0


if __name__ == "__main__":
    cmd, path, arg = sys.argv[1], sys.argv[2], sys.argv[3]
    sys.exit({"find": find, "menu": menu, "has": has, "switch": switch, "window": window, "jobs": jobs,
              "widget": widget, "top": top}[cmd](path, arg))
