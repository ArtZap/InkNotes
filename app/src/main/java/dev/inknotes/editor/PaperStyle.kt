package dev.inknotes.editor

import android.graphics.Color

/** Paper and ruling colours for one theme. Shared by the page view and the background previews. */
class PaperStyle(
    val desk: Int,
    val paper: Int,
    val edge: Int,
    val line: Int,
    val margin: Int,
    val grid: Int,
    val dot: Int,
) {
    companion object {
        val LIGHT = PaperStyle(
            desk = 0xFFE3E6EA.toInt(),
            paper = Color.WHITE,
            edge = 0x33000000,
            line = 0xFFB3C7E0.toInt(),
            margin = 0xFFF0A8A8.toInt(),
            grid = 0xFFD5DDE6.toInt(),
            dot = 0xFFAFB8C2.toInt(),
        )
        val DARK = PaperStyle(
            desk = 0xFF111214.toInt(),
            paper = 0xFF1F2124.toInt(),
            edge = 0x33FFFFFF,
            line = 0xFF3B4757.toInt(),
            margin = 0xFF6E3B3B.toInt(),
            grid = 0xFF2F3742.toInt(),
            dot = 0xFF515C69.toInt(),
        )

        fun of(dark: Boolean) = if (dark) DARK else LIGHT

        /** Colours for paper of [color] (null: the theme's paper): ruling made to suit it. */
        fun of(dark: Boolean, color: Int?): PaperStyle {
            if (color == null) return of(dark)
            val base = of(isDark(color))
            // Ruling a little darker (or lighter) than the paper, tinted with it.
            fun shade(k: Float) = Color.rgb(
                (Color.red(color) * k + Color.red(base.line) * (1 - k)).toInt(),
                (Color.green(color) * k + Color.green(base.line) * (1 - k)).toInt(),
                (Color.blue(color) * k + Color.blue(base.line) * (1 - k)).toInt(),
            )
            return PaperStyle(of(dark).desk, color or 0xFF000000.toInt(), base.edge, shade(0.35f), base.margin, shade(0.55f), shade(0.3f))
        }

        /** Paper this dark gets light ink (black ink would vanish on it). */
        fun isDark(color: Int) =
            (0.2126f * Color.red(color) + 0.7152f * Color.green(color) + 0.0722f * Color.blue(color)) / 255f < 0.45f

        // Ruling in page units (1000 units = 210 mm).
        const val LINE_SPACING = 38f // 8 mm
        const val FINE_LINE_SPACING = 28.5f // 6 mm
        const val GRID_STEP = 23.8f // 5 mm
        const val MARGIN_X = 110f
    }
}

object InkColors {
    private fun luminance(c: Int) =
        (0.2126f * Color.red(c) + 0.7152f * Color.green(c) + 0.0722f * Color.blue(c)) / 255f

    /**
     * Neutral ink that would disappear on the paper is shown as its opposite extreme: black
     * becomes light on dark paper and white/light grey becomes dark on light paper.
     * Saturated colours (a yellow highlighter, a red pen) are never touched. Alpha is preserved.
     */
    fun forPaper(color: Int, dark: Boolean): Int {
        val hsv = FloatArray(3).also { Color.colorToHSV(color, it) }
        if (hsv[1] > 0.15f) return color
        val l = luminance(color)
        val alpha = color and 0xFF000000.toInt()
        return when {
            dark && l < 0.2f -> alpha or 0x00E8E8E8
            !dark && l > 0.85f -> alpha or 0x001A1A1A
            else -> color
        }
    }
}
