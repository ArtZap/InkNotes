"""Handwriting as pen strokes: text set in a handwritten font, thinned to its centre line and
traced into polylines, ordered roughly as a hand would write them (left to right)."""

import math

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from skimage.morphology import skeletonize

DIR = __file__.rsplit("/", 1)[0]
FONT = DIR + "/MarckScript-Regular.ttf"
RENDER = 220  # font size the text is thinned at


def _trace(skel):
    h, w = skel.shape
    pts = set(zip(*np.nonzero(skel)))
    nb = [(-1, -1), (-1, 0), (-1, 1), (0, -1), (0, 1), (1, -1), (1, 0), (1, 1)]

    def neigh(p):
        return [(p[0] + dy, p[1] + dx) for dy, dx in nb if (p[0] + dy, p[1] + dx) in pts]

    deg = {p: len(neigh(p)) for p in pts}
    nodes = {p for p in pts if deg[p] != 2}
    used = set()
    paths = []

    def walk(a, b):
        path = [a, b]
        used.add(frozenset((a, b)))
        prev, cur = a, b
        while cur not in nodes:
            nxt = [q for q in neigh(cur) if q != prev and frozenset((cur, q)) not in used]
            if not nxt:
                break
            # Prefer orthogonal neighbours, so a pixel staircase is not taken as a branch.
            nxt.sort(key=lambda q: abs(q[0] - cur[0]) + abs(q[1] - cur[1]))
            q = nxt[0]
            used.add(frozenset((cur, q)))
            path.append(q)
            prev, cur = cur, q
        return path

    for n in sorted(nodes, key=lambda p: p[1]):
        for q in neigh(n):
            if frozenset((n, q)) not in used:
                paths.append(walk(n, q))
    # A dot: a lone pixel or a tiny blob, a short tap of the pen.
    for p in pts:
        if deg[p] == 0:
            paths.append([p, (p[0] + 1, p[1] + 1)])
    # Loops with no node (an "o"): start anywhere.
    for p in sorted(pts, key=lambda p: p[1]):
        for q in neigh(p):
            if frozenset((p, q)) not in used:
                paths.append(walk(p, q))
    return paths


def _join(paths, gap=3.0):
    """Join paths meeting end to end, preferring the straightest continuation."""
    paths = [list(p) for p in paths if len(p) > 1]
    changed = True
    while changed:
        changed = False
        for i, a in enumerate(paths):
            best = None
            for j, b in enumerate(paths):
                if i == j:
                    continue
                for rb in (False, True):
                    bb = b[::-1] if rb else b
                    if math.dist(a[-1], bb[0]) <= gap:
                        da = np.subtract(a[-1], a[max(0, len(a) - 6)])
                        db = np.subtract(bb[min(len(bb) - 1, 5)], bb[0])
                        cos = np.dot(da, db) / (np.linalg.norm(da) * np.linalg.norm(db) + 1e-6)
                        if cos > 0.3 and (best is None or cos > best[0]):
                            best = (cos, j, bb)
            if best:
                _, j, bb = best
                paths[i] = a + bb[1:]
                del paths[j]
                changed = True
                break
    return paths


def _simplify(p, eps=0.8):
    if len(p) < 3:
        return p
    a, b = np.array(p[0], float), np.array(p[-1], float)
    d = b - a
    n = np.linalg.norm(d)
    best, idx = 0.0, 0
    for i in range(1, len(p) - 1):
        q = np.array(p[i], float)
        dist = abs(d[0] * (q[1] - a[1]) - d[1] * (q[0] - a[0])) / n if n else np.linalg.norm(q - a)
        if dist > best:
            best, idx = dist, i
    if best > eps:
        return _simplify(p[: idx + 1], eps)[:-1] + _simplify(p[idx:], eps)
    return [p[0], p[-1]]


def _smooth(p, rounds=2):
    """Chaikin corner cutting: rounds the pixel staircase off a traced line."""
    for _ in range(rounds):
        if len(p) < 3:
            return p
        q = [p[0]]
        for a, b in zip(p, p[1:]):
            q += [(0.75 * a[0] + 0.25 * b[0], 0.75 * a[1] + 0.25 * b[1]), (0.25 * a[0] + 0.75 * b[0], 0.25 * a[1] + 0.75 * b[1])]
        q.append(p[-1])
        p = q
    return p


def _font():
    font = ImageFont.truetype(FONT, RENDER)
    if FONT.endswith("Caveat.ttf"):
        font.set_variation_by_axes([430])
    return font


def strokes(text, x, y, height, slant=0.0, space=0.38):
    """Word by word, so the gaps between words are a hand's, not the font's (wide) ones.
    Pen strokes (lists of (x, y) in screen pixels) writing [text] with its x-height box top at
    ([x], [y]); [height] is the cap height in pixels."""
    if " " in text.strip():
        out = []
        for word in text.split():
            out += strokes(word, x, y, height, slant)
            x += width(word, height) + space * height
        return out
    font = _font()
    l, t, r, b = font.getbbox(text)
    img = Image.new("L", (r - l + 40, b - t + 40), 0)
    ImageDraw.Draw(img).text((20 - l, 20 - t), text, font=font, fill=255)
    skel = skeletonize(np.array(img) > 110)
    paths = _join(_trace(skel))
    # Short spurs of the thinning are dropped, dots and commas (apart from all else) are kept.
    def alone(p):
        return all(min(math.dist(a, b) for b in q) > 6 for q in paths if q is not p for a in (p[0], p[-1]))
    paths = [p for p in paths if len(p) >= 10 or alone(p)]
    k = height / (font.getbbox("Н")[3] - font.getbbox("Н")[1])
    cap_top = font.getbbox("Н")[1] - t + 20
    out = []
    for p in paths:
        p = _smooth(_simplify(p, 1.2))
        out.append([(x + (c - 20) * k + slant * (cap_top - r_) * k, y + (r_ - cap_top) * k) for r_, c in p])
    # Writing order: by the leftmost point of each stroke, roughly letter by letter.
    out.sort(key=lambda s: min(px for px, _ in s))
    return out


def width(text, height):
    font = _font()
    k = height / (font.getbbox("Н")[3] - font.getbbox("Н")[1])
    l, _, r, _ = font.getbbox(text)
    return (r - l) * k


def line_width(text, height, space=0.38):
    words = text.split()
    return sum(width(w, height) for w in words) + space * height * (len(words) - 1)


if __name__ == "__main__":
    import sys
    fonts = sys.argv[1:] or ["BadScript-Regular.ttf"]
    img = Image.new("RGB", (1100, 150 * len(fonts)), "white")
    d = ImageDraw.Draw(img)
    for i, f in enumerate(fonts):
        FONT = DIR + "/" + f
        d.text((10, 150 * i + 4), f, fill=(150, 150, 150))
        for st in strokes("Древний Рим: 753 г. до н.э. — основание", 20, 150 * i + 40, 42):
            d.line(st, fill=(20, 40, 160), width=3, joint="curve")
    img.save("/tmp/claude-1000/-mnt-addmem-claude-Procreate/ce6ea5ef-4255-4a7f-938a-51a43a3574bd/scratchpad/ink.png")
