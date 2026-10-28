package dev.inknotes.probe

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PageCodec
import dev.inknotes.data.PageContent
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: lists the strokes of one page (`-e title T -e page N`) into pagefix.txt, or with
 * `-e drop 12-40,45` removes those strokes (by index) and saves the page, keeping a copy of the
 * old file next to it. Runs only on request.
 */
@RunWith(AndroidJUnit4::class)
class PageFix {
    @Test
    fun fix() {
        val args = InstrumentationRegistry.getArguments()
        val title = args.getString("title")
        assumeTrue(title != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repo = NotebookRepository(context.filesDir)
        val nb = repo.list().single { it.title == title }
        val pageId = nb.pageIds[args.getString("page")?.toInt() ?: 0]
        val file = File(File(context.filesDir, "notebooks/${nb.id}"), "pages/$pageId.ink")
        val content = PageCodec.read(file)
        val drop = args.getString("drop")
        if (drop == null) {
            val out = StringBuilder("${nb.id} $pageId strokes=${content.strokes.size} fills=${content.fills.size} images=${content.images.size}\n")
            content.strokes.forEachIndexed { i, s ->
                val inputs = s.stroke.inputs
                var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                for (k in 0 until inputs.size) {
                    val p = inputs.get(k)
                    x0 = minOf(x0, p.x); y0 = minOf(y0, p.y); x1 = maxOf(x1, p.x); y1 = maxOf(y1, p.y)
                }
                out.append("$i ${s.kind} #${Integer.toHexString(s.stroke.brush.colorIntArgb)} size=${s.stroke.brush.size} n=${inputs.size} box=${x0.toInt()},${y0.toInt()}-${x1.toInt()},${y1.toInt()}\n")
            }
            File(context.getExternalFilesDir(null), "pagefix.txt").writeText(out.toString())
            return
        }
        val gone = if (drop == "all") content.strokes.indices.toSet() else drop.split(",").flatMap { r -> r.split("-").let { if (it.size == 1) listOf(it[0].toInt()) else (it[0].toInt()..it[1].toInt()).toList() } }.toSet()
        file.copyTo(File(file.parentFile, "${file.name}.before-fix"), overwrite = true)
        val kept = if (drop == "all") PageContent(emptyList(), emptyList(), emptyList()) else PageContent(content.strokes.filterIndexed { i, _ -> i !in gone }, content.fills, content.images)
        file.outputStream().use { PageCodec.write(it, kept) }
    }
}
