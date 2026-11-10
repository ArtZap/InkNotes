package dev.inknotes.math

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * A handwritten formula from an InkML file (CROHME): its strokes, the symbols they make up
 * (label and stroke indices, when the file has them) and the LaTeX of the whole formula.
 */
class InkSample(val id: String, val truth: String, val strokes: List<Ink>, val symbols: List<InkSymbol>)

class InkSymbol(val label: String, val strokes: List<Int>)

object InkMl {

    /** Line height the samples are scaled to: about the size of digits on a tablet page. */
    const val TARGET_H = 30f

    fun load(file: File): InkSample? = runCatching { parse(file) }.getOrNull()

    fun loadDir(dir: File, limit: Int = Int.MAX_VALUE): List<InkSample> =
        dir.walkTopDown().filter { it.isFile && it.name.endsWith(".inkml") }.sortedBy { it.path }.take(limit).mapNotNull(::load).toList()

    private fun parse(file: File): InkSample? {
        val f = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }
        val doc = f.newDocumentBuilder().parse(file)
        val root = doc.documentElement
        var truth: String? = null
        val ann = root.getElementsByTagName("annotation")
        for (i in 0 until ann.length) {
            val a = ann.item(i) as Element
            if (a.parentNode === root && a.getAttribute("type") == "truth") truth = a.textContent.trim()
        }
        truth ?: return null
        val traces = root.getElementsByTagName("trace")
        val ids = HashMap<String, Int>()
        val raw = ArrayList<Pair<FloatArray, FloatArray>>()
        for (i in 0 until traces.length) {
            val t = traces.item(i) as Element
            val pts = t.textContent.trim().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            if (pts.isEmpty()) continue
            val xs = FloatArray(pts.size)
            val ys = FloatArray(pts.size)
            for ((k, p) in pts.withIndex()) {
                val v = p.split(Regex("\\s+"))
                xs[k] = v[0].toFloat()
                ys[k] = v[1].toFloat()
            }
            ids[t.getAttribute("id").ifEmpty { i.toString() }] = raw.size
            raw += xs to ys
        }
        if (raw.isEmpty()) return null
        val symbols = ArrayList<InkSymbol>()
        val groups = root.getElementsByTagName("traceGroup")
        for (i in 0 until groups.length) {
            val g = groups.item(i) as Element
            var label: String? = null
            val refs = ArrayList<Int>()
            var child = g.firstChild
            while (child != null) {
                if (child is Element) {
                    if (child.tagName == "annotation" && child.getAttribute("type") == "truth") label = child.textContent.trim()
                    if (child.tagName == "traceView") ids[child.getAttribute("traceDataRef")]?.let { refs += it }
                }
                child = child.nextSibling
            }
            if (label != null && refs.isNotEmpty()) symbols += InkSymbol(label, refs)
        }
        // Scale so symbols are about TARGET_H tall: the median height of symbols that are not
        // dashes or dots.
        val heights = symbols.mapNotNull { s ->
            val ys = s.strokes.flatMap { raw[it].second.toList() }
            val xs = s.strokes.flatMap { raw[it].first.toList() }
            val h = ys.max() - ys.min()
            h.takeIf { s.label.length == 1 && (s.label[0].isLetterOrDigit()) && h > 0.5f * (xs.max() - xs.min()) }
        }.sorted()
        val all = raw.flatMap { it.second.toList() }
        val h0 = heights.getOrNull(heights.size / 2) ?: ((all.max() - all.min()) * 0.6f)
        if (h0 <= 0f) return null
        val k = TARGET_H / h0
        val minX = raw.minOf { it.first.min() }
        val minY = raw.minOf { it.second.min() }
        val strokes = raw.mapIndexed { idx, (xs, ys) ->
            Ink(FloatArray(xs.size) { 100f + (xs[it] - minX) * k }, FloatArray(ys.size) { 200f + (ys[it] - minY) * k }, idx)
        }
        return InkSample(file.nameWithoutExtension, truth.removePrefix("$").removeSuffix("$").trim(), strokes, symbols)
    }
}

/**
 * Reads runs from what the strokes are known to be: the labels of the symbols wholly in the
 * run, left to right. A symbol cut apart by the layout reads as "?", so every wrong answer
 * it gives is the layout's fault, not the handwriting reader's.
 */
class PerfectReader(sample: InkSample, private val text: (String) -> String? = ::labelText) : RunReader {
    private val symbolOf = HashMap<Ink, Int>()
    private val sizes = sample.symbols.map { it.strokes.size }

    init {
        for ((i, s) in sample.symbols.withIndex()) for (k in s.strokes) symbolOf[sample.strokes[k]] = i
    }

    var cut = 0
        private set

    override fun read(strokes: List<Ink>, lineHeight: Float): List<String> {
        val bySymbol = strokes.groupBy { symbolOf[it] ?: -1 }
        val out = StringBuilder()
        for ((sym, inks) in bySymbol.entries.sortedBy { e -> e.value.minOf { it.left } }) {
            if (sym < 0 || inks.size != sizes[sym]) {
                cut++
                out.append('?')
                continue
            }
            out.append(text(labelOf(sym)) ?: "?")
        }
        return listOf(out.toString())
    }

    private val labels = sample.symbols.map { it.label }
    private fun labelOf(i: Int) = labels[i]

    companion object {
        /** A symbol label as the handwriting reader would give it; null for what InkNotes cannot use. */
        fun labelText(label: String): String? = when {
            label.length == 1 && (label[0].isLetterOrDigit() || label[0] in "+-=()[]|,.!/") -> label
            else -> SYMBOLS[label]
        }

        private val SYMBOLS = mapOf(
            "\\times" to "×", "\\div" to "÷", "\\sqrt" to "√", "\\int" to "∫", "\\pi" to "π", "\\infty" to "∞",
            "\\sin" to "sin", "\\cos" to "cos", "\\tan" to "tg", "\\log" to "log", "\\ln" to "ln", "\\prime" to "'",
            "\\cdot" to "·", "\\ldots" to "...",
        )
    }
}

/** LaTeX of the datasets as InkNotes' parser reads it; null when it uses what InkNotes has not got. */
object Latex {
    private val COMMANDS = mapOf(
        "frac" to "\\frac", "sqrt" to "\\sqrt", "int" to "\\int ",
        "times" to "*", "div" to "/", "cdot" to "*", "pi" to "π", "infty" to "∞",
        "sin" to "sin ", "cos" to "cos ", "tan" to "tan ", "log" to "log ", "ln" to "ln ",
        "left" to "", "right" to "", "prime" to "'",
    )

    fun toParser(latex: String): String? {
        val s = StringBuilder()
        var i = 0
        while (i < latex.length) {
            val c = latex[i]
            if (c == '\\') {
                var j = i + 1
                while (j < latex.length && latex[j].isLetter()) j++
                if (j == i + 1) {
                    // \{ \} \, \;
                    val n = latex.getOrNull(i + 1) ?: return null
                    if (n == ',' || n == ';' || n == ' ' || n == '!') { i += 2; continue }
                    return null
                }
                s.append(COMMANDS[latex.substring(i + 1, j)] ?: return null)
                i = j
                continue
            }
            if (c.isLetterOrDigit() || c in " +-*/^_{}()[]|.!'") s.append(c) else if (c == '=') return null else return null
            i++
        }
        return s.toString().trim().takeIf { it.isNotEmpty() }
    }
}
