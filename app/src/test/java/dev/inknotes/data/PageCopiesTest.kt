package dev.inknotes.data

import dev.inknotes.data.PageCopies.Failure
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.EOFException
import java.io.IOException

class PageCopiesTest {
    @Test
    fun storageErrorsAndMemoryLockThePageDamageSetsItAside() {
        assertEquals(Failure.LOCKED, PageCopies.failure(IOException("I/O error"), reading = true))
        assertEquals(Failure.LOCKED, PageCopies.failure(OutOfMemoryError(), reading = false))
        assertEquals(Failure.LOCKED, PageCopies.failure(NewerFormatException("v99"), reading = false))
        assertEquals(Failure.DAMAGED, PageCopies.failure(EOFException(), reading = false))
        assertEquals(Failure.DAMAGED, PageCopies.failure(IllegalArgumentException(), reading = false))
    }

    private val five = (1..5).map { "p.ink.broken-$it" } + listOf("q.ink.kept-1", "p.ink", "other.txt")

    @Test
    fun aGoodSaveLeavesTheNewestThree() {
        assertEquals(listOf("p.ink.broken-2", "p.ink.broken-1"), PageCopies.toDelete(five, "p.ink", savedAndRead = true))
    }

    @Test
    fun withoutAGoodSaveEveryCopyStays() {
        assertEquals(emptyList<String>(), PageCopies.toDelete(five, "p.ink", savedAndRead = false))
    }

    @Test
    fun copiesKnowTheirPage() {
        assertEquals("p.ink", PageCopies.pageOf("p.ink.kept-17"))
        assertEquals(null, PageCopies.pageOf("p.ink"))
    }
}
