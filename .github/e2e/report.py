"""Turn e2e-out/ into a results table and one captioned demo video.

  python3 report.py e2e-out

Writes e2e-out/report.md (also appended to $GITHUB_STEP_SUMMARY when set) and,
when ffmpeg is installed, e2e-out/demo.mp4: every feature clip at 2x speed with
its name and result on top.
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
ICON = {"PASS": "✅", "FAIL": "❌", "VIDEO": "🎬"}


def read_results(out):
    rows = []
    path = out / "results.tsv"
    if not path.exists():
        return rows
    for line in path.read_text().splitlines():
        fid, status, title, slug = line.split("\t")
        log = out / "logs" / f"{fid}-{slug}.log"
        lines = log.read_text(errors="replace").splitlines() if log.exists() else []
        rows.append({
            "id": fid, "status": status, "title": title, "slug": slug,
            "checks": [l[7:] for l in lines if l.startswith("CHECK: ")],
            "notes": [l for l in lines if l.startswith(("FAIL: ", "VIDEO: "))],
        })
    return rows


def write_report(out, rows):
    counts = {s: sum(r["status"] == s for r in rows) for s in ICON}
    md = [
        "## Emulator feature tests",
        "",
        f"{counts['PASS']} passed, {counts['FAIL']} failed, {counts['VIDEO']} to check in the video.",
        "",
        "| # | Feature | Result | What was checked |",
        "|---|---|---|---|",
    ]
    for r in rows:
        checked = "<br>".join(r["checks"] + r["notes"]) or "-"
        md.append(f"| {r['id']} | {r['title']} | {ICON[r['status']]} {r['status']} | {checked} |")
    md += ["", "Videos, screenshots and logs are in the `e2e-videos` artifact. `demo.mp4` shows every feature at 2x speed."]
    text = "\n".join(md) + "\n"
    (out / "report.md").write_text(text)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as f:
            f.write(text)
    print(text)


def make_demo(out, rows):
    if not shutil.which("ffmpeg"):
        print("ffmpeg not found: no demo.mp4")
        return
    work = out / "demo-parts"
    work.mkdir(exist_ok=True)
    parts = []
    for r in rows:
        clip = out / "video" / f"{r['id']}-{r['slug']}.mp4"
        if not clip.exists() or clip.stat().st_size < 1000:
            continue
        caption = work / f"{r['id']}.txt"
        caption.write_text(f"{int(r['id'])}. {r['title']}  {r['status']}")
        part = work / f"{r['id']}.mp4"
        vf = (
            "setpts=0.5*PTS,"
            "scale=540:1200:force_original_aspect_ratio=decrease,"
            "pad=540:1200:(ow-iw)/2:(oh-ih)/2:color=0x1b1530,"
            "pad=540:1290:0:90:color=0x1b1530,"
            f"drawtext=fontfile={FONT}:textfile={caption}:fontcolor=white:fontsize=24:x=(w-tw)/2:y=32,"
            "fps=30,format=yuv420p"
        )
        # Without the drawtext filter (an ffmpeg built without freetype) the clip goes in uncaptioned.
        for filters in (vf, ",".join(f for f in vf.split(",") if not f.startswith("drawtext"))):
            cmd = ["ffmpeg", "-loglevel", "error", "-y", "-i", str(clip), "-vf", filters, "-an",
                   "-c:v", "libx264", "-preset", "veryfast", "-crf", "28", str(part)]
            if subprocess.run(cmd).returncode == 0:
                parts.append(part)
                break
    if not parts:
        print("no clips to join")
        return
    listing = work / "list.txt"
    listing.write_text("".join(f"file '{p.resolve()}'\n" for p in parts))
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
                    "-c", "copy", "-movflags", "+faststart", str(out / "demo.mp4")], check=False)
    shutil.rmtree(work, ignore_errors=True)
    print(f"demo.mp4 from {len(parts)} clips")


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "e2e-out")
    rows = read_results(out)
    write_report(out, rows)
    make_demo(out, rows)


if __name__ == "__main__":
    main()
