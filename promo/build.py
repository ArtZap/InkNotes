"""Cuts the filmed clips into the promo: captions, transitions, an end card and music."""

import os
import subprocess

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

DIR = os.path.dirname(os.path.abspath(__file__))
CLIPS = f"{DIR}/clips"
OUT = f"{DIR}/build"
FPS = 30
W, H = 1920, 1080
XF = 0.5  # crossfade between scenes

INK = (30, 26, 22)
GOLD = (201, 164, 92)
PAPER = (246, 239, 226)
SOFT = (222, 210, 188)


def font(name, size, weight=None):
    f = ImageFont.truetype(f"{DIR}/fonts/{name}", size)
    if weight is not None:
        try:
            f.set_variation_by_axes([weight])
        except OSError:
            pass
    return f


# (clip, [(in, out, speed)], title, subtitle, caption start in the scene)
SCENES = [
    ("intro", [(4.6, 11.6, 1.0)], "Ваши тетради — как книги", "Шесть старинных переплётов в стиле «Загадочный»", 2.4),
    ("notes", [(9.0, 17.0, 2.5), (17.0, 71.5, 7.0), (71.5, 75.8, 1.3)], "Пишите от руки", "Ручка, перьевая ручка, маркер — как на бумаге", 0.4),
    ("shapes", [(12.6, 25.2, 1.8)], "Идеальные фигуры", "Нарисуйте и задержите перо — фигура выровняется", 0.4),
    ("lasso", [(12.5, 26.6, 2.0)], "Лассо", "Выделяйте, двигайте, вращайте, перекрашивайте", 0.4),
    ("hatch", [(12.5, 30.4, 2.2)], "Штриховка", "Линии, сетка, точки, волны, кирпич — с настройкой", 0.4),
    ("insert", [(14.8, 38.5, 2.6)], "Штампы и таблицы", "106 готовых рисунков: механика, электрика, геометрия", 0.4),
    ("neon", [(17.2, 30.0, 1.6)], "Неоновая ручка", "Светится на тёмной бумаге", 0.4),
    ("maths", [(9.5, 21.0, 2.6), (21.0, 24.2, 1.0), (24.6, 27.0, 1.0), (29.2, 34.0, 1.2)], "Ответы после «=»", "Вычисления, системы с ходом решения, графики", 0.4),
    ("search", [(11.0, 16.6, 1.1)], "Поиск по почерку", "Рукопись распознаётся прямо на планшете", 0.3),
    ("styles", [(8.0, 9.4, 1.0), (11.8, 13.6, 1.0), (17.8, 19.6, 1.0), (23.8, 25.6, 1.0), (28.2, 31.0, 1.0), (35.8, 37.5, 1.0)], "Шесть стилей интерфейса", "От строгой классики до жидкого стекла", 0.3),
    ("glass", [(12.8, 13.8, 1.0), (14.0, 22.5, 1.3)], "Полупрозрачные обложки", "Сквозь обложку видна первая страница", 0.3),
    ("help", [(9.6, 22.3, 1.8)], "Встроенная справка", "Каждый инструмент — с описанием и поиском", 0.4),
]
END = 5.5


def caption_png(title, sub, path):
    img = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ft, fs = font("Playfair.ttf", 52, 600), font("Manrope.ttf", 28, 500)
    d = ImageDraw.Draw(img)
    tw = d.textlength(title, font=ft)
    sw = d.textlength(sub, font=fs)
    bw = int(max(tw, sw)) + 110
    bh = 150
    x0, y0 = 64, H - bh - 56
    # Soft shadow, then the card.
    shadow = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((x0 + 4, y0 + 10, x0 + bw + 4, y0 + bh + 10), 26, fill=(0, 0, 0, 90))
    img = Image.alpha_composite(img, shadow.filter(ImageFilter.GaussianBlur(14)))
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((x0, y0, x0 + bw, y0 + bh), 26, fill=INK + (226,))
    d.rounded_rectangle((x0 + 26, y0 + 30, x0 + 32, y0 + bh - 30), 3, fill=GOLD + (255,))
    d.text((x0 + 58, y0 + 22), title, font=ft, fill=PAPER + (255,))
    d.text((x0 + 60, y0 + 94), sub, font=fs, fill=SOFT + (255,))
    img.save(path)


def end_png(path):
    img = Image.new("RGB", (W, H))
    # Warm paper gradient with a soft vignette.
    y = np.linspace(0, 1, H)[:, None]
    x = np.linspace(-1, 1, W)[None, :]
    top, bottom = np.array([243, 235, 220]), np.array([226, 212, 186])
    base = top * (1 - y[..., None]) + bottom * y[..., None]
    vig = 1 - 0.18 * (x ** 2 + ((y - 0.5) * 2) ** 2)[..., None]
    img = Image.fromarray(np.clip(base * vig, 0, 255).astype(np.uint8))
    d = ImageDraw.Draw(img)
    gold = (140, 104, 48)
    f_ink, f_notes = font("PlayfairItalic.ttf", 170, 500), font("Playfair.ttf", 170, 400)
    wi, wn = d.textlength("Ink", font=f_ink), d.textlength("Notes", font=f_notes)
    x0 = (W - wi - wn) / 2
    d.text((x0, 250), "Ink", font=f_ink, fill=gold)
    d.text((x0 + wi + 6, 250), "Notes", font=f_notes, fill=gold)
    # Rule with a four-pointed star.
    cy = 500
    d.line((W / 2 - 260, cy, W / 2 - 30, cy), fill=gold, width=2)
    d.line((W / 2 + 30, cy, W / 2 + 260, cy), fill=gold, width=2)
    r, k = 16, 3.5
    d.polygon([(W / 2, cy - r), (W / 2 + k, cy - k), (W / 2 + r, cy), (W / 2 + k, cy + k), (W / 2, cy + r), (W / 2 - k, cy + k), (W / 2 - r, cy), (W / 2 - k, cy - k)], fill=gold)
    f_tag = font("Manrope.ttf", 40, 500)
    tag = "Рукописные конспекты на вашем планшете"
    d.text(((W - d.textlength(tag, font=f_tag)) / 2, 545), tag, font=f_tag, fill=(70, 58, 44))
    f_list = font("Manrope.ttf", 28, 500)
    items = "PDF  ·  обмен тетрадями  ·  автокопия  ·  корзина  ·  страницы A3–A6  ·  тёмная тема"
    d.text(((W - d.textlength(items, font=f_list)) / 2, 640), items, font=f_list, fill=(110, 94, 72))
    f_small = font("Manrope.ttf", 26, 600)
    foot = "Android 10+   ·   версия 0.22"
    d.text(((W - d.textlength(foot, font=f_small)) / 2, 900), foot, font=f_small, fill=gold)
    img.save(path)


def run(cmd):
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


def scene_video(i, clip, parts, title, sub, cap_at):
    src = f"{CLIPS}/{clip}.mkv"
    cap = f"{OUT}/cap{i}.png"
    caption_png(title, sub, cap)
    chains, labels = [], []
    dur = 0.0
    for j, (a, b, sp) in enumerate(parts):
        chains.append(f"[0:v]trim=start={a}:end={b},setpts=(PTS-STARTPTS)/{sp},fps={FPS},crop={W}:{H}:0:48,setsar=1[p{j}]")
        labels.append(f"[p{j}]")
        dur += (b - a) / sp
    chains.append(f"{''.join(labels)}concat=n={len(parts)}:v=1:a=0[v]")
    fade_out = max(cap_at + 1.0, dur - 0.9)
    chains.append(f"[1:v]format=rgba,fade=in:st={cap_at}:d=0.45:alpha=1,fade=out:st={fade_out}:d=0.4:alpha=1[c]")
    chains.append("[v][c]overlay=0:0:shortest=1,format=yuv420p[out]")
    out = f"{OUT}/scene{i:02d}.mp4"
    run(["ffmpeg", "-y", "-i", src, "-loop", "1", "-framerate", str(FPS), "-i", cap, "-filter_complex", ";".join(chains), "-map", "[out]",
         "-t", f"{dur:.3f}", "-c:v", "libx264", "-preset", "medium", "-crf", "17", "-r", str(FPS), out])
    return out, dur


def end_video():
    png = f"{OUT}/end.png"
    end_png(png)
    out = f"{OUT}/end.mp4"
    frames = int(END * FPS)
    run(["ffmpeg", "-y", "-loop", "1", "-framerate", str(FPS), "-i", png, "-vf",
         f"zoompan=z='1.0+0.035*on/{frames}':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d={frames}:s={W}x{H}:fps={FPS},fade=out:st={END - 0.8}:d=0.8,format=yuv420p",
         "-frames:v", str(frames), "-c:v", "libx264", "-crf", "17", out])
    return out, END


def music(seconds, path, sr=44100):
    """A calm piano-and-pad loop: Fmaj7 – Am7 – Dm7 – Bbmaj7, 96 bpm."""
    t_beat = 60 / 96
    n = int(seconds * sr)
    out = np.zeros((n, 2))
    rng = np.random.default_rng(7)

    def note_freq(m):
        return 440 * 2 ** ((m - 69) / 12)

    chords = [[53, 57, 60, 64], [57, 60, 64, 67], [50, 53, 57, 60], [46, 50, 53, 57]]
    bar = 4 * t_beat

    def add(sig, start, pan=0.0):
        i0 = int(start * sr)
        if i0 >= n:
            return
        sig = sig[: n - i0]
        out[i0:i0 + len(sig), 0] += sig * (1 - pan) * 0.5 ** 0.5 * 1.2
        out[i0:i0 + len(sig), 1] += sig * (1 + pan) * 0.5 ** 0.5 * 1.2

    def pluck(m, length, vel):
        t = np.arange(int(length * sr)) / sr
        f = note_freq(m)
        env = np.exp(-t * 3.2) * (1 - np.exp(-t * 400))
        s = sum(a * np.sin(2 * np.pi * f * h * t) for h, a in [(1, 1.0), (2, 0.42), (3, 0.18), (4, 0.08)])
        return vel * s * env

    def pad(ms, length):
        t = np.arange(int(length * sr)) / sr
        env = np.minimum(1, t / 1.2) * np.minimum(1, (length - t) / 1.2)
        s = np.zeros_like(t)
        for m in ms:
            f = note_freq(m)
            for det in (-0.12, 0.0, 0.12):
                s += np.sin(2 * np.pi * f * (1 + det / 100) * t + rng.uniform(0, 6)) * 0.5
                s += np.sin(2 * np.pi * 2 * f * (1 + det / 100) * t) * 0.12
        return s * env * 0.045

    t = 0.0
    k = 0
    while t < seconds:
        ch = chords[k % 4]
        add(pad([m - 12 for m in ch[:3]] + ch, bar + 1.0), t)
        add(pluck(ch[0] - 12, 2.5, 0.22), t)
        arp = [ch[0], ch[1], ch[2], ch[3], ch[2] + 12, ch[3], ch[2], ch[1]]
        for j, m in enumerate(arp):
            add(pluck(m + 12, 1.6, 0.11 + 0.03 * (j % 2 == 0)), t + j * t_beat / 2, pan=0.35 * np.sin(j))
        if t > 3 * bar:  # soft pulse after the opening
            for b in range(4):
                tk = np.arange(int(0.25 * sr)) / sr
                kick = np.sin(2 * np.pi * (52 + 60 * np.exp(-tk * 30)) * tk) * np.exp(-tk * 14) * 0.22
                if b in (0, 2):
                    add(kick, t + b * t_beat)
                hat = rng.normal(0, 1, int(0.05 * sr)) * np.exp(-np.arange(int(0.05 * sr)) / sr * 90) * 0.02
                add(np.diff(hat, prepend=0), t + b * t_beat + t_beat / 2, pan=0.3)
        t += bar
        k += 1
    # Reverb: a decaying noise tail, convolved in the frequency domain.
    ir_len = int(2.2 * sr)
    ir = rng.normal(0, 1, (ir_len, 2)) * np.exp(-np.arange(ir_len) / sr * 2.6)[:, None] * 0.015
    size = 1 << int(np.ceil(np.log2(n + ir_len)))
    wet = np.stack([np.fft.irfft(np.fft.rfft(out[:, c], size) * np.fft.rfft(ir[:, c], size), size)[:n] for c in range(2)], axis=1)
    mix = out * 0.8 + wet * 0.6
    fade = np.ones(n)
    fi, fo = int(1.0 * sr), int(3.5 * sr)
    fade[:fi] = np.linspace(0, 1, fi)
    fade[-fo:] = np.linspace(1, 0, fo)
    mix *= fade[:, None]
    mix *= 0.7 / np.max(np.abs(mix))
    pcm = (mix * 32767).astype(np.int16)
    import wave
    with wave.open(path, "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(pcm.tobytes())


def main():
    os.makedirs(OUT, exist_ok=True)
    vids = []
    for i, (clip, parts, title, sub, cap_at) in enumerate(SCENES):
        vids.append(scene_video(i, clip, parts, title, sub, cap_at))
        print(f"scene {clip}: {vids[-1][1]:.1f}s", flush=True)
    vids.append(end_video())
    # Crossfades between the scenes.
    inputs, chain = [], []
    for p, _ in vids:
        inputs += ["-i", p]
    acc = vids[0][1]
    last = "[0:v]"
    for i in range(1, len(vids)):
        offset = acc - XF
        lab = f"[x{i}]"
        chain.append(f"{last}[{i}:v]xfade=transition=fade:duration={XF}:offset={offset:.3f}{lab}")
        last = lab
        acc = offset + vids[i][1]
    total = acc
    print(f"total {total:.1f}s", flush=True)
    music(total, f"{OUT}/music.wav")
    final = f"{DIR}/InkNotes_promo.mp4"
    run(["ffmpeg", "-y", *inputs, "-i", f"{OUT}/music.wav", "-filter_complex", ";".join(chain), "-map", last, "-map", f"{len(vids)}:a",
         "-c:v", "libx264", "-preset", "slow", "-crf", "19", "-pix_fmt", "yuv420p", "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", "-shortest", final])
    print(final)


if __name__ == "__main__":
    main()
