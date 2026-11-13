package dev.inknotes.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A canvas page keeps its grown size through saving, reopening and a shared file. */
@RunWith(AndroidJUnit4::class)
class CanvasPagesTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun grownCanvasIsKept() {
        val root = File(ctx.cacheDir, "canvas-${System.nanoTime()}").apply { mkdirs() }
        val repo = NotebookRepository(root)
        var nb = repo.create("Холст", PaperKind.DOTS, pageSize = PageSize.CANVAS)
        val id = nb.pageIds[0]
        assertTrue(nb.pageSize(id).isCanvas)
        assertEquals(PageSize.CANVAS.canvasW / Notebook.MM_PER_UNIT, nb.pageWidthUnits(id), 0.01f)
        nb = repo.setPageSize(nb, listOf(id), nb.pageSize(id).copy(canvasW = 870f, canvasH = 640f))
        // Writes are queued: a read through the same repository waits for them.
        repo.list()

        val again = NotebookRepository(root).list().single()
        assertEquals(PageSize(canvasW = 870f, canvasH = 640f), again.pageSize(id))
        assertEquals(870f, again.pageWidthMm(id), 0.01f)
        assertEquals(640f / Notebook.MM_PER_UNIT, again.pageHeightUnits(id), 0.01f)
    }
}
