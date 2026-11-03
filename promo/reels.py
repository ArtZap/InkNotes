"""Vertical 9:16 Reels cut: a result-first hook, beat-synced 2 s shots with a virtual camera
punching into the action, kinetic captions inside the Reels safe zone, sound effects and a
comment-bait call to action that loops back into the hook."""

import math
import os
import subprocess
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

DIR = os.path.dirname(os.path.abspath(__file__))
CLIPS = f"{DIR}/clips"
OUT = f"{DIR}/build"
W, H, FPS = 1080, 1920, 30
SRC_W, SRC_H = 1920, 1152
K = SRC_W / 2000  # device pixels -> recorded pixels
PANEL_Y, PANEL = 560, 1080  # the square "camera" window
YELLOW = (255, 214, 74)
GOLD = (214, 176, 100)
BPM = 120
BEAT = 60 / BPM

EMOJI = "/usr/share/fonts/truetype/noto/NotoColorEmoji.ttf"


def font(size, weight=800, name="Manrope.ttf"):
    f = ImageFont.truetype(f"{DIR}/fonts/{name}", size)
    try:
        f.set_variation_by_axes([weight])
    except OSError:
        pass
    return f


def ease(t):
    t = min(1.0, max(0.0, t))
    return t * t * (3 - 2 * t)


def back_out(t, s=1.9):
    t = min(1.0, max(0.0, t)) - 1
    return t * t * ((s + 1) * t + s) + 1


# Shots: (clip, src_start, speed, seconds, camera keyframes [(t 0..1, cx, cy, side) in device px], line1, line2)
# Words wrapped in *...* get the yellow highlight box.
SHOTS = [
    ("maths", 17.1, 3.0, 2.0, [(0, 470, 500, 860), (1, 450, 480, 780)], "Пишу «=» от руки…", "а он *РЕШАЕТ* сам 🤯"),
    ("maths", 24.5, 1.0, 2.0, [(0, 1000, 620, 1020), (1, 1000, 600, 960)], "Ещё и показывает", "*ХОД РЕШЕНИЯ* 🧠"),
    ("maths", 30.6, 1.6, 2.0, [(0, 520, 450, 820), (1, 640, 440, 860)], "y = x² − 3  →", "*ГРАФИК* за секунду 📈"),
    ("notes", 10.6, 3.2, 2.0, [(0, 800, 470, 820), (1, 860, 560, 900)], "Пишешь пером —", "как *НА БУМАГЕ* ✍️"),
    ("notes", 70.6, 2.5, 2.0, [(0, 960, 640, 900), (1, 900, 660, 840)], "Маркер ложится", "*ПОД* текст 🖍️"),
    ("shapes", 13.0, 6.0, 2.0, [(0, 650, 520, 900), (0.55, 700, 520, 900), (1, 1450, 520, 900)], "Кривой круг →", "*ИДЕАЛЬНЫЙ* ⭕"),
    ("lasso", 19.0, 3.8, 2.0, [(0, 850, 620, 900), (1, 760, 760, 880)], "Обвёл лассо —", "двигай и *КРУТИ* 🔄"),
    ("neon", 19.6, 5.0, 2.0, [(0, 760, 700, 1000), (1, 930, 720, 1200)], "Неоновая ручка", "*СВЕТИТСЯ* 🔥"),
    ("insert", 30.5, 3.9, 2.0, [(0, 1000, 640, 1150), (1, 1000, 700, 1100)], "*106* штампов", "+ таблицы в 1 тап 📐"),
    ("hatch", 16.0, 6.8, 2.0, [(0, 700, 560, 1100), (1, 1000, 560, 1250)], "Штриховка", "*В ОДНО* касание"),
    ("search", 11.0, 2.8, 2.0, [(0, 640, 460, 1000), (1, 700, 600, 1000)], "Ищет даже", "*ПО ПОЧЕРКУ* 🔍"),
]
# Styles montage: one beat each, the last one longer.
STYLES = [("styles", 12.4), ("styles", 18.4), ("styles", 24.4), ("styles", 29.0), ("glass", 8.0), ("intro", 9.5)]
GLASS = ("glass", 15.0, 3.5, 2.0, [(0, 1000, 720, 1250), (1, 1000, 760, 1150)], "Обложки", "из *СТЕКЛА* ✨")
CTA_SECONDS = 4.0


# ---------------------------------------------------------------- source frames

def frames(clip, start, speed, n):
    cmd = ["ffmpeg", "-v", "error", "-ss", f"{start:.3f}", "-i", f"{CLIPS}/{clip}.mkv", "-vf",
           f"setpts=(PTS-STARTPTS)/{speed},fps={FPS}", "-frames:v", str(n), "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    p = subprocess.Popen(cmd, stdout=subprocess.PIPE)
    size = SRC_W * SRC_H * 3
    last = None
    for _ in range(n):
        buf = p.stdout.read(size)
        if len(buf) < size:
            break
        last = np.frombuffer(buf, np.uint8).reshape(SRC_H, SRC_W, 3)
        yield last
    p.stdout.close()
    p.wait()


def camera(keys, t):
    for (t0, *a), (t1, *b) in zip(keys, keys[1:]):
        if t <= t1:
            u = ease((t - t0) / max(1e-6, t1 - t0))
            return [x + (y - x) * u for x, y in zip(a, b)]
    return keys[-1][1:]


# ---------------------------------------------------------------- drawing

def rounded_mask(w, h, r):
    m = Image.new("L", (w, h), 0)
    ImageDraw.Draw(m).rounded_rectangle((0, 0, w - 1, h - 1), r, fill=255)
    return m


PANEL_MASK = rounded_mask(PANEL - 60, PANEL - 60, 44)
_sh = Image.new("RGBA", (PANEL + 80, PANEL + 80), (0, 0, 0, 0))
ImageDraw.Draw(_sh).rounded_rectangle((70, 80, PANEL - 60 + 70, PANEL - 60 + 80), 48, fill=(0, 0, 0, 150))
SHADOW = _sh.filter(ImageFilter.GaussianBlur(26))


def background(frame):
    small = Image.fromarray(frame).resize((160, 96), Image.BILINEAR)
    crop = small.crop((80 - 27, 0, 80 + 27, 96)).filter(ImageFilter.GaussianBlur(3))
    bg = np.asarray(crop.resize((W, H), Image.BILINEAR)).astype(np.float32)
    y = np.linspace(0, 1, H)[:, None, None]
    bg = bg * 0.42 + np.array([18, 14, 10]) * 0.5
    bg *= 1 - 0.35 * (np.abs(y - 0.5) * 2) ** 2
    return Image.fromarray(np.clip(bg, 0, 255).astype(np.uint8)).convert("RGBA")


def panel(frame, cx, cy, side, pop):
    s = side * K
    x0, y0 = cx * K - s / 2, cy * K - s / 2
    x0 = min(max(0, x0), SRC_W - s)
    y0 = min(max(0, y0), SRC_H - s) if s <= SRC_H else (SRC_H - s) / 2
    box = (x0, y0, x0 + s, y0 + s)
    inner = PANEL - 60
    img = Image.fromarray(frame).crop(tuple(int(round(v)) for v in box)).resize((inner, inner), Image.LANCZOS)
    if pop < 1:
        # Punch-in at the cut: a quick zoom out from 112 %.
        z = 1 + 0.12 * (1 - back_out(pop, 1.2))
        zz = int(inner * z)
        img = img.resize((zz, zz), Image.BILINEAR).crop(((zz - inner) // 2, (zz - inner) // 2, (zz - inner) // 2 + inner, (zz - inner) // 2 + inner))
    return img


class Word:
    def __init__(self, text, size, hl=False):
        self.hl = hl
        text = text.strip("*")
        emoji = any(ord(c) > 0x2190 and c not in "«»—…−→²" for c in text)
        if emoji:
            f = ImageFont.truetype(EMOJI, 109)
            l, t, r, b = f.getbbox(text)
            im = Image.new("RGBA", (r - l + 8, b - t + 8), (0, 0, 0, 0))
            ImageDraw.Draw(im).text((4 - l, 4 - t), text, font=f, embedded_color=True)
            k = size * 1.05 / im.height
            self.img = im.resize((int(im.width * k), int(im.height * k)), Image.LANCZOS)
            return
        f = font(size)
        l, t, r, b = f.getbbox(text)
        pad = 22 if self.hl else 10
        im = Image.new("RGBA", (r - l + pad * 2, int(size * 1.32) + pad), (0, 0, 0, 0))
        d = ImageDraw.Draw(im)
        if self.hl:
            d.rounded_rectangle((0, 4, im.width - 1, im.height - 4), 18, fill=YELLOW + (255,))
            d.text((pad - l, (im.height - (b - t)) / 2 - t), text, font=f, fill=(20, 16, 10))
        else:
            d.text((pad - l, (im.height - (b - t)) / 2 - t), text, font=f, fill=(255, 255, 255), stroke_width=7, stroke_fill=(12, 10, 8))
        self.img = im


def caption(line1, line2):
    size = 82
    while True:
        lines = []
        for ln in (line1, line2):
            words, on = [], False
            for w in ln.split(" "):
                if not w:
                    continue
                start = w.startswith("*")
                hl = on or start
                if start:
                    on = True
                if w.endswith("*"):
                    on = False
                words.append(Word(w, size, hl))
            lines.append(words)
        widths = [sum(w.img.width for w in ln) + 14 * (len(ln) - 1) for ln in lines]
        if max(widths) <= 900 or size < 56:
            return lines, widths, size
        size -= 4


def draw_caption(canvas, cap, t):
    lines, widths, size = cap
    i = 0
    y = 240
    for ln, wsum in zip(lines, widths):
        x = (W - wsum) / 2
        lh = max(w.img.height for w in ln)
        for w in ln:
            t0 = 0.04 + 0.075 * i
            u = (t - t0) / 0.2
            if u > 0:
                sc = back_out(u, 2.4) if u < 1 else 1.0
                sc = max(0.05, 0.4 + 0.6 * sc)
                im = w.img
                if sc != 1:
                    im = im.resize((max(1, int(im.width * sc)), max(1, int(im.height * sc))), Image.BILINEAR)
                a = min(1.0, u * 2.5)
                if a < 1:
                    im = im.copy()
                    im.putalpha(im.getchannel("A").point(lambda v: int(v * a)))
                cx, cy = x + w.img.width / 2, y + lh / 2
                canvas.alpha_composite(im, (int(cx - im.width / 2), int(cy - im.height / 2)))
            x += w.img.width + 14
            i += 1
        y += lh + 6


def compose(frame, keys, t, dur, cap):
    canvas = background(frame)
    u = t / dur
    cx, cy, side = camera(keys, u)
    side *= 1 - 0.05 * u  # slow push-in
    pop = min(1.0, t / 0.28)
    img = panel(frame, cx, cy, side, pop)
    px, py = (W - (PANEL - 60)) // 2, PANEL_Y + 30
    canvas.alpha_composite(SHADOW, (px - 70, py - 80))
    canvas.paste(img, (px, py), PANEL_MASK)
    ImageDraw.Draw(canvas).rounded_rectangle((px, py, px + PANEL - 61, py + PANEL - 61), 44, outline=(255, 255, 255, 60), width=3)
    if t < 0.12:  # white flash on the cut
        fl = Image.new("RGBA", (W, H), (255, 255, 255, int(150 * (1 - t / 0.12))))
        canvas.alpha_composite(fl)
    if cap:
        draw_caption(canvas, cap, t)
    return canvas


def cta(bg_frame, t):
    canvas = background(bg_frame)
    d = ImageDraw.Draw(canvas)
    # Logo.
    u = back_out(t / 0.4, 1.6)
    f_i, f_n = font(150, 500, "PlayfairItalic.ttf"), font(150, 400, "Playfair.ttf")
    logo = Image.new("RGBA", (900, 230), (0, 0, 0, 0))
    ld = ImageDraw.Draw(logo)
    wi = ld.textlength("Ink", font=f_i)
    wn = ld.textlength("Notes", font=f_n)
    x0 = (900 - wi - wn) / 2
    ld.text((x0, 10), "Ink", font=f_i, fill=GOLD)
    ld.text((x0 + wi + 4, 10), "Notes", font=f_n, fill=GOLD)
    sc = max(0.05, u)
    lg = logo.resize((max(1, int(900 * sc)), max(1, int(230 * sc))), Image.BILINEAR)
    canvas.alpha_composite(lg, ((W - lg.width) // 2, 420 + (230 - lg.height) // 2))
    sub = "Рукописные тетради для планшета с пером"
    fs = font(38, 600)
    if t > 0.35:
        d.text(((W - d.textlength(sub, font=fs)) / 2, 680), sub, font=fs, fill=(235, 225, 205))
    cap = caption("Напиши *«ХОЧУ»* 👇", "пришлю ссылку")
    tmp = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    draw_caption(tmp, cap, max(0, t - 0.6))
    canvas.alpha_composite(tmp, (0, 600))
    if t > 1.6:
        f2 = font(40, 700)
        txt = "Сохрани, чтобы не потерять"
        a = min(1, (t - 1.6) / 0.3)
        d.text(((W - d.textlength(txt, font=f2)) / 2, 1130), txt, font=f2, fill=(255, 255, 255, int(255 * a)))
    # Pulsing arrow down to the comments.
    if t > 1.0:
        bounce = 14 * math.sin((t - 1.0) * 2 * math.pi * 2)
        ay = 1260 + bounce
        d.polygon([(W / 2 - 34, ay), (W / 2 + 34, ay), (W / 2, ay + 40)], fill=YELLOW)
    return canvas


# ---------------------------------------------------------------- audio

def synth(total, events, path, sr=44100):
    n = int(total * sr)
    out = np.zeros((n, 2))
    rng = np.random.default_rng(3)

    def put(sig, at, gain=1.0, pan=0.0):
        i = int(at * sr)
        if i >= n or i + len(sig) <= 0:
            return
        if i < 0:
            sig, i = sig[-i:], 0
        sig = sig[: n - i]
        out[i:i + len(sig), 0] += sig * gain * (1 - pan)
        out[i:i + len(sig), 1] += sig * gain * (1 + pan)

    def tone(f, dur, decay, harm=((1, 1),)):
        t = np.arange(int(dur * sr)) / sr
        return sum(a * np.sin(2 * np.pi * f * h * t) for h, a in harm) * np.exp(-t * decay) * (1 - np.exp(-t * 300))

    def hz(m):
        return 440 * 2 ** ((m - 69) / 12)

    end_drums = total - CTA_SECONDS
    chords = [[57, 60, 64], [53, 57, 60], [48, 52, 55], [55, 59, 62]]  # Am F C G
    beats = int(total / BEAT)
    for b in range(beats):
        t = b * BEAT
        bar = b // 4
        ch = chords[bar % 4]
        in_drop = t < end_drums
        # Kick, clap, hats.
        tk = np.arange(int(0.3 * sr)) / sr
        kick = np.sin(2 * np.pi * (48 + 110 * np.exp(-tk * 38)) * tk) * np.exp(-tk * 9)
        if in_drop or b % 2 == 0:
            put(kick, t, 0.55 if in_drop else 0.3)
        if in_drop and b % 2 == 1:
            cl = rng.normal(0, 1, int(0.18 * sr)) * np.exp(-np.arange(int(0.18 * sr)) / sr * 28)
            put(np.convolve(cl, [1, -0.9], "same"), t, 0.16)
        if in_drop:
            for h in range(2):
                hat = rng.normal(0, 1, int(0.06 * sr)) * np.exp(-np.arange(int(0.06 * sr)) / sr * (70 if h else 120))
                put(np.diff(hat, prepend=0), t + h * BEAT / 2, 0.05 if h else 0.035, pan=0.25)
        # Sub bass on the root, pumping with the kick.
        put(tone(hz(ch[0] - 24), BEAT * 0.95, 3.0, ((1, 1), (2, 0.25))), t + 0.02, 0.32)
        # Pluck arpeggio, 16ths.
        arp = [ch[0], ch[1], ch[2], ch[1] + 12]
        for s in range(4):
            m = arp[(b * 4 + s) % 4] + 12
            put(tone(hz(m), 0.35, 9, ((1, 1), (2, 0.35), (3, 0.12))), t + s * BEAT / 4, 0.085, pan=0.3 * math.sin(b + s))
        # Chord pad per bar.
        if b % 4 == 0:
            tp = np.arange(int(4 * BEAT * sr)) / sr
            pad = sum(np.sin(2 * np.pi * hz(m) * tp * (1 + d)) for m in ch for d in (-0.002, 0.002))
            pad *= np.minimum(1, tp / 0.08) * np.exp(-tp * 0.4) * 0.03
            put(pad, t)
    # Sound effects.
    for kind, at in events:
        if kind == "whoosh":
            m = int(0.32 * sr)
            nz = rng.normal(0, 1, m)
            env = np.sin(np.linspace(0, np.pi, m)) ** 2
            sweep = np.cumsum(nz) * 0.02
            sig = (nz * 0.5 + sweep * 0.2) * env
            sig -= np.convolve(sig, np.ones(40) / 40, "same") * 0.6
            put(sig, at - 0.2, 0.09)
        elif kind == "pop":
            tp = np.arange(int(0.09 * sr)) / sr
            put(np.sin(2 * np.pi * (1300 - 6000 * tp) * tp) * np.exp(-tp * 45), at, 0.12)
        elif kind == "ding":
            put(tone(1568, 1.4, 3.5, ((1, 1), (2.76, 0.4), (5.4, 0.2))), at, 0.22)
            put(tone(2093, 1.0, 4.5), at + 0.08, 0.12)
        elif kind == "riser":
            m = int(1.0 * sr)
            tp = np.arange(m) / sr
            sig = rng.normal(0, 1, m) * (tp / 1.0) ** 2 * 0.5 + np.sin(2 * np.pi * (200 + 900 * tp ** 2) * tp) * (tp / 1.0) ** 2 * 0.3
            put(sig, at - 1.0, 0.12)
        elif kind == "impact":
            tk = np.arange(int(1.2 * sr)) / sr
            put(np.sin(2 * np.pi * (40 + 60 * np.exp(-tk * 10)) * tk) * np.exp(-tk * 3), at, 0.7)
    # Light glue: soft clip and normalise.
    out = np.tanh(out * 1.4) / 1.4
    fo = int(0.4 * sr)
    out[-fo:] *= np.linspace(1, 0, fo)[:, None]
    out *= 0.85 / np.max(np.abs(out))
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes((out * 32767).astype(np.int16).tobytes())


# ---------------------------------------------------------------- main

def main():
    os.makedirs(OUT, exist_ok=True)
    video = f"{OUT}/reels_video.mp4"
    enc = subprocess.Popen(["ffmpeg", "-y", "-v", "error", "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-",
                            "-c:v", "libx264", "-preset", "medium", "-crf", "17", "-pix_fmt", "yuv420p", video], stdin=subprocess.PIPE)
    t_global = 0.0
    events = []

    def emit(img):
        enc.stdin.write(img.convert("RGB").tobytes())

    def shot(clip, start, speed, dur, keys, l1, l2):
        nonlocal t_global
        cap = caption(l1, l2) if l1 else None
        n = int(round(dur * FPS))
        events.append(("whoosh", t_global))
        if l1:
            events.append(("pop", t_global + 0.05))
        last = None
        for i, fr in enumerate(frames(clip, start, speed, n)):
            last = fr
            emit(compose(fr, keys, i / FPS, dur, cap))
        got = i + 1
        for j in range(got, n):  # clip ran short: hold the last frame
            emit(compose(last, keys, j / FPS, dur, cap))
        t_global += n / FPS

    for k, s in enumerate(SHOTS):
        shot(*s)
        if k == 0:
            events.append(("ding", 1.3))
        if k == 2:
            events.append(("ding", t_global - 2.0 + 0.85))
        print(f"shot {k} done, t={t_global:.1f}", flush=True)
    # Styles montage on the beat: 5 quick beats, then a held one.
    cap = caption("*6 стилей* —", "выбери свой 🎨")
    t0 = t_global
    durs = [BEAT] * 5 + [BEAT * 1.0]
    for (clip, start), d in zip(STYLES, durs):
        n = int(round(d * FPS))
        events.append(("whoosh", t_global))
        for i, fr in enumerate(frames(clip, start, 1.0, n)):
            emit(_styles_frame(fr, cap, t_global - t0 + i / FPS, i / FPS))
        t_global += n / FPS
    events.append(("pop", t0 + 0.05))
    shot(*GLASS)
    # Call to action over the mystic library, then back to the hook (a loop).
    bg = next(frames("intro", 9.5, 1.0, 1))
    events.append(("riser", t_global))
    events.append(("impact", t_global))
    n = int(CTA_SECONDS * FPS)
    for i in range(n):
        emit(cta(bg, i / FPS))
    t_global += n / FPS
    enc.stdin.close()
    enc.wait()
    print(f"video {t_global:.1f}s", flush=True)
    synth(t_global, events, f"{OUT}/reels_audio.wav")
    final = f"{DIR}/InkNotes_reels.mp4"
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", video, "-i", f"{OUT}/reels_audio.wav", "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
                    "-movflags", "+faststart", "-shortest", final], check=True)
    print(final)


def _styles_frame(fr, cap, t_cap, t_shot):
    canvas = background(fr)
    side = 1250 * (1 - 0.03 * t_shot)
    img = panel(fr, 1000, 640, side, min(1.0, t_shot / 0.15))
    px, py = (W - (PANEL - 60)) // 2, PANEL_Y + 30
    canvas.alpha_composite(SHADOW, (px - 70, py - 80))
    canvas.paste(img, (px, py), PANEL_MASK)
    ImageDraw.Draw(canvas).rounded_rectangle((px, py, px + PANEL - 61, py + PANEL - 61), 44, outline=(255, 255, 255, 60), width=3)
    if t_shot < 0.08:
        canvas.alpha_composite(Image.new("RGBA", (W, H), (255, 255, 255, int(120 * (1 - t_shot / 0.08)))))
    draw_caption(canvas, cap, t_cap)
    return canvas


if __name__ == "__main__":
    main()
