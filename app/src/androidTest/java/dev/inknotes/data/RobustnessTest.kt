package dev.inknotes.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.ui.AppPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The library's files when things go wrong: a damaged folder list, pages that can't be read,
 * copies of damaged pages, a backup with nothing new. (The parts that are plain rules are
 * tested on the computer: BackupFilesTest, SafeFilesTest, PageCopiesTest, TabsMemoryTest.)
 */
@RunWith(AndroidJUnit4::class)
class RobustnessTest {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext

    private fun fresh(name: String) = File(ctx.cacheDir, "robust-$name-${System.nanoTime()}").apply { mkdirs() }

    @Test
    fun damagedFolderListIsKeptAside() {
        val root = fresh("folders")
        val repo = NotebookRepository(root)
        repo.createFolder("Учёба")
        val list = File(root, "notebooks/.folders.json")
        list.writeText("[{\"id\": broken")
        // Reading it finds the damage; a new folder must not destroy the old list.
        repo.createFolder("Новая")
        val aside = File(root, "notebooks").listFiles().orEmpty().filter { it.name.startsWith(".folders.json.broken-") }
        assertEquals(1, aside.size)
        assertEquals("[{\"id\": broken", aside.single().readText())
        assertEquals(listOf("Новая"), repo.folders().map { it.name })
        assertTrue(repo.foldersProblem != null)
    }

    @Test
    fun unreadablePageStaysAndIsNotWrittenOver() {
        val root = fresh("locked")
        val repo = NotebookRepository(root)
        val nb = repo.create("Тетрадь", PaperKind.BLANK)
        repo.list()
        // A page "file" that can't be read (a folder in its place): a read error, not damage.
        val page = File(root, "notebooks/${nb.id}/pages/${nb.pageIds[0]}.ink").apply {
            parentFile!!.mkdirs()
            mkdirs()
            File(this, "keep").writeText("x")
        }
        assertEquals(0, repo.loadPage(nb, nb.pageIds[0]).strokes.size)
        assertTrue(repo.isLocked(nb, nb.pageIds[0]))
        repo.savePageAsync(nb, nb.pageIds[0], PageContent.EMPTY)
        repo.list()
        assertTrue(File(page, "keep").isFile)
        val names = page.parentFile!!.list().orEmpty()
        assertFalse(names.any { it.contains(".broken-") || it.contains(".kept-") })
    }

    @Test
    fun damagedPageIsSetAsideAndCopiesArePrunedAfterAGoodSave() {
        val root = fresh("broken")
        val repo = NotebookRepository(root)
        val nb = repo.create("Тетрадь", PaperKind.BLANK)
        repo.list()
        val pages = File(root, "notebooks/${nb.id}/pages").apply { mkdirs() }
        val name = "${nb.pageIds[0]}.ink"
        File(pages, name).writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
        repo.loadPage(nb, nb.pageIds[0])
        assertEquals(1, pages.list().orEmpty().count { it.startsWith("$name.broken-") })
        // Four more copies from earlier runs: five in all.
        for (t in 1..4) File(pages, "$name.kept-$t").writeText("old")
        repo.savePageAsync(nb, nb.pageIds[0], PageContent.EMPTY)
        repo.list()
        assertEquals(PageCopies.KEEP, pages.list().orEmpty().count { PageCopies.pageOf(it) == name })
    }

    @Test
    fun backupWithNothingNewAnswersOnce() {
        val root = fresh("backup")
        val repo = NotebookRepository(root)
        repo.lastChange()
        File(root, "notebooks").walkTopDown().forEach { it.setLastModified(1000L) }
        val prefs = AppPrefs(ctx)
        val keepFolder = prefs.backupFolder
        val keepAt = prefs.lastBackupAt
        try {
            prefs.backupFolder = "content://dev.inknotes.test/tree/none"
            prefs.lastBackupAt = System.currentTimeMillis() - 3L * 24 * 3600 * 1000
            val calls = AtomicInteger()
            var result: Backup.Result? = null
            val done = CountDownLatch(1)
            instr.runOnMainSync {
                Backup(ctx, repo, prefs).run(force = false) {
                    calls.incrementAndGet()
                    result = it
                    done.countDown()
                }
            }
            assertTrue(done.await(20, TimeUnit.SECONDS))
            Thread.sleep(500)
            assertEquals(1, calls.get())
            assertEquals(Backup.Result.Skipped, result)
        } finally {
            prefs.backupFolder = keepFolder
            prefs.lastBackupAt = keepAt
        }
    }
}
