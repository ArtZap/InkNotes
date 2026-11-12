package dev.inknotes.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import kotlin.math.sqrt
import java.io.Closeable
import java.util.concurrent.Executors

/**
 * Rasterises pages of a notebook's PDFs. PdfRenderer is not thread-safe, so every call
 * runs on one private thread; open renderers are kept until [close].
 */
class PdfPages(private val repo: NotebookRepository) : Closeable {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "pdf-render") }
    private val main = Handler(Looper.getMainLooper())
    private val open = HashMap<String, Pair<ParcelFileDescriptor, PdfRenderer>>()
    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** Renders on the background thread and delivers on the main thread (null on failure); [dark]: see [DARK]. */
    fun renderAsync(nb: Notebook, ref: PdfPageRef, widthPx: Int, dark: Boolean = false, onDone: (Bitmap?) -> Unit) {
        val key = "${ref.file}#${ref.index}@$widthPx/${ref.rotation}/${ref.margins}/$dark"
        cache.get(key)?.let { return onDone(it) }
        exec.execute {
            val bitmap = runCatching { render(nb, ref, widthPx, dark) }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            main.post { onDone(bitmap) }
        }
    }

    /** Blocking variant for the exporter; must not be called on the main thread. */
    fun renderBlocking(nb: Notebook, ref: PdfPageRef, widthPx: Int, dark: Boolean = false): Bitmap? =
        exec.submit<Bitmap?> { runCatching { render(nb, ref, widthPx, dark) }.getOrNull() }.get()

    /**
     * Part of a page, sharp at the zoom it is shown at: the page [fullWidthPx] wide, the
     * [w]×[h] pixels from ([left], [top]). Blocking; must not be called on the main thread.
     */
    fun renderRegionBlocking(nb: Notebook, ref: PdfPageRef, fullWidthPx: Float, left: Float, top: Float, w: Int, h: Int, dark: Boolean = false): Bitmap? {
        if (w < 1 || h < 1 || w.toLong() * h > MAX_PIXELS || w > MAX_SIDE || h > MAX_SIDE || !fullWidthPx.isFinite()) return null
        return runCatching {
            exec.submit<Bitmap?> {
                runCatching {
                    val pdf = renderer(nb, ref)
                    pdf.openPage(ref.index).use { page ->
                        draw(page, ref, w, h, fullWidthPx, left, top, dark)
                    }
                }.getOrNull()
            }.get()
        }.getOrNull()
    }

    override fun close() {
        exec.execute {
            for ((fd, pdf) in open.values) {
                pdf.close()
                fd.close()
            }
            open.clear()
        }
        exec.shutdown()
    }

    private fun renderer(nb: Notebook, ref: PdfPageRef): PdfRenderer =
        open.getOrPut(ref.file) {
            val file = repo.pdfFile(nb, ref.file) ?: error("bad pdf name ${ref.file}")
            val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            val renderer = try {
                PdfRenderer(fd)
            } catch (t: Throwable) {
                fd.close()
                throw t
            }
            fd to renderer
        }.second

    private fun render(nb: Notebook, ref: PdfPageRef, widthPx: Int, dark: Boolean): Bitmap {
        val pdf = renderer(nb, ref)
        pdf.openPage(ref.index).use { page -> return renderPage(page, ref, widthPx, dark) }
    }

    companion object {
        /**
         * The whole notebook page [widthPx] wide: the PDF page as [ref] turns it, white, and
         * see-through margins round it (the paper shows there). Shared with the library covers.
         */
        fun renderPage(page: PdfRenderer.Page, ref: PdfPageRef, widthPx: Int, dark: Boolean = false): Bitmap {
            // The page's own shape, whatever a (possibly shared) notebook.json says, and never
            // more pixels than a big screen page: huge drawings would not fit in memory.
            val r = live(page, ref)
            val (w, heightPx) = PdfGeometry.fitPixels(widthPx, r.outerHeight / r.outerWidth, MAX_PIXELS.toLong(), MAX_SIDE)
            return draw(page, ref, w, heightPx, w.toFloat(), 0f, 0f, dark)
        }

        /** [ref] with the size the page really has. */
        private fun live(page: PdfRenderer.Page, ref: PdfPageRef) =
            ref.copy(width = page.width.coerceAtLeast(1).toFloat(), height = page.height.coerceAtLeast(1).toFloat())

        private val darkPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(PdfWords.darkMatrix())) }

        /** [w]×[h] pixels from ([left], [top]) of the page drawn [fullWidthPx] wide. */
        private fun draw(page: PdfRenderer.Page, ref: PdfPageRef, w: Int, h: Int, fullWidthPx: Float, left: Float, top: Float, dark: Boolean): Bitmap {
            val r = live(page, ref)
            val k = fullWidthPx / r.outerWidth
            val toPx = PdfGeometry.then(PdfGeometry.then(PdfGeometry.pdfToOuter(r.width, r.height, r.rotation, r.margins), PdfGeometry.scale(k, k)), PdfGeometry.translate(-left, -top))
            val m = Matrix().apply { setValues(PdfGeometry.toMatrixValues(toPx)) }
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            // PDF pages are transparent where nothing is painted; their paper is white.
            val box = RectF(0f, 0f, r.width, r.height).also(m::mapRect)
            Canvas(bitmap).drawRect(box, Paint().apply { color = Color.WHITE })
            page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            // With margins: a thin edge shows where the PDF ends and the room for notes begins.
            if (!r.margins.isNone) {
                Canvas(bitmap).drawRect(box, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    color = 0x40000000
                    strokeWidth = (fullWidthPx / 600f).coerceAtLeast(1f)
                })
            }
            if (!dark) return bitmap
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            Canvas(out).drawBitmap(bitmap, 0f, 0f, darkPaint)
            bitmap.recycle()
            return out
        }

        const val CACHE_BYTES = 64 * 1024 * 1024

        /** About 24 megapixels (~96 MB) at most per rendered page, and no side over 16k. */
        const val MAX_PIXELS = 24_000_000L
        const val MAX_SIDE = 16_384
    }
}
