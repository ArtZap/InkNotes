package dev.inknotes.data

/**
 * Where a PDF page lies on its notebook page: turned by a quarter turn or more and with room
 * for notes round it. Plain arithmetic (no Android), so it is tested on the computer.
 *
 * An affine map is six numbers `a b c d e f`: x' = a·x + c·y + e, y' = b·x + d·y + f.
 */
object PdfGeometry {
    /** Room round the PDF page, as shares of its width (as turned): left, top, right, bottom. */
    data class Margins(val left: Float = 0f, val top: Float = 0f, val right: Float = 0f, val bottom: Float = 0f) {
        val isNone get() = left == 0f && top == 0f && right == 0f && bottom == 0f

        fun sane() = Margins(left.coerceIn(0f, MAX), top.coerceIn(0f, MAX), right.coerceIn(0f, MAX), bottom.coerceIn(0f, MAX))

        companion object {
            val NONE = Margins()
            const val MAX = 2f
        }
    }

    fun normalRotation(degrees: Int) = ((degrees % 360) + 360) % 360 / 90 * 90

    /** Size of the PDF page [w]×[h] (points) once turned by [rotation] degrees clockwise. */
    fun turned(w: Float, h: Float, rotation: Int): Pair<Float, Float> =
        if (normalRotation(rotation) % 180 == 0) w to h else h to w

    /** The whole notebook page in points: the turned PDF page and the margins round it. */
    fun outer(w: Float, h: Float, rotation: Int, m: Margins): Pair<Float, Float> {
        val (cw, ch) = turned(w, h, rotation)
        return cw * (1 + m.left + m.right) to ch + cw * (m.top + m.bottom)
    }

    /** From points of the PDF page as it is (top left origin) to points of the notebook page. */
    fun pdfToOuter(w: Float, h: Float, rotation: Int, m: Margins): FloatArray {
        val (cw, _) = turned(w, h, rotation)
        val ox = cw * m.left
        val oy = cw * m.top
        val r = when (normalRotation(rotation)) {
            90 -> floatArrayOf(0f, 1f, -1f, 0f, h, 0f)
            180 -> floatArrayOf(-1f, 0f, 0f, -1f, w, h)
            270 -> floatArrayOf(0f, -1f, 1f, 0f, 0f, w)
            else -> floatArrayOf(1f, 0f, 0f, 1f, 0f, 0f)
        }
        return then(r, translate(ox, oy))
    }

    /** From the PDF page's points to page units ([unitsWide] across the notebook page). */
    fun pdfToUnits(w: Float, h: Float, rotation: Int, m: Margins, unitsWide: Float): FloatArray {
        val (ow, _) = outer(w, h, rotation, m)
        val k = unitsWide / ow
        return then(pdfToOuter(w, h, rotation, m), scale(k, k))
    }

    /**
     * Page units of the page laid out one way to page units laid out another: what is written
     * stays on the same place of the PDF.
     */
    fun moveContent(w: Float, h: Float, oldRotation: Int, oldMargins: Margins, newRotation: Int, newMargins: Margins, unitsWide: Float): FloatArray =
        then(invert(pdfToUnits(w, h, oldRotation, oldMargins, unitsWide)), pdfToUnits(w, h, newRotation, newMargins, unitsWide))

    /** A point (0…1 across and down) of a page shown unturned, on the page turned by [rotation]. */
    fun turnUnit(u: Float, v: Float, rotation: Int): Pair<Float, Float> = when (normalRotation(rotation)) {
        90 -> (1 - v) to u
        180 -> (1 - u) to (1 - v)
        270 -> v to (1 - u)
        else -> u to v
    }

    fun translate(dx: Float, dy: Float) = floatArrayOf(1f, 0f, 0f, 1f, dx, dy)

    fun scale(sx: Float, sy: Float) = floatArrayOf(sx, 0f, 0f, sy, 0f, 0f)

    /** [first], then [second]. */
    fun then(first: FloatArray, second: FloatArray): FloatArray {
        val (a1, b1, c1, d1, e1) = first
        val f1 = first[5]
        val (a2, b2, c2, d2, e2) = second
        val f2 = second[5]
        return floatArrayOf(
            a2 * a1 + c2 * b1,
            b2 * a1 + d2 * b1,
            a2 * c1 + c2 * d1,
            b2 * c1 + d2 * d1,
            a2 * e1 + c2 * f1 + e2,
            b2 * e1 + d2 * f1 + f2,
        )
    }

    fun invert(m: FloatArray): FloatArray {
        val (a, b, c, d, e) = m
        val f = m[5]
        val det = a * d - b * c
        require(det != 0f) { "not invertible" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return floatArrayOf(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
    }

    fun map(m: FloatArray, x: Float, y: Float): Pair<Float, Float> = (m[0] * x + m[2] * y + m[4]) to (m[1] * x + m[3] * y + m[5])

    /** How much lengths grow under [m] (the maps here only scale evenly and turn). */
    fun scaleOf(m: FloatArray): Float = kotlin.math.sqrt(kotlin.math.abs(m[0] * m[3] - m[1] * m[2]))

    /** Degrees clockwise [m] turns by. */
    fun degreesOf(m: FloatArray): Float = Math.toDegrees(kotlin.math.atan2(m[1].toDouble(), m[0].toDouble())).toFloat()

    /**
     * Pixels to draw a page [aspect] (height / width) times as tall as wide: [widthPx] wide if
     * it fits, else smaller with the same shape, so that neither side passes [maxSide] and the
     * area stays within [maxPixels]. Very tall pages are narrowed, never cut.
     */
    fun fitPixels(widthPx: Int, aspect: Float, maxPixels: Long, maxSide: Int): Pair<Int, Int> {
        val a = if (aspect.isFinite() && aspect > 0f) aspect.toDouble() else 1.0
        var w = widthPx.coerceAtLeast(1).toDouble()
        w = minOf(w, maxSide.toDouble(), maxSide / a, kotlin.math.sqrt(maxPixels / a))
        val wi = w.toInt().coerceAtLeast(1)
        val hi = (wi * a).toInt().coerceIn(1, maxSide)
        return wi to hi
    }

    /** As Android's Matrix.setValues takes it (3×3, row by row). */
    fun toMatrixValues(m: FloatArray) = floatArrayOf(m[0], m[2], m[4], m[1], m[3], m[5], 0f, 0f, 1f)
}
