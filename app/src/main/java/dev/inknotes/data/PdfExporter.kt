package dev.inknotes.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.pdf.PdfDocument
import dev.inknotes.editor.PageRenderer
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Writes a notebook (all its pages or some of them) as PDF or a page as PNG, in its
 * light-theme look. In PDF, ink, fills and ruling stay vector; imported PDF pages and photo
 * backgrounds are embedded as images. Blocking: call off the main thread.
 */
object PdfExporter {

    /** Imported PDF pages are rasterised at this many pixels per point (~216 dpi). */
    private const val PDF_PAGE_DENSITY = 3f
    private const val PAGE_IMAGE_PIXELS = 32.0 * 1024 * 1024
    private const val MIN_IMAGE_SIDE = 128
    private const val MAX_IMAGE_SIDE = 2048

    /** PNG pixels per PDF point (~180 dpi), and the longest side of a PNG page. */
    private const val PNG_DENSITY = 2.5f
    const val PNG_MAX_SIDE = 3600

    fun export(
        repo: NotebookRepository,
        nb: Notebook,
        out: OutputStream,
        pages: List<Int> = nb.pageIds.indices.toList(),
        onProgress: (done: Int, total: Int) -> Unit,
    ) {
        val doc = PdfDocument()
        try {
            write(repo, nb, doc, pages, onProgress)
            doc.writeTo(out)
        } finally {
            doc.close()
        }
    }

    /** Size of page [index] in PDF points (1/72 inch). */
    fun pagePoints(nb: Notebook, index: Int): Pair<Float, Float> {
        val pageId = nb.pageIds[index]
        val ref = nb.pdfPages[pageId]
        val size = nb.pageSize(pageId)
        return (ref?.width ?: (size.widthMm / Notebook.MM_PER_POINT)) to (ref?.height ?: (size.heightMm / Notebook.MM_PER_POINT))
    }

    private fun renderer(repo: NotebookRepository, nb: Notebook) = PageRenderer().apply {
        dark = false
        paper = nb.light
        softGlow = false
        fillLayers = false
        photo = nb.light.photo?.let { repo.loadPhoto(nb, it) }
    }

    private fun write(repo: NotebookRepository, nb: Notebook, doc: PdfDocument, pages: List<Int>, onProgress: (done: Int, total: Int) -> Unit) {
        val renderer = renderer(repo, nb)
        PdfPages(repo).use { pdfPages ->
            pages.forEachIndexed { k, i ->
                val (widthPt, heightPt) = pagePoints(nb, i)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(widthPt.roundToInt(), heightPt.roundToInt(), k + 1).create())
                val k0 = widthPt / nb.pageWidthUnits(nb.pageIds[i])
                // Tell the ink renderer the output is print resolution so curves stay smooth.
                drawPage(repo, nb, i, page.canvas, renderer, pdfPages, k0, detail = 4f, pdfWidthPx = (widthPt * PDF_PAGE_DENSITY).toInt())
                doc.finishPage(page)
                onProgress(k + 1, pages.size)
            }
        }
    }

    /** Page [index] as a PNG picture, about 180 dpi (at most [PNG_MAX_SIDE] pixels on a side). */
    fun writePng(repo: NotebookRepository, nb: Notebook, index: Int, out: OutputStream) {
        val (widthPt, heightPt) = pagePoints(nb, index)
        var density = PNG_DENSITY
        val longest = max(widthPt, heightPt) * density
        if (longest > PNG_MAX_SIDE) density *= PNG_MAX_SIDE / longest
        val bitmap = Bitmap.createBitmap(max(1, (widthPt * density).roundToInt()), max(1, (heightPt * density).roundToInt()), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val k = bitmap.width / nb.pageWidthUnits(nb.pageIds[index])
            PdfPages(repo).use { pdfPages ->
                drawPage(repo, nb, index, canvas, renderer(repo, nb), pdfPages, k, detail = 1f, pdfWidthPx = bitmap.width)
            }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        } finally {
            bitmap.recycle()
        }
    }

    /** Page [index] drawn on [canvas] at [scale] output units per page unit. */
    private fun drawPage(
        repo: NotebookRepository,
        nb: Notebook,
        index: Int,
        canvas: Canvas,
        renderer: PageRenderer,
        pdfPages: PdfPages,
        scale: Float,
        detail: Float,
        pdfWidthPx: Int,
    ) {
        val pageId = nb.pageIds[index]
        val ref = nb.pdfPages[pageId]
        // Hidden layers stay out, as they are off the screen.
        val content = Layers.visible(repo.loadPage(nb, pageId), nb.hiddenLayers())
        val toPage = Matrix().apply { setScale(scale, scale) }
        canvas.save()
        canvas.concat(toPage)
        renderer.pageWidth = nb.pageWidthUnits(pageId)
        renderer.pageHeight = nb.pageHeightUnits(pageId)
        renderer.pdf = ref?.let { pdfPages.renderBlocking(nb, it, pdfWidthPx) }
        renderer.drawBackground(canvas, edgeWidth = 0f)
        val detailMatrix = Matrix(toPage).apply { postScale(detail, detail) }
        // All pictures of a page live until the page is finished: keep their total memory
        // bounded (~128 MB) however many there are, by decoding smaller when there are many.
        val names = content.images.map { it.file }.distinct()
        val side = if (names.isEmpty()) 0 else sqrt(PAGE_IMAGE_PIXELS / names.size).toInt().coerceIn(MIN_IMAGE_SIDE, MAX_IMAGE_SIDE)
        val pictures = names.associateWith { repo.loadImage(nb, it, side) }
        renderer.drawContent(canvas, content.strokes, content.fills, detailMatrix, content.images, loadImage = { pictures[it] })
        pictures.values.forEach { it?.recycle() }
        canvas.restore()
        renderer.pdf?.recycle()
        renderer.pdf = null
    }
}
