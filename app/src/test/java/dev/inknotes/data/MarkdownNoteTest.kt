package dev.inknotes.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownNoteTest {
    @Test
    fun aNoteForObsidian() {
        val pages = listOf(
            MarkdownNote.Page(1, MarkdownNote.imageName("Матан: лекция 3", 1), listOf("Пределы", "  "), listOf("x^{2}+1=10" to "x = ±3", "\\int x\\,dx" to null)),
            MarkdownNote.Page(2, MarkdownNote.imageName("Матан: лекция 3", 2), emptyList(), emptyList()),
        )
        val md = MarkdownNote.build("Матан: лекция 3", "2026-11-15", pages)
        assertTrue(md, md.startsWith("---\ntitle: \"Матан: лекция 3\"\nsource: InkNotes\nexported: 2026-11-15\npages: 2\n"))
        assertTrue(md, "# Матан: лекция 3\n" in md)
        assertTrue(md, "![Страница 1](Матан__лекция_3_files/Матан__лекция_3-p1.png)" in md)
        assertTrue(md, "Пределы  \n" in md)
        assertTrue(md, "- \$x^{2}+1=10\$ → x = ±3\n" in md)
        assertTrue(md, "- \$\\int x\\,dx\$\n" in md)
        assertEquals("Матан_ лекция 3.md", MarkdownNote.noteName("Матан: лекция 3"))
    }

    @Test
    fun saysOnceWhenTheTextWasNotRead() {
        val md = MarkdownNote.build("A", "2026-11-15", listOf(MarkdownNote.Page(1, "A-p1.png", emptyList(), emptyList())), textMissing = true)
        assertEquals(1, Regex("поиск по почерку|handwriting search").findAll(md).count())
    }
}
