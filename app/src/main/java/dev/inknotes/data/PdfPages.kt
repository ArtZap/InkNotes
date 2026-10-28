package dev.inknotes.data

import android.graphics.Bitmap
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

    /** Renders on the background thread and delivers on the main thread (null on failure). */
    fun renderAsync(nb: Notebook, ref: PdfPageRef, widthPx: Int, onDone: (Bitmap?) -> Unit) {
        val key = "${ref.file}#${ref.index}@$widthPx"
        cache.get(key)?.let { return onDone(it) }
        exec.execute {
            val bitmap = runCatching { render(nb, ref, widthPx) }.getOrNull()
            if (bitmap != null) cache.put(key, bitmap)
            main.post { onDone(bitmap) }
        }
    }

    /** Blocking variant for the exporter; must not be called on the main thread. */
    fun renderBlocking(nb: Notebook, ref: PdfPageRef, widthPx: Int): Bitmap? =
        exec.submit<Bitmap?> { runCatching { render(nb, ref, widthPx) }.getOrNull() }.get()

    /**
     * Part of a page, sharp at the zoom it is shown at: the page [fullWidthPx] wide, the
     * [w]×[h] pixels from ([left], [top]). Blocking; must not be called on the main thread.
     */
    fun renderRegionBlocking(nb: Notebook, ref: PdfPageRef, fullWidthPx: Float, left: Float, top: Float, w: Int, h: Int): Bitmap? {
        if (w < 1 || h < 1 || w.toLong() * h > MAX_PIXELS || w > MAX_SIDE || h > MAX_SIDE || !fullWidthPx.isFinite()) return null
        return runCatching {
            exec.submit<Bitmap?> {
                runCatching {
                    val pdf = renderer(nb, ref)
                    pdf.openPage(ref.index).use { page ->
                        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        val k = fullWidthPx / page.width.coerceAtLeast(1)
                        val m = Matrix().apply {
                            setScale(k, k)
                            postTranslate(-left, -top)
                        }
                        page.render(bitmap, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
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

    private fun render(nb: Notebook, ref: PdfPageRef, widthPx: Int): Bitmap {
        val pdf = renderer(nb, ref)
        pdf.openPage(ref.index).use { page ->
            // The page's own shape, whatever a (possibly shared) notebook.json says, and never
            // more pixels than a big screen page: huge drawings would not fit in memory.
            val aspect = page.height.toFloat() / page.width.coerceAtLeast(1)
            var w = widthPx.coerceAtLeast(1)
            if (w.toLong() * (w * aspect).toLong() > MAX_PIXELS) w = sqrt(MAX_PIXELS / aspect.toDouble()).toInt().coerceAtLeast(1)
            val heightPx = (w * aspect).toInt().coerceIn(1, MAX_SIDE)
            val bitmap = Bitmap.createBitmap(w, heightPx, Bitmap.Config.ARGB_8888)
            // PDF pages are transparent where nothing is painted; paper is white.
            bitmap.eraseColor(Color.WHITE)
            val k = w.toFloat() / page.width
            page.render(bitmap, null, Matrix().apply { setScale(k, k) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            return bitmap
        }
    }

    private companion object {
        const val CACHE_BYTES = 64 * 1024 * 1024

        /** About 24 megapixels (~96 MB) at most per rendered page, and no side over 16k. */
        const val MAX_PIXELS = 24_000_000L
        const val MAX_SIDE = 16_384
    }
}
