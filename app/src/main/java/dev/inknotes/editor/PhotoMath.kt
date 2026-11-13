package dev.inknotes.editor

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Picture edits as plain arithmetic on pixels and corner points (no Android classes, so they
 * are tested on the JVM): turning a photo in perspective, a document's four corners made a
 * rectangle, the background taken away, a scanned page made crisp.
 */
object PhotoMath {

    /**
     * Where the corners of a [w]×[h] picture go when it is turned [aroundX] degrees about the
     * horizontal axis through its middle (top edge away from the viewer for positive angles)
     * and [aroundY] degrees about the vertical one (right edge away), seen from [distance]
     * picture widths in front. Top left, top right, bottom right, bottom left, as x, y pairs,
     * moved so the smallest x and y are 0.
     */
    fun tiltCorners(w: Float, h: Float, aroundX: Float, aroundY: Float, distance: Float = 2.2f): FloatArray {
        val f = max(w, h) * distance
        val ax = Math.toRadians(aroundX.toDouble())
        val ay = Math.toRadians(aroundY.toDouble())
        val src = floatArrayOf(-w / 2, -h / 2, w / 2, -h / 2, w / 2, h / 2, -w / 2, h / 2)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val x = src[2 * i].toDouble()
            val y = src[2 * i + 1].toDouble()
            // About the horizontal axis, then the vertical one.
            val y1 = y * cos(ax)
            val z1 = -y * sin(ax)
            val x2 = x * cos(ay) - z1 * sin(ay)
            val z2 = x * sin(ay) + z1 * cos(ay)
            val s = f / (f + z2)
            out[2 * i] = (x2 * s).toFloat()
            out[2 * i + 1] = (y1 * s).toFloat()
        }
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        for (i in 0 until 4) {
            minX = min(minX, out[2 * i])
            minY = min(minY, out[2 * i + 1])
        }
        for (i in 0 until 4) {
            out[2 * i] -= minX
            out[2 * i + 1] -= minY
        }
        return out
    }

    /** Width and height of the box around [corners] (x, y pairs). */
    fun boundsSize(corners: FloatArray): Pair<Float, Float> {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until corners.size / 2) {
            minX = min(minX, corners[2 * i])
            maxX = max(maxX, corners[2 * i])
            minY = min(minY, corners[2 * i + 1])
            maxY = max(maxY, corners[2 * i + 1])
        }
        return (maxX - minX) to (maxY - minY)
    }

    /**
     * Size of the rectangle a sheet photographed with these [corners] (top left, top right,
     * bottom right, bottom left) is straightened into: its edges' average lengths.
     */
    fun straightSize(corners: FloatArray): Pair<Int, Int> {
        fun d(a: Int, b: Int) = hypot(corners[2 * a] - corners[2 * b], corners[2 * a + 1] - corners[2 * b + 1])
        val w = (d(0, 1) + d(3, 2)) / 2
        val h = (d(0, 3) + d(1, 2)) / 2
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    /** The four corners are a usable outline: in order round the sheet and not folded over. */
    fun isConvex(corners: FloatArray): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val ax = corners[2 * ((i + 1) % 4)] - corners[2 * i]
            val ay = corners[2 * ((i + 1) % 4) + 1] - corners[2 * i + 1]
            val bx = corners[2 * ((i + 2) % 4)] - corners[2 * ((i + 1) % 4)]
            val by = corners[2 * ((i + 2) % 4) + 1] - corners[2 * ((i + 1) % 4) + 1]
            val cross = ax * by - ay * bx
            if (abs(cross) < 1e-3f) return false
            val s = if (cross > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    private fun dist(a: Int, b: Int): Int {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return max(abs(dr), max(abs(dg), abs(db)))
    }

    /**
     * The background made see-through: everything joined to the picture's edge whose colour is
     * within [tolerance] (0…1) of the edge's usual colour. A thin soft rim keeps edges smooth.
     * [px] are ARGB pixels of a [w]×[h] picture; a new array is returned.
     */
    fun clearBackground(px: IntArray, w: Int, h: Int, tolerance: Float): IntArray {
        val out = px.copyOf()
        if (w < 2 || h < 2) return out
        // The edge's usual colour: the middle value of each channel round the border.
        val border = IntArray(2 * (w + h))
        var n = 0
        for (x in 0 until w) {
            border[n++] = px[x]
            border[n++] = px[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            border[n++] = px[y * w]
            border[n++] = px[y * w + w - 1]
        }
        fun median(shift: Int): Int {
            val c = IntArray(n) { (border[it] shr shift) and 0xFF }
            c.sort()
            return c[n / 2]
        }
        val ref = (0xFF shl 24) or (median(16) shl 16) or (median(8) shl 8) or median(0)
        val limit = (tolerance.coerceIn(0f, 1f) * 255).roundToInt()
        val soft = limit + max(6, limit / 3)
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var head = 0
        var tail = 0
        fun push(i: Int) {
            if (seen[i]) return
            seen[i] = true
            if (dist(px[i], ref) <= limit) queue[tail++] = i
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        val gone = BooleanArray(w * h)
        while (head < tail) {
            val i = queue[head++]
            gone[i] = true
            out[i] = out[i] and 0x00FFFFFF
            val x = i % w
            val y = i / w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (y > 0) push(i - w)
            if (y < h - 1) push(i + w)
        }
        // The rim: pixels next to the cleared part fade by how close they are to the background.
        for (i in 0 until w * h) {
            if (gone[i]) continue
            val x = i % w
            val y = i / w
            val touches = (x > 0 && gone[i - 1]) || (x < w - 1 && gone[i + 1]) || (y > 0 && gone[i - w]) || (y < h - 1 && gone[i + w])
            if (!touches) continue
            val d = dist(px[i], ref)
            if (d <= soft) {
                val a = (255 * (d - limit).coerceAtLeast(0) / max(1, soft - limit)).coerceIn(0, 255)
                val alpha = ((px[i] ushr 24) * a) / 255
                out[i] = (alpha shl 24) or (px[i] and 0x00FFFFFF)
            }
        }
        return out
    }

    /**
     * A photographed page made crisp: grey, the paper white and the ink dark (levels stretched
     * between the darkest and the lightest few per cent). With [color] the colours stay, only
     * stretched.
     */
    fun documentLevels(px: IntArray, color: Boolean = false): IntArray {
        if (px.isEmpty()) return px.copyOf()
        val hist = IntArray(256)
        fun luma(c: Int) = (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8
        for (c in px) hist[luma(c)]++
        fun at(share: Float): Int {
            val goal = (px.size * share).toLong()
            var sum = 0L
            for (v in 0..255) {
                sum += hist[v]
                if (sum >= goal) return v
            }
            return 255
        }
        val lo = at(0.02f)
        // The paper: most of a page is paper, so a high share of pixels is at most this light.
        val hi = max(lo + 16, at(0.9f) - 6)
        val span = (hi - lo).toFloat()
        fun stretch(v: Int) = (((v - lo) / span) * 255f).roundToInt().coerceIn(0, 255)
        return IntArray(px.size) { i ->
            val c = px[i]
            val a = c and 0xFF000000.toInt()
            if (color) {
                a or (stretch((c shr 16) and 0xFF) shl 16) or (stretch((c shr 8) and 0xFF) shl 8) or stretch(c and 0xFF)
            } else {
                val g = stretch(luma(c))
                a or (g shl 16) or (g shl 8) or g
            }
        }
    }
}
