#!/usr/bin/env python3
"""Pages of an InkNotes notebook (.inknote export, or .ink page files) as stroke JSON for
NotesReplayTest: the strokes MathNotes.toInk gives the math (pen kinds, every k-th point of
long strokes, the stroke's index on the page as its order).

    tools/notes-ink.py notebook.inknote OUTDIR   # OUTDIR/<page id>.json and .ink
"""
import gzip
import io
import json
import os
import struct
import sys
import zipfile

MAX_POINTS = 400  # MathNotes.MAX_POINTS
NOT_INK = {1}  # HIGHLIGHTER; SPOILER is looked up by name below
KINDS = ["PEN", "HIGHLIGHTER", "FOUNTAIN", "NEON", "PENCIL", "DASHED", "LINE", "SPOILER"]


def varint(b, i):
    r = s = 0
    while True:
        c = b[i]
        i += 1
        r |= (c & 0x7F) << s
        s += 7
        if c < 0x80:
            return r, i


def fields(b):
    i, out = 0, {}
    while i < len(b):
        k, i = varint(b, i)
        f, t = k >> 3, k & 7
        if t == 0:
            v, i = varint(b, i)
        elif t == 1:
            v, i = b[i:i + 8], i + 8
        elif t == 5:
            v, i = struct.unpack("<f", b[i:i + 4])[0], i + 4
        elif t == 2:
            n, i = varint(b, i)
            v, i = b[i:i + n], i + n
        else:
            raise ValueError("wire type %d" % t)
        out[f] = v
    return out


def numbers(run):
    """A coded numeric run of androidx.ink: zigzag deltas, scale, offset."""
    d = fields(run)
    deltas, scale, offset = d.get(1, b""), d.get(2, 1.0), d.get(3, 0.0)
    out, i, acc = [], 0, 0
    while i < len(deltas):
        z, i = varint(deltas, i)
        acc += (z >> 1) ^ -(z & 1)
        out.append(offset + scale * acc)
    return out


def strokes(data):
    r = io.BytesIO(data)

    def i32():
        return struct.unpack(">i", r.read(4))[0]

    if r.read(4) != b"INKP":
        raise ValueError("not a page")
    i32()  # version
    out = []
    for order in range(i32()):
        kind = r.read(1)[0]
        i32()  # colour
        r.read(4)  # size
        raw = r.read(i32())
        if raw[:2] == b"\x1f\x8b":
            raw = gzip.decompress(raw)
        if kind in NOT_INK or (kind < len(KINDS) and KINDS[kind] == "SPOILER"):
            continue
        f = fields(raw)
        xs, ys = numbers(f[1]), numbers(f[2])
        ts = numbers(f[3]) if 3 in f else [0.0] * len(xs)
        n = len(xs)
        if n == 0:
            continue
        step = max(1, n // MAX_POINTS)
        idx = range(0, n, step)
        out.append({"o": order, "x": [round(xs[k], 2) for k in idx], "y": [round(ys[k], 2) for k in idx], "t": [int(ts[k] * 1000) for k in idx]})
    return out


def main(src, dst):
    os.makedirs(dst, exist_ok=True)
    pages = {}
    if src.endswith(".ink"):
        pages[os.path.basename(src)[:-4]] = open(src, "rb").read()
    else:
        with zipfile.ZipFile(src) as z:
            for name in z.namelist():
                if name.startswith("pages/") and name.endswith(".ink"):
                    pages[os.path.basename(name)[:-4]] = z.read(name)
    for pid, data in pages.items():
        open(os.path.join(dst, pid + ".ink"), "wb").write(data)
        json.dump(strokes(data), open(os.path.join(dst, pid + ".json"), "w"))
        print(pid, len(strokes(data)), "strokes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
