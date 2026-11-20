package dev.inknotes.math

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Random

/**
 * The second voice on CROHME symbols: samples of other people's writing (harder than one's
 * own) and how often a sure vote is right. Its "sure" must almost never be wrong: a wrong
 * sure vote changes a right reading.
 */
class SamplesBenchTest {
    @Test
    fun sureVotesAreRight() {
        val root = System.getenv("CROHME_DIR")?.let(::File)
        assumeTrue("CROHME_DIR not set", root != null && root.isDirectory)
        val train = InkMl.loadDir(File(root, "CROHME2013_data/TrainINKML"), limit = 1500)
        val test = InkMl.loadDir(File(root, "CROHME2013_data/TestINKMLGT"))
        fun symbols(set: List<InkSample>) = set.flatMap { s -> s.symbols.mapNotNull { sym -> sym.strokes.mapNotNull { s.strokes.getOrNull(it) }.takeIf { it.isNotEmpty() }?.let { sym.label to it } } }
        val rnd = Random(1)
        val sweep = System.getenv("SAMPLES_SWEEP") != null
        val grid = if (sweep) listOf(0.08f, 0.10f, 0.12f, 0.14f).flatMap { n -> listOf(0.5f, 0.6f, 0.7f).map { n to it } } else listOf(Samples.NEAR to Samples.MARGIN)
        for ((near, margin) in grid) for (perLabel in listOf(5, 15, 30)) {
            Samples.NEAR = near
            Samples.MARGIN = margin
            val samples = Samples()
            val byLabel = symbols(train).groupBy { it.first }
            for ((label, list) in byLabel) for ((_, strokes) in list.shuffled(rnd).take(perLabel)) samples.add(label, strokes)
            var sure = 0
            var right = 0
            var total = 0
            for ((label, strokes) in symbols(test)) {
                if (label !in byLabel) continue
                total++
                val v = samples.vote(strokes) ?: continue
                if (v.sure) {
                    sure++
                    if (v.token == label) right++
                }
            }
            println("near $near margin $margin samples $perLabel per symbol: sure on ${sure * 100 / total.coerceAtLeast(1)} % of $total, right ${if (sure == 0) 0 else right * 1000 / sure / 10.0} %")
            // Across writers, with enough samples, sure votes must be right ≥ 94 %.
            if (!sweep && perLabel >= 15) assertTrue("sure votes must be right: $right of $sure", sure < 20 || right >= 0.94 * sure)
        }
    }
}
