package dev.inknotes.data

import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import dev.inknotes.editor.PageEditorView
import dev.inknotes.editor.PageRenderer
import java.io.OutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Writes a whole notebook as PDF in its light-theme look. Ink, fills and ruling stay vector;
 * imported PDF pages and photo backgrounds are embedded as images. Blocking: call off the
 * main thread.
 */
object PdfExporter {

    /** Imported PDF pages are rasterised at this many pixels per point (~216 dpi). */
    private const val PDF_PAGE_DENSITY = 3f
    private const val PAGE_IMAGE_PIXELS = 32.0 * 1024 * 1024
    private const val MIN_IMAGE_SIDE = 128
    private const val MAX_IMAGE_SIDE = 2048

    fun export(repo: NotebookRepository, nb: Notebook, out: OutputStream, onProgress: (done: Int, total: Int) -> Unit) {
        val doc = PdfDocument()
        try {
            write(repo, nb, doc, onProgress)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    private fun write(repo: NotebookRepository, nb: Notebook, doc: PdfDocument, onProgress: (done: Int, total: Int) -> Unit) {
        val renderer = PageRenderer().apply {
            dark = false
            paper = nb.light
            softGlow = false
            fillLayers = false
            photo = nb.light.photo?.let { repo.loadPhoto(nb, it) }
        }
        PdfPages(repo).use { pdfPages ->
            nb.pageIds.forEachIndexed { i, pageId ->
                val ref = nb.pdfPages[pageId]
                val size = nb.pageSize(pageId)
                val widthPt = ref?.width ?: (size.widthMm / Notebook.MM_PER_POINT)
                val heightPt = ref?.height ?: (size.heightMm / Notebook.MM_PER_POINT)
                // Hidden layers stay out of the PDF, as they are off the screen.
                val content = Layers.visible(repo.loadPage(nb, pageId), nb.hiddenLayers())
                val page = doc.startPage(PdfDocument.PageInfo.Builder(widthPt.roundToInt(), heightPt.roundToInt(), i + 1).create())
                val widthUnits = nb.pageWidthUnits(pageId)
                val k = widthPt / widthUnits
                val toPage = Matrix().apply { setScale(k, k) }
                page.canvas.concat(toPage)
                renderer.pageWidth = widthUnits
                renderer.pageHeight = nb.pageHeightUnits(pageId)
                renderer.pdf = ref?.let { pdfPages.renderBlocking(nb, it, (widthPt * PDF_PAGE_DENSITY).toInt()) }
                renderer.drawBackground(page.canvas, edgeWidth = 0f)
                // Tell the ink renderer the output is print resolution so curves stay smooth.
                val detail = Matrix(toPage).apply { postScale(4f, 4f) }
                // All pictures of a page live until the page is finished: keep their total memory
                // bounded (~128 MB) however many there are, by decoding smaller when there are many.
                val names = content.images.map { it.file }.distinct()
                val side = if (names.isEmpty()) 0 else sqrt(PAGE_IMAGE_PIXELS / names.size).toInt().coerceIn(MIN_IMAGE_SIDE, MAX_IMAGE_SIDE)
                val pictures = names.associateWith { repo.loadImage(nb, it, side) }
                renderer.drawContent(page.canvas, content.strokes, content.fills, detail, content.images, loadImage = { pictures[it] })
                pictures.values.forEach { it?.recycle() }
                doc.finishPage(page)
                renderer.pdf?.recycle()
                renderer.pdf = null
                onProgress(i + 1, nb.pageIds.size)
            }
        }
    }
}
