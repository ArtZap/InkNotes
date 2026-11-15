package dev.inknotes.data

/**
 * A notebook as a Markdown note for Obsidian (or any Markdown app): a header with the title
 * and date, then each page as its picture, the handwriting read as text, and its formulas in
 * LaTeX with their answers. The pictures lie in a folder beside the note.
 */
object MarkdownNote {
    /** A page of the note: its number, picture file, lines of text, formulas (LaTeX → answer). */
    class Page(val number: Int, val image: String?, val lines: List<String>, val formulas: List<Pair<String, String?>>)

    /** The file names for notebook [title]: the note, the pictures' folder, page [n]'s picture. */
    fun slug(title: String): String = title.replace(Regex("[\\\\/:*?\"<>|\\[\\]#^\\n\\r\\t]"), "_").trim().take(60).ifEmpty { "notebook" }

    fun noteName(title: String) = slug(title) + ".md"

    fun folderName(title: String) = slug(title).replace(' ', '_') + "_files"

    fun imageName(title: String, n: Int) = slug(title).replace(' ', '_') + "-p$n.png"

    /**
     * The note's text. [date]: the day exported (yyyy-mm-dd). [textMissing]: the handwriting was
     * not read (search by handwriting off): said once, not page by page.
     */
    fun build(title: String, date: String, pages: List<Page>, textMissing: Boolean = false): String = buildString {
        append("---\n")
        append("title: \"").append(title.replace("\"", "\\\"")).append("\"\n")
        append("source: InkNotes\n")
        append("exported: ").append(date).append('\n')
        append("pages: ").append(pages.size).append('\n')
        append("tags: [inknotes]\n")
        append("---\n\n")
        append("# ").append(title).append("\n\n")
        if (textMissing) {
            append("> ").append(tr("Рукописный текст не распознан: включите поиск по почерку в настройках InkNotes и экспортируйте снова.", "Handwriting was not read as text: turn on handwriting search in InkNotes' settings and export again.")).append("\n\n")
        }
        for (p in pages) {
            append("## ").append(tr("Страница ${p.number}", "Page ${p.number}")).append("\n\n")
            if (p.image != null) {
                append("![").append(tr("Страница ${p.number}", "Page ${p.number}")).append("](").append(folderName(title)).append('/').append(p.image).append(")\n\n")
            }
            for (line in p.lines) if (line.isNotBlank()) append(line.trim()).append("  \n")
            if (p.lines.any { it.isNotBlank() }) append('\n')
            if (p.formulas.isNotEmpty()) {
                append("**").append(tr("Формулы", "Formulas")).append("**\n\n")
                for ((latex, answer) in p.formulas) {
                    append("- $").append(latex).append('$')
                    if (!answer.isNullOrBlank()) append(" → ").append(answer)
                    append('\n')
                }
                append('\n')
            }
        }
    }
}
