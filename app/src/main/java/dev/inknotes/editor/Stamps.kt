package dev.inknotes.editor

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
            "Механика" to listOf(
                Stamp("Неподвижная опора", pinnedSupport()),
                Stamp("Подвижная опора", rollerSupport()),
                Stamp("Жёсткая заделка", fixedSupport()),
                Stamp("Скользящая заделка", slidingSupport()),
                Stamp("Опорный стержень", linkSupport()),
                Stamp("Шарнир", listOf(circle(50f, 50f, 8f))),
                Stamp("Стержень", listOf(circle(10f, 50f, 5f), floatArrayOf(15f, 50f, 85f, 50f), circle(90f, 50f, 5f))),
                Stamp("Балка", listOf(floatArrayOf(8f, 42f, 92f, 42f, 92f, 58f, 8f, 58f, 8f, 42f))),
                Stamp("Сила", arrow(50f, 10f, 50f, 90f)),
                Stamp("Распределённая нагрузка", distributedLoad()),
                Stamp("Треугольная нагрузка", triangularLoad()),
                Stamp("Момент по часовой", moment(clockwise = true)),
                Stamp("Момент против часовой", moment(clockwise = false)),
                Stamp("Наклонная плоскость", inclinedPlane()),
                Stamp("Груз", listOf(floatArrayOf(50f, 12f, 50f, 40f), floatArrayOf(30f, 40f, 70f, 40f, 70f, 80f, 30f, 80f, 30f, 40f))),
                Stamp("Каток", listOf(circle(50f, 50f, 27f), circle(50f, 50f, 2.5f, n = 10)) + ground(77f)),
                Stamp("Блок", pulley()),
                Stamp("Пружина", spring()),
                Stamp("Оси координат", axes()),
                Stamp("Консоль", listOf(floatArrayOf(14f, 10f, 14f, 90f)) + (0..8).map { i -> floatArrayOf(14f, 14f + i * 9f, 6f, 22f + i * 9f) } +
                    listOf(floatArrayOf(14f, 44f, 90f, 44f, 90f, 56f, 14f, 56f))),
                Stamp("Сила под углом", arrow(20f, 80f, 78f, 22f)),
                Stamp("Две составляющие", arrow(20f, 80f, 80f, 80f) + arrow(20f, 80f, 20f, 20f) + dashed(floatArrayOf(80f, 80f, 80f, 20f, 20f, 20f)) + arrow(20f, 80f, 80f, 20f)),
                Stamp("Ферма", listOf(floatArrayOf(10f, 75f, 90f, 75f, 70f, 35f, 30f, 35f, 10f, 75f), floatArrayOf(30f, 35f, 50f, 75f, 70f, 35f))),
                Stamp("Трос с грузом", listOf(floatArrayOf(20f, 8f, 80f, 8f), floatArrayOf(50f, 8f, 50f, 55f), floatArrayOf(36f, 55f, 64f, 55f, 64f, 85f, 36f, 85f, 36f, 55f)) +
                    (0..6).map { i -> floatArrayOf(24f + i * 9f, 8f, 18f + i * 9f, 2f) }),
                Stamp("Маятник", listOf(floatArrayOf(25f, 10f, 75f, 10f), floatArrayOf(50f, 10f, 68f, 70f), circle(70f, 76f, 7f)) + dashed(floatArrayOf(50f, 10f, 50f, 80f)) +
                    listOf(circle(50f, 10f, 18f, from = 90f, sweep = -17f, n = 6))),
                Stamp("Брусок на опоре", listOf(floatArrayOf(30f, 40f, 70f, 40f, 70f, 70f, 30f, 70f, 30f, 40f)) + ground(70f, 10f, 90f)),
                Stamp("Скорость", listOf(circle(30f, 50f, 8f)) + arrow(38f, 50f, 88f, 50f)),
            ),
            "Электрика" to listOf(
                Stamp("Резистор", listOf(floatArrayOf(5f, 50f, 25f, 50f), floatArrayOf(25f, 40f, 75f, 40f, 75f, 60f, 25f, 60f, 25f, 40f), floatArrayOf(75f, 50f, 95f, 50f))),
                Stamp("Конденсатор", listOf(floatArrayOf(5f, 50f, 44f, 50f), floatArrayOf(44f, 28f, 44f, 72f), floatArrayOf(56f, 28f, 56f, 72f), floatArrayOf(56f, 50f, 95f, 50f))),
                Stamp("Катушка", inductor()),
                Stamp("Источник ЭДС", battery()),
                Stamp("Лампа", lamp()),
                Stamp("Ключ", listOf(floatArrayOf(5f, 62f, 30f, 62f), circle(32f, 62f, 2f, n = 10), floatArrayOf(34f, 61f, 68f, 38f), circle(70f, 62f, 2f, n = 10), floatArrayOf(72f, 62f, 95f, 62f))),
                Stamp("Диод", listOf(floatArrayOf(5f, 50f, 35f, 50f), floatArrayOf(35f, 32f, 35f, 68f, 65f, 50f, 35f, 32f), floatArrayOf(65f, 32f, 65f, 68f), floatArrayOf(65f, 50f, 95f, 50f))),
                Stamp("Заземление", listOf(floatArrayOf(50f, 15f, 50f, 50f), floatArrayOf(28f, 50f, 72f, 50f), floatArrayOf(36f, 60f, 64f, 60f), floatArrayOf(44f, 70f, 56f, 70f))),
                Stamp("Амперметр", meter(listOf(floatArrayOf(42f, 60f, 50f, 39f, 58f, 60f), floatArrayOf(45f, 53f, 55f, 53f)))),
                Stamp("Вольтметр", meter(listOf(floatArrayOf(42f, 40f, 50f, 61f, 58f, 40f)))),
                Stamp("Узел", listOf(circle(50f, 50f, 3f, n = 12), circle(50f, 50f, 1.2f, n = 8))),
                Stamp("Переменный резистор", listOf(floatArrayOf(5f, 50f, 25f, 50f), floatArrayOf(25f, 40f, 75f, 40f, 75f, 60f, 25f, 60f, 25f, 40f), floatArrayOf(75f, 50f, 95f, 50f)) +
                    arrow(30f, 75f, 72f, 25f)),
                Stamp("Предохранитель", listOf(floatArrayOf(5f, 50f, 95f, 50f), floatArrayOf(25f, 42f, 75f, 42f, 75f, 58f, 25f, 58f, 25f, 42f))),
                Stamp("Светодиод", listOf(floatArrayOf(5f, 55f, 35f, 55f), floatArrayOf(35f, 37f, 35f, 73f, 65f, 55f, 35f, 37f), floatArrayOf(65f, 37f, 65f, 73f), floatArrayOf(65f, 55f, 95f, 55f)) +
                    arrow(52f, 32f, 66f, 16f) + arrow(62f, 38f, 76f, 22f)),
                Stamp("Источник тока", listOf(floatArrayOf(5f, 50f, 30f, 50f), circle(50f, 50f, 20f), floatArrayOf(70f, 50f, 95f, 50f)) + arrow(50f, 64f, 50f, 36f)),
                Stamp("Переменный ток", listOf(floatArrayOf(5f, 50f, 30f, 50f), circle(50f, 50f, 20f), floatArrayOf(70f, 50f, 95f, 50f), sine(38f, 62f, 50f, 7f, 1f))),
                Stamp("Двигатель", listOf(floatArrayOf(5f, 50f, 30f, 50f), circle(50f, 50f, 20f), floatArrayOf(70f, 50f, 95f, 50f), floatArrayOf(39f, 62f, 39f, 38f, 50f, 55f, 61f, 38f, 61f, 62f))),
                Stamp("Трансформатор", transformer()),
                Stamp("Транзистор", listOf(circle(55f, 50f, 26f), floatArrayOf(10f, 50f, 42f, 50f), floatArrayOf(42f, 34f, 42f, 66f), floatArrayOf(42f, 44f, 64f, 28f, 64f, 14f), floatArrayOf(42f, 56f, 64f, 72f, 64f, 86f), head(42f, 56f, 62f, 70f, 9f))),
                Stamp("Звонок", listOf(floatArrayOf(25f, 30f, 25f, 60f), floatArrayOf(75f, 30f, 75f, 60f), circle(50f, 60f, 25f, from = 0f, sweep = 180f, n = 18), floatArrayOf(25f, 60f, 75f, 60f))),
                Stamp("Розетка", listOf(circle(50f, 55f, 25f, from = 180f, sweep = 180f, n = 18), floatArrayOf(25f, 55f, 75f, 55f), floatArrayOf(50f, 30f, 50f, 10f))),
            ),
            "Геометрия" to listOf(
                Stamp("Прямой угол", listOf(floatArrayOf(20f, 20f, 20f, 80f, 80f, 80f), floatArrayOf(20f, 68f, 32f, 68f, 32f, 80f))),
                Stamp("Угол", listOf(floatArrayOf(90f, 80f, 15f, 80f, 75f, 26f), circle(15f, 80f, 24f, from = -42f, sweep = 42f, n = 10))),
                Stamp("Окружность", listOf(circle(50f, 50f, 36f), circle(50f, 50f, 1.5f, n = 8), floatArrayOf(50f, 50f, 86f, 50f))),
                Stamp("Куб", cube()),
                Stamp("Цилиндр", cylinder()),
                Stamp("Конус", cone()),
                Stamp("Пирамида", pyramid()),
                Stamp("Числовая прямая", arrow(5f, 50f, 95f, 50f) + (0..5).map { i -> floatArrayOf(15f + i * 14, 44f, 15f + i * 14, 56f) }),
                Stamp("Треугольник", listOf(floatArrayOf(50f, 12f, 90f, 85f, 10f, 85f, 50f, 12f))),
                Stamp("Прямоугольный треугольник", listOf(floatArrayOf(15f, 15f, 15f, 85f, 85f, 85f, 15f, 15f), floatArrayOf(15f, 75f, 25f, 75f, 25f, 85f))),
                Stamp("Квадрат", listOf(floatArrayOf(15f, 15f, 85f, 15f, 85f, 85f, 15f, 85f, 15f, 15f))),
                Stamp("Прямоугольник", listOf(floatArrayOf(5f, 25f, 95f, 25f, 95f, 75f, 5f, 75f, 5f, 25f))),
                Stamp("Параллелограмм", listOf(floatArrayOf(25f, 25f, 95f, 25f, 75f, 75f, 5f, 75f, 25f, 25f))),
                Stamp("Трапеция", listOf(floatArrayOf(30f, 25f, 70f, 25f, 95f, 75f, 5f, 75f, 30f, 25f))),
                Stamp("Ромб", listOf(floatArrayOf(50f, 8f, 85f, 50f, 50f, 92f, 15f, 50f, 50f, 8f))),
                Stamp("Шестиугольник", listOf(polygon(6, 42f, 0f))),
                Stamp("Пятиугольник", listOf(polygon(5, 42f, -90f))),
                Stamp("Эллипс", listOf(ellipse(50f, 50f, 44f, 26f))),
                Stamp("Окружность с хордой", listOf(circle(50f, 50f, 38f), floatArrayOf(20f, 74f, 84f, 34f), circle(50f, 50f, 1.5f, n = 8))),
                Stamp("Вписанный угол", listOf(circle(50f, 50f, 38f), floatArrayOf(18f, 70f, 50f, 12f, 82f, 70f))),
                Stamp("Шар", listOf(circle(50f, 50f, 38f), ellipse(50f, 50f, 38f, 11f, from = 0f, sweep = 180f, n = 18)) + dashed(ellipse(50f, 50f, 38f, 11f, from = 180f, sweep = 180f, n = 18))),
                Stamp("Призма", prism()),
                Stamp("Параллелепипед", listOf(floatArrayOf(10f, 35f, 65f, 35f, 65f, 90f, 10f, 90f, 10f, 35f), floatArrayOf(10f, 35f, 35f, 12f, 90f, 12f, 65f, 35f), floatArrayOf(90f, 12f, 90f, 67f, 65f, 90f)) +
                    dashed(floatArrayOf(10f, 90f, 35f, 67f, 90f, 67f)) + dashed(floatArrayOf(35f, 67f, 35f, 12f))),
                Stamp("Параллельные прямые", listOf(floatArrayOf(5f, 35f, 95f, 35f), floatArrayOf(5f, 65f, 95f, 65f), floatArrayOf(25f, 90f, 75f, 10f))),
                Stamp("Перпендикуляр", listOf(floatArrayOf(5f, 80f, 95f, 80f), floatArrayOf(50f, 80f, 50f, 10f), floatArrayOf(50f, 70f, 60f, 70f, 60f, 80f))),
                Stamp("Вектор", arrow(15f, 75f, 85f, 25f) + arrow(40f, 32f, 62f, 32f, len = 7f)),
                Stamp("Отрезок", listOf(floatArrayOf(10f, 50f, 90f, 50f), circle(10f, 50f, 2f, n = 8), circle(90f, 50f, 2f, n = 8))),
                Stamp("Луч", listOf(circle(10f, 50f, 2f, n = 8), floatArrayOf(10f, 50f, 95f, 50f))),
                Stamp("Транспортир", listOf(circle(50f, 80f, 42f, from = 180f, sweep = 180f, n = 36), floatArrayOf(8f, 80f, 92f, 80f), circle(50f, 80f, 2f, n = 8)) +
                    (1..5).map { i -> val a = PI.toFloat() * i / 6; floatArrayOf(50f - 42f * cos(a), 80f - 42f * sin(a), 50f - 34f * cos(a), 80f - 34f * sin(a)) }),
            ),
            "Графики" to listOf(
                Stamp("Парабола", axesSmall() + listOf(curve(-1f, 1f) { x -> x * x })),
                Stamp("Синусоида", axesSmall() + listOf(sine(10f, 92f, 50f, 22f, 2f))),
                Stamp("Гипербола", axesSmall() + listOf(curve(0.12f, 1f) { x -> 0.1f / x }, curve(-1f, -0.12f) { x -> 0.1f / x })),
                Stamp("Экспонента", axesSmall() + listOf(curve(-1f, 1f) { x -> (Math.exp(2.0 * x).toFloat() - 1f) / 7f })),
                Stamp("Логарифм", axesSmall() + listOf(curve(0.03f, 1f) { x -> (kotlin.math.ln(x) / 3.5f) })),
                Stamp("Модуль", axesSmall() + listOf(curve(-1f, 1f, n = 2) { x -> kotlin.math.abs(x) * 0.9f })),
                Stamp("Прямая", axesSmall() + listOf(curve(-1f, 1f, n = 2) { x -> 0.6f * x + 0.15f })),
                Stamp("Кубическая", axesSmall() + listOf(curve(-1f, 1f) { x -> x * x * x * 0.9f })),
            ),
            "Химия" to listOf(
                Stamp("Бензольное кольцо", listOf(polygon(6, 38f, 30f), circle(50f, 50f, 22f))),
                Stamp("Колба", listOf(floatArrayOf(42f, 8f, 42f, 40f, 15f, 88f, 85f, 88f, 58f, 40f, 58f, 8f), floatArrayOf(38f, 8f, 62f, 8f), floatArrayOf(26f, 70f, 74f, 70f))),
                Stamp("Пробирка", listOf(floatArrayOf(40f, 8f, 40f, 78f), circle(50f, 78f, 10f, from = 180f, sweep = -180f, n = 18), floatArrayOf(60f, 78f, 60f, 8f), floatArrayOf(36f, 8f, 64f, 8f), floatArrayOf(40f, 55f, 60f, 55f))),
                Stamp("Стакан", listOf(floatArrayOf(22f, 12f, 28f, 88f, 72f, 88f, 78f, 12f), floatArrayOf(25f, 50f, 75f, 50f))),
                Stamp("Химическая стрелка", arrow(10f, 50f, 90f, 50f)),
                Stamp("Равновесие", listOf(floatArrayOf(10f, 42f, 90f, 42f, 80f, 34f), floatArrayOf(90f, 58f, 10f, 58f, 20f, 66f))),
                Stamp("Выделение газа", arrow(50f, 85f, 50f, 15f)),
                Stamp("Осадок", arrow(50f, 15f, 50f, 85f)),
            ),
            "Разное" to listOf(
                Stamp("Стрелка", arrow(10f, 50f, 90f, 50f)),
                Stamp("Двойная стрелка", doubleArrow(10f, 50f, 90f, 50f)),
                Stamp("Галочка", listOf(floatArrayOf(20f, 55f, 42f, 76f, 82f, 24f))),
                Stamp("Крестик", listOf(floatArrayOf(25f, 25f, 75f, 75f), floatArrayOf(75f, 25f, 25f, 75f))),
                Stamp("Внимание", listOf(floatArrayOf(50f, 12f, 90f, 84f, 10f, 84f, 50f, 12f), floatArrayOf(50f, 36f, 50f, 62f), circle(50f, 72f, 2.5f, n = 10))),
                Stamp("Выноска", listOf(floatArrayOf(12f, 18f, 88f, 18f, 88f, 62f, 45f, 62f, 28f, 84f, 32f, 62f, 12f, 62f, 12f, 18f))),
                Stamp("Звезда", listOf(star())),
                Stamp("Сердечко", listOf(heart())),
                Stamp("Вопрос", listOf(floatArrayOf(32f, 30f, 36f, 18f, 50f, 12f, 64f, 18f, 68f, 30f, 62f, 42f, 50f, 50f, 50f, 64f), circle(50f, 80f, 2.5f, n = 10))),
                Stamp("Восклицание", listOf(floatArrayOf(50f, 12f, 50f, 62f), circle(50f, 80f, 2.5f, n = 10))),
                Stamp("Облачко", listOf(cloud())),
                Stamp("Фигурная скобка", listOf(floatArrayOf(70f, 5f, 58f, 8f, 55f, 20f, 55f, 40f, 50f, 48f, 40f, 50f, 50f, 52f, 55f, 60f, 55f, 80f, 58f, 92f, 70f, 95f))),
                Stamp("Бесконечность", listOf(infinity())),
                Stamp("Флажок", listOf(floatArrayOf(25f, 92f, 25f, 8f, 80f, 22f, 25f, 38f))),
                Stamp("Часы", listOf(circle(50f, 50f, 38f), floatArrayOf(50f, 24f, 50f, 50f, 68f, 60f))),
                Stamp("Лампочка", listOf(circle(50f, 38f, 24f, from = 135f, sweep = 270f, n = 30), floatArrayOf(33f, 55f, 38f, 70f, 62f, 70f, 67f, 55f), floatArrayOf(40f, 78f, 60f, 78f), floatArrayOf(43f, 86f, 57f, 86f))),
                Stamp("Плюс", listOf(floatArrayOf(50f, 15f, 50f, 85f), floatArrayOf(15f, 50f, 85f, 50f))),
                Stamp("Минус", listOf(floatArrayOf(15f, 50f, 85f, 50f))),
                Stamp("Рамка", listOf(floatArrayOf(5f, 20f, 95f, 20f, 95f, 80f, 5f, 80f, 5f, 20f))),
                Stamp("Подчёркивание волной", listOf(sine(5f, 95f, 50f, 6f, 4f))),
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
