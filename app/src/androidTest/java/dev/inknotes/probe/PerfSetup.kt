package dev.inknotes.probe

import android.graphics.Color
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.InkBrushes
import dev.inknotes.data.InkKind
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PageContent
import dev.inknotes.data.PageStroke
import dev.inknotes.data.PaperKind
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Not a test: `-e perfpages 100` adds a notebook "Тест производительности" of that many A4
 * pages filled with handwriting-like strokes (35 lines of ~60 letters, a letter a stroke),
 * to measure scrolling and writing in the real app. Delete it in the library afterwards.
 */
@RunWith(AndroidJUnit4::class)
class PerfSetup {
    @Test
    fun setup() {
        val pages = InstrumentationRegistry.getArguments().getString("perfpages")?.toIntOrNull()
        assumeTrue(pages != null)
        val empty = InstrumentationRegistry.getArguments().getString("perfempty") != null
        val title = if (empty) "Тест пустой" else TITLE
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = NotebookRepository(context.filesDir)
        for (old in repo.list().filter { it.title == title }) repo.delete(old).also { repo.purge(old) }
        var nb = repo.create(title, PaperKind.LINED)
        repeat(pages!! - 1) { nb = repo.insertPage(nb, nb.pageIds.size) }
        val rnd = Random(7)
        if (empty) return
        for (pageId in nb.pageIds) {
            val strokes = ArrayList<PageStroke>()
            var y = 140f
            while (y < 1380f) {
                var x = 120f
                while (x < 940f) {
                    val letters = 3 + rnd.nextInt(6)
                    repeat(letters) {
                        strokes += letter(x, y, rnd)
                        x += 11f + rnd.nextFloat() * 4f
                    }
                    x += 14f
                }
                y += 38f
            }
            repo.savePageAsync(nb, pageId, PageContent(strokes, emptyList()))
        }
        repo.list() // waits for the queued writes
    }

    private companion object {
        const val TITLE = "Тест производительности"
    }

    /** A cursive-looking letter: a small loop with a tail, ~30 points, x-height 14. */
    private fun letter(x0: Float, base: Float, rnd: Random): PageStroke {
        val batch = MutableStrokeInputBatch()
        val n = 26 + rnd.nextInt(10)
        val tall = rnd.nextInt(4) == 0
        for (i in 0..n) {
            val t = i.toFloat() / n
            val a = 2 * PI * t * 1.2
            val x = x0 + 12f * t + 4f * sin(a).toFloat()
            val y = base - 7f - (if (tall) 10f else 6f) * cos(a).toFloat() * (1 - 0.3f * t)
            batch.add(InputToolType.STYLUS, x, y, i * 8L, pressure = 0.5f + 0.2f * sin(a).toFloat())
        }
        return PageStroke(InkKind.PEN, Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 2.2f), batch))
    }
}
