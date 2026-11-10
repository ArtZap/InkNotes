package dev.inknotes.math

import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Surfaces z = f(x, y): heights on a square grid, and the grid seen from a direction as
 * shaded four-sided patches drawn back to front (so nearer parts hide farther ones).
 */
object Plot3D {

    /** Grid lines along each axis. */
    const val N = 25

    fun of(f: (Double, Double) -> Double, xv: String, yv: String, trig: Boolean): Answer.Surface? {
        val r = if (trig) Math.PI else 3.0
        val zs = FloatArray(N * N)
        val finite = ArrayList<Float>()
        for (i in 0 until N) for (j in 0 until N) {
            val x = -r + 2 * r * i / (N - 1)
            val y = -r + 2 * r * j / (N - 1)
            val z = runCatching { f(x, y) }.getOrDefault(Double.NaN)
            zs[j * N + i] = if (z.isFinite()) z.toFloat() else Float.NaN
            if (z.isFinite()) finite += z.toFloat()
        }
        if (finite.size < N * N / 3) return null
        finite.sort()
        // Poles (1/(x² + y²)) are cut off rather than squash the rest flat.
        var z0 = finite[(finite.size * 0.02).toInt()]
        var z1 = finite[((finite.size - 1) * 0.98).toInt()]
        if (z1 - z0 < 1e-9f) {
            z0 -= 1f
            z1 += 1f
        }
        for (k in zs.indices) if (!zs[k].isNaN()) zs[k] = zs[k].coerceIn(z0, z1)
        return Answer.Surface(xv, yv, -r.toFloat(), r.toFloat(), -r.toFloat(), r.toFloat(), z0, z1, zs)
    }

    /** A patch in box coordinates (0…1, y down): four corners, its height (0…1) and lighting (0…1). */
    class Patch(val pts: FloatArray, val height: Float, val light: Float)

    /** Where the box's corners and the axis ends go, for frames and letters. */
    class View(val patches: List<Patch>, val axisX: FloatArray, val axisY: FloatArray, val axisZ: FloatArray)

    /**
     * The surface turned by [azimuth] degrees around the vertical and looked at from [elevation]
     * degrees above, fitted into a 1×[aspect] box (width 1, height [aspect]).
     */
    fun view(s: Answer.Surface, azimuth: Float, elevation: Float, aspect: Float = Plot.HEIGHT / Plot.WIDTH): View {
        val a = Math.toRadians(azimuth.toDouble())
        val e = Math.toRadians(elevation.toDouble())
        val ca = cos(a).toFloat()
        val sa = sin(a).toFloat()
        val ce = cos(e).toFloat()
        val se = sin(e).toFloat()
        // Unit cube −0.5…0.5, the height a bit flatter.
        fun project(u: Float, v: Float, w: Float): FloatArray {
            val x = u * ca - v * sa
            val y = u * sa + v * ca
            val z = w * 0.75f
            return floatArrayOf(x, z * ce + y * se, y * ce - z * se)
        }
        // Fit by the corners of the cube, so turning keeps the size steady.
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (u in listOf(-0.5f, 0.5f)) for (v in listOf(-0.5f, 0.5f)) for (w in listOf(-0.5f, 0.5f)) {
            val p = project(u, v, w)
            minX = min(minX, p[0]); maxX = max(maxX, p[0]); minY = min(minY, p[1]); maxY = max(maxY, p[1])
        }
        val k = 0.9f * min(1f / (maxX - minX), aspect / (maxY - minY))
        val cx = (minX + maxX) / 2
        val cy = (minY + maxY) / 2
        fun toBox(p: FloatArray, out: FloatArray, at: Int) {
            out[at] = 0.5f + (p[0] - cx) * k
            out[at + 1] = aspect / 2 - (p[1] - cy) * k
        }
        val n = N
        val zr = s.z1 - s.z0
        fun w(i: Int, j: Int) = (s.zs[j * n + i] - s.z0) / zr - 0.5f
        val patches = ArrayList<Pair<Float, Patch>>((n - 1) * (n - 1))
        for (i in 0 until n - 1) for (j in 0 until n - 1) {
            val ws = floatArrayOf(w(i, j), w(i + 1, j), w(i + 1, j + 1), w(i, j + 1))
            if (ws.any { it.isNaN() }) continue
            val us = floatArrayOf(i.toFloat(), i + 1f, i + 1f, i.toFloat())
            val vs = floatArrayOf(j.toFloat(), j.toFloat(), j + 1f, j + 1f)
            val pts = FloatArray(8)
            var depth = 0f
            for (c in 0 until 4) {
                val p = project(us[c] / (n - 1) - 0.5f, vs[c] / (n - 1) - 0.5f, ws[c])
                toBox(p, pts, c * 2)
                depth += p[2]
            }
            // Light from above and a little to the side: steep parts darker.
            val dzdu = (ws[1] + ws[2] - ws[0] - ws[3]) / 2 * (n - 1)
            val dzdv = (ws[2] + ws[3] - ws[0] - ws[1]) / 2 * (n - 1)
            val light = (1f / kotlin.math.sqrt(1f + dzdu * dzdu * 0.3f + dzdv * dzdv * 0.3f)).coerceIn(0f, 1f)
            patches += depth to Patch(pts, (ws.sum() / 4 + 0.5f).coerceIn(0f, 1f), light)
        }
        // Farthest first.
        val sorted = patches.sortedByDescending { it.first }.map { it.second }
        fun axis(u0: Float, v0: Float, w0: Float, u1: Float, v1: Float, w1: Float) = FloatArray(4).also {
            toBox(project(u0, v0, w0), it, 0)
            toBox(project(u1, v1, w1), it, 2)
        }
        return View(
            sorted,
            axis(-0.5f, -0.5f, -0.5f, 0.5f, -0.5f, -0.5f),
            axis(-0.5f, -0.5f, -0.5f, -0.5f, 0.5f, -0.5f),
            axis(-0.5f, -0.5f, -0.5f, -0.5f, -0.5f, 0.5f),
        )
    }

    const val AZIMUTH = 35f
    const val ELEVATION = 28f
}
