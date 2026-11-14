package dev.inknotes.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every Russian text of the app is the first argument of [tr], with its English beside it.
 * A Russian string anywhere else (or inside the English one) would show in Russian to
 * someone using the app in English. A constant used as the Russian side of a tr() is marked
 * `// ru-only` on its line.
 */
class TranslationTest {

    /** Russian on purpose, in every language. */
    private val allowed = setOf(
        "Язык · Language", // the language picker's title, readable whatever is set
        "Русский", // the name of the language, as it calls itself
        // tabsWord: Russian plurals, used only on the Russian side of tr()
        "вкладок",
        "вкладку",
        "вкладки",
    )

    @Test
    fun russianTextIsAlwaysTranslated() {
        val sources = File("src/main/java").walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("sources not found from ${File(".").absolutePath}", sources.size > 20)
        val problems = ArrayList<String>()
        for (f in sources) {
            val text = f.readText()
            for (lit in KotlinStrings.find(text)) {
                if (lit.content in allowed) continue
                // The picker's title may be quoted in English text (the help).
                if (!CYRILLIC.containsMatchIn(lit.content.replace("Язык · Language", ""))) continue
                val line = text.substring(0, lit.start).count { it == '\n' } + 1
                // A constant that is the Russian side of a tr() elsewhere says so on its line.
                val lineEnd = text.indexOf('\n', lit.start).let { if (it < 0) text.length else it }
                if ("// ru-only" in text.substring(lit.start, lineEnd)) continue
                when {
                    lit.isRussianSide -> continue
                    else -> problems += "${f.path.substringAfter("src/main/java/")}:$line: \"${lit.content.take(60)}\""
                }
            }
        }
        assertTrue("Russian text outside tr(\"ru\", \"en\"):\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun theCheckSeesWhatItShould() {
        val sample = """
            val a = tr("Готово", "Done")
            val b = "Отмена"
            val c = tr("Закрыть ${'$'}{if (x) "все" else "одну"}", "Close ${'$'}{if (x) "все" else "one"}")
            // "Комментарий" is not text
            val d = tr(
                "Длинная строка",
                "A long line",
            )
        """.trimIndent()
        val bad = KotlinStrings.find(sample).filter { CYRILLIC.containsMatchIn(it.content) && !it.isRussianSide }.map { it.content }
        assertTrue(bad.toString(), bad == listOf("Отмена", "все"))
    }

    private companion object {
        val CYRILLIC = Regex("[А-Яа-яЁё]")
    }
}

/**
 * String literals of Kotlin source, nested ones (in `${…}`) included, with comments skipped.
 * [Lit.isRussianSide]: the literal is the first argument of `tr(` or lies within it.
 */
internal object KotlinStrings {
    class Lit(val start: Int, val content: String, val isRussianSide: Boolean)

    fun find(s: String): List<Lit> {
        val out = ArrayList<Lit>()
        var i = 0
        while (i < s.length) {
            i = skipCode(s, i, out, insideRussian = false, stopAtBrace = false)
        }
        return out
    }

    /** Code from [from] up to the end, or to the `}` closing a template when [stopAtBrace]. */
    private fun skipCode(s: String, from: Int, out: MutableList<Lit>, insideRussian: Boolean, stopAtBrace: Boolean): Int {
        var i = from
        var depth = 0
        while (i < s.length) {
            val c = s[i]
            when {
                s.startsWith("//", i) -> i = s.indexOf('\n', i).let { if (it < 0) s.length else it }
                s.startsWith("/*", i) -> i = s.indexOf("*/", i + 2).let { if (it < 0) s.length else it + 2 }
                c == '\'' -> i = skipChar(s, i)
                c == '"' -> {
                    val russian = insideRussian || precededByTr(s, i)
                    i = string(s, i, out, russian)
                }
                c == '{' -> { depth++; i++ }
                c == '}' -> {
                    if (stopAtBrace && depth == 0) return i + 1
                    depth--
                    i++
                }
                else -> i++
            }
        }
        return i
    }

    private fun skipChar(s: String, i: Int): Int {
        var j = i + 1
        while (j < s.length && s[j] != '\'') j += if (s[j] == '\\') 2 else 1
        return j + 1
    }

    /** The literal at [i] (a quote); returns the index after it. */
    private fun string(s: String, i: Int, out: MutableList<Lit>, russian: Boolean): Int {
        val raw = s.startsWith("\"\"\"", i)
        var j = i + if (raw) 3 else 1
        val text = StringBuilder()
        while (j < s.length) {
            if (raw && s.startsWith("\"\"\"", j)) { j += 3; break }
            if (!raw && s[j] == '"') { j++; break }
            if (!raw && s[j] == '\\') { text.append(s, j, minOf(j + 2, s.length)); j += 2; continue }
            if (s.startsWith("\${", j)) {
                text.append("\${…}")
                j = skipCode(s, j + 2, out, russian, stopAtBrace = true)
                continue
            }
            text.append(s[j])
            j++
        }
        out += Lit(i, text.toString(), russian)
        return j
    }

    /** `tr(` just before [i], blank space and line breaks between them allowed. */
    private fun precededByTr(s: String, i: Int): Boolean {
        var k = i - 1
        while (k >= 0 && s[k].isWhitespace()) k--
        if (k < 0 || s[k] != '(') return false
        k--
        return k >= 1 && s[k] == 'r' && s[k - 1] == 't' && (k < 2 || !s[k - 2].isLetterOrDigit())
    }
}
