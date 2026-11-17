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

    @Test
    fun aSecondExportTakesNewNames() {
        val taken = setOf("Матан.md", "Матан_files")
        assertEquals("Матан 2", MarkdownNote.freeName("Матан", taken))
        assertEquals("Матан", MarkdownNote.freeName("Матан", setOf("Другое.md")))
        val long = "x".repeat(80)
        val first = MarkdownNote.freeName(long, emptySet())
        val second = MarkdownNote.freeName(long, setOf(MarkdownNote.noteName(first)))
        assertTrue(second != first && second.endsWith(" 2"))
    }

    @Test
    fun linesStayTextAndTheTitleStaysYaml() {
        assertEquals("\\# 1 вопрос", MarkdownNote.plain("# 1 вопрос"))
        assertEquals("\\1. пункт", MarkdownNote.plain("1. пункт"))
        assertEquals("обычная строка", MarkdownNote.plain("обычная строка"))
        assertEquals("a \\\"b\\\" c\\\\d e", MarkdownNote.yaml("a \"b\" c\\d\ne"))
        val md = MarkdownNote.build("T", "2026-11-17", listOf(MarkdownNote.Page(1, "t-p1.png", listOf("- не список"), emptyList())), folder = "T 2_files")
        assertTrue(md, md.contains("](T 2_files/t-p1.png)"))
        assertTrue(md, md.contains("\\- не список"))
    }
}
