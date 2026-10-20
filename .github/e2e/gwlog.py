"""Query the fake gateway's request log.

  gwlog.py BASE count                         number of requests logged so far
  gwlog.py BASE since MARK [METHOD] [PATH]    requests after MARK, one per line:
                                              "METHOD PATH STATUS cookie=0|1 UA"
PATH "page" matches any dashboard page (not /api/ and not /files/).
"""
import json
import sys
import urllib.request

base, cmd = sys.argv[1], sys.argv[2]
rows = json.load(urllib.request.urlopen(base + "/test/log", timeout=10))
if cmd == "count":
    print(len(rows))
    sys.exit(0)

mark = int(sys.argv[3])
method = sys.argv[4] if len(sys.argv) > 4 else None
path = sys.argv[5] if len(sys.argv) > 5 else None


def is_page(p):
    return not p.startswith("/api/") and not p.startswith("/files/")


for r in rows[mark:]:
    if method and r["method"] != method:
        continue
    if path == "page" and not is_page(r["path"]):
        continue
    if path and path != "page" and r["path"] != path:
        continue
    print(r["method"], r["path"], r["status"], "cookie=%d" % r["cookie"], r["ua"])
