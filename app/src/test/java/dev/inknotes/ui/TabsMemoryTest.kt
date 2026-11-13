package dev.inknotes.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TabsMemoryTest {
    @Test
    fun theSameTabsComeBack() {
        val s = TabsMemory.decode(TabsMemory.encode(listOf("a", "b", "c"), "b"))
        val r = TabsMemory.restore(s, setOf("a", "b", "c"), 5)
        assertEquals(listOf("a", "b", "c"), r.ids)
        assertEquals("b", r.active)
    }

    @Test
    fun aDeletedNotebookDoesNotComeBack() {
        val r = TabsMemory.restore(TabsMemory.decode("a,b,c;b"), setOf("a", "c"), 5)
        assertEquals(listOf("a", "c"), r.ids)
        assertNull(r.active)
    }

    @Test
    fun atMostFiveAndTheLibraryWhenNoneShown() {
        val r = TabsMemory.restore(TabsMemory.decode("1,2,3,4,5,6,7;"), (1..7).map { "$it" }.toSet(), 5)
        assertEquals(listOf("3", "4", "5", "6", "7"), r.ids)
        assertNull(r.active)
        assertEquals(0, TabsMemory.decode(null).ids.size)
    }
}
