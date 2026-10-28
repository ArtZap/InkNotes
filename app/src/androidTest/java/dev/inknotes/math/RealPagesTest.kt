package dev.inknotes.math

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.PageCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Two pages a user filled with checks of the answers after "=" (equations, fractions with a
 * dot, integrals with "·dx", graphs, arcsin; a table of neon squiggles), read with the real
 * reader. Every line must get its right answer and nothing else.
 */
@RunWith(AndroidJUnit4::class)
class RealPagesTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun answers(asset: String): List<String> {
        val file = File(instrumentation.targetContext.cacheDir, asset.substringAfter('/'))
        instrumentation.context.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        val content = PageCodec.read(file)
        lateinit var math: MathNotes
        instrumentation.runOnMainSync { math = MathNotes({ true }, {}) }
        val deadline = System.currentTimeMillis() + 180_000
        while (math.state != MathNotes.State.Ready) {
            assertTrue("model: ${math.state}", math.state !is MathNotes.State.Failed && System.currentTimeMillis() < deadline)
            Thread.sleep(200)
        }
        return MathPage.analyze(math.toInk(content.strokes), math.reader)
            .sortedWith(compareBy({ it.x > 500 }, { it.y }))
            .map { a ->
                when (val v = a.answer) {
                    is Answer.Text -> v.lines.joinToString(" | ")
                    is Answer.Graph -> "graph"
                    is Answer.Grid -> "grid"
                }
            }
    }

    @Test
    fun firstPage() {
        assertEquals(
            listOf("10", "x = −2,5", "x = −2,5", "x = −2,5", "8", "−46", "0,16", "1", "1", "1122", "3344", "graph"),
            answers("pages/math-check-1.ink"),
        )
    }

    @Test
    fun secondPage() {
        assertEquals(
            listOf(
                "x = −2,5", "x = 0", "x = 0,0598802", "15x²/2 + C", "10x² + C", "2x + C", "2x + C", "x = 2", "1,456·10⁴¹",
                "15x²/2 + 19x + C", "12x² + 19x + C", "3x²/2 + C", "2x² + C", "12x² − 13x + C", "5", "x = 0", "0,766044", "0,927295 = 53,1301°",
                "graph", "graph", "graph", "graph", "graph",
            ),
            answers("pages/math-check-2.ink"),
        )
    }
}
