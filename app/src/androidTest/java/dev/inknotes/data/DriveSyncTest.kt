package dev.inknotes.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Two libraries ("two tablets") synced through a cloud folder kept in memory: what goes up,
 * comes down, what was changed in both places, what was deleted, what is open.
 */
@RunWith(AndroidJUnit4::class)
class DriveSyncTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    /** The Drive folder in memory: versions grow at every change, like Google's. */
    private class FakeDrive : DriveApi {
        class Entry(var name: String, var version: Long, var props: Map<String, String>, var bytes: ByteArray, var trashed: Boolean = false)

        val files = LinkedHashMap<String, Entry>()
        private var next = 1
        private var clock = 100L

        /** The InkNotes folder now: another id when it was deleted and made anew. */
        var folderId = "folder"

        override fun folder() = folderId

        override fun exists(folderId: String) = folderId == this.folderId

        override fun list(folderId: String) = files.filter { !it.value.trashed }.map { (id, e) -> DriveFile(id, e.name, e.version, e.props) }

        override fun upload(folderId: String, fileId: String?, name: String, props: Map<String, String>, from: File): DriveFile {
            val id = fileId ?: "f${next++}"
            val e = files[id]
            if (e == null) files[id] = Entry(name, ++clock, props, from.readBytes()) else {
                e.name = name
                e.version = ++clock
                e.props = props
                e.bytes = from.readBytes()
            }
            val f = files.getValue(id)
            return DriveFile(id, f.name, f.version, f.props)
        }

        override fun download(fileId: String, to: File): Unit = to.writeBytes(files.getValue(fileId).bytes)

        override fun trash(fileId: String) {
            files.getValue(fileId).trashed = true
        }

        fun live() = files.values.count { !it.trashed }
    }

    private inner class Device(name: String, val drive: FakeDrive) {
        val root = File(ctx.cacheDir, "sync-$name-${System.nanoTime()}").apply { mkdirs() }
        val repo = NotebookRepository(root)
        val sync = DriveSync(repo, drive, File(root, "drive-sync.json"), ctx.cacheDir)

        fun run(busy: Set<String> = emptySet(), trashMany: Boolean = false) = runBlocking { sync.run({ busy }, trashMany) }

        fun titles() = repo.list().map { it.title }.sorted()
    }

    @Test
    fun twoTabletsStayTheSame() {
        val drive = FakeDrive()
        val a = Device("a", drive)
        val b = Device("b", drive)

        // A new notebook goes up, and down to the other tablet with the same id.
        val nb = a.repo.create("Лекция", PaperKind.LINED)
        assertEquals(1, a.run().uploaded)
        assertEquals(1, drive.live())
        assertEquals(1, b.run().downloaded)
        assertEquals(listOf(nb.id), b.repo.list().map { it.id })
        assertTrue(a.run().nothing)
        assertTrue(b.run().nothing)

        // Changed on one: the other gets it.
        Thread.sleep(5)
        b.repo.rename(b.repo.list().single(), "Лекция 1")
        assertEquals(1, b.run().uploaded)
        assertEquals(1, a.run().downloaded)
        assertEquals(listOf("Лекция 1"), a.titles())

        // Open in the editor: not replaced under it, until it is closed.
        Thread.sleep(5)
        b.repo.rename(b.repo.list().single(), "Лекция 2")
        b.run()
        assertTrue(a.run(busy = setOf(nb.id)).nothing)
        assertEquals(listOf("Лекция 1"), a.titles())
        assertEquals(1, a.run().downloaded)
        assertEquals(listOf("Лекция 2"), a.titles())

        // Changed on both: nothing is lost, both versions are on both tablets.
        Thread.sleep(5)
        a.repo.rename(a.repo.list().single(), "Версия A")
        Thread.sleep(5)
        b.repo.rename(b.repo.list().single(), "Версия B")
        a.run()
        assertEquals(1, b.run().conflicts)
        // The copy made on B goes up next time, and then down to A.
        a.run()
        b.run()
        a.run()
        assertEquals(a.titles(), b.titles())
        assertEquals(2, a.titles().size)
        assertTrue(a.titles().any { it.startsWith("Версия A") } && a.titles().any { it.startsWith("Версия B") })
        assertEquals(2, drive.live())

        // Deleted on one: to the Drive's trash, then to the other tablet's trash.
        val gone = a.repo.list().first()
        a.repo.delete(gone)
        assertEquals(1, a.run().trashedThere)
        assertEquals(1, drive.live())
        assertEquals(1, b.run().trashedHere)
        assertEquals(a.titles(), b.titles())
        assertTrue(b.repo.trash().any { it.notebook.id == gone.id })
        assertTrue(a.run().nothing)
        assertTrue(b.run().nothing)
    }

    @Test
    fun aDamagedFileOnTheDriveIsLeftAlone() {
        val drive = FakeDrive()
        drive.files["bad"] = FakeDrive.Entry("x.inknote", 1, mapOf(DriveSync.PROP_ID to "nb-x", DriveSync.PROP_TIME to "1"), byteArrayOf(1, 2, 3))
        val a = Device("bad", drive)
        a.repo.create("Своя", PaperKind.BLANK)
        val r = a.run()
        assertEquals(1, r.uploaded)
        assertEquals(0, r.downloaded)
        assertEquals(1, r.unread)
        assertEquals(listOf("Своя"), a.titles())
    }

    @Test
    fun aFolderDeletedOnTheDriveDeletesNothingHere() {
        val drive = FakeDrive()
        val a = Device("lost", drive)
        a.repo.create("Первая", PaperKind.BLANK)
        a.repo.create("Вторая", PaperKind.BLANK)
        assertEquals(2, a.run().uploaded)
        // The folder is deleted on the Drive: the app makes a new, empty one.
        drive.files.clear()
        drive.folderId = "folder-2"
        val r = a.run()
        assertTrue(r.folderLost)
        assertEquals(0, r.trashedHere)
        assertEquals(2, r.uploaded)
        assertEquals(listOf("Вторая", "Первая"), a.titles())
    }

    @Test
    fun manyDeletionsAtOnceWaitForTheUser() {
        val drive = FakeDrive()
        val a = Device("many", drive)
        repeat(3) { a.repo.create("Тетрадь $it", PaperKind.BLANK) }
        a.run()
        // Every file gone from the folder at once (the folder itself is still there).
        drive.files.values.forEach { it.trashed = true }
        val held = a.run()
        assertEquals(0, held.trashedHere)
        assertEquals(3, held.held.size)
        assertEquals(3, a.titles().size)
        // Asked again until decided; agreed, they go to the trash here.
        assertEquals(3, a.run().held.size)
        assertEquals(3, a.run(trashMany = true).trashedHere)
        assertTrue(a.titles().isEmpty())
    }

    @Test
    fun notebooksKeptHereGoUpAgain() {
        val drive = FakeDrive()
        val a = Device("keep", drive)
        repeat(2) { a.repo.create("Тетрадь $it", PaperKind.BLANK) }
        a.run()
        drive.files.values.forEach { it.trashed = true }
        val held = a.run().held
        assertEquals(2, held.size)
        a.sync.keepHere(held.map { it.first })
        assertEquals(2, a.run().uploaded)
        assertEquals(2, drive.live())
    }

    @Test
    fun aRecoveredNotebookDoesNotGoUpOverTheWholeOne() {
        val drive = FakeDrive()
        val a = Device("recovered", drive)
        val nb = a.repo.create("Лекция", PaperKind.LINED)
        a.run()
        File(a.root, "notebooks/${nb.id}/notebook.json").writeText("{damaged")
        a.run()
        // The whole one came back from the Drive under its id; what was rebuilt is a notebook of its own.
        assertEquals(listOf("Восстановленная тетрадь", "Лекция"), a.titles())
        assertEquals("Лекция", a.repo.list().first { it.id == nb.id }.title)
        // Split off once only: more syncs make no more copies, here or on the Drive.
        repeat(3) { a.run() }
        assertEquals(listOf("Восстановленная тетрадь", "Лекция"), a.titles())
        assertEquals(2, drive.live())
    }

    @Test
    fun aRecoveredNotebookNeverSyncedIsSplitOffNever() {
        val drive = FakeDrive()
        val a = Device("recovered-new", drive)
        val nb = a.repo.create("Своя", PaperKind.LINED)
        // Written in the background: waited for, then damaged.
        a.repo.list()
        File(a.root, "notebooks/${nb.id}/notebook.json").writeText("{damaged")
        repeat(3) { a.run() }
        assertEquals(listOf("Восстановленная тетрадь"), a.titles())
        assertEquals(1, drive.live())
    }

    @Test
    fun aDeletedNotebookDoesNotComeBackFromASecondFile() {
        val drive = FakeDrive()
        val a = Device("twice", drive)
        val nb = a.repo.create("Дважды", PaperKind.BLANK)
        a.run()
        // A second file of the same notebook (two devices sent it up at once).
        val e = drive.files.values.first()
        drive.files["dup"] = FakeDrive.Entry(e.name, 1, e.props, e.bytes)
        a.repo.delete(nb)
        assertEquals(1, a.run().trashedThere)
        assertEquals(0, drive.live())
        a.run()
        assertTrue(a.titles().isEmpty())
    }
}
