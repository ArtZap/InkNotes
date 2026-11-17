package dev.inknotes.math

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FixStoreBackupTest {
    private fun dir() = File.createTempFile("fixes", "").apply { delete(); mkdirs() }

    private fun stroke(x: Float) = Ink(floatArrayOf(x, x + 10, x + 5), floatArrayOf(0f, 0f, 20f), 0)

    @Test
    fun aNewDeviceGetsWhatTheBackupHeld() {
        val old = FixStore(dir(), background = true)
        old.pin("abc", "x+1")
        old.learn(listOf("l"), listOf("1"), listOf(listOf(stroke(0f))))
        old.hand.add('7', HandGlyphs.glyph(listOf(floatArrayOf(0f, 0f, 10f, 10f)), 0f, 10f)!!)
        old.saveHand()
        val files = old.backupFiles()
        assertTrue(files.keys.toString(), files.keys.containsAll(listOf("formula-pins.tsv", "formula-learned.tsv", "formula-samples.tsv", "hand-glyphs.tsv")))

        val fresh = FixStore(dir())
        assertTrue(fresh.restore(files))
        assertEquals("x+1", fresh.pins()["abc"])
        assertTrue(!fresh.learned.isEmpty())
        assertEquals(1, fresh.samples.count())
        assertTrue(fresh.hand.has('7'))
    }

    @Test
    fun whatThisDeviceLearnedIsNotReplaced() {
        val here = FixStore(dir())
        here.pin("mine", "2")
        val there = FixStore(dir())
        there.pin("theirs", "3")
        here.restore(there.backupFiles())
        assertEquals(setOf("mine"), here.pins().keys)
    }

    @Test
    fun anUnreadableFileIsSetAsideNotWrittenOver() {
        val d = dir()
        // A directory where the file should be: reading fails.
        File(d, "formula-learned.tsv").mkdirs()
        val s = FixStore(d)
        assertTrue(s.learned.isEmpty())
        assertTrue(File(d, "formula-learned.tsv.broken").exists())
    }

    @Test
    fun writesLeaveNoSideFiles() {
        val d = dir()
        val s = FixStore(d, background = true)
        repeat(20) { s.pin("k$it", "$it") }
        s.awaitWrites()
        assertEquals(20, FixStore(d).pins().size)
        assertTrue(d.listFiles()!!.none { it.name.endsWith(".tmp") })
    }
}
