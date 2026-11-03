"""Builds scripts for the on-device ScriptPlayer, runs them, films the screen."""

import subprocess
import sys
import time

import ink

ADB = "/mnt/addmem/android-sdk/platform-tools/adb"
SCRATCH = "/tmp/claude-1000/-mnt-addmem-claude-Procreate/ce6ea5ef-4255-4a7f-938a-51a43a3574bd/scratchpad"
FILES = "/sdcard/Android/data/dev.inknotes/files"
DIR = "/mnt/addmem/claude/Procreate/promo"
CLIPS = DIR + "/clips"


def adb(*args, check=True):
    return subprocess.run([ADB, *args], check=check, capture_output=True, text=True).stdout


class Script:
    def __init__(self):
        self.lines = []

    def add(self, *w):
        self.lines.append(" ".join(str(round(x, 1)) if isinstance(x, float) else str(x) for x in w))
        return self

    def sleep(self, ms):
        return self.add("sleep", int(ms))

    def tap(self, x, y, after=500):
        self.add("tap", x, y)
        return self.sleep(after)

    def long(self, x, y, after=900):
        self.add("long", x, y)
        return self.sleep(after)

    def swipe(self, ms, *pts, after=300):
        self.add("swipe", ms, *[c for p in pts for c in p])
        return self.sleep(after)

    def pen(self, pts, ms=None, hold=0):
        if ms is None:
            length = sum(((pts[i][0] - pts[i - 1][0]) ** 2 + (pts[i][1] - pts[i - 1][1]) ** 2) ** 0.5 for i in range(1, len(pts)))
            ms = max(40, length / 1.1)  # about 1100 px a second
        flat = [c for p in pts for c in p]
        if hold:
            return self.add("hold", int(ms), int(hold), *flat)
        return self.add("pen", int(ms), *flat)

    def write(self, text, x, y, h, speed=1.0, gap=35):
        """Handwrites [text] with its cap top at ([x], [y]), capitals [h] px tall."""
        for s in ink.strokes(text, x, y, h):
            length = sum(((s[i][0] - s[i - 1][0]) ** 2 + (s[i][1] - s[i - 1][1]) ** 2) ** 0.5 for i in range(1, len(s)))
            self.pen(s, ms=max(30, length / (0.9 * speed)))
            self.sleep(gap / speed)
        return self

    def paste(self, text, after=400):
        self.add("paste", text)
        return self.sleep(after)

    def key(self, code, meta=0, after=200):
        self.add("key", code, meta)
        return self.sleep(after)

    def two(self, x, y, after=600):
        self.add("two", x, y)
        return self.sleep(after)

    def three(self, x, y, after=600):
        self.add("three", x, y)
        return self.sleep(after)


def run(script, name="s.txt", wait=3500):
    path = f"{SCRATCH}/{name}"
    open(path, "w").write("\n".join(script.lines) + "\n")
    adb("push", path, f"{FILES}/{name}")
    out = adb("shell", "am", "instrument", "-w", "-e", "script", name, "-e", "wait", str(wait), "-e", "class", "dev.inknotes.probe.ScriptPlayer", "dev.inknotes.test/androidx.test.runner.AndroidJUnitRunner", check=False)
    if "OK (1 test)" not in out:
        print(out[-1500:], file=sys.stderr)
        raise SystemExit("script failed")


def shot(name):
    adb("shell", "screencap", "-p", "/sdcard/s.png")
    adb("pull", "/sdcard/s.png", f"{SCRATCH}/{name}.png")
    return f"{SCRATCH}/{name}.png"


def film(name, script, wait=3500):
    """Runs [script] while scrcpy records the screen into clips/[name].mkv."""
    import os
    import signal
    os.makedirs(CLIPS, exist_ok=True)
    out = f"{CLIPS}/{name}.mkv"
    if os.path.exists(out):
        os.remove(out)
    env = dict(os.environ, ADB=ADB)
    rec = subprocess.Popen(
        [f"{DIR}/scrcpy-linux-x86_64-v5.0.1/scrcpy", "--no-window", "--no-audio", "--no-control", "--max-size=1920",
         "--max-fps=30", "--video-codec=h264", "--video-bit-rate=14M", f"--record={out}"],
        env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, start_new_session=True)
    time.sleep(2.5)
    try:
        run(script, f"{name}.txt", wait)
        time.sleep(1.0)
    finally:
        rec.send_signal(signal.SIGTERM)
        rec.wait(timeout=30)
    return out


def snap(script, name):
    """Adds a screenshot to [script]; fetch it with [pull] after the run."""
    return script.add("shot", name)


def pull(name):
    adb("pull", f"{FILES}/{name}.png", f"{SCRATCH}/{name}.png")
    return f"{SCRATCH}/{name}.png"


def _text(self, label, n=0, after=500):
    self.add("text", n, label)
    return self.sleep(after)


def _longtext(self, label, n=0, after=900):
    self.add("longtext", n, label)
    return self.sleep(after)


def _wait(self, label, n=0):
    return self.add("wait", n, label)


Script.text = _text
Script.longtext = _longtext
Script.wait = _wait


def _trytext(self, label, after=500):
    self.add("trytext", label)
    return self.sleep(after)


Script.trytext = _trytext


def clear(title):
    """Empties the first page of [title] (strokes, fills, pictures) for a fresh take."""
    out = adb("shell", "am", "instrument", "-w", "-e", "title", __import__("shlex").quote(title), "-e", "drop", "all", "-e", "class", "dev.inknotes.probe.PageFix", "dev.inknotes.test/androidx.test.runner.AndroidJUnitRunner", check=False)
    if "OK (1 test)" not in out:
        raise SystemExit(out[-800:])


def _math(self, text, x, top, h, speed=1.0, gap=60):
    """Writes [text] with the math glyphs (what the answer recognizer reads)."""
    import mathglyphs
    strokes, end = mathglyphs.strokes(text, x, top, h)
    for st in strokes:
        length = sum(((st[i][0] - st[i - 1][0]) ** 2 + (st[i][1] - st[i - 1][1]) ** 2) ** 0.5 for i in range(1, len(st)))
        self.pen(st, ms=max(60, length / (0.8 * speed)))
        self.sleep(gap / speed)
    return self


Script.math = _math
