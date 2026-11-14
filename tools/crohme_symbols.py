#!/usr/bin/env python3
"""Single symbols of CROHME formulas for the level-0 test (ML Kit reading symbols).

Writes one line per symbol: set, formula, label, expected text, strokes. Strokes are scaled as
the formula bench scales formulas (letters and digits about 30 units tall) and written
"x y,x y;x y,..." (strokes split by ';'). Only symbols the formula reader can use, as in
PerfectReader.labelText. CROHME is for research only: the file goes to a test device, never
into the app.

usage: crohme_symbols.py <CROHME_full_v2 dir> <out.tsv> [max symbols]
"""
import os
import re
import sys
import xml.etree.ElementTree as ET

SETS = ["CROHME2013_data/TestINKMLGT", "CROHME2012_data/testDataGT"]
TARGET_H = 30.0
SYMBOLS = {
    "\\times": "×", "\\div": "÷", "\\sqrt": "√", "\\int": "∫", "\\pi": "π", "\\infty": "∞",
    "\\sin": "sin", "\\cos": "cos", "\\tan": "tg", "\\log": "log", "\\ln": "ln", "\\prime": "'",
    "\\cdot": "·", "\\ldots": "...",
}


def text_of(label):
    if len(label) == 1 and (label.isalnum() or label in "+-=()[]|,.!/"):
        return label
    return SYMBOLS.get(label)


def local(tag):
    return tag.split("}")[-1]


def parse(path):
    root = ET.parse(path).getroot()
    traces = {}
    for t in root.iter():
        if local(t.tag) != "trace":
            continue
        pts = []
        for p in (t.text or "").strip().split(","):
            v = p.split()
            if len(v) >= 2:
                pts.append((float(v[0]), float(v[1])))
        if pts:
            traces[t.get("id")] = pts
    symbols = []
    for g in root.iter():
        if local(g.tag) != "traceGroup":
            continue
        label = None
        refs = []
        for c in g:
            if local(c.tag) == "annotation" and c.get("type") == "truth":
                label = (c.text or "").strip()
            if local(c.tag) == "traceView" and c.get("traceDataRef") in traces:
                refs.append(c.get("traceDataRef"))
        if label and refs:
            symbols.append((label, refs))
    return traces, symbols


def main():
    root, out = sys.argv[1], sys.argv[2]
    limit = int(sys.argv[3]) if len(sys.argv) > 3 else 4000
    rows = []
    for s in SETS:
        d = os.path.join(root, s)
        for name in sorted(os.listdir(d)):
            if not name.endswith(".inkml"):
                continue
            try:
                traces, symbols = parse(os.path.join(d, name))
            except ET.ParseError:
                continue
            heights = []
            for label, refs in symbols:
                xs = [x for r in refs for x, _ in traces[r]]
                ys = [y for r in refs for _, y in traces[r]]
                h = max(ys) - min(ys)
                if len(label) == 1 and label.isalnum() and h > 0.5 * (max(xs) - min(xs)):
                    heights.append(h)
            if not heights:
                continue
            heights.sort()
            k = TARGET_H / heights[len(heights) // 2] if heights[len(heights) // 2] > 0 else None
            if not k:
                continue
            for label, refs in symbols:
                t = text_of(label)
                if t is None:
                    continue
                xs = [x for r in refs for x, _ in traces[r]]
                ys = [y for r in refs for _, y in traces[r]]
                x0, y0 = min(xs), min(ys)
                strokes = ";".join(",".join("%.1f %.1f" % (100 + (x - x0) * k, 200 + (y - y0) * k) for x, y in traces[r]) for r in refs)
                rows.append("\t".join([s.split("/")[0], name[:-6], label, t, strokes]))
    # Evenly over all, so every set and writer is in.
    step = max(1, len(rows) // limit)
    rows = rows[::step][:limit]
    with open(out, "w", encoding="utf-8") as f:
        f.write("\n".join(rows) + "\n")
    print("symbols: %d" % len(rows))


if __name__ == "__main__":
    main()
