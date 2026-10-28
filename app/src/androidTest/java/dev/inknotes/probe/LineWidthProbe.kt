package dev.inknotes.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.ink.brush.InputToolType
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.InkBrushes
import dev.inknotes.data.InkKind
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Not a test: measures how thick a pen line and an even line of the same size come out. */
@RunWith(AndroidJUnit4::class)
class LineWidthProbe {
    @Test
    fun measure() {
        val out = StringBuilder()
        val r = CanvasStrokeRenderer.create()
        for (size in listOf(2f, 2.8f, 5f, 10f)) {
            for (kind in listOf(InkKind.PEN, InkKind.LINE)) {
                for (pressure in listOf(0.3f, 0.6f, 0.9f)) {
                    val b = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
                    val c = Canvas(b)
                    c.drawColor(Color.WHITE)
                    val batch = MutableStrokeInputBatch()
                    var t = 0L
                    for (i in 0..100) {
                        batch.add(InputToolType.STYLUS, 20f + i * 3f, 100f, t, pressure = pressure)
                        t += 4
                    }
                    // 10 px per unit.
                    val m = Matrix().apply { setScale(10f, 10f, 200f, 100f) }
                    c.concat(m)
                    r.draw(c, Stroke(InkBrushes.create(kind, Color.BLACK, size), batch), m)
                    var dark = 0
                    for (y in 0 until 200) if (Color.red(b.getPixel(200, y)) < 128) dark++
                    out.append("$kind size=$size p=$pressure width=${dark / 10f}\n")
                }
            }
        }
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "linewidth.txt").writeText(out.toString())
    }
}
