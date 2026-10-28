package dev.inknotes.editor

import android.graphics.RectF
import dev.inknotes.data.InkKind
import dev.inknotes.data.PageStroke

/**
 * Proportions of the neon pen, shared by drawing and by everything that needs its extent.
 * The brush size of a neon line is the width of its lit tube; the light core and the halo
 * are in proportion to it, so the size the user picks is the line they see.
 */
object Neon {
    /** Core width as a share of the tube. */
    const val CORE = 0.45f

    /** Halo width (past the tube) as a share of the tube. */
    const val HALO = 1.1f

    /** How far the halo reaches past the tube, in halo sizes (half its width + blur spread). */
    private const val REACH = 4f

    fun coreWidth(tube: Float) = tube * CORE

    fun haloWidth(tube: Float) = tube * HALO

    /** The line's colour mixed most of the way to white. */
    fun core(color: Int): Int {
        fun mix(c: Int) = c + ((255 - c) * 0.78f).toInt()
        return (color and 0xFF000000.toInt()) or
            (mix(color shr 16 and 0xFF) shl 16) or (mix(color shr 8 and 0xFF) shl 8) or mix(color and 0xFF)
    }

    /** [bounds] of the line grown to take in the tube and its glow (other kinds unchanged). */
    fun withGlow(s: PageStroke, bounds: RectF): RectF {
        if (s.kind != InkKind.NEON) return bounds
        val tube = s.stroke.brush.size
        val r = tube + haloWidth(tube) * REACH
        return RectF(bounds).apply { inset(-r, -r) }
    }
}
