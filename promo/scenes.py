"""The filmed scenes: each builds a script for the ScriptPlayer (coordinates: Huawei 2000x1200)."""
import math

import drive as d
import prep as p

# Tools without the pen-kind button (fountain pen, marker, neon) have the colours further left.
COLORS2 = [(1227, 179), (1295, 179), (1363, 179), (1431, 179), (1499, 179)]
SETTINGS = "Настройки: стиль интерфейса и тема"


def wobble_circle(cx, cy, r, n=44, seed=0.0):
    return [(cx + r * math.cos(2 * math.pi * k / 40 + seed) + 2 * math.sin(k * 0.7), cy + r * 0.96 * math.sin(2 * math.pi * k / 40 + seed) + 2 * math.cos(k * 0.5)) for k in range(n)]


def star(cx, cy, r):
    pts = []
    for k in range(6):
        a = -math.pi / 2 + k * 4 * math.pi / 5
        pts.append((cx + r * math.cos(a) + 4 * math.sin(k * 2.0), cy + r * math.sin(a) + 3 * math.cos(k)))
    return pts


ROW2 = {"История", "Планы на неделю", "Скетчбук", "Физика"}


def open_nb(s, title):
    """Opens a notebook from the library (sorted by name; the second row needs a scroll)."""
    s.sleep(800)
    if title in ROW2:
        s.swipe(600, (1000, 1000), (1000, 560), after=900)
    return s.text(title, after=2200)


def intro():
    """The app opening: splash with the name, then the library."""
    s = d.Script()
    s.sleep(1200)
    s.swipe(1400, (1000, 1000), (1000, 650), after=900)
    s.swipe(1400, (1000, 650), (1000, 1000), after=1200)
    return s


def notes():
    s = d.Script()
    open_nb(s, "Биология")
    s.text("Перьевая ручка", after=300).tap(950, 179, after=200).tap(*COLORS2[0], after=300)
    s.write("Клетка", 640, 330, 70, speed=1.3)
    s.text("Ручка", after=300).tap(*p.THIN, after=200).tap(*p.BLUE, after=300)
    p.lines(s, ["Клетка — основная единица жизни", "Ядро хранит ДНК", "Митохондрии дают энергию (АТФ)"], 625, 635, h=36, speed=1.5)
    s.tap(*p.RED, after=300)
    p.lines(s, ["Что такое клетка?"], 60, 635, h=30, speed=1.5)
    p.lines(s, ["Где хранится ДНК?"], 60, 709, h=30, speed=1.5)
    s.text("Маркер", after=300).tap(990, 179, after=200).tap(*COLORS2[0], after=300)
    s.pen([(905, 614), (1200, 611), (1340, 615)], ms=650)
    s.sleep(400).pen([(625, 762), (1255, 759)], ms=650)
    s.sleep(1500)
    return s


def shapes():
    s = d.Script()
    open_nb(s, "Скетчбук")
    s.text("Ручка", after=300).tap(1150, 179, after=200).tap(*p.VIOLET, after=300)
    s.pen(wobble_circle(420, 520, 130), ms=900, hold=900)
    s.sleep(500)
    s.pen([(760, 650), (905, 385), (1050, 652), (765, 645)], ms=900, hold=900)
    s.sleep(500)
    s.tap(*p.BLUE, after=200)
    s.pen([(1200, 400), (1540, 412), (1548, 652), (1206, 643), (1202, 408)], ms=1000, hold=900)
    s.sleep(500)
    s.tap(*p.RED, after=200)
    s.pen(star(1770, 525, 135), ms=1100, hold=900)
    s.sleep(1200)
    return s


def lasso():
    s = d.Script()
    open_nb(s, "Скетчбук")
    s.text("Лассо: выделить и двигать", after=400)
    s.pen([(720, 330), (1090, 340), (1100, 690), (710, 700), (715, 335)], ms=800)
    s.sleep(700)
    s.pen([(905, 560), (850, 700), (720, 900)], ms=900)  # drag down
    s.sleep(600)
    # Rotate by the handle above the frame (frame centre is now about 720, 855).
    hy, cx, cy = 655, 720, 855
    r = cy - hy
    s.pen([(cx + r * math.sin(math.radians(a)), cy - r * math.cos(math.radians(a))) for a in range(0, 50, 4)], ms=900)
    s.sleep(600)
    s.tap(1176, 179, after=800)  # recolour red
    s.tap(1500, 1000, after=900)
    return s


def insert():
    s = d.Script()
    open_nb(s, "Скетчбук")
    s.text("Добавить страницу после текущей", after=1500)
    s.text("Ручка", after=300).tap(*p.THIN, after=200).tap(*p.BLACK, after=300)
    for name, to in [("Неподвижная опора", (520, 520)), ("Сила", (1000, 480)), ("Момент", (1480, 520))]:
        s.text("Штампы: готовые рисунки", after=1300).text(name, after=1200)
        s.pen([(1000, 715), (to[0], to[1])], ms=700)
        s.sleep(300).tap(1900, 1150, after=500)
    s.text("Вставить таблицу", after=1000).text("Вставить", after=1200)
    s.pen([(1000, 715), (1000, 900)], ms=700)
    s.sleep(400).tap(1900, 1150, after=1000)
    return s


def hatch():
    s = d.Script()
    open_nb(s, "Скетчбук")
    s.text("Область: заштриховать", after=400)
    s.pen(wobble_circle(420, 520, 160, n=42), ms=900)
    s.sleep(500).text("Заштриховать", after=900)
    s.pen([(1170, 375), (1580, 385), (1580, 680), (1170, 675), (1172, 380)], ms=900)
    s.sleep(500).text("Узор: Линии. Выбрать узор и настроить", after=1200)
    s.text("Сетка", after=900).text("Готово", after=800)
    s.text("Заштриховать", after=1500)
    return s


def neon():
    s = d.Script()
    open_nb(s, "Скетчбук")
    s.text("Следующая страница", after=800)
    s.text("Добавить страницу после текущей", after=1500)
    s.text("Ещё", after=800).text("Тема: светлая", after=1500)
    s.text("Неон", after=400).tap(1000, 179, after=300).tap(*COLORS2[0], after=300)
    s.pen(star(560, 640, 230), ms=1500)
    s.sleep(400).tap(*COLORS2[2], after=300)
    pts = [(1250 + 18 * t * math.cos(t), 650 + 18 * t * math.sin(t)) for t in [k * 0.25 for k in range(60)]]
    s.pen(pts, ms=1500)
    s.sleep(400).tap(*COLORS2[1], after=300)
    s.write("Neon", 900, 930, 90, speed=1.2)
    s.sleep(2500)
    s.text("Ещё", after=800).text("Тема: тёмная", after=1200)
    return s


def maths():
    s = d.Script()
    open_nb(s, "Высшая математика")
    s.text("Ручка", after=300).tap(*p.THIN, after=200).tap(*p.BLACK, after=300)
    h = 60
    s.math("12+30=", 120, 300, h, speed=1.5)
    s.math("x=5", 120, 430, h, speed=1.5)
    s.math("2x+1=", 520, 430, h, speed=1.5)
    # 6/3 + 2² =
    s.math("6", 140, 545, h, speed=1.5)
    s.pen([(120, 623), (200, 623)], ms=150)
    s.math("3", 140, 637, h, speed=1.5)
    s.math("+2", 220, 593, h, speed=1.5)
    s.math("2", 316, 571, h * 0.55, speed=1.5)
    s.math("=", 360, 593, h, speed=1.5)
    # System { x+y=3 ; x−y=1
    brace = []
    for k in range(31):
        t = k / 30
        x = 30 * (1 - 2 * t) ** 2 if t < 0.5 else 30 * (2 * t - 1) ** 2
        brace.append((1020 + x + (-14 if 0.45 <= t <= 0.55 else 0), 300 + 190 * t))
    s.pen(brace, ms=500)
    s.math("x+y=3", 1070, 310, h, speed=1.5)
    s.math("x-y=1", 1070, 410, h, speed=1.5)
    s.sleep(4500)
    # The solution steps of the system's answer.
    s.tap(1150, 540, after=2200)
    d.snap(s, "answer")
    s.key(4, after=900)
    # y = x² − 3 on a fresh page: a graph.
    s.text("Добавить страницу после текущей", after=1500)
    s.math("y=x", 200, 400, h, speed=1.5)
    s.math("2", 370, 380, h * 0.55, speed=1.5)
    s.math("-3", 410, 400, h, speed=1.5)
    s.sleep(6000)
    return s


def style(s, name, hold=1800):
    s.text(SETTINGS, after=1300).text(name, after=2300)
    s.add("backif", "Стиль интерфейса")
    return s.sleep(hold)


def styles():
    s = d.Script()
    s.sleep(1000)
    for name in ["Классический", "Премиум", "Минимал", "Тетрадки", "Жидкое стекло"]:
        style(s, name)
    return s


def glass():
    s = d.Script()
    s.sleep(1200)
    s.text(SETTINGS, after=1300).swipe(500, (1000, 1000), (1000, 400), after=900)
    s.text("Полупрозрачные обложки", after=1000).text("Готово", after=2500)
    s.swipe(1500, (1000, 1000), (1000, 650), after=1200)
    s.swipe(1500, (1000, 650), (1000, 1000), after=1200)
    return s


def search():
    s = d.Script()
    s.sleep(1000)
    s.text("Поиск по названиям и рукописному тексту", after=900).key(123, after=200).paste("основание", after=500).key(4, after=2500)
    s.tap(300, 520, after=3000)
    d.snap(s, "found")
    return s


def help_():
    s = d.Script()
    s.sleep(1000)
    s.text("Ещё", after=900).text("Справка: все инструменты", after=1800)
    s.text("Пишущие инструменты", after=1500)
    s.swipe(700, (330, 1000), (330, 550), after=900)
    s.text("Математика: ответы после «=»", after=1800)
    s.swipe(900, (1300, 1000), (1300, 600), after=1200)
    s.text("Оформление", after=1800)
    return s
