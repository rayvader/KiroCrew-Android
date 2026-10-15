"""Print the centre "x y" of the first UI node whose text, content-desc or resource-id matches argv[2]."""
import re
import sys
import xml.etree.ElementTree as ET

path, wanted = sys.argv[1], sys.argv[2].lower()
for node in ET.parse(path).iter("node"):
    values = [node.get(k, "").lower() for k in ("text", "content-desc", "resource-id")]
    if wanted in values:
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            print((x1 + x2) // 2, (y1 + y2) // 2)
            sys.exit(0)
sys.exit(f"not found: {sys.argv[2]}")
