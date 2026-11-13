package dev.inknotes.math

import java.io.File

/**
 * Picks the layout thresholds ([Tune]) on a training set of handwritten formulas by coordinate
 * descent: each threshold in turn is scaled up and down, and a change is kept when it gives
 * more right answers for fewer wrong ones (a wrong answer costs two right ones: it is a number
 * one believes) and every unit test on real pages still passes.
 *
 * Run: `java … dev.inknotes.math.LayoutTuner <train dir> <check dir>…` — prints the values to
 * put into [Tune] and the figures on the check sets (not used for choosing, so not fitted to).
 */
object LayoutTuner {

    private val TESTS = listOf("dev.inknotes.math.MathPageTest", "dev.inknotes.math.RealPageTest", "dev.inknotes.math.CalcTest")

    /** Every unit test of the formula code passes. */
    fun testsPass(): Boolean {
        // The tests expect the app's default way of writing numbers.
        val numbers = Printer.numbers
        Printer.numbers = Printer.Numbers.MIXED
        try {
            return TESTS.all { name ->
                val c = runCatching { Class.forName(name) }.getOrNull() ?: return@all true
                c.declaredMethods.filter { it.getAnnotation(org.junit.Test::class.java) != null }.all { m ->
                    runCatching { m.isAccessible = true; m.invoke(c.getDeclaredConstructor().newInstance()) }.isSuccess
                        .also { if (!it) println("    тест ${c.simpleName}.${m.name} не прошёл") }
                }
            }
        } finally {
            Printer.numbers = numbers
        }
    }

    class Set(val samples: List<Pair<InkSample, Expr>>)

    fun load(dir: File): Set = Set(InkMl.loadDir(dir).mapNotNull { s -> FormulaBench.covered(s)?.let { s to it } })

    fun score(set: Set): Pair<Int, String> {
        var right = 0
        var wrong = 0
        for ((s, e) in set.samples) {
            for (r in listOfNotNull(FormulaBench.layout(s, e), FormulaBench.page(s, e))) {
                if (r.outcome == FormulaBench.Outcome.RIGHT) right++
                if (r.outcome == FormulaBench.Outcome.WRONG) wrong++
            }
        }
        return right - 2 * wrong to "верно $right, неверно $wrong"
    }

    @JvmStatic
    fun main(args: Array<String>) {
        Printer.numbers = Printer.Numbers.DECIMAL
        val train = load(File(args[0]))
        val checks = args.drop(1).map { it to load(File(it)) }
        fun report(tag: String) {
            println("$tag: train ${score(train).second}; " + checks.joinToString("; ") { (n, s) -> "${File(n).name} ${score(s).second}" })
        }
        report("было")
        check(testsPass()) { "unit tests fail before tuning" }
        var best = score(train).first
        val steps = listOf(0.8f, 0.9f, 1.1f, 1.25f)
        repeat(System.getProperty("tune.rounds")?.toInt() ?: 2) { round ->
            for ((name, v0) in Tune.values()) {
                var cur = v0
                for (k in steps) {
                    val v = cur * k
                    Tune.set(name, v)
                    val s = score(train).first
                    if (s > best && testsPass()) {
                        best = s
                        cur = v
                        println("  раунд ${round + 1}: $name = $v (оценка $s)")
                    } else {
                        Tune.set(name, cur)
                    }
                }
            }
        }
        report("стало")
        println(Tune.values().entries.joinToString("\n") { (k, v) -> "    @JvmField var $k = ${"%.3f".format(v)}f" })
    }
}
