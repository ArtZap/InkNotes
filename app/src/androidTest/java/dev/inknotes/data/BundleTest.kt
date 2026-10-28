package dev.inknotes.data

import android.graphics.Color
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** `.inknotes` files: several notebooks with their folders, both ways, and broken files. */
@RunWith(AndroidJUnit4::class)
class BundleTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val base = File(context.cacheDir, "bundle-test").apply { deleteRecursively(); mkdirs() }
    private val mine = NotebookRepository(File(base, "mine"))
    private val friend = NotebookRepository(File(base, "friend"))

    @After
    fun cleanUp() {
        repeat(5) {
            if (runCatching { base.deleteRecursively() }.getOrDefault(false)) return
            Thread.sleep(100)
        }
    }

    @Test
    fun severalItemsMoveAtOnce() {
        val study = mine.createFolder("Учёба")
        val math = mine.createFolder("Математика", study.id)
        val archive = mine.createFolder("Архив")
        val a = mine.create("Первая", PaperKind.BLANK)
        val b = mine.create("Вторая", PaperKind.BLANK, folder = study.id)
        val c = mine.create("Третья", PaperKind.BLANK)

        val (moved, folders) = mine.moveAll(listOf(a, b), setOf(study.id), archive.id)
        assertEquals(2, moved.size)
        assertEquals(1, folders)
        Thread.sleep(300)
        val byId = mine.list().associateBy { it.id }
        assertEquals(archive.id, byId[a.id]!!.folder)
        assertEquals(archive.id, byId[b.id]!!.folder)
        assertEquals(null, byId[c.id]!!.folder)
        val tree = mine.folders().associateBy { it.id }
        assertEquals(archive.id, tree[study.id]!!.parent)
        assertEquals(study.id, tree[math.id]!!.parent)

        // A folder can't go inside itself: "Учёба" stays, the notebook still moves.
        val (again, none) = mine.moveAll(listOf(c), setOf(study.id), math.id)
        assertEquals(1, again.size)
        assertEquals(0, none)
        assertEquals(archive.id, mine.folders().first { it.id == study.id }.parent)

        // Back to the top level.
        mine.moveAll(mine.list(), emptySet(), null)
        Thread.sleep(300)
        assertTrue(mine.list().all { it.folder == null })
    }

    @Test
    fun folderWithSubfolderTravels() {
        val study = mine.createFolder("Учёба")
        val math = mine.createFolder("Математика", study.id)
        val other = mine.createFolder("Другое")
        val a = mine.create("Конспект", PaperKind.GRID, folder = study.id)
        val b = mine.create("Задачи", PaperKind.LINED, folder = math.id)
        mine.create("Не в папке", PaperKind.BLANK, folder = other.id)
        mine.savePageAsync(b, b.pageIds[0], PageContent(listOf(line(100f), line(200f)), emptyList()))
        Thread.sleep(300)

        val file = File(base, "study.inknotes")
        // The folder "Учёба" with everything in it.
        val folders = mine.folders().filter { it.id in FolderTree.subtree(mine.folders(), study.id) }
        val notebooks = mine.list().filter { it.folder == study.id || it.folder == math.id }
        assertTrue(await<Boolean> { mine.exportBundleAsync(notebooks, folders, { file.outputStream() }, it) })

        // The friend has a folder "Входящие" open and adds the file there.
        val inbox = friend.createFolder("Входящие")
        val set = await<NotebookRepository.ImportSet> { friend.readImportsAsync(listOf({ file.inputStream() }), it) }
        assertEquals(2, set.items.size)
        assertEquals(0, set.conflicts)
        val added = await<List<Notebook>> { friend.commitImportsAsync(set, false, inbox.id, it) }
        assertEquals(2, added.size)

        val theirFolders = friend.folders()
        val theirStudy = theirFolders.single { it.name == "Учёба" }
        val theirMath = theirFolders.single { it.name == "Математика" }
        assertEquals(inbox.id, theirStudy.parent)
        assertEquals(theirStudy.id, theirMath.parent)
        val list = friend.list()
        assertEquals(theirStudy.id, list.single { it.title == "Конспект" }.folder)
        val tasks = list.single { it.title == "Задачи" }
        assertEquals(theirMath.id, tasks.folder)
        assertEquals(b.id, tasks.id)
        assertEquals(2, friend.loadPage(tasks, tasks.pageIds[0]).strokes.size)
        assertTrue(list.none { it.title == "Не в папке" })
        assertTrue(stagingIsEmpty(friend))
    }

    @Test
    fun severalFilesAtOnceAndConflicts() {
        val a = mine.create("Первая", PaperKind.BLANK)
        val b = mine.create("Вторая", PaperKind.BLANK)
        Thread.sleep(200)
        val fa = File(base, "a.inknote")
        val fb = File(base, "b.inknote")
        assertTrue(await<Boolean> { mine.exportArchiveAsync(a, { fa.outputStream() }, it) })
        assertTrue(await<Boolean> { mine.exportArchiveAsync(b, { fb.outputStream() }, it) })
        val junk = File(base, "junk.inknote").apply { writeText("not a zip") }

        // Back into the same library: both exist, kept as copies; the broken file is reported.
        val set = await<NotebookRepository.ImportSet> { mine.readImportsAsync(listOf({ fa.inputStream() }, { fb.inputStream() }, { junk.inputStream() }), it) }
        assertEquals(2, set.items.size)
        assertEquals(2, set.conflicts)
        assertEquals(1, set.failures.size)
        val added = await<List<Notebook>> { mine.commitImportsAsync(set, false, null, it) }
        assertEquals(2, added.size)
        assertTrue(added.all { it.title.endsWith("(копия)") && it.id != a.id && it.id != b.id })
        assertEquals(4, mine.list().size)
        assertTrue(stagingIsEmpty(mine))
    }

    @Test
    fun hostileBundles() {
        // A folder that is its own parent, an unknown entry and a notebook listed twice.
        val nbId = UUID.randomUUID().toString()
        val pageId = UUID.randomUUID().toString()
        val inner = File(base, "inner.zip")
        ZipOutputStream(inner.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("inknote.json")); z.write(JSONObject().put("format", "inknotes-notebook").put("version", 1).toString().toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("notebook.json")); z.write(
                JSONObject().put("id", nbId).put("title", "t").put("light", JSONObject().put("kind", "BLANK")).put("dark", JSONObject().put("kind", "BLANK"))
                    .put("createdAt", 1L).put("updatedAt", 1L).put("pages", JSONArray(listOf(pageId))).toString().toByteArray(),
            ); z.closeEntry()
        }
        val loop = UUID.randomUUID().toString()
        val manifest = JSONObject().put("format", "inknotes-bundle").put("version", 1)
            .put("folders", JSONArray().put(JSONObject().put("id", loop).put("name", "Петля").put("parent", loop)))
            .put("notebooks", JSONArray().put(JSONObject().put("file", "notebooks/0.inknote").put("folder", loop)).put(JSONObject().put("file", "notebooks/0.inknote")))
        val bundle = File(base, "hostile.inknotes")
        ZipOutputStream(bundle.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("bundle.json")); z.write(manifest.toString().toByteArray()); z.closeEntry()
            z.putNextEntry(ZipEntry("../../evil")); z.write(ByteArray(1000)); z.closeEntry()
            z.putNextEntry(ZipEntry("notebooks/0.inknote")); z.write(inner.readBytes()); z.closeEntry()
        }
        val set = await<NotebookRepository.ImportSet> { friend.readImportsAsync(listOf({ bundle.inputStream() }), it) }
        assertEquals(1, set.items.size)
        val added = await<List<Notebook>> { friend.commitImportsAsync(set, false, null, it) }
        assertEquals(1, added.size)
        val f = friend.folders().single()
        assertEquals(null, f.parent)
        assertEquals(f.id, added[0].folder)
        assertTrue(File(base, "friend").walkTopDown().none { it.name == "evil" })
        assertTrue(base.parentFile!!.listFiles().orEmpty().none { it.name == "evil" })

        // A bundle that is not ours.
        val alien = File(base, "alien.inknotes")
        ZipOutputStream(alien.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("bundle.json")); z.write(JSONObject().put("format", "other").toString().toByteArray()); z.closeEntry()
        }
        val none = await<NotebookRepository.ImportSet> { friend.readImportsAsync(listOf({ alien.inputStream() }), it) }
        assertEquals(0, none.items.size)
        assertNotEquals(0, none.failures.size)
        assertTrue(stagingIsEmpty(friend))
    }

    private fun stagingIsEmpty(repo: NotebookRepository): Boolean {
        Thread.sleep(200)
        val dir = File(base, if (repo === mine) "mine/notebooks" else "friend/notebooks")
        return dir.listFiles().orEmpty().none { it.name.startsWith(".import") }
    }

    private fun line(y: Float): PageStroke {
        val batch = MutableStrokeInputBatch()
        for (i in 0..20) batch.add(InputToolType.STYLUS, 100f + i * 20f, y, i * 8L)
        return PageStroke(InkKind.PEN, Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 3f), batch))
    }

    private fun <T> await(start: ((T) -> Unit) -> Unit): T {
        val latch = CountDownLatch(1)
        var value: T? = null
        start {
            value = it
            latch.countDown()
        }
        assertTrue(latch.await(30, TimeUnit.SECONDS))
        @Suppress("UNCHECKED_CAST")
        return value as T
    }

    @Test
    fun trashKeepsAndRestores() {
        val gone = mine.createFolder("Удалённая")
        val a = mine.create("В корзину", PaperKind.BLANK, folder = gone.id)
        val b = mine.create("Остаётся", PaperKind.BLANK)
        mine.delete(a)
        assertEquals(listOf(b.id), mine.list().map { it.id })
        assertEquals(listOf(a.id), mine.trash().map { it.notebook.id })
        // Its folder is gone meanwhile: it comes back to the top level.
        mine.deleteFolder(gone)
        val back = mine.restore(a)!!
        assertEquals(null, back.folder)
        assertEquals(setOf(a.id, b.id), mine.list().map { it.id }.toSet())
        assertTrue(mine.trash().isEmpty())

        mine.delete(b)
        mine.purge(b)
        assertTrue(mine.trash().isEmpty())
        assertEquals(listOf(a.id), mine.list().map { it.id })
    }

    @Test
    fun backupHasEverything() {
        val f = mine.createFolder("Учёба")
        mine.create("Одна", PaperKind.BLANK, folder = f.id)
        mine.create("Другая", PaperKind.BLANK)
        val out = File(base, "backup.inknotes")
        val done = CountDownLatch(1)
        var ok = false
        mine.backupAsync({ out.outputStream() }) {
            ok = it
            done.countDown()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertTrue(ok)
        val read = CountDownLatch(1)
        var set: NotebookRepository.ImportSet? = null
        friend.readImportsAsync(listOf({ out.inputStream() })) {
            set = it
            read.countDown()
        }
        assertTrue(read.await(10, TimeUnit.SECONDS))
        assertEquals(setOf("Одна", "Другая"), set!!.items.map { it.ready.notebook.title }.toSet())
        assertEquals(listOf("Учёба"), set!!.folders.map { it.name })
        friend.discardImports(set!!)
    }
}
