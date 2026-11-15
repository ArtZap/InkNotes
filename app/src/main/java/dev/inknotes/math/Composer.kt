package dev.inknotes.math

/**
 * The formula keyboard's formula: a row of keys typed and templates with empty places (a
 * fraction, a root, an integral with its limits), a cursor in it, read out as the app's
 * formula text (what handwriting is read into, `\frac{a}{b}`, `x^{2}`, `\int_{0}^{1}x dx`).
 * Nobody needs to know that text: keys and templates make it.
 */
class Composer private constructor(val root: Seq) {

    /** A row of items; an empty place of a template is an empty row. */
    class Seq(val items: MutableList<Item> = ArrayList())

    sealed class Item {
        /** What one key types: formula text (`7`, `x`, `+`, `\pi`). */
        class Tok(val text: String) : Item()

        /**
         * A template: formula text with its places marked #0, #1… (`\frac{#0}{#1}`), and the
         * rows that fill them, in the order the cursor visits them.
         */
        class Box(val pattern: String, val slots: List<Seq>) : Item()
    }

    /** Where typing goes: before item [pos] of [seq]. */
    var seq: Seq = root
        private set
    var pos: Int = root.items.size
        private set

    /** Types [text] at the cursor. */
    fun type(text: String) {
        seq.items.add(pos++, Item.Tok(text))
    }

    /** Puts the template [pattern] at the cursor and the cursor in its first empty place. */
    fun template(pattern: String) {
        val n = places(pattern)
        val box = Item.Box(pattern, List(n) { Seq() })
        seq.items.add(pos++, box)
        if (n > 0) {
            seq = box.slots[0]
            pos = 0
        }
    }

    /**
     * Erases what is before the cursor: a key; a template whose places are all empty; into a
     * filled template (its last place). At the start of a place: out of the template, which
     * goes too when it is empty.
     */
    fun erase() {
        if (pos > 0) {
            when (val it = seq.items[pos - 1]) {
                is Item.Tok -> seq.items.removeAt(--pos)
                is Item.Box -> if (it.slots.all { s -> s.items.isEmpty() }) {
                    seq.items.removeAt(--pos)
                } else {
                    seq = it.slots.last()
                    pos = seq.items.size
                }
            }
            return
        }
        val (parent, index, box) = parentOf(seq) ?: return
        seq = parent
        pos = index
        if (box.slots.all { it.items.isEmpty() }) parent.items.removeAt(index)
    }

    /** The formula replaced by [text], taken apart as [of] does; the cursor at its end. */
    fun set(text: String) {
        root.items.clear()
        root.items.addAll(Reader(text).seq(null).items)
        seq = root
        pos = root.items.size
    }

    /** Everything erased. */
    fun clear() {
        root.items.clear()
        seq = root
        pos = 0
    }

    /** One step left or right through every place the cursor can be, into and out of templates. */
    fun left() = step(-1)
    fun right() = step(1)

    /** To the next (or previous) empty place, if any. */
    fun nextEmpty(forward: Boolean = true) {
        val all = positions()
        val here = all.indexOfFirst { it.first === seq && it.second == pos }
        val order = if (forward) (here + 1 until all.size) + (0 until here) else (here - 1 downTo 0) + (all.size - 1 downTo here + 1)
        for (k in order) {
            val (s, p) = all[k]
            if (s.items.isEmpty() && s !== root) {
                seq = s
                pos = p
                return
            }
        }
    }

    private fun step(by: Int) {
        val all = positions()
        val here = all.indexOfFirst { it.first === seq && it.second == pos }
        val (s, p) = all.getOrNull(here + by) ?: return
        seq = s
        pos = p
    }

    /** Every cursor place, left to right: before each item, inside templates before what follows them. */
    private fun positions(): List<Pair<Seq, Int>> {
        val out = ArrayList<Pair<Seq, Int>>()
        fun walk(s: Seq) {
            for (i in 0..s.items.size) {
                out += s to i
                val it = s.items.getOrNull(i)
                if (it is Item.Box) it.slots.forEach(::walk)
            }
        }
        walk(root)
        return out
    }

    private fun parentOf(target: Seq): Triple<Seq, Int, Item.Box>? {
        fun find(s: Seq): Triple<Seq, Int, Item.Box>? {
            for ((i, it) in s.items.withIndex()) {
                if (it is Item.Box) {
                    if (it.slots.any { slot -> slot === target }) return Triple(s, i, it)
                    for (slot in it.slots) find(slot)?.let { found -> return found }
                }
            }
            return null
        }
        return find(root)
    }

    /** The formula text; an empty place is □. */
    fun text(): String = write(root, null)

    /** The text to set in print, with the cursor shown as a thin bar. */
    fun shown(): String = write(root, CURSOR)

    /** Nothing typed yet. */
    val isEmpty get() = root.items.isEmpty()

    /** Some place is still empty. */
    val hasEmpty get() = positions().any { (s, _) -> s !== root && s.items.isEmpty() }

    private fun write(s: Seq, cursor: String?): String {
        val b = StringBuilder()
        if (s.items.isEmpty() && s !== root && !(cursor != null && s === seq)) return EMPTY
        for ((i, it) in s.items.withIndex()) {
            if (cursor != null && s === seq && i == pos) b.append(cursor)
            when (it) {
                is Item.Tok -> {
                    b.append(it.text)
                    // \pi x, not \pix: a command name ends before a letter.
                    val next = s.items.getOrNull(i + 1)
                    if (it.text.length > 1 && it.text[0] == '\\' && it.text[1].isLetter() && next is Item.Tok && next.text.first().isLetter()) b.append(' ')
                }
                is Item.Box -> b.append(fill(it.pattern) { k -> write(it.slots[k], cursor) })
            }
        }
        if (cursor != null && s === seq && pos == s.items.size) b.append(cursor)
        return b.toString()
    }

    companion object {
        const val EMPTY = "□"
        const val CURSOR = "▏"

        /** An empty formula. */
        fun empty() = Composer(Seq())

        /**
         * [text] (formula text, as read or typed before) taken apart: fractions, roots, exponents
         * and indexes as templates to edit inside, every other character or `\command` a key.
         */
        fun of(text: String): Composer {
            val c = Composer(Reader(text).seq(null))
            c.seq = c.root
            c.pos = c.root.items.size
            return c
        }

        /** How many places [pattern] has (#0 … #n). */
        fun places(pattern: String): Int = Regex("#(\\d)").findAll(pattern).maxOfOrNull { it.groupValues[1].toInt() + 1 } ?: 0

        private fun fill(pattern: String, slot: (Int) -> String): String = Regex("#(\\d)").replace(pattern) { slot(it.groupValues[1].toInt()) }
    }

    private class Reader(val s: String) {
        var i = 0

        /** Items up to [end] (a closing brace or bracket) or the end. */
        fun seq(end: Char?): Seq {
            val out = Seq()
            while (i < s.length) {
                val c = s[i]
                if (end != null && c == end) {
                    i++
                    return out
                }
                when {
                    s.startsWith("\\frac{", i) -> {
                        i += 6
                        val a = seq('}')
                        out.items += if (i < s.length && s[i] == '{') {
                            i++
                            Item.Box("\\frac{#0}{#1}", listOf(a, seq('}')))
                        } else {
                            Item.Box("\\frac{#0}{#1}", listOf(a, Seq()))
                        }
                    }
                    s.startsWith("\\sqrt[", i) -> {
                        i += 6
                        val degree = seq(']')
                        if (i < s.length && s[i] == '{') {
                            i++
                            out.items += Item.Box("\\sqrt[#0]{#1}", listOf(degree, seq('}')))
                        } else {
                            out.items += Item.Box("\\sqrt[#0]{#1}", listOf(degree, Seq()))
                        }
                    }
                    s.startsWith("\\sqrt{", i) -> {
                        i += 6
                        out.items += Item.Box("\\sqrt{#0}", listOf(seq('}')))
                    }
                    s.startsWith("^{", i) || s.startsWith("_{", i) -> {
                        val mark = c
                        i += 2
                        out.items += Item.Box("$mark{#0}", listOf(seq('}')))
                    }
                    c == '\\' -> {
                        var j = i + 1
                        while (j < s.length && s[j].isLetter()) j++
                        if (j == i + 1 && j < s.length) j++
                        out.items += Item.Tok(s.substring(i, j))
                        i = j
                    }
                    // A space kept where it parts letters ("x dx"): one key's worth.
                    c == ' ' -> {
                        if ((out.items.lastOrNull() as? Item.Tok)?.text != " ") out.items += Item.Tok(" ")
                        i++
                    }
                    else -> {
                        out.items += Item.Tok(c.toString())
                        i++
                    }
                }
            }
            return out
        }
    }
}
