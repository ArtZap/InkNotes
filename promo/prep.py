"""Fills the demo notebooks (not filmed)."""
import drive as d

PEN = (68, 179)
FOUNTAIN = (164, 179)
BLACK, BLUE, RED, GREEN, VIOLET = (1358, 179), (1426, 179), (1494, 179), (1562, 179), (1630, 179)
THIN = (1062, 179)


def create(s, title, paper, color):
    s.text("Тетрадь", after=1200).text(paper, after=300).tap(*color, after=300)
    s.text("Название", after=1000).key(123, after=300).paste(title, after=800).key(4, after=800)
    s.text("Создать", after=2500)
    return s


def lines(s, texts, x, first, step=74, h=36, speed=1.7):
    for i, t in enumerate(texts):
        s.write(t, x, first + step * i - h * 1.05, h, speed=speed, gap=12)
    return s
