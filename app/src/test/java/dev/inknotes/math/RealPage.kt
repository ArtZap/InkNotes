package dev.inknotes.math

/** Strokes of a real handwritten page saved by MathProbe (test resource). */
object RealPage {
    fun load(name: String): List<Ink> {
        val text = RealPage::class.java.classLoader!!.getResource(name)!!.readText()
        val out = ArrayList<Ink>()
        val re = Regex("\\{\"x\":\\[([^\\]]*)\\],\"y\":\\[([^\\]]*)\\]\\}")
        for (m in re.findAll(text)) {
            val xs = m.groupValues[1].split(',').map { it.toFloat() }.toFloatArray()
            val ys = m.groupValues[2].split(',').map { it.toFloat() }.toFloatArray()
            out += Ink(xs, ys, out.size)
        }
        return out
    }
}
