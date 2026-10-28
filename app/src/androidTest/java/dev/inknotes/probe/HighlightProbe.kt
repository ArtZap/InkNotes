package dev.inknotes.probe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PdfPages
import dev.inknotes.editor.HighlightAligner
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.sin

/**
 * Not a test: runs the highlighter alignment over cells of page 1 of the notebook named by
 * `-e hlprobe <title>` (read only) and saves the page with the bands to `hl-probe.png`.
 */
@RunWith(AndroidJUnit4::class)
class HighlightProbe {
    @Test
    fun probe() {
        val title = InstrumentationRegistry.getArguments().getString("hlprobe")
        assumeTrue(title != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = NotebookRepository(context.filesDir)
        val nb = repo.list().first { it.title == title }
        val ref = nb.pdfPages[nb.pageIds[0]]!!
        val pdf = PdfPages(repo).use { it.renderBlocking(nb, ref, 2000)!! }
        val h = 1000f * ref.height / ref.width
        val draw = { c: Canvas -> c.drawBitmap(pdf, null, RectF(0f, 0f, 1000f, h), Paint(Paint.FILTER_BITMAP_FLAG)) }
        // Passes over cells: x0, x1, y (a bit off the text), marker size.
        val passes = listOf(
            floatArrayOf(423f, 488f, 388f, 10f), // (песч.)-1,5
            floatArrayOf(434f, 475f, 418f, 10f), // 160
            floatArrayOf(370f, 410f, 330f, 10f), // 12
            floatArrayOf(370f, 410f, 348f, 10f), // 2,9
            floatArrayOf(115f, 310f, 360f, 10f), // Вариант исполнения наружных стен
            floatArrayOf(140f, 310f, 287f, 10f), // Наименование показателей
            floatArrayOf(370f, 420f, 392f, 12f), // (спс.)-1,1
        )
        val out = pdf.copy(Bitmap.Config.ARGB_8888, true)
        val c = Canvas(out)
        c.scale(2f, 2f)
        val paint = Paint().apply { color = 0x80FF8000.toInt() }
        val log = StringBuilder()
        for (p in passes) {
            val n = 30
            val xs = FloatArray(n) { p[0] + (p[1] - p[0]) * it / (n - 1) }
            val ys = FloatArray(n) { p[2] + 2f * sin(it * 0.8).toFloat() }
            val band = HighlightAligner.align(xs, ys, p[3]) { c, _ -> draw(c) }
            log.append("pass ${p.toList()} -> ").append(band?.let { "x ${it.x0}..${it.x1} y ${it.y} t ${it.thickness}" } ?: "null").append('\n')
            if (band != null) c.drawRect(band.x0, band.y - band.thickness / 2, band.x1, band.y + band.thickness / 2, paint)
        }
        val dir = context.getExternalFilesDir(null)
        File(dir, "hl-probe.txt").writeText(log.toString())
        File(dir, "hl-probe.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
