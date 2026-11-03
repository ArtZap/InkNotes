package dev.inknotes.data

import android.graphics.Color
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.NotebookRepository.ArchiveImport
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `.inknote` files: round trip between two "devices" and rejection of hostile files. */
@RunWith(AndroidJUnit4::class)
class ArchiveTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = File(context.cacheDir, "archive-test").apply { deleteRecursively(); mkdirs() }
    private val mine = NotebookRepository(File(base, "mine"))
    private val friend = NotebookRepository(File(base, "friend"))

    @After
    fun cleanUp() {
        // A discarded import may still be deleting its staging folder in the background.
        repeat(5) {
            if (runCatching { base.deleteRecursively() }.getOrDefault(false)) return
            Thread.sleep(100)
        }
    }

    @Test
    fun friendEditsAndSendsBack() {
        // I write a page and send the notebook.
        val nb = mine.create("Конспект", PaperKind.GRID)
        val fill = PageFill(floatArrayOf(10f, 10f, 200f, 10f, 200f, 200f), FillPattern.CROSS, Color.RED)
        mine.savePageAsync(nb, nb.pageIds[0], PageContent(listOf(line(100f)), listOf(fill)))
        val file1 = File(base, "1.inknote")
        assertTrue(export(mine, nb, file1))

        // The friend opens it: new to them, so it is added as is (same id).
        val atFriend = read(friend, file1) as ArchiveImport.Ready
        assertEquals(null, atFriend.existing)
        val theirs = commit(friend, atFriend, replace = false)!!
        assertEquals(nb.id, theirs.id)
        val page = friend.loadPage(theirs, theirs.pageIds[0])
        assertEquals(1, page.strokes.size)
        assertEquals(1, page.fills.size)

        // They erase my line, draw their own and send it back.
        friend.savePageAsync(theirs, theirs.pageIds[0], PageContent(listOf(line(300f), line(400f)), page.fills))
        val file2 = File(base, "2.inknote")
        assertTrue(export(friend, friend.list().single(), file2))

        // I get it back: it exists here, so I'm asked; "replace" keeps the id.
        val back = read(mine, file2) as ArchiveImport.Ready
        assertEquals(nb.id, back.existing?.id)
        val replaced = commit(mine, back, replace = true)!!
        assertEquals(nb.id, replaced.id)
        assertEquals(2, mine.loadPage(replaced, replaced.pageIds[0]).strokes.size)
        assertEquals(1, mine.list().size)

        // "Save as copy" gives a separate notebook.
        val again = read(mine, file2) as ArchiveImport.Ready
        val copy = commit(mine, again, replace = false)!!
        assertNotEquals(nb.id, copy.id)
        assertTrue(copy.title.endsWith("(копия)"))
        assertEquals(2, mine.list().size)
    }

    /** The cover colour travels with the file; folders are local to each device. */
    @Test
    fun coverTravelsFolderStaysLocal() {
        val myFolder = mine.createFolder("Учёба")
        val nb = mine.moveToFolder(mine.setCover(mine.create("С обложкой", PaperKind.BLANK), 0xFF00897B.toInt()), myFolder.id)
        val file = File(base, "cover.inknote")
        assertTrue(export(mine, nb, file))

        val atFriend = commit(friend, read(friend, file) as ArchiveImport.Ready, replace = false)!!
        assertEquals(0xFF00897B.toInt(), friend.list().single().coverColor())
        assertEquals(null, friend.list().single().folder)

        // Back home with "replace": still in my folder.
        assertTrue(export(friend, atFriend, file))
        commit(mine, read(mine, file) as ArchiveImport.Ready, replace = true)!!
        assertEquals(myFolder.id, mine.list().single().folder)

        // Deleting the folder keeps the notebook, back at the top level.
        mine.deleteFolder(myFolder)
        assertEquals(null, mine.list().single().folder)
        assertTrue(mine.folders().isEmpty())
    }

    /** A picture placed on a page travels with the notebook file. */
    @Test
    fun picturesTravel() {
        val nb = mine.create("С картинкой", PaperKind.BLANK)
        val png = File(base, "pic.png").apply {
            outputStream().use { android.graphics.Bitmap.createBitmap(40, 30, android.graphics.Bitmap.Config.ARGB_8888).compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
        val imported = await<Triple<String, Int, Int>?> { done ->
            mine.importImageAsync(nb, android.net.Uri.fromFile(png), context.contentResolver, done)
        }!!
        assertEquals(40, imported.second)
        mine.savePageAsync(nb, nb.pageIds[0], PageContent(emptyList(), emptyList(), listOf(PageImage(imported.first, 0f, 0f, 400f, 300f))))
        val file = File(base, "pic.inknote")
        assertTrue(export(mine, nb, file))

        val theirs = commit(friend, read(friend, file) as ArchiveImport.Ready, replace = false)!!
        val img = friend.loadPage(theirs, theirs.pageIds[0]).images.single()
        assertEquals(40, friend.loadImage(theirs, img.file)!!.width)
    }

    @Test
    fun nestedFolders() {
        val study = mine.createFolder("Учёба")
        val math = mine.createFolder("Математика", study.id)
        val algebra = mine.createFolder("Алгебра", math.id)
        val nb = mine.moveToFolder(mine.create("Конспект", PaperKind.BLANK), algebra.id)
        assertEquals(listOf(study, math, algebra), FolderTree.path(mine.folders(), nb.folder))

        // No loops: a folder can't go inside itself or its own subfolder.
        assertFalse(mine.moveFolder(study, algebra.id))
        assertFalse(mine.moveFolder(study, study.id))
        assertTrue(mine.moveFolder(algebra, null))
        assertEquals(null, mine.folders().single { it.id == algebra.id }.parent)
        assertTrue(mine.moveFolder(algebra, math.id))

        // Deleting a middle folder moves its contents one level up.
        mine.deleteFolder(math)
        assertEquals(study.id, mine.folders().single { it.id == algebra.id }.parent)
        mine.deleteFolder(algebra)
        assertEquals(study.id, mine.list().single().folder)
    }

    @Test
    fun rejectsFilesThatAreNotNotebooks() {
        val text = File(base, "x.inknote").apply { writeText("hello") }
        assertTrue(read(mine, text) is ArchiveImport.Failed)
        val zipWithoutManifest = zip("a.zip", "notebook.json" to "{}".toByteArray())
        assertTrue(read(mine, zipWithoutManifest) is ArchiveImport.Failed)
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun ignoresPathsOutsideTheNotebook() {
        val id = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val file = zip(
            "evil.inknote",
            "inknote.json" to manifest(),
            "notebook.json" to meta(id, pageId),
            "../../escaped.txt" to "x".toByteArray(),
            "pages/../../escaped2.ink" to "x".toByteArray(),
            "/abs.txt" to "x".toByteArray(),
            "pages/$pageId.ink" to emptyPage(),
        )
        val result = read(mine, file)
        assertTrue(result is ArchiveImport.Ready)
        val all = base.walkTopDown().map { it.name }.toList()
        assertFalse(all.any { it.startsWith("escaped") || it == "abs.txt" })
        mine.discardImport(result as ArchiveImport.Ready)
    }

    @Test
    fun rejectsCorruptPagesAndHugeLengths() {
        val id = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        // Claims a billion strokes: must fail on the length check, not by allocating.
        val bytes = ByteArrayOutputStream().also { out ->
            DataOutputStream(out).apply {
                writeInt(0x494E4B50)
                writeInt(2)
                writeInt(1_000_000_000)
            }
        }.toByteArray()
        val file = zip(
            "bad.inknote",
            "inknote.json" to manifest(),
            "notebook.json" to meta(id, pageId),
            "pages/$pageId.ink" to bytes,
        )
        val result = read(mine, file)
        assertTrue(result is ArchiveImport.Failed)
        assertTrue(stagingIsEmpty())
    }

    @Test
    fun rejectsIdsThatAreNotUuids() {
        val file = zip(
            "ids.inknote",
            "inknote.json" to manifest(),
            "notebook.json" to meta("../../x", "../y"),
        )
        assertTrue(read(mine, file) is ArchiveImport.Failed)
    }

    /** A highly compressible unknown entry must hit the size cap, not be inflated in full. */
    @Test
    fun capsSkippedEntries() {
        val id = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val file = File(base, "bomb.inknote")
        ZipOutputStream(file.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("inknote.json")); z.write(manifest()); z.closeEntry()
            z.putNextEntry(ZipEntry("notebook.json")); z.write(meta(id, pageId)); z.closeEntry()
            z.putNextEntry(ZipEntry("junk.bin"))
            val zeros = ByteArray(1024 * 1024)
            repeat(200) { z.write(zeros) } // 200 MB of zeros, ~200 KB compressed
            z.closeEntry()
        }
        assertTrue(file.length() < 5 * 1024 * 1024)
        // Reading up to the cap is 150 MB of inflating: a slow tablet in the middle of a run needs time.
        val result = read(mine, file, seconds = 180)
        assertTrue(result is ArchiveImport.Failed)
        assertEquals("Файл слишком большой", (result as ArchiveImport.Failed).reason)
    }

    /** Absurd PDF page proportions in notebook.json are dropped, so no giant bitmaps later. */
    @Test
    fun dropsAbsurdPdfPageSizes() {
        val id = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val json = JSONObject(String(meta(id, pageId))).put(
            "pdf",
            JSONObject().put(pageId, JSONObject().put("file", "doc-${UUID.randomUUID()}.pdf").put("index", 0).put("w", 0.001).put("h", 19999)),
        )
        val file = zip("ratio.inknote", "inknote.json" to manifest(), "notebook.json" to json.toString().toByteArray())
        val result = read(mine, file) as ArchiveImport.Ready
        assertTrue(result.notebook.pdfPages.isEmpty())
        mine.discardImport(result)
    }

    // ------------------------------------------------------------------

    private fun stagingIsEmpty() = File(base, "mine/notebooks").listFiles().orEmpty().none { it.name.startsWith(".import") }

    private fun manifest() = JSONObject().put("format", "inknotes-notebook").put("version", 1).toString().toByteArray()

    private fun meta(id: String, pageId: String) = JSONObject()
        .put("id", id)
        .put("title", "t")
        .put("light", JSONObject().put("kind", "BLANK"))
        .put("dark", JSONObject().put("kind", "BLANK"))
        .put("createdAt", 1L)
        .put("updatedAt", 1L)
        .put("pages", JSONArray(listOf(pageId)))
        .toString().toByteArray()

    private fun emptyPage() = ByteArrayOutputStream().also { PageCodec.write(it, PageContent.EMPTY) }.toByteArray()

    private fun zip(name: String, vararg entries: Pair<String, ByteArray>): File {
        val file = File(base, name)
        ZipOutputStream(file.outputStream()).use { z ->
            for ((entryName, data) in entries) {
                z.putNextEntry(ZipEntry(entryName))
                z.write(data)
                z.closeEntry()
            }
        }
        return file
    }

    private fun line(y: Float): PageStroke {
        val batch = MutableStrokeInputBatch()
        for (i in 0..20) batch.add(InputToolType.STYLUS, 100f + i * 20f, y, i * 8L)
        return PageStroke(InkKind.PEN, Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 3f), batch))
    }

    private fun export(repo: NotebookRepository, nb: Notebook, file: File): Boolean = await { done ->
        repo.exportArchiveAsync(nb, { file.outputStream() }, done)
    }

    private fun read(repo: NotebookRepository, file: File, seconds: Long = 20): ArchiveImport = await(seconds) { done ->
        repo.readArchiveAsync({ file.inputStream() }, done)
    }

    private fun commit(repo: NotebookRepository, ready: ArchiveImport.Ready, replace: Boolean): Notebook? = await { done ->
        repo.commitImportAsync(ready, replace, done)
    }

    private fun <T> await(seconds: Long = 20, start: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var value: T? = null
        start {
            value = it
            latch.countDown()
        }
        assertTrue("no result in $seconds s", latch.await(seconds, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST")
        return value as T
    }
}
