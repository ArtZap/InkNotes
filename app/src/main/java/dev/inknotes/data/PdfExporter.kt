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

    /**
     * The whole PDF is built in memory before it is written out ([PdfDocument] can't stream),
     * and imported PDF pages are its big part. Up to this many of them keep [PDF_PAGE_DENSITY];
     * more are rasterised less finely, so a book of hundreds of pages fits (down to
     * [MIN_PDF_PAGE_DENSITY], ~110 dpi, still sharp in print).
     */
    private const val FULL_DENSITY_PDF_PAGES = 60
    private const val MIN_PDF_PAGE_DENSITY = 1.5f
    private const val PAGE_IMAGE_PIXELS = 32.0 * 1024 * 1024
    private const val MIN_IMAGE_SIDE = 128
    private const val MAX_IMAGE_SIDE = 2048

    /** PNG pixels per PDF point (~180 dpi), and the longest side of a PNG page. */
    private const val PNG_DENSITY = 2.5f
    const val PNG_MAX_SIDE = 3600

    /**
     * Writes [pages] as one PDF. Returns the numbers (from 1) of pages that could not be read
     * fully and went out empty or without their PDF page: the caller says so instead of a
     * plain "saved".
     */
    fun export(
        repo: NotebookRepository,
        nb: Notebook,
        out: OutputStream,
        pages: List<Int> = nb.pageIds.indices.toList(),
        onProgress: (done: Int, total: Int) -> Unit,
    ): List<Int> {
        val doc = PdfDocument()
        try {
            val missing = write(repo, nb, doc, pages, onProgress)
            doc.writeTo(out)
            return missing
        } finally {
            doc.close()
        }
    }

    /** Pixels per point for imported PDF pages when [count] of them go into one file. */
    fun pdfPageDensity(count: Int): Float =
        if (count <= FULL_DENSITY_PDF_PAGES) PDF_PAGE_DENSITY else (PDF_PAGE_DENSITY * sqrt(FULL_DENSITY_PDF_PAGES.toFloat() / count)).coerceAtLeast(MIN_PDF_PAGE_DENSITY)

    /** Size of page [index] in PDF points (1/72 inch). */
    fun pagePoints(nb: Notebook, index: Int): Pair<Float, Float> {
        val pageId = nb.pageIds[index]
        val ref = nb.pdfPages[pageId]
        val size = nb.pageSize(pageId)
        return (ref?.outerWidth ?: (size.widthMm / Notebook.MM_PER_POINT)) to (ref?.outerHeight ?: (size.heightMm / Notebook.MM_PER_POINT))
    }

    private fun renderer(repo: NotebookRepository, nb: Notebook) = PageRenderer().apply {
        dark = false
        paper = nb.light
        softGlow = false
        fillLayers = false
        photo = nb.light.photo?.let { repo.loadPhoto(nb, it) }
    }

    private fun write(repo: NotebookRepository, nb: Notebook, doc: PdfDocument, pages: List<Int>, onProgress: (done: Int, total: Int) -> Unit): List<Int> {
        val renderer = renderer(repo, nb)
        val density = pdfPageDensity(pages.count { nb.pdfPages.containsKey(nb.pageIds[it]) })
        val missing = ArrayList<Int>()
        PdfPages(repo).use { pdfPages ->
            pages.forEachIndexed { k, i ->
                val (widthPt, heightPt) = pagePoints(nb, i)
                val page = doc.startPage(PdfDocument.PageInfo.Builder(widthPt.roundToInt(), heightPt.roundToInt(), k + 1).create())
                val k0 = widthPt / nb.pageWidthUnits(nb.pageIds[i])
                // Tell the ink renderer the output is print resolution so curves stay smooth.
                if (!drawPage(repo, nb, i, page.canvas, renderer, pdfPages, k0, detail = 4f, pdfWidthPx = (widthPt * density).toInt())) missing += i + 1
                doc.finishPage(page)
                onProgress(k + 1, pages.size)
            }
        }
        return missing
    }

    /**
     * Page [index] as a PNG picture, about 180 dpi (at most [PNG_MAX_SIDE] pixels on a side).
     * False if the page could not be read fully (see [export]).
     */
    fun writePng(repo: NotebookRepository, nb: Notebook, index: Int, out: OutputStream): Boolean {
        val (widthPt, heightPt) = pagePoints(nb, index)
        var density = PNG_DENSITY
        val longest = max(widthPt, heightPt) * density
        if (longest > PNG_MAX_SIDE) density *= PNG_MAX_SIDE / longest
        val bitmap = Bitmap.createBitmap(max(1, (widthPt * density).roundToInt()), max(1, (heightPt * density).roundToInt()), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(android.graphics.Color.WHITE)
            val k = bitmap.width / nb.pageWidthUnits(nb.pageIds[index])
            val complete = PdfPages(repo).use { pdfPages ->
                drawPage(repo, nb, index, canvas, renderer(repo, nb), pdfPages, k, detail = 1f, pdfWidthPx = bitmap.width)
            }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            return complete
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Page [index] drawn on [canvas] at [scale] output units per page unit. False if its
     * writing could not be read or its PDF page could not be drawn (it is drawn without them).
     */
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
    ): Boolean {
        val pageId = nb.pageIds[index]
        val ref = nb.pdfPages[pageId]
        // Hidden layers stay out, as they are off the screen.
        val read = repo.readPage(nb, pageId)
        val content = Layers.visible(read ?: PageContent.EMPTY, nb.hiddenLayers())
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
        val pdfOk = ref == null || renderer.pdf != null
        renderer.pdf?.recycle()
        renderer.pdf = null
        return read != null && pdfOk
    }
}
