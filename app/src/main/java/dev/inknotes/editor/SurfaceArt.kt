package dev.inknotes.editor

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import dev.inknotes.math.Answer
import dev.inknotes.math.Plot
import dev.inknotes.math.Plot3D

/** Draws a surface z = f(x, y): shaded patches back to front, the three axes and their letters. */
object SurfaceArt {

    /** Into [canvas] scaled so the box is [width] wide (its height is [width] · HEIGHT/WIDTH). */
    fun draw(canvas: Canvas, s: Answer.Surface, width: Float, color: Int, dark: Boolean, azimuth: Float = Plot3D.AZIMUTH, elevation: Float = Plot3D.ELEVATION) {
        val v = Plot3D.view(s, azimuth, elevation)
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width * 0.0016f
            this.color = (color and 0x00FFFFFF) or 0x66000000
        }
        val path = Path()
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        for (p in v.patches) {
            path.rewind()
            path.moveTo(p.pts[0] * width, p.pts[1] * width)
            for (k in 2 until 8 step 2) path.lineTo(p.pts[k] * width, p.pts[k + 1] * width)
            path.close()
            // Higher parts warmer, lit faces lighter.
            val h = floatArrayOf((hsv[0] - 40f * p.height + 360f) % 360f, (0.25f + 0.45f * p.height).coerceIn(0f, 1f), if (dark) 0.35f + 0.55f * p.light else 0.55f + 0.42f * p.light)
            fill.color = Color.HSVToColor(0xE6, h)
            canvas.drawPath(path, fill)
            canvas.drawPath(path, edge)
        }
        val axis = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = width * 0.004f
            this.color = (color and 0x00FFFFFF) or 0xAA000000.toInt()
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = (color and 0x00FFFFFF) or 0xDD000000.toInt()
            textSize = width * 0.045f
            textAlign = Paint.Align.CENTER
        }
        for ((a, name) in listOf(v.axisX to s.xv, v.axisY to s.yv, v.axisZ to "z")) {
            canvas.drawLine(a[0] * width, a[1] * width, a[2] * width, a[3] * width, axis)
            val dx = a[2] - a[0]
            val dy = a[3] - a[1]
            val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-6f)
            canvas.drawText(name, (a[2] + dx / len * 0.04f) * width, (a[3] + dy / len * 0.04f) * width + text.textSize * 0.35f, text)
        }
    }

    /** The surface as a picture [widthPx] wide, for the page (drawn once, then scaled). */
    fun bitmap(s: Answer.Surface, widthPx: Int, color: Int, dark: Boolean): Bitmap {
        val h = (widthPx * Plot.HEIGHT / Plot.WIDTH).toInt()
        val bmp = Bitmap.createBitmap(widthPx, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), s, widthPx.toFloat(), color, dark)
        return bmp
    }
}
