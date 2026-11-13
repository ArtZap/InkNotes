package dev.inknotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolbarLayoutTest {
    @Test
    fun everyButtonHasOnePlaceInEveryPreset() {
        for (p in Toolbars.Preset.entries) {
            val l = p.layout
            val placed = l.tools + l.top + l.menu
            assertEquals(p.name, placed.size, placed.toSet().size)
            assertTrue(p.name, placed.all { it in Toolbars.ALL })
            for (id in Toolbars.ALL) assertTrue("${p.name} $id", l.zoneOf(id) in Toolbars.allowed(id))
        }
        assertEquals(Toolbars.ALL.toSet(), (Toolbars.FULL.tools + Toolbars.FULL.top + Toolbars.FULL.menu).toSet())
    }

    @Test
    fun moveBetweenZonesAndWithin() {
        var l = Toolbars.FULL.moved("toc", Zone.TOP, 0)
        assertEquals("toc", l.top.first())
        assertEquals(Zone.TOP, l.zoneOf("toc"))
        l = l.moved("neon", Zone.HIDDEN)
        assertEquals(Zone.HIDDEN, l.zoneOf("neon"))
        assertTrue("neon" in l.hidden)
        l = l.moved("pen", Zone.TOOLS, 3)
        assertEquals(3, l.tools.indexOf("pen"))
    }

    @Test
    fun undoStaysInSight() {
        val l = Toolbars.FULL.moved("undo", Zone.HIDDEN)
        assertEquals(Toolbars.FULL, l)
        assertEquals(Toolbars.FULL, Toolbars.FULL.moved("undo", Zone.MENU))
        assertEquals(Zone.TOOLS, Toolbars.FULL.moved("undo", Zone.TOOLS).zoneOf("undo"))
        assertEquals(Toolbars.FULL, Toolbars.FULL.moved("help", Zone.HIDDEN))
    }

    @Test
    fun roundTrip() {
        val l = Toolbars.Preset.NOTES.layout.moved("math", Zone.TOP, 1)
        assertEquals(l, ToolbarLayout.decode(l.encode()))
        assertEquals(Toolbars.FULL, ToolbarLayout.decode(null))
        assertEquals(Toolbars.FULL, ToolbarLayout.decode("garbage"))
    }

    @Test
    fun unknownDroppedAndRepeatsOnce() {
        val l = ToolbarLayout.decode("tools=pen,pen,warp;top=undo;menu=help;seen=" + Toolbars.ALL.joinToString(","))
        assertEquals(listOf("pen"), l.tools)
        assertEquals(listOf("undo"), l.top)
    }

    @Test
    fun newButtonsOfLaterVersionsGoWhereTheFullLayoutHasThem() {
        // Saved when "cards" did not exist yet: it appears in the menu, not hidden.
        val seen = Toolbars.ALL.filter { it != "cards" }.joinToString(",")
        val l = ToolbarLayout.decode("tools=pen;top=undo;menu=help;seen=$seen")
        assertEquals(Zone.MENU, l.zoneOf("cards"))
        // Known then and left out: hidden by the user, stays hidden.
        assertEquals(Zone.HIDDEN, l.zoneOf("neon"))
    }

    @Test
    fun undoPutBackWhenASavedLayoutHidesIt() {
        val l = ToolbarLayout.decode("tools=pen;top=;menu=help;seen=" + Toolbars.ALL.joinToString(","))
        assertEquals(Zone.TOP, l.zoneOf("undo"))
    }

    @Test
    fun layoutOf031GetsTheButtonsOf032() {
        val old = Toolbars.ALL - setOf("zoom-window", "scan", "pdf-share", "print", "pressure", "formula", "protractor", "compass", "timer", "pdf-text", "pdf-page", "pdf-dark")
        val l = ToolbarLayout.decode("tools=pen,eraser;top=undo;menu=help;seen=" + old.joinToString(","))
        assertEquals(Zone.TOP, l.zoneOf("zoom-window"))
        assertEquals(Zone.MENU, l.zoneOf("pdf-share"))
        assertEquals(Zone.MENU, l.zoneOf("print"))
        assertEquals(Zone.MENU, l.zoneOf("formula"))
        assertEquals(Zone.MENU, l.zoneOf("timer"))
        // What the user had hidden stays hidden.
        assertEquals(Zone.HIDDEN, l.zoneOf("cards"))
    }

    @Test
    fun layoutOf033GetsThePdfButtons() {
        val old = Toolbars.ALL - setOf("pdf-text", "pdf-page", "pdf-dark")
        val l = ToolbarLayout.decode("tools=pen,eraser;top=undo;menu=help;seen=" + old.joinToString(","))
        assertEquals(Zone.MENU, l.zoneOf("pdf-text"))
        assertEquals(Zone.MENU, l.zoneOf("pdf-page"))
        assertEquals(Zone.MENU, l.zoneOf("pdf-dark"))
        assertEquals(Zone.HIDDEN, l.zoneOf("timer"))
    }
}
