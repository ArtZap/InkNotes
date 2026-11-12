package dev.inknotes.data

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Files written by 0.34, before R8 shrank the app (assets/golden): a v12 page, a notebook.json,
 * a .inknote and a .inknotes. Each must still read, and a page written again must come out the
 * same byte for byte. In CI this runs against the shrunk ("minified") build.
 *
 * `-e golden make` writes the files anew instead (into the app's files/golden), to refresh them.
 */
@RunWith(AndroidJUnit4::class)
class GoldenFilesTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val assets = instr.context.assets

    private fun asset(name: String) = assets.open("golden/$name").use { it.readBytes() }

    private fun fresh(name: String) = File(ctx.cacheDir, "golden-$name-${System.nanoTime()}").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val done = CountDownLatch(1)
        var out: T? = null
        instr.runOnMainSync {
            start { out = it; done.countDown() }
        }
        check(done.await(30, TimeUnit.SECONDS)) { "timed out" }
        @Suppress("UNCHECKED_CAST")
        return out as T
    }

    @Test
    fun make() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("golden") == "make")
        val out = File(ctx.getExternalFilesDir(null), "golden").apply {
            deleteRecursively()
            mkdirs()
        }
        val root = fresh("make")
        val repo = NotebookRepository(root)
        val nb0 = repo.create("Эталон 0.34", PaperKind.GRID)
        val content = sampleContent()
        repo.savePageAsync(nb0, nb0.pageIds[0], content)
        val nb = repo.setToc(nb0, listOf(Bookmark(nb0.pageIds[0], 120f, "Глава 1")))
        repo.list() // waits for the writes
        File(out, "page.ink").writeBytes(ByteArrayOutputStream().also { PageCodec.write(it, content) }.toByteArray())
        val dir = File(root, "notebooks/${nb.id}")
        File(dir, "notebook.json").copyTo(File(out, "notebook.json"))
        File(dir, "pages/${nb.pageIds[0]}.ink").copyTo(File(out, "notebook-page.ink"))
        assertTrue(await { done -> repo.exportArchiveAsync(nb, { File(out, "notebook.inknote").outputStream() }, done) })
        val folder = repo.createFolder("Папка")
        val inFolder = repo.moveToFolder(nb, folder.id)
        repo.list()
        assertTrue(await { done -> repo.exportBundleAsync(listOf(inFolder), listOf(folder), { File(out, "library.inknotes").outputStream() }, done) })
    }

    /** Strokes of three kinds, a dashed one, a hatch on a layer, a text box: every v12 part. */
    private fun sampleContent(): PageContent {
        val batch = androidx.ink.strokes.MutableStrokeInputBatch()
        for (i in 0..20) batch.add(androidx.ink.brush.InputToolType.STYLUS, 40f + i * 12f, 80f + (i % 5) * 3f, i * 8L)
        fun stroke(kind: InkKind, color: Int, layer: Int = 0, dash: DashStyle? = null) =
            PageStroke(kind, androidx.ink.strokes.Stroke(InkBrushes.create(kind, color, 3f), batch), dash, layer)
        val square = floatArrayOf(50f, 200f, 250f, 200f, 250f, 400f, 50f, 400f)
        return PageContent(
            listOf(stroke(InkKind.PEN, Color.BLACK), stroke(InkKind.HIGHLIGHTER, Color.YELLOW, 1), stroke(InkKind.DASHED, Color.BLUE, 2, DashStyle(DashPattern.DASH_DOT, 18f, 6f))),
            listOf(PageFill(square, FillPattern.BRICKS, Color.RED, style = HatchStyle(width = 2f, spacing = 16f, angle = 30f, scale = 1f, opacity = 0.5f, lineType = LineType.SOLID), layer = 1)),
            listOf(PageImage.textBox(PageText("Формула: E = mc²", 22f, Color.DKGRAY), 60f, 460f, 320f, 40f, layer = 0)),
        )
    }

    private fun golden() = assumeTrue("no golden files", runCatching { assets.list("golden")?.isNotEmpty() == true }.getOrDefault(false))

    @Test
    fun pageReadsAndWritesTheSame() {
        golden()
        val bytes = asset("page.ink")
        val page = PageCodec.decode(bytes)
        assertEquals(3, page.strokes.size)
        assertEquals(1, page.fills.size)
        assertEquals("Формула: E = mc²", page.images.single().text?.text)
        val again = ByteArrayOutputStream().also { PageCodec.write(it, page) }.toByteArray()
        assertArrayEquals(bytes, again)
    }

    @Test
    fun notebookJsonReads() {
        golden()
        val root = fresh("meta")
        val json = String(asset("notebook.json"))
        val id = Regex("\"id\"\\s*:\\s*\"([0-9a-f-]{36})\"").find(json)!!.groupValues[1]
        val page = Regex("\"pages\"\\s*:\\s*\\[\\s*\"([0-9a-f-]{36})\"").find(json)!!.groupValues[1]
        val dir = File(root, "notebooks/$id").apply { mkdirs() }
        File(dir, "notebook.json").writeBytes(asset("notebook.json"))
        File(dir, "pages").mkdirs()
        File(dir, "pages/$page.ink").writeBytes(asset("notebook-page.ink"))
        val repo = NotebookRepository(root)
        val nb = repo.list().single()
        assertEquals("Эталон 0.34", nb.title)
        assertEquals(listOf(page), nb.pageIds)
        assertEquals("Глава 1", nb.toc.single().title)
        assertEquals(3, repo.loadPage(nb, page).strokes.size)
        // Written again and read back: the same.
        val renamed = repo.rename(nb, "Эталон 0.34")
        assertEquals(renamed.pageIds, repo.list().single().pageIds)
    }

    @Test
    fun inknoteImports() {
        golden()
        val repo = NotebookRepository(fresh("inknote"))
        val r = await<NotebookRepository.ArchiveImport> { done -> repo.readArchiveAsync({ assets.open("golden/notebook.inknote") }, done) }
        assertTrue("$r", r is NotebookRepository.ArchiveImport.Ready)
        val nb = await<Notebook?> { done -> repo.commitImportAsync(r as NotebookRepository.ArchiveImport.Ready, replace = false, onDone = done) }!!
        assertEquals("Эталон 0.34", nb.title)
        assertEquals(3, repo.loadPage(nb, nb.pageIds[0]).strokes.size)
    }

    @Test
    fun inknotesImports() {
        golden()
        val repo = NotebookRepository(fresh("inknotes"))
        val set = await<NotebookRepository.ImportSet> { done -> repo.readImportsAsync(listOf { assets.open("golden/library.inknotes") }, done) }
        assertEquals(1, set.items.size)
        assertEquals(listOf("Папка"), set.folders.map { it.name })
        val added = await<List<Notebook>> { done -> repo.commitImportsAsync(set, false, null, done) }
        assertEquals("Эталон 0.34", added.single().title)
        assertTrue(added.single().folder != null)
    }
}
