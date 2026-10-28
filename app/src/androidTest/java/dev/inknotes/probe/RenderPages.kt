package dev.inknotes.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.PageBackground
import dev.inknotes.data.PageCodec
import dev.inknotes.data.PaperKind
import dev.inknotes.editor.PageEdits
import dev.inknotes.editor.PageRenderer
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Not a test: renders every `*.ink` in `<external files>/render/` to PNG and lists its elements. */
@RunWith(AndroidJUnit4::class)
class RenderPages {
    @Test
    fun render() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "render")
        val files = dir.listFiles { f -> f.name.endsWith(".ink") }.orEmpty()
        assumeTrue(files.isNotEmpty())
        val out = StringBuilder()
        for (f in files.sortedBy { it.name }) {
            val c = PageCodec.read(f)
            out.append("${f.name} strokes=${c.strokes.size} fills=${c.fills.size} images=${c.images.size}\n")
            c.strokes.forEachIndexed { i, s ->
                val b = PageEdits.bounds(s)
                out.append("  $i ${s.kind} #${Integer.toHexString(s.stroke.brush.colorIntArgb)} size=${s.stroke.brush.size} n=${s.stroke.inputs.size} box=${b?.let { "${it.left.toInt()},${it.top.toInt()}-${it.right.toInt()},${it.bottom.toInt()}" }}\n")
            }
            c.fills.forEachIndexed { i, fl -> out.append("  fill $i ${fl.pattern} pts=${fl.outline.size / 2} box=${fl.bounds}\n") }
            val r = PageRenderer().apply { paper = PageBackground(PaperKind.GRID) }
            val k = 1.6f
            val bmp = Bitmap.createBitmap((1000 * k).toInt(), (1414 * k).toInt(), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.scale(k, k)
            r.drawBackground(canvas, 0f)
            r.drawContent(canvas, c.strokes, c.fills, Matrix().apply { setScale(k, k) }, c.images)
            File(dir, f.name + ".png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        File(dir, "list.txt").writeText(out.toString())
    }
}
