"""Digits and math symbols drawn like the instrumented MathDemo (shapes the recognizer reads)."""
import math
import random


def _ellipse(cx, cy, rx, ry):
    return [(cx + rx * math.cos(-math.pi / 2 - 2 * math.pi * k / 24), cy + ry * math.sin(-math.pi / 2 - 2 * math.pi * k / 24)) for k in range(25)]


SHAPES = {
    '0': [_ellipse(5, 10, 4.5, 9.5)],
    '1': [[(2, 5), (6, 0), (6, 20)]],
    '2': [[(1, 5), (2, 1.5), (5, 0), (8.5, 1.5), (9, 5), (7, 10), (1, 20), (10, 20)]],
    '3': [[(1, 2), (4, 0), (8, 1), (9, 4.5), (5, 9), (9, 12), (9.5, 16), (6, 20), (1.5, 19)]],
    '4': [[(7, 20), (7, 0), (0, 14), (10, 14)]],
    '5': [[(9, 0), (2, 0), (1.5, 9), (5, 7.5), (8.5, 10), (9, 15), (6.5, 19.5), (3, 20), (0.5, 17.5)]],
    '6': [[(8.5, 1), (5, 0), (1.5, 5), (0.5, 13), (2, 19), (5, 20), (8.5, 17.5), (9, 13), (6, 10), (2.5, 11), (0.8, 14)]],
    '7': [[(0, 0), (10, 0), (4, 20)]],
    '8': [[(8, 2), (5, 0), (2, 2), (2.5, 6), (8, 13), (8.5, 17), (5, 20), (1.5, 17), (2, 13), (8, 6), (8, 2)]],
    '9': [[(9, 6), (6, 9), (2, 8), (1, 4), (4, 0), (8, 1), (9, 6), (8, 20)]],
    '+': [[(5, 5), (5, 17)], [(-1, 11), (11, 11)]],
    '-': [[(0, 11), (10, 11)]],
    'x': [[(1, 8), (9, 20)], [(9, 8), (1, 20)]],
    'y': [[(1, 8), (5, 16)], [(9, 8), (5, 16), (2, 26)]],
    'd': [_ellipse(5, 14, 4, 6), [(9, 0), (9, 20)]],
    '(': [[(7, 0), (3, 5), (2, 10), (3, 15), (7, 20)]],
    ')': [[(3, 0), (7, 5), (8, 10), (7, 15), (3, 20)]],
    "'": [[(6, -2), (4, 5)]],
    '=': [[(0, 8), (10, 8)], [(0, 12.5), (10, 12.5)]],
}


def _hand(pts, k, seed):
    """Smooths a glyph polyline and adds a little tremble and slant."""
    rnd = random.Random(seed)
    out = []
    for i in range(len(pts) - 1):
        (ax, ay), (bx, by) = pts[i], pts[i + 1]
        n = max(2, int(math.hypot(bx - ax, by - ay) * k / 6))
        for j in range(n):
            t = j / n
            out.append((ax + (bx - ax) * t, ay + (by - ay) * t))
    out.append(pts[-1])
    jx, jy = rnd.uniform(-0.25, 0.25), rnd.uniform(-0.25, 0.25)
    return [(x + jx + 0.12 * (20 - y) + 0.15 * math.sin(i * 0.7), y + jy) for i, (x, y) in enumerate(out)]


def strokes(text, x, top, h, seed=1):
    k = h / 20
    cx = x
    out = []
    for i, c in enumerate(text):
        if c == ' ':
            cx += 0.4 * h
            continue
        for j, s in enumerate(SHAPES[c]):
            out.append([(cx + px * k, top + py * k) for px, py in _hand(s, k, seed * 1000 + i * 10 + j)])
        cx += {'=': 0.75, "'": 0.4}.get(c, 0.72) * h
    return out, cx
