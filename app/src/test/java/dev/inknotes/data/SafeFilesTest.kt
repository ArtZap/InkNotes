package dev.inknotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class SafeFilesTest {
    private fun dirWith(root: File, name: String, text: String) = File(root, name).apply {
        mkdirs()
        File(this, "notebook.json").writeText(text)
    }

    @Test
    fun replaceMovesTheNewInAndTheOldOut() {
        val root = Files.createTempDirectory("safe").toFile()
        val dest = dirWith(root, "nb", "old")
        val staging = dirWith(root, ".import-1", "new")
        SafeFiles.replaceDir(staging, dest)
        assertEquals("new", File(dest, "notebook.json").readText())
        assertFalse(staging.exists())
        assertFalse(SafeFiles.asideOf(dest).exists())
    }

    @Test
    fun aFailedMoveKeepsTheOldNotebook() {
        val root = Files.createTempDirectory("safe").toFile()
        val dest = dirWith(root, "nb", "old")
        val staging = dirWith(root, ".import-1", "new")
        // The move of the import into place fails; moving aside and back works.
        val rename = { from: File, to: File -> if (from == staging) false else from.renameTo(to) }
        try {
            SafeFiles.replaceDir(staging, dest, rename)
            fail("should throw")
        } catch (_: IOException) {
        }
        assertEquals("old", File(dest, "notebook.json").readText())
        assertTrue(staging.exists())
    }

    @Test
    fun aCrashHalfWayIsUndoneAtTheNextStart() {
        val root = Files.createTempDirectory("safe").toFile()
        val aside = dirWith(root, ".replacing-nb", "old")
        SafeFiles.recoverAside(root)
        assertEquals("old", File(root, "nb/notebook.json").readText())
        assertFalse(aside.exists())
        // Both there: the new one is in place, the old one goes.
        dirWith(root, ".replacing-nb", "older")
        SafeFiles.recoverAside(root)
        assertEquals("old", File(root, "nb/notebook.json").readText())
        assertFalse(File(root, ".replacing-nb").exists())
    }

    @Test
    fun theChosenFolderWinsOverTheOldOne() {
        assertEquals("chosen", SafeFiles.importFolder(null, "chosen", "old"))
        assertEquals("old", SafeFiles.importFolder(null, null, "old"))
        assertEquals("fromFile", SafeFiles.importFolder("fromFile", "chosen", "old"))
        assertEquals(null, SafeFiles.importFolder(null, null, null))
    }
}
