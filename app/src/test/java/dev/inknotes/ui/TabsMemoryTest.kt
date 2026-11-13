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

    @Test
    fun theNotebookBesideComesBackToo() {
        val r = TabsMemory.restore(TabsMemory.decode(TabsMemory.encode(listOf("a"), "a", "pdf")), setOf("a", "pdf"), 5)
        assertEquals("pdf", r.side)
        // Saved by 0.35 (no third part): nothing beside.
        assertNull(TabsMemory.decode("a,b;a").side)
        assertNull(TabsMemory.restore(TabsMemory.decode("a;a;gone"), setOf("a"), 5).side)
    }

    @Test
    fun theTabsOfTheHalfBesideComeBackToo() {
        val text = TabsMemory.encode(listOf("a", "b"), "a", "pdf", listOf("x", "pdf"))
        val r = TabsMemory.restore(TabsMemory.decode(text), setOf("a", "b", "x", "pdf"), 5)
        assertEquals(listOf("x", "pdf"), r.sideIds)
        assertEquals("pdf", r.side)
        // Saved by 0.36–0.38: the one notebook beside is that half's only tab.
        assertEquals(listOf("pdf"), TabsMemory.decode("a;a;pdf").sideIds)
        // One that is gone: the last left there is shown.
        assertEquals("x", TabsMemory.restore(TabsMemory.decode(text), setOf("a", "b", "x"), 5).side)
    }
}
