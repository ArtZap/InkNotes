package dev.inknotes.editor

import dev.inknotes.data.tr
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import dev.inknotes.data.FillPattern
import dev.inknotes.data.InkBrushes
import dev.inknotes.data.InkKind
import dev.inknotes.data.PageFill
import dev.inknotes.data.PageStroke
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Ready-made drawings ("stamps") and tables. Each is a set of polylines in a 100×100 box
 * (y down) plus solid polygons (arrowheads); placing one turns them into even lines and solid
 * fills, so they can be moved, resized, recoloured or erased like handwriting.
 */
object Stamps {

    /** [parts]: polylines, and solid polygons marked by a leading NaN (see [solid]). */
    class Stamp(val title: String, parts: List<FloatArray>) {
        val paths: List<FloatArray> = parts.filter { it.isEmpty() || !it[0].isNaN() }
        val solids: List<FloatArray> = parts.filter { it.isNotEmpty() && it[0].isNaN() }.map { it.copyOfRange(1, it.size) }
    }

    /** What placing a stamp adds to the page. */
    class Placed(val strokes: List<PageStroke>, val fills: List<PageFill>)

    /** Default width of a placed stamp, in page units. */
    const val SIZE = 130f

    /** Stamps grouped by subject, in the order the palette shows them. */
    val CATEGORIES: List<Pair<String, List<Stamp>>> by lazy {
        listOf(
            tr("Механика", "Mechanics") to listOf(
                Stamp(tr("Неподвижная опора", "Pinned support"), pinnedSupport()),
                Stamp(tr("Подвижная опора", "Roller support"), rollerSupport()),
                Stamp(tr("Жёсткая заделка", "Fixed support"), fixedSupport()),
                Stamp(tr("Скользящая заделка", "Sliding support"), slidingSupport()),
                Stamp(tr("Опорный стержень", "Support link"), linkSupport()),
                Stamp(tr("Шарнир", "Hinge"), listOf(circle(50f, 50f, 8f))),
                Stamp(tr("Стержень", "Bar"), listOf(circle(10f, 50f, 5f), floatArrayOf(15f, 50f, 85f, 50f), circle(90f, 50f, 5f))),
                Stamp(tr("Балка", "Beam"), listOf(floatArrayOf(8f, 42f, 92f, 42f, 92f, 58f, 8f, 58f, 8f, 42f))),
                Stamp(tr("Сила", "Force"), arrow(50f, 10f, 50f, 90f)),
                Stamp(tr("Распределённая нагрузка", "Distributed load"), distributedLoad()),
                Stamp(tr("Треугольная нагрузка", "Triangular load"), triangularLoad()),
                Stamp(tr("Момент по часовой", "Moment clockwise"), moment(clockwise = true)),
                Stamp(tr("Момент против часовой", "Moment counterclockwise"), moment(clockwise = false)),
                Stamp(tr("Наклонная плоскость", "Inclined plane"), inclinedPlane()),
                Stamp(tr("Груз", "Weight"), listOf(floatArrayOf(50f, 12f, 50f, 40f), floatArrayOf(30f, 40f, 70f, 40f, 70f, 80f, 30f, 80f, 30f, 40f))),
                Stamp(tr("Каток", "Roller"), listOf(circle(50f, 50f, 27f), circle(50f, 50f, 2.5f, n = 10)) + ground(77f)),
                Stamp(tr("Блок", "Pulley"), pulley()),
                Stamp(tr("Пружина", "Spring"), spring()),
                Stamp(tr("Оси координат", "Coordinate axes"), axes()),
                Stamp(tr("Консоль", "Cantilever"), listOf(floatArrayOf(14f, 10f, 14f, 90f)) + (0..8).map { i -> floatArrayOf(14f, 14f + i * 9f, 6f, 22f + i * 9f) } +
                    listOf(floatArrayOf(14f, 44f, 90f, 44f, 90f, 56f, 14f, 56f))),
                Stamp(tr("Сила под углом", "Force at an angle"), arrow(20f, 80f, 78f, 22f)),
                Stamp(tr("Две составляющие", "Two components"), arrow(20f, 80f, 80f, 80f) + arrow(20f, 80f, 20f, 20f) + dashed(floatArrayOf(80f, 80f, 80f, 20f, 20f, 20f)) + arrow(20f, 80f, 80f, 20f)),
                Stamp(tr("Ферма", "Truss"), listOf(floatArrayOf(10f, 75f, 90f, 75f, 70f, 35f, 30f, 35f, 10f, 75f), floatArrayOf(30f, 35f, 50f, 75f, 70f, 35f))),
                Stamp(tr("Трос с грузом", "Rope with a weight"), listOf(floatArrayOf(20f, 8f, 80f, 8f), floatArrayOf(50f, 8f, 50f, 55f), floatArrayOf(36f, 55f, 64f, 55f, 64f, 85f, 36f, 85f, 36f, 55f)) +
                    (0..6).map { i -> floatArrayOf(24f + i * 9f, 8f, 18f + i * 9f, 2f) }),
                Stamp(tr("Маятник", "Pendulum"), listOf(floatArrayOf(25f, 10f, 75f, 10f), floatArrayOf(50f, 10f, 68f, 70f), circle(70f, 76f, 7f)) + dashed(floatArrayOf(50f, 10f, 50f, 80f)) +
                    listOf(circle(50f, 10f, 18f, from = 90f, sweep = -17f, n = 6))),
                Stamp(tr("Брусок на опоре", "Block on a surface"), listOf(floatArrayOf(30f, 40f, 70f, 40f, 70f, 70f, 30f, 70f, 30f, 40f)) + ground(70f, 10f, 90f)),
                Stamp(tr("Скорость", "Velocity"), listOf(circle(30f, 50f, 8f)) + arrow(38f, 50f, 88f, 50f)),
            ),
            tr("Электрика", "Electricity") to listOf(
                Stamp(tr("Резистор", "Resistor"), listOf(floatArrayOf(5f, 50f, 25f, 50f), floatArrayOf(25f, 40f, 75f, 40f, 75f, 60f, 25f, 60f, 25f, 40f), floatArrayOf(75f, 50f, 95f, 50f))),
                Stamp(tr("Конденсатор", "Capacitor"), listOf(floatArrayOf(5f, 50f, 44f, 50f), floatArrayOf(44f, 28f, 44f, 72f), floatArrayOf(56f, 28f, 56f, 72f), floatArrayOf(56f, 50f, 95f, 50f))),
                Stamp(tr("Катушка", "Inductor"), inductor()),
                Stamp(tr("Источник ЭДС", "EMF source"), battery()),
                Stamp(tr("Лампа", "Lamp"), lamp()),
                Stamp(tr("Ключ", "Switch"), listOf(floatArrayOf(5f, 62f, 30f, 62f), circle(32f, 62f, 2f, n = 10), floatArrayOf(34f, 61f, 68f, 38f), circle(70f, 62f, 2f, n = 10), floatArrayOf(72f, 62f, 95f, 62f))),
                Stamp(tr("Диод", "Diode"), listOf(floatArrayOf(5f, 50f, 35f, 50f), floatArrayOf(35f, 32f, 35f, 68f, 65f, 50f, 35f, 32f), floatArrayOf(65f, 32f, 65f, 68f), floatArrayOf(65f, 50f, 95f, 50f))),
                Stamp(tr("Заземление", "Ground"), listOf(floatArrayOf(50f, 15f, 50f, 50f), floatArrayOf(28f, 50f, 72f, 50f), floatArrayOf(36f, 60f, 64f, 60f), floatArrayOf(44f, 70f, 56f, 70f))),
                Stamp(tr("Амперметр", "Ammeter"), meter(listOf(floatArrayOf(42f, 60f, 50f, 39f, 58f, 60f), floatArrayOf(45f, 53f, 55f, 53f)))),
                Stamp(tr("Вольтметр", "Voltmeter"), meter(listOf(floatArrayOf(42f, 40f, 50f, 61f, 58f, 40f)))),
                Stamp(tr("Узел", "Junction"), listOf(circle(50f, 50f, 3f, n = 12), circle(50f, 50f, 1.2f, n = 8))),
                Stamp(tr("Переменный резистор", "Variable resistor"), listOf(floatArrayOf(5f, 50f, 25f, 50f), floatArrayOf(25f, 40f, 75f, 40f, 75f, 60f, 25f, 60f, 25f, 40f), floatArrayOf(75f, 50f, 95f, 50f)) +
                    arrow(30f, 75f, 72f, 25f)),
                Stamp(tr("Предохранитель", "Fuse"), listOf(floatArrayOf(5f, 50f, 95f, 50f), floatArrayOf(25f, 42f, 75f, 42f, 75f, 58f, 25f, 58f, 25f, 42f))),
                Stamp(tr("Светодиод", "LED"), listOf(floatArrayOf(5f, 55f, 35f, 55f), floatArrayOf(35f, 37f, 35f, 73f, 65f, 55f, 35f, 37f), floatArrayOf(65f, 37f, 65f, 73f), floatArrayOf(65f, 55f, 95f, 55f)) +
                    arrow(52f, 32f, 66f, 16f) + arrow(62f, 38f, 76f, 22f)),
                Stamp(tr("Источник тока", "Current source"), listOf(floatArrayOf(5f, 50f, 30f, 50f), circle(50f, 50f, 20f), floatArrayOf(70f, 50f, 95f, 50f)) + arrow(50f, 64f, 50f, 36f)),
                Stamp(tr("Переменный ток", "Alternating current"), listOf(floatArrayOf(5f, 50f, 30f, 50f), circle(50f, 50f, 20f), floatArrayOf(70f, 50f, 95f, 50f), sine(38f, 62f, 50f, 7f, 1f))),
                Stamp(tr("Двигатель", "Motor"), listOf(floatArrayOf(5f, 50f, 30f, 50f), circle(50f, 50f, 20f), floatArrayOf(70f, 50f, 95f, 50f), floatArrayOf(39f, 62f, 39f, 38f, 50f, 55f, 61f, 38f, 61f, 62f))),
                Stamp(tr("Трансформатор", "Transformer"), transformer()),
                Stamp(tr("Транзистор", "Transistor"), listOf(circle(55f, 50f, 26f), floatArrayOf(10f, 50f, 42f, 50f), floatArrayOf(42f, 34f, 42f, 66f), floatArrayOf(42f, 44f, 64f, 28f, 64f, 14f), floatArrayOf(42f, 56f, 64f, 72f, 64f, 86f), head(42f, 56f, 62f, 70f, 9f))),
                Stamp(tr("Звонок", "Bell"), listOf(floatArrayOf(25f, 30f, 25f, 60f), floatArrayOf(75f, 30f, 75f, 60f), circle(50f, 60f, 25f, from = 0f, sweep = 180f, n = 18), floatArrayOf(25f, 60f, 75f, 60f))),
                Stamp(tr("Розетка", "Socket"), listOf(circle(50f, 55f, 25f, from = 180f, sweep = 180f, n = 18), floatArrayOf(25f, 55f, 75f, 55f), floatArrayOf(50f, 30f, 50f, 10f))),
            ),
            tr("Геометрия", "Geometry") to listOf(
                Stamp(tr("Прямой угол", "Right angle"), listOf(floatArrayOf(20f, 20f, 20f, 80f, 80f, 80f), floatArrayOf(20f, 68f, 32f, 68f, 32f, 80f))),
                Stamp(tr("Угол", "Angle"), listOf(floatArrayOf(90f, 80f, 15f, 80f, 75f, 26f), circle(15f, 80f, 24f, from = -42f, sweep = 42f, n = 10))),
                Stamp(tr("Окружность", "Circle"), listOf(circle(50f, 50f, 36f), circle(50f, 50f, 1.5f, n = 8), floatArrayOf(50f, 50f, 86f, 50f))),
                Stamp(tr("Куб", "Cube"), cube()),
                Stamp(tr("Цилиндр", "Cylinder"), cylinder()),
                Stamp(tr("Конус", "Cone"), cone()),
                Stamp(tr("Пирамида", "Pyramid"), pyramid()),
                Stamp(tr("Числовая прямая", "Number line"), arrow(5f, 50f, 95f, 50f) + (0..5).map { i -> floatArrayOf(15f + i * 14, 44f, 15f + i * 14, 56f) }),
                Stamp(tr("Треугольник", "Triangle"), listOf(floatArrayOf(50f, 12f, 90f, 85f, 10f, 85f, 50f, 12f))),
                Stamp(tr("Прямоугольный треугольник", "Right triangle"), listOf(floatArrayOf(15f, 15f, 15f, 85f, 85f, 85f, 15f, 15f), floatArrayOf(15f, 75f, 25f, 75f, 25f, 85f))),
                Stamp(tr("Квадрат", "Square"), listOf(floatArrayOf(15f, 15f, 85f, 15f, 85f, 85f, 15f, 85f, 15f, 15f))),
                Stamp(tr("Прямоугольник", "Rectangle"), listOf(floatArrayOf(5f, 25f, 95f, 25f, 95f, 75f, 5f, 75f, 5f, 25f))),
                Stamp(tr("Параллелограмм", "Parallelogram"), listOf(floatArrayOf(25f, 25f, 95f, 25f, 75f, 75f, 5f, 75f, 25f, 25f))),
                Stamp(tr("Трапеция", "Trapezoid"), listOf(floatArrayOf(30f, 25f, 70f, 25f, 95f, 75f, 5f, 75f, 30f, 25f))),
                Stamp(tr("Ромб", "Rhombus"), listOf(floatArrayOf(50f, 8f, 85f, 50f, 50f, 92f, 15f, 50f, 50f, 8f))),
                Stamp(tr("Шестиугольник", "Hexagon"), listOf(polygon(6, 42f, 0f))),
                Stamp(tr("Пятиугольник", "Pentagon"), listOf(polygon(5, 42f, -90f))),
                Stamp(tr("Эллипс", "Ellipse"), listOf(ellipse(50f, 50f, 44f, 26f))),
                Stamp(tr("Окружность с хордой", "Circle with a chord"), listOf(circle(50f, 50f, 38f), floatArrayOf(20f, 74f, 84f, 34f), circle(50f, 50f, 1.5f, n = 8))),
                Stamp(tr("Вписанный угол", "Inscribed angle"), listOf(circle(50f, 50f, 38f), floatArrayOf(18f, 70f, 50f, 12f, 82f, 70f))),
                Stamp(tr("Шар", "Sphere"), listOf(circle(50f, 50f, 38f), ellipse(50f, 50f, 38f, 11f, from = 0f, sweep = 180f, n = 18)) + dashed(ellipse(50f, 50f, 38f, 11f, from = 180f, sweep = 180f, n = 18))),
                Stamp(tr("Призма", "Prism"), prism()),
                Stamp(tr("Параллелепипед", "Cuboid"), listOf(floatArrayOf(10f, 35f, 65f, 35f, 65f, 90f, 10f, 90f, 10f, 35f), floatArrayOf(10f, 35f, 35f, 12f, 90f, 12f, 65f, 35f), floatArrayOf(90f, 12f, 90f, 67f, 65f, 90f)) +
                    dashed(floatArrayOf(10f, 90f, 35f, 67f, 90f, 67f)) + dashed(floatArrayOf(35f, 67f, 35f, 12f))),
                Stamp(tr("Параллельные прямые", "Parallel lines"), listOf(floatArrayOf(5f, 35f, 95f, 35f), floatArrayOf(5f, 65f, 95f, 65f), floatArrayOf(25f, 90f, 75f, 10f))),
                Stamp(tr("Перпендикуляр", "Perpendicular"), listOf(floatArrayOf(5f, 80f, 95f, 80f), floatArrayOf(50f, 80f, 50f, 10f), floatArrayOf(50f, 70f, 60f, 70f, 60f, 80f))),
                Stamp(tr("Вектор", "Vector"), arrow(15f, 75f, 85f, 25f) + arrow(40f, 32f, 62f, 32f, len = 7f)),
                Stamp(tr("Отрезок", "Segment"), listOf(floatArrayOf(10f, 50f, 90f, 50f), circle(10f, 50f, 2f, n = 8), circle(90f, 50f, 2f, n = 8))),
                Stamp(tr("Луч", "Ray"), listOf(circle(10f, 50f, 2f, n = 8), floatArrayOf(10f, 50f, 95f, 50f))),
                Stamp(tr("Транспортир", "Protractor"), listOf(circle(50f, 80f, 42f, from = 180f, sweep = 180f, n = 36), floatArrayOf(8f, 80f, 92f, 80f), circle(50f, 80f, 2f, n = 8)) +
                    (1..5).map { i -> val a = PI.toFloat() * i / 6; floatArrayOf(50f - 42f * cos(a), 80f - 42f * sin(a), 50f - 34f * cos(a), 80f - 34f * sin(a)) }),
            ),
            tr("Графики", "Graphs") to listOf(
                Stamp(tr("Парабола", "Parabola"), axesSmall() + listOf(curve(-1f, 1f) { x -> x * x })),
                Stamp(tr("Синусоида", "Sine wave"), axesSmall() + listOf(sine(10f, 92f, 50f, 22f, 2f))),
                Stamp(tr("Гипербола", "Hyperbola"), axesSmall() + listOf(curve(0.12f, 1f) { x -> 0.1f / x }, curve(-1f, -0.12f) { x -> 0.1f / x })),
                Stamp(tr("Экспонента", "Exponential"), axesSmall() + listOf(curve(-1f, 1f) { x -> (Math.exp(2.0 * x).toFloat() - 1f) / 7f })),
                Stamp(tr("Логарифм", "Logarithm"), axesSmall() + listOf(curve(0.03f, 1f) { x -> (kotlin.math.ln(x) / 3.5f) })),
                Stamp(tr("Модуль", "Absolute value"), axesSmall() + listOf(curve(-1f, 1f, n = 2) { x -> kotlin.math.abs(x) * 0.9f })),
                Stamp(tr("Прямая", "Line"), axesSmall() + listOf(curve(-1f, 1f, n = 2) { x -> 0.6f * x + 0.15f })),
                Stamp(tr("Кубическая", "Cubic"), axesSmall() + listOf(curve(-1f, 1f) { x -> x * x * x * 0.9f })),
            ),
            tr("Химия", "Chemistry") to listOf(
                Stamp(tr("Бензольное кольцо", "Benzene ring"), listOf(polygon(6, 38f, 30f), circle(50f, 50f, 22f))),
                Stamp(tr("Колба", "Flask"), listOf(floatArrayOf(42f, 8f, 42f, 40f, 15f, 88f, 85f, 88f, 58f, 40f, 58f, 8f), floatArrayOf(38f, 8f, 62f, 8f), floatArrayOf(26f, 70f, 74f, 70f))),
                Stamp(tr("Пробирка", "Test tube"), listOf(floatArrayOf(40f, 8f, 40f, 78f), circle(50f, 78f, 10f, from = 180f, sweep = -180f, n = 18), floatArrayOf(60f, 78f, 60f, 8f), floatArrayOf(36f, 8f, 64f, 8f), floatArrayOf(40f, 55f, 60f, 55f))),
                Stamp(tr("Стакан", "Beaker"), listOf(floatArrayOf(22f, 12f, 28f, 88f, 72f, 88f, 78f, 12f), floatArrayOf(25f, 50f, 75f, 50f))),
                Stamp(tr("Химическая стрелка", "Reaction arrow"), arrow(10f, 50f, 90f, 50f)),
                Stamp(tr("Равновесие", "Equilibrium"), listOf(floatArrayOf(10f, 42f, 90f, 42f, 80f, 34f), floatArrayOf(90f, 58f, 10f, 58f, 20f, 66f))),
                Stamp(tr("Выделение газа", "Gas released"), arrow(50f, 85f, 50f, 15f)),
                Stamp(tr("Осадок", "Precipitate"), arrow(50f, 15f, 50f, 85f)),
            ),
            tr("Разное", "Miscellaneous") to listOf(
                Stamp(tr("Стрелка", "Arrow"), arrow(10f, 50f, 90f, 50f)),
                Stamp(tr("Двойная стрелка", "Double arrow"), doubleArrow(10f, 50f, 90f, 50f)),
                Stamp(tr("Галочка", "Check mark"), listOf(floatArrayOf(20f, 55f, 42f, 76f, 82f, 24f))),
                Stamp(tr("Крестик", "Cross"), listOf(floatArrayOf(25f, 25f, 75f, 75f), floatArrayOf(75f, 25f, 25f, 75f))),
                Stamp(tr("Внимание", "Warning"), listOf(floatArrayOf(50f, 12f, 90f, 84f, 10f, 84f, 50f, 12f), floatArrayOf(50f, 36f, 50f, 62f), circle(50f, 72f, 2.5f, n = 10))),
                Stamp(tr("Выноска", "Callout"), listOf(floatArrayOf(12f, 18f, 88f, 18f, 88f, 62f, 45f, 62f, 28f, 84f, 32f, 62f, 12f, 62f, 12f, 18f))),
                Stamp(tr("Звезда", "Star"), listOf(star())),
                Stamp(tr("Сердечко", "Heart"), listOf(heart())),
                Stamp(tr("Вопрос", "Question"), listOf(floatArrayOf(32f, 30f, 36f, 18f, 50f, 12f, 64f, 18f, 68f, 30f, 62f, 42f, 50f, 50f, 50f, 64f), circle(50f, 80f, 2.5f, n = 10))),
                Stamp(tr("Восклицание", "Exclamation"), listOf(floatArrayOf(50f, 12f, 50f, 62f), circle(50f, 80f, 2.5f, n = 10))),
                Stamp(tr("Облачко", "Speech bubble"), listOf(cloud())),
                Stamp(tr("Фигурная скобка", "Curly brace"), listOf(floatArrayOf(70f, 5f, 58f, 8f, 55f, 20f, 55f, 40f, 50f, 48f, 40f, 50f, 50f, 52f, 55f, 60f, 55f, 80f, 58f, 92f, 70f, 95f))),
                Stamp(tr("Бесконечность", "Infinity"), listOf(infinity())),
                Stamp(tr("Флажок", "Flag"), listOf(floatArrayOf(25f, 92f, 25f, 8f, 80f, 22f, 25f, 38f))),
                Stamp(tr("Часы", "Clock"), listOf(circle(50f, 50f, 38f), floatArrayOf(50f, 24f, 50f, 50f, 68f, 60f))),
                Stamp(tr("Лампочка", "Light bulb"), listOf(circle(50f, 38f, 24f, from = 135f, sweep = 270f, n = 30), floatArrayOf(33f, 55f, 38f, 70f, 62f, 70f, 67f, 55f), floatArrayOf(40f, 78f, 60f, 78f), floatArrayOf(43f, 86f, 57f, 86f))),
                Stamp(tr("Плюс", "Plus"), listOf(floatArrayOf(50f, 15f, 50f, 85f), floatArrayOf(15f, 50f, 85f, 50f))),
                Stamp(tr("Минус", "Minus"), listOf(floatArrayOf(15f, 50f, 85f, 50f))),
                Stamp(tr("Рамка", "Frame"), listOf(floatArrayOf(5f, 20f, 95f, 20f, 95f, 80f, 5f, 80f, 5f, 20f))),
                Stamp(tr("Подчёркивание волной", "Wavy underline"), listOf(sine(5f, 95f, 50f, 6f, 4f))),
            ),
        )
    }

    val ALL: List<Stamp> by lazy { CATEGORIES.flatMap { it.second } }

    /** Even lines and solid arrowheads of [stamp], [size] units wide, centred at ([cx], [cy]). */
    fun place(stamp: Stamp, cx: Float, cy: Float, size: Float, color: Int, width: Float): Placed {
        val k = size / 100f
        fun map(p: FloatArray) = FloatArray(p.size) { i -> if (i % 2 == 0) cx + (p[i] - 50f) * k else cy + (p[i] - 50f) * k }
        return Placed(
            stamp.paths.map { stroke(map(it), color, width) },
            stamp.solids.map { PageFill(map(it), FillPattern.INK, color) },
        )
    }

    /**
     * A [rows]×[cols] grid centred at ([cx], [cy]): one stroke per line, cells sized to fit
     * the page width.
     */
    fun table(rows: Int, cols: Int, cx: Float, cy: Float, color: Int, width: Float, pageWidth: Float = PageEditorView.PAGE_W): List<PageStroke> {
        val cellW = min(TABLE_CELL_W, TABLE_MAX_W / cols)
        val w = cellW * cols
        val h = TABLE_CELL_H * rows
        val left = (cx - w / 2).coerceIn(10f, max(10f, pageWidth - 10f - w))
        val top = cy - h / 2
        val lines = ArrayList<FloatArray>()
        for (r in 0..rows) lines += floatArrayOf(left, top + r * TABLE_CELL_H, left + w, top + r * TABLE_CELL_H)
        for (c in 0..cols) lines += floatArrayOf(left + c * cellW, top, left + c * cellW, top + h)
        return lines.map { stroke(it, color, width) }
    }

    private const val TABLE_CELL_W = 160f
    private const val TABLE_CELL_H = 52f
    private const val TABLE_MAX_W = 940f

    /** A polyline as an even line, densified so curves render smoothly. */
    fun stroke(points: FloatArray, color: Int, width: Float): PageStroke {
        val batch = MutableStrokeInputBatch()
        var t = 0L
        fun add(x: Float, y: Float) {
            batch.add(InputToolType.STYLUS, x, y, t, pressure = 0.6f)
            t += 4
        }
        add(points[0], points[1])
        for (i in 2 until points.size step 2) {
            val x0 = points[i - 2]
            val y0 = points[i - 1]
            val n = (hypot(points[i] - x0, points[i + 1] - y0) / STEP).toInt().coerceAtLeast(1)
            for (k in 1..n) add(x0 + (points[i] - x0) * k / n, y0 + (points[i + 1] - y0) * k / n)
        }
        return PageStroke(InkKind.LINE, Stroke(InkBrushes.create(InkKind.LINE, color, InkBrushes.lineWidth(width)), batch))
    }

    private const val STEP = 3f

    // ------------------------------------------------------------- drawings

    private fun circle(cx: Float, cy: Float, r: Float, from: Float = 0f, sweep: Float = 360f, n: Int = 36): FloatArray =
        FloatArray((n + 1) * 2) { i ->
            val a = (from + sweep * (i / 2) / n) * PI.toFloat() / 180f
            if (i % 2 == 0) cx + r * cos(a) else cy + r * sin(a)
        }

    /** A solid polygon (x, y pairs), told from the polylines by the NaN in front. */
    private fun solid(vararg points: Float): FloatArray = floatArrayOf(Float.NaN, *points)

    /** Solid arrowhead: an even, slim triangle with its tip at (x1, y1), pointing away from (x0, y0). */
    private fun head(x0: Float, y0: Float, x1: Float, y1: Float, len: Float = 12f): FloatArray {
        val a = atan2(y1 - y0, x1 - x0)
        val bx = x1 - len * cos(a)
        val by = y1 - len * sin(a)
        val hw = len * HEAD_HALF_WIDTH
        return solid(x1, y1, bx - hw * sin(a), by + hw * cos(a), bx + hw * sin(a), by - hw * cos(a))
    }

    /** The shaft stops inside the head, so its round end never blunts the tip. */
    private fun arrow(x0: Float, y0: Float, x1: Float, y1: Float, len: Float = 12f): List<FloatArray> {
        val d = hypot(x1 - x0, y1 - y0)
        val k = (d - len * 0.6f) / d
        return listOf(floatArrayOf(x0, y0, x0 + (x1 - x0) * k, y0 + (y1 - y0) * k), head(x0, y0, x1, y1, len))
    }

    private fun doubleArrow(x0: Float, y0: Float, x1: Float, y1: Float, len: Float = 12f): List<FloatArray> {
        val d = hypot(x1 - x0, y1 - y0)
        val ux = (x1 - x0) / d
        val uy = (y1 - y0) / d
        val inset = len * 0.6f
        return listOf(floatArrayOf(x0 + ux * inset, y0 + uy * inset, x1 - ux * inset, y1 - uy * inset), head(x0, y0, x1, y1, len), head(x1, y1, x0, y0, len))
    }

    /** Half width of an arrowhead to its length. */
    private const val HEAD_HALF_WIDTH = 0.34f

    /** Ground: a line with short diagonal hatching under it. */
    private fun ground(y: Float, from: Float = 18f, to: Float = 82f): List<FloatArray> =
        listOf(floatArrayOf(from, y, to, y)) + (0..((to - from - 6) / 9).toInt()).map { i ->
            val x = from + 8 + i * 9
            floatArrayOf(x, y, x - 7, y + 9)
        }

    private fun pinnedSupport() = listOf(
        circle(50f, 24f, 6f),
        floatArrayOf(46f, 28.5f, 30f, 64f, 70f, 64f, 54f, 28.5f),
    ) + ground(64f)

    private fun rollerSupport() = listOf(
        circle(50f, 18f, 6f),
        floatArrayOf(46f, 22.5f, 32f, 52f, 68f, 52f, 54f, 22.5f),
        circle(39f, 58f, 5f),
        circle(61f, 58f, 5f),
    ) + ground(64f)

    private fun fixedSupport() = listOf(
        floatArrayOf(42f, 12f, 42f, 88f),
        floatArrayOf(42f, 50f, 90f, 50f),
    ) + (0..8).map { i ->
        val y = 16f + i * 9
        floatArrayOf(42f, y, 33f, y + 8)
    }

    private fun distributedLoad() = listOf(floatArrayOf(10f, 22f, 90f, 22f)) +
        (0..5).flatMap { i ->
            val x = 10f + i * 16
            arrow(x, 22f, x, 76f)
        }

    /**
     * A bending moment as engineers draw it: a radius from the point it acts at (the bottom
     * centre, where it attaches to a beam) to an arc over it, with a solid head at the arc's end.
     */
    private fun moment(clockwise: Boolean): List<FloatArray> {
        val cx = 50f
        val cy = 76f
        val r = 44f
        val len = 13f
        val from = -140f
        val to = -40f
        // The arc stops inside the head; the head's tip sits on the circle.
        val stop = to - Math.toDegrees(0.6 * len / r).toFloat()
        val arc = circle(cx, cy, r, from = from, sweep = stop - from, n = 30)
        val base = Math.toRadians(to - Math.toDegrees((len / r).toDouble())).toFloat()
        val tip = Math.toRadians(to.toDouble()).toFloat()
        val parts = listOf(
            floatArrayOf(cx, cy, arc[0], arc[1]),
            arc,
            head(cx + r * cos(base), cy + r * sin(base), cx + r * cos(tip), cy + r * sin(tip), len),
        )
        return if (clockwise) parts else parts.map(::mirror)
    }

    /** Left and right swapped; a solid keeps its NaN mark in front of its x, y pairs. */
    private fun mirror(p: FloatArray): FloatArray {
        val first = if (p.isNotEmpty() && p[0].isNaN()) 1 else 0
        return FloatArray(p.size) { i -> if (i >= first && (i - first) % 2 == 0) 100f - p[i] else p[i] }
    }

    private fun axes() = arrow(16f, 82f, 92f, 82f) + arrow(16f, 82f, 16f, 8f) + listOf(
        floatArrayOf(84f, 87f, 93f, 96f), floatArrayOf(93f, 87f, 84f, 96f), // x
        floatArrayOf(22f, 4f, 26.5f, 10f), floatArrayOf(31f, 4f, 24f, 16f), // y
    )

    private fun spring(): List<FloatArray> {
        val pts = arrayListOf(5f, 50f, 15f, 50f)
        for (i in 0..9) {
            pts += 18.5f + i * 7f
            pts += if (i % 2 == 0) 38f else 62f
        }
        pts += 85f; pts += 50f; pts += 95f; pts += 50f
        return listOf(pts.toFloatArray())
    }

    private fun pulley() = listOf(
        circle(50f, 58f, 26f),
        circle(50f, 58f, 3f, n = 12),
        floatArrayOf(50f, 55f, 50f, 8f),
        floatArrayOf(36f, 8f, 64f, 8f),
    )

    private fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, from: Float = 0f, sweep: Float = 360f, n: Int = 36): FloatArray =
        FloatArray((n + 1) * 2) { i ->
            val a = (from + sweep * (i / 2) / n) * PI.toFloat() / 180f
            if (i % 2 == 0) cx + rx * cos(a) else cy + ry * sin(a)
        }

    /** Hidden edges: the polyline cut into short dashes. */
    private fun dashed(points: FloatArray, dash: Float = 5f, gap: Float = 4f): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        var drawing = true
        var left = dash
        var cur = arrayListOf(points[0], points[1])
        for (i in 2 until points.size step 2) {
            var x0 = points[i - 2]
            var y0 = points[i - 1]
            val x1 = points[i]
            val y1 = points[i + 1]
            var seg = hypot(x1 - x0, y1 - y0)
            while (seg > 0f) {
                val step = min(seg, left)
                val t = step / seg
                val x = x0 + (x1 - x0) * t
                val y = y0 + (y1 - y0) * t
                if (drawing) {
                    cur += x
                    cur += y
                }
                seg -= step
                left -= step
                x0 = x
                y0 = y
                if (left <= 0f) {
                    if (drawing && cur.size >= 4) out += cur.toFloatArray()
                    drawing = !drawing
                    left = if (drawing) dash else gap
                    cur = arrayListOf(x, y)
                }
            }
        }
        if (drawing && cur.size >= 4) out += cur.toFloatArray()
        return out
    }

    private fun slidingSupport() = listOf(
        floatArrayOf(55f, 50f, 92f, 50f),
        floatArrayOf(55f, 28f, 55f, 72f),
        circle(49f, 36f, 5f),
        circle(49f, 64f, 5f),
        floatArrayOf(43f, 18f, 43f, 82f),
    ) + (0..6).map { i ->
        val y = 22f + i * 9
        floatArrayOf(43f, y, 35f, y + 7)
    }

    private fun linkSupport() = listOf(
        circle(50f, 14f, 5f),
        floatArrayOf(50f, 19f, 50f, 63f),
        circle(50f, 68f, 5f),
    ) + ground(73f)

    private fun triangularLoad(): List<FloatArray> = listOf(floatArrayOf(10f, 75f, 90f, 75f), floatArrayOf(10f, 65f, 90f, 15f)) +
        (1..5).flatMap { i ->
            val x = 10f + i * 16
            val top = 65f - (x - 10f) * 50f / 80f
            arrow(x, top, x, 75f, len = 8f)
        }

    private fun inclinedPlane() = listOf(
        floatArrayOf(10f, 78f, 90f, 78f, 90f, 30f, 10f, 78f),
        circle(10f, 78f, 18f, from = -31f, sweep = 31f, n = 8),
    ) + ground(78f, from = 10f, to = 90f).drop(1)

    private fun inductor(): List<FloatArray> {
        val pts = arrayListOf(5f, 60f, 15f, 60f)
        for (k in 0 until 4) {
            val arc = circle(23.75f + k * 17.5f, 60f, 8.75f, from = 180f, sweep = 180f, n = 12)
            for (i in 2 until arc.size) pts += arc[i]
        }
        pts += 95f; pts += 60f
        return listOf(pts.toFloatArray())
    }

    private fun battery() = listOf(
        floatArrayOf(5f, 50f, 44f, 50f),
        floatArrayOf(44f, 25f, 44f, 75f),
        floatArrayOf(56f, 37f, 56f, 63f),
        floatArrayOf(58f, 37f, 58f, 63f),
        floatArrayOf(58f, 50f, 95f, 50f),
        floatArrayOf(31f, 20f, 39f, 20f), floatArrayOf(35f, 16f, 35f, 24f), // +
    )

    private fun lamp() = listOf(
        circle(50f, 50f, 18f),
        floatArrayOf(37.3f, 37.3f, 62.7f, 62.7f),
        floatArrayOf(62.7f, 37.3f, 37.3f, 62.7f),
        floatArrayOf(5f, 50f, 32f, 50f),
        floatArrayOf(68f, 50f, 95f, 50f),
    )

    /** A measuring device: circle with leads and the letter inside. */
    private fun meter(letter: List<FloatArray>) = listOf(
        circle(50f, 50f, 20f),
        floatArrayOf(5f, 50f, 30f, 50f),
        floatArrayOf(70f, 50f, 95f, 50f),
    ) + letter

    private fun cube() = listOf(
        floatArrayOf(15f, 35f, 65f, 35f, 65f, 85f, 15f, 85f, 15f, 35f),
        floatArrayOf(15f, 35f, 35f, 15f, 85f, 15f, 65f, 35f),
        floatArrayOf(85f, 15f, 85f, 65f, 65f, 85f),
    ) + dashed(floatArrayOf(35f, 15f, 35f, 65f, 85f, 65f)) + dashed(floatArrayOf(35f, 65f, 15f, 85f))

    private fun cylinder() = listOf(
        ellipse(50f, 22f, 30f, 9f),
        floatArrayOf(20f, 22f, 20f, 78f),
        floatArrayOf(80f, 22f, 80f, 78f),
        ellipse(50f, 78f, 30f, 9f, from = 0f, sweep = 180f, n = 18),
    ) + dashed(ellipse(50f, 78f, 30f, 9f, from = 180f, sweep = 180f, n = 18))

    private fun cone() = listOf(
        floatArrayOf(18f, 80f, 50f, 12f, 82f, 80f),
        ellipse(50f, 80f, 32f, 9f, from = 0f, sweep = 180f, n = 18),
    ) + dashed(ellipse(50f, 80f, 32f, 9f, from = 180f, sweep = 180f, n = 18))

    private fun pyramid() = listOf(
        floatArrayOf(15f, 82f, 70f, 82f, 88f, 64f),
        floatArrayOf(15f, 82f, 48f, 12f, 70f, 82f),
        floatArrayOf(48f, 12f, 88f, 64f),
    ) + dashed(floatArrayOf(88f, 64f, 33f, 64f, 15f, 82f)) + dashed(floatArrayOf(33f, 64f, 48f, 12f))

    /** The classic heart curve fitted to the box. */
    /** y = f(x) for x in [x0, x1] of −1..1 drawn over the small axes (y up, −1..1 → 92..8). */
    private fun curve(x0: Float, x1: Float, n: Int = 40, f: (Float) -> Float): FloatArray {
        val pts = ArrayList<Float>()
        for (i in 0..n) {
            val x = x0 + (x1 - x0) * i / n
            val y = f(x)
            if (y < -1.05f || y > 1.05f) continue
            pts += 50f + x * 42f
            pts += 50f - y * 42f
        }
        return pts.toFloatArray()
    }

    private fun axesSmall() = arrow(4f, 50f, 96f, 50f) + arrow(50f, 96f, 50f, 4f)

    /** [periods] waves between [x0] and [x1] around [cy]. */
    private fun sine(x0: Float, x1: Float, cy: Float, amp: Float, periods: Float): FloatArray {
        val n = (24 * periods).toInt().coerceAtLeast(12)
        return FloatArray((n + 1) * 2) { i ->
            val t = (i / 2).toFloat() / n
            if (i % 2 == 0) x0 + (x1 - x0) * t else cy - amp * sin(t * periods * 2 * PI.toFloat())
        }
    }

    /** Regular polygon around the centre, first corner at [start] degrees. */
    private fun polygon(sides: Int, r: Float, start: Float): FloatArray = FloatArray((sides + 1) * 2) { i ->
        val a = (start + 360f * (i / 2) / sides) * PI.toFloat() / 180f
        if (i % 2 == 0) 50f + r * cos(a) else 50f + r * sin(a)
    }

    private fun prism() = listOf(
        floatArrayOf(15f, 85f, 65f, 85f, 85f, 70f),
        floatArrayOf(15f, 85f, 15f, 30f, 65f, 30f, 65f, 85f),
        floatArrayOf(15f, 30f, 35f, 15f, 85f, 15f, 65f, 30f),
        floatArrayOf(85f, 15f, 85f, 70f),
    ) + dashed(floatArrayOf(15f, 85f, 35f, 70f, 85f, 70f)) + dashed(floatArrayOf(35f, 70f, 35f, 15f))

    private fun transformer(): List<FloatArray> {
        fun coil(x: Float) = FloatArray(4 * 13 * 2) { i ->
            val k = i / 2
            val a = PI.toFloat() * (k % 13) / 12
            val turn = k / 13
            if (i % 2 == 0) x + (if (x < 50f) 1 else -1) * 9f * sin(a) else 22f + turn * 14f + 7f - 7f * cos(a)
        }
        return listOf(floatArrayOf(5f, 22f, 32f, 22f), coil(32f), floatArrayOf(32f, 78f, 5f, 78f), floatArrayOf(47f, 18f, 47f, 82f), floatArrayOf(53f, 18f, 53f, 82f),
            floatArrayOf(95f, 22f, 68f, 22f), coil(68f), floatArrayOf(68f, 78f, 95f, 78f))
    }

    private fun cloud(): FloatArray {
        // The upper outline of overlapping circles, closed by a flat bottom.
        val bumps = listOf(Triple(26f, 62f, 12f), Triple(43f, 50f, 17f), Triple(63f, 50f, 15f), Triple(78f, 62f, 10f))
        val pts = ArrayList<Float>()
        val n = 60
        for (i in 0..n) {
            val x = 14f + 74f * i / n
            var y = 72f
            for ((cx, cy, r) in bumps) {
                val d = r * r - (x - cx) * (x - cx)
                if (d >= 0f) y = min(y, cy - kotlin.math.sqrt(d))
            }
            pts += x
            pts += y
        }
        pts += 88f; pts += 72f; pts += 14f; pts += 72f; pts += 14f; pts += 62f
        return pts.toFloatArray()
    }

    private fun infinity(): FloatArray = FloatArray(81 * 2) { i ->
        val t = 2 * PI.toFloat() * (i / 2) / 80
        val d = 1 + sin(t) * sin(t)
        if (i % 2 == 0) 50f + 40f * cos(t) / d else 50f + 40f * sin(t) * cos(t) / d
    }

    private fun heart(): FloatArray {
        val n = 60
        return FloatArray((n + 1) * 2) { i ->
            val t = 2 * PI * (i / 2) / n
            if (i % 2 == 0) {
                (50 + 2.4 * 16 * sin(t) * sin(t) * sin(t)).toFloat()
            } else {
                (46 - 2.4 * (13 * cos(t) - 5 * cos(2 * t) - 2 * cos(3 * t) - cos(4 * t))).toFloat()
            }
        }
    }

    private fun star(): FloatArray = FloatArray(22) { i ->
        val k = i / 2
        val r = if (k % 2 == 0) 44f else 18f
        val a = (-90f + 36f * k) * PI.toFloat() / 180f
        if (i % 2 == 0) 50f + r * cos(a) else 52f + r * sin(a)
    }
}
