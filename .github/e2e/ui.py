"""Read a uiautomator dump.

  ui.py find   DUMP TEXT       centre "x y" of the first node whose text, content-desc
                               or resource-id equals TEXT (case-insensitive)
  ui.py menu   DUMP TEXT       like find, but only popup and overflow menu items
  ui.py has    DUMP TEXT       exit 0 when any node's text or content-desc contains TEXT
  ui.py switch DUMP TITLE      "x y true|false" for the settings row titled TITLE
  ui.py window DUMPSYS FLAG    exit 0 when the app's main window has FLAG (from dumpsys window)
  ui.py jobs   DUMPSYS PKG     job ids the app has scheduled (from dumpsys jobscheduler)
  ui.py widget DUMP -          Froyo widget rows shown; exit 1 when rows or the "+N more" line
                               overlap or stick out
  ui.py top    DUMP TEXT       top y of the node whose text equals TEXT
  ui.py bounds DUMP ID         "x1 y1 x2 y2" of the node with resource id ID
  ui.py rows   DUMP -          Froyo list lines on screen, top to bottom: "y day|card TEXT"
  ui.py cards  DUMP -          cards fully in view; exit 1 when a title or Done chip is cut
  ui.py height DUMP TEXT       height in px of the node whose text equals TEXT
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
    """The Froyo widget's list rows on screen: prints how many; exit 1 when one overlaps
    another or the header, or sticks out of the widget."""
    root = header = None
    rows = []
    for node in nodes(path):
        rid = node.get("resource-id", "")
        if rid.endswith(":id/froyo_root") and root is None:
            root = box(node)
        elif rid.endswith(":id/froyo_header") and header is None:
            header = box(node)
        elif rid.endswith(":id/froyo_item"):
            b = box(node)
            if b and b[3] > b[1]:
                rows.append(b)
    if not root:
        print("no widget on screen")
        return 1
    above = header
    for r in sorted(rows, key=lambda r: r[1]):
        if r[1] < root[1] or r[3] > root[3]:
            print("row outside the widget", r, root)
            return 1
        if above and r[1] < above[3]:
            print("row overlaps the one above", r, above)
            return 1
        above = r
    print(len(rows))
    return 0


def _rid(node, name):
    return node.get("resource-id", "").endswith(":id/" + name)


def _inside(inner, outer, slack=1):
    return (inner[0] >= outer[0] - slack and inner[1] >= outer[1] - slack
            and inner[2] <= outer[2] + slack and inner[3] <= outer[3] + slack)


def rows(path, _arg):
    """The Froyo list's lines on screen, top to bottom: "y day|card TEXT"."""
    out = []
    for node in nodes(path):
        b = box(node)
        if not b or b[3] <= b[1]:
            continue
        if _rid(node, "froyo_day"):
            out.append((b[1], "day", node.get("text", "")))
        elif _rid(node, "froyo_title"):
            out.append((b[1], "card", node.get("text", "")))
    for y, kind, text in sorted(out):
        print(y, kind, text)
    return 0


def cards(path, _arg):
    """Check every card fully inside the list: its title and Done chip sit wholly inside the
    card. Prints how many cards were checked; exit 1 on the first one cut or covered."""
    root = ET.parse(path).getroot()
    lst = next((box(n) for n in root.iter("node") if _rid(n, "froyo_list")), None)
    if not lst:
        print("no list on screen")
        return 1
    checked = 0
    for card in root.iter("node"):
        if not _rid(card, "froyo_item"):
            continue
        cb = box(card)
        if not cb or cb[1] <= lst[1] + 1 or cb[3] >= lst[3] - 1:
            continue  # partly scrolled out of view: checked when it is in view
        title = next((n for n in card.iter("node") if _rid(n, "froyo_title")), None)
        done = next((n for n in card.iter("node") if _rid(n, "froyo_done")), None)
        if title is None or not box(title) or not _inside(box(title), cb):
            print("title cut or missing in card", cb, title.get("text", "") if title is not None else "")
            return 1
        if done is None or not box(done) or box(done)[2] - box(done)[0] < 10 or not _inside(box(done), cb):
            print("Done chip hidden or cut in card", cb, title.get("text", ""))
            return 1
        checked += 1
    print(checked)
    return 0


def height(path, text):
    """Height in px of the node whose text equals TEXT."""
    for node in nodes(path):
        if node.get("text", "") == text:
            b = box(node)
            if b:
                print(b[3] - b[1])
                return 0
    return 1


def bounds(path, rid):
    """"x1 y1 x2 y2" of the first node whose resource id ends with :id/RID."""
    for node in nodes(path):
        if node.get("resource-id", "").endswith(":id/" + rid):
            b = box(node)
            if b:
                print(*b)
                return 0
    return 1


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
              "widget": widget, "top": top, "bounds": bounds, "rows": rows, "cards": cards,
              "height": height}[cmd](path, arg))
