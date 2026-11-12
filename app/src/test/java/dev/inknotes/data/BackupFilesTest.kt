package dev.inknotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BackupFilesTest {
    private fun name(day: Int) = "InkNotes 2026-11-%02d 03-00.inknotes".format(day)

    private fun bundle(notebooks: Int = 2): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // As Android's JSON writes it: "/" escaped.
            val list = (0 until notebooks).joinToString(",") { "{\"file\":\"notebooks\\/$it.inknote\"}" }
            zip.putNextEntry(ZipEntry(BackupFiles.MANIFEST))
            zip.write("{\"format\":\"${BackupFiles.FORMAT}\",\"notebooks\":[$list]}".toByteArray())
            zip.closeEntry()
            repeat(notebooks) {
                zip.putNextEntry(ZipEntry("notebooks/$it.inknote"))
                zip.write(ByteArray(50_000) { k -> (k * 31 + it).toByte() })
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun wholeCopyReadsBack() {
        assertTrue(BackupFiles.isWhole(ByteArrayInputStream(bundle())))
    }

    @Test
    fun cutOffCopyIsNotWhole() {
        val b = bundle()
        for (cut in listOf(10, 200, b.size / 2, b.size - 400)) {
            assertFalse("cut at $cut", BackupFiles.isWhole(ByteArrayInputStream(b.copyOf(cut))))
        }
        assertFalse(BackupFiles.isWhole(ByteArrayInputStream(ByteArray(0))))
    }

    @Test
    fun copiesCutOffSeveralTimesInARowKeepTheGoodOnes() {
        // Seven good copies, then five days on which the copy was cut off (never verified).
        val good = (1..7).map(::name)
        val cut = (8..12).map(::name)
        val drop = BackupFiles.toDelete(good + cut + "notes.txt", good.toSet(), 7)
        assertEquals(emptyList<String>(), drop)
        // A new good copy: only the oldest good one goes, and the cut ones older than 7 good.
        val all = good + cut + name(13)
        assertEquals(listOf(name(1)), BackupFiles.toDelete(all, (good + name(13)).toSet(), 7))
    }

    @Test
    fun partialsAreNotCopiesAndGoAfterADay() {
        val p = name(3) + BackupFiles.PARTIAL
        assertFalse(BackupFiles.isCopy(p))
        assertTrue(BackupFiles.isPartial(p))
        val now = 10L * 24 * 3600 * 1000
        assertEquals(listOf(p), BackupFiles.stalePartials(mapOf(p to 0L, name(4) + BackupFiles.PARTIAL to now - 1000, "x.partial" to 0L), now))
    }
}
