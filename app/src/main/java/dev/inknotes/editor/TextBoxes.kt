package dev.inknotes.editor

import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import dev.inknotes.data.PageImage
import dev.inknotes.data.PageText
import kotlin.math.ceil
import kotlin.math.max

/** Typed text on the page: how it is laid out in its box, and boxes made to fit it. */
object TextBoxes {

    fun paint(text: PageText, color: Int = text.color) = TextPaint(TextPaint.ANTI_ALIAS_FLAG or TextPaint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = text.size
        this.color = color
        typeface = Typeface.DEFAULT
    }

    fun layout(text: PageText, width: Float, color: Int = text.color): StaticLayout {
        val p = paint(text, color)
        val w = max(1, width.toInt())
        return StaticLayout.Builder.obtain(text.text, 0, text.text.length, p, w)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .build()
    }

    /** Height of [text] laid out [width] wide. */
    fun height(text: PageText, width: Float): Float = max(text.size, layout(text, width).height.toFloat())

    /**
     * A box for [text] at ([x], [y]): as wide as its longest line (up to [maxWidth]), as high
     * as it then needs.
     */
    fun box(text: PageText, x: Float, y: Float, maxWidth: Float): PageImage {
        val p = paint(text)
        val longest = text.text.lines().maxOfOrNull { p.measureText(it) } ?: 0f
        val w = ceil(longest.coerceIn(text.size * 2, maxWidth)) + 2f
        return PageImage.textBox(text, x, y, w, height(text, w))
    }

    /** The same box with new text (or size), its width kept and its height fitted. */
    fun edited(img: PageImage, text: PageText): PageImage =
        PageImage(img.file, img.x, img.y, img.w, height(text, img.w), img.rotation, text, img.layer)
}
