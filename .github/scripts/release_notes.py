"""Write the changelog for one release to stdout.

  release_notes.py TAG [--sha SHA] [--prerelease]

What counts as "changed since the last release":

- A main release lists the pull requests merged since the previous main
  release (by PR title).
- A branch pre-release lists the commits since the previous release of any kind.

TAG may not exist yet (CI runs this before it creates the release); pass --sha
then. Needs `git` with tags fetched, and `gh` (GH_TOKEN) to read PR titles and
the release list.
"""
import argparse
import json
import re
import subprocess
import sys


def run(*cmd):
    return subprocess.run(cmd, capture_output=True, text=True, check=True).stdout.strip()


def version(tag):
    m = re.fullmatch(r"v(\d+(?:\.\d+)*)", tag)
    return tuple(int(x) for x in m.group(1).split(".")) if m else None


def releases():
    raw = run("gh", "release", "list", "--limit", "300", "--json", "tagName,isPrerelease")
    return [(version(r["tagName"]), r["tagName"], r["isPrerelease"]) for r in json.loads(raw) if version(r["tagName"])]


def previous(tag, prerelease):
    me = version(tag)
    older = [r for r in releases() if r[0] < me and (prerelease or not r[2])]
    return max(older)[1] if older else None


def pr_title(number):
    try:
        return run("gh", "pr", "view", number, "--json", "title", "-q", ".title")
    except subprocess.CalledProcessError:
        return None


def entries(prev, head, prerelease):
    if prerelease:
        base = run("git", "merge-base", prev, head) if prev else None
        rng = f"{base}..{head}" if base else head
        subjects = run("git", "log", "--no-merges", "--format=%s", rng).splitlines()
        return [s for s in subjects if s]
    rng = f"{prev}..{head}" if prev else head
    out = []
    for subject in run("git", "log", "--first-parent", "--format=%s", rng).splitlines():
        m = re.match(r"Merge pull request #(\d+)", subject)
        if m:
            title = pr_title(m.group(1))
            out.append(f"{title} (#{m.group(1)})" if title else subject)
        elif not subject.startswith("Merge "):
            out.append(subject)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("tag")
    ap.add_argument("--sha")
    ap.add_argument("--prerelease", action="store_true")
    a = ap.parse_args()
    if not version(a.tag):
        sys.exit(f"not a version tag: {a.tag}")

    head = a.sha or a.tag
    prev = previous(a.tag, a.prerelease)
    items = list(dict.fromkeys(entries(prev, head, a.prerelease)))  # drop repeats, keep order

    print("## What's new\n")
    if prev is None:
        print("- First release.")
    elif items:
        print("\n".join(f"- {i}" for i in items))
    else:
        print("- Maintenance build. No changes to the app.")
    if prev:
        repo = run("gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner")
        print(f"\n**Full changelog:** https://github.com/{repo}/compare/{prev}...{a.tag}")


if __name__ == "__main__":
    main()
