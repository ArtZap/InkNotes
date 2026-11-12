package dev.inknotes.data

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

/**
 * What PdfRenderer does not give: the words of a PDF page with their places, its links and the
 * PDF's own contents. Read with PdfBox on one private thread, a page at a time, when asked.
 * Places are shares (0…1) of the page as PdfRenderer shows it.
 */
class PdfText(context: Context) : Closeable {
    class Page(val words: List<PdfWord>, val links: List<PdfLink>)

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-text").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private var openFile: File? = null
    private var openDoc: PDDocument? = null

    init {
        PDFBoxResourceLoader.init(context.applicationContext)
    }

    /** Words and links of page [index] of [file], on the main thread; null when it can't be read. */
    fun pageAsync(file: File, index: Int, onDone: (Page?) -> Unit) {
        exec.execute {
            val page = runCatching { Reader.readPage(doc(file), index) }
                .onFailure { android.util.Log.w(TAG, "page $index of ${file.name}", it) }
                .getOrNull()
            page?.let { p -> android.util.Log.i(TAG, "page $index: ${p.words.size} words, ${p.links.size} links, first ${p.words.firstOrNull()}") }
            main.post { onDone(page) }
        }
    }

    /** The PDF's contents (empty when it has none), blocking: not on the main thread. */
    fun outlineBlocking(file: File): List<PdfOutlineItem> =
        exec.submit<List<PdfOutlineItem>> { runCatching { Reader.readOutline(doc(file)) }.getOrDefault(emptyList()) }.get()

    fun outlineAsync(file: File, onDone: (List<PdfOutlineItem>) -> Unit) {
        exec.execute {
            val items = runCatching { Reader.readOutline(doc(file)) }.getOrDefault(emptyList())
            main.post { onDone(items) }
        }
    }

    override fun close() {
        exec.execute {
            runCatching { openDoc?.close() }
            openDoc = null
            openFile = null
        }
        exec.shutdown()
    }

    /** One document open at a time: a notebook's PDFs are read one after another. */
    private fun doc(file: File): PDDocument {
        openDoc?.let { if (openFile == file) return it }
        runCatching { openDoc?.close() }
        openDoc = null
        return PDDocument.load(file).also {
            openDoc = it
            openFile = file
        }
    }

    /** Reading of a document, on whatever thread calls it. */
    internal object Reader {
        class Box(val llx: Float, val lly: Float, val w: Float, val h: Float, val rotation: Int)

        fun box(page: PDPage): Box {
            val crop = page.cropBox
            return Box(crop.lowerLeftX, crop.lowerLeftY, max(crop.width, 1f), max(crop.height, 1f), PdfGeometry.normalRotation(page.rotation))
        }

        /** A point of PDF user space (y up) as a share of the page as shown (turned by its /Rotate). */
        fun unit(b: Box, x: Float, y: Float): Pair<Float, Float> =
            PdfGeometry.turnUnit((x - b.llx) / b.w, (b.lly + b.h - y) / b.h, b.rotation)

        fun readPage(doc: PDDocument, index: Int): Page {
            if (index !in 0 until doc.numberOfPages) return Page(emptyList(), emptyList())
            val page = doc.getPage(index)
            val b = box(page)
            val words = runCatching { readWords(doc, index, b) }.onFailure { android.util.Log.w(TAG, "words", it) }.getOrDefault(emptyList())
            val links = runCatching { readLinks(doc, page, b) }.getOrDefault(emptyList())
            return Page(PdfWords.numberLines(words), links)
        }

        fun readWords(doc: PDDocument, index: Int, b: Box): List<PdfWord> {
            val out = ArrayList<PdfWord>()
            // Text places are of the page as shown: its width and height once turned.
            val (pw, ph) = if (b.rotation % 180 == 0) b.w to b.h else b.h to b.w
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String?, textPositions: MutableList<TextPosition>?) {
                    val sb = StringBuilder()
                    var l = 0f
                    var t = 0f
                    var r = 0f
                    var bt = 0f
                    fun flush() {
                        if (sb.isNotBlank() && r > l) out += PdfWord(sb.toString(), l / pw, t / ph, r / pw, bt / ph)
                        sb.setLength(0)
                    }
                    for (tp in textPositions.orEmpty()) {
                        val s = tp.unicode ?: continue
                        if (s.isBlank()) {
                            flush()
                            continue
                        }
                        val size = max(tp.fontSizeInPt, tp.heightDir)
                        val x0 = tp.xDirAdj
                        val x1 = x0 + max(tp.widthDirAdj, 0f)
                        val top = tp.yDirAdj - max(tp.heightDir, size * 0.7f)
                        val bottom = tp.yDirAdj + size * 0.22f
                        if (sb.isEmpty()) {
                            l = x0; t = top; r = x1; bt = bottom
                        } else {
                            l = min(l, x0); t = min(t, top); r = max(r, x1); bt = max(bt, bottom)
                        }
                        sb.append(s)
                    }
                    flush()
                }
            }
            stripper.sortByPosition = true
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            stripper.getText(doc)
            return out.take(MAX_WORDS)
        }

        fun readLinks(doc: PDDocument, page: PDPage, b: Box): List<PdfLink> =
            page.annotations.filterIsInstance<PDAnnotationLink>().mapNotNull { link -> runCatching { readLink(doc, link, b) }.getOrNull() }.take(MAX_LINKS)

        fun readLink(doc: PDDocument, link: PDAnnotationLink, b: Box): PdfLink? {
                val r = link.rectangle ?: return null
                val (u1, v1) = unit(b, r.lowerLeftX, r.lowerLeftY)
                val (u2, v2) = unit(b, r.upperRightX, r.upperRightY)
                val left = min(u1, u2)
                val top = min(v1, v2)
                val right = max(u1, u2)
                val bottom = max(v1, v2)
                return when (val action = link.action) {
                    is PDActionURI -> action.uri?.takeIf { it.isNotBlank() }?.let { PdfLink(left, top, right, bottom, url = it) }
                    is PDActionGoTo -> target(doc, action.destination)?.let { (p, y) -> PdfLink(left, top, right, bottom, p, y) }
                    else -> target(doc, link.destination)?.let { (p, y) -> PdfLink(left, top, right, bottom, p, y) }
                }
        }

        /** Page (from 0) and the place down it (0…1) a destination goes to. */
        fun target(doc: PDDocument, d: PDDestination?): Pair<Int, Float>? {
            val pd = when (d) {
                is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(d)
                is PDPageDestination -> d
                else -> null
            } ?: return null
            var index = pd.retrievePageNumber()
            if (index < 0) index = pd.page?.let { doc.pages.indexOf(it) } ?: -1
            if (index !in 0 until doc.numberOfPages) return null
            val top = (pd as? PDPageXYZDestination)?.top ?: -1
            if (top < 0) return index to 0f
            val b = box(doc.getPage(index))
            val v = ((b.lly + b.h - top) / b.h).coerceIn(0f, 1f)
            val y = when (b.rotation) {
                0 -> v
                180 -> 1 - v
                else -> 0f
            }
            return index to y
        }

        fun readOutline(doc: PDDocument): List<PdfOutlineItem> {
            val root = doc.documentCatalog.documentOutline ?: return emptyList()
            val out = ArrayList<PdfOutlineItem>()
            fun walk(node: PDOutlineNode, depth: Int) {
                for (item in node.children()) {
                    if (out.size >= MAX_OUTLINE) return
                    val dest = runCatching { item.destination ?: (item.action as? PDActionGoTo)?.destination }.getOrNull()
                    val t = runCatching { target(doc, dest) }.getOrNull()
                        ?: runCatching { item.findDestinationPage(doc)?.let { doc.pages.indexOf(it) }?.takeIf { it >= 0 }?.let { it to 0f } }.getOrNull()
                    val title = runCatching { item.title }.getOrNull()?.trim().orEmpty()
                    if (t != null && title.isNotEmpty()) out += PdfOutlineItem(title, t.first, t.second, depth)
                    if (depth < MAX_DEPTH) walk(item, depth + 1)
                }
            }
            walk(root, 0)
            return out
        }
    }

    companion object {
        /** The contents of the PDF [file], on the calling thread (not the main one); empty when none. */
        fun outlineOf(context: Context, file: File): List<PdfOutlineItem> = runCatching {
            PDFBoxResourceLoader.init(context.applicationContext)
            PDDocument.load(file).use { doc -> Reader.readOutline(doc) }
        }.getOrDefault(emptyList())

        const val TAG = "InkPdf"
        const val MAX_WORDS = 20_000
        const val MAX_LINKS = 500
        const val MAX_OUTLINE = 1_000
        const val MAX_DEPTH = 6
    }
}
