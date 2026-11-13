package dev.inknotes.data

import kotlin.math.ceil

/** How a canvas page grows: when writing comes near its right or bottom edge, room is added past it. */
object CanvasGrowth {
    const val START_W = 420f
    const val START_H = 297f

    /** Largest side: about two metres (a page picture still fits in memory at any zoom). */
    const val MAX = 2000f

    /** Writing closer than this to an edge makes the canvas grow there. */
    const val EDGE = 40f

    /** Room left past the writing after growing. */
    const val ROOM = 150f

    /**
     * The new size in mm for writing reaching [rightMm] and [bottomMm] on a [w]×[h] mm canvas;
     * null when it need not grow. Grows in whole centimetres and never shrinks.
     */
    fun grow(w: Float, h: Float, rightMm: Float, bottomMm: Float): Pair<Float, Float>? {
        fun side(size: Float, reach: Float): Float =
            if (reach <= size - EDGE || size >= MAX) size else minOf(MAX, ceil((reach + ROOM) / 10f) * 10f).coerceAtLeast(size)
        val nw = side(w, rightMm)
        val nh = side(h, bottomMm)
        return if (nw == w && nh == h) null else nw to nh
    }

    /** A canvas cut back to what is written on it ([rightMm], [bottomMm]) with a margin, never smaller than at the start. */
    fun fit(rightMm: Float, bottomMm: Float): Pair<Float, Float> =
        maxOf(START_W, minOf(MAX, ceil((rightMm + EDGE) / 10f) * 10f)) to maxOf(START_H, minOf(MAX, ceil((bottomMm + EDGE) / 10f) * 10f))
}
