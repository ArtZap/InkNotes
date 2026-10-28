package dev.inknotes.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import dev.inknotes.data.Notebook
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PaperKind
import dev.inknotes.data.PdfPageRef
import dev.inknotes.editor.PageRenderer
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Small pictures of notebooks' first pages for the glass covers of the library. Rendered one at
 * a time on a low-priority thread and kept while the notebook is unchanged, so scrolling the
 * library only draws bitmaps.
 */
class CoverPreviews(private val repo: NotebookRepository) {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "cover-previews").apply { priority = Thread.MIN_PRIORITY } }
    private val main = Handler(Looper.getMainLooper())
    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    // Main thread only.
    private val waiting = HashMap<String, MutableList<(ImageBitmap?) -> Unit>>()
    private val latest = HashMap<String, String>()

    /** Used only on [exec]. */
    private val renderer by lazy { PageRenderer() }

    fun key(nb: Notebook, dark: Boolean): String =
        "${nb.id}|${nb.updatedAt}|${nb.pageIds.firstOrNull()}|$dark|${nb.background(dark).hashCode()}"

    /** The current picture, or the one before the notebook changed while the new one renders. */
    fun cached(nb: Notebook, dark: Boolean): ImageBitmap? =
        cache.get(key(nb, dark)) ?: latest[nb.id]?.let { cache.get(it) }

    /** Main thread; [onDone] is called on the main thread (null when the page can't be drawn). */
    fun request(nb: Notebook, dark: Boolean, onDone: (ImageBitmap?) -> Unit) {
        val key = key(nb, dark)
        cache.get(key)?.let { return onDone(it) }
        waiting[key]?.let {
            it += onDone
            return
        }
        waiting[key] = mutableListOf(onDone)
        runCatching {
            exec.execute {
                // A broken page costs its preview, never the app.
                val image = runCatching { render(nb, dark) }.getOrNull()?.asImageBitmap()
                main.post {
                    if (image != null) {
                        cache.put(key, image)
                        latest[nb.id] = key
                    }
                    waiting.remove(key)?.forEach { it(image) }
                }
            }
        }.onFailure { waiting.remove(key) }
    }

    private fun render(nb: Notebook, dark: Boolean): Bitmap? {
        val pageId = nb.pageIds.firstOrNull() ?: return null
        val content = repo.loadPage(nb, pageId)
        val pageWidth = nb.pageWidthUnits(pageId)
        val pageHeight = nb.pageHeightUnits(pageId)
        val k = WIDTH / pageWidth
        // Only the top of the page shows on a cover; a long page isn't drawn whole.
        val height = min((pageHeight * k).toInt(), (WIDTH / COVER_ASPECT).toInt() + 1).coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(WIDTH, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val scale = Matrix().apply { setScale(k, k) }
        canvas.concat(scale)
        val bg = nb.background(dark)
        val pdf = nb.pdfPages[pageId]?.let { runCatching { pdf(nb, it) }.getOrNull() }
        try {
            renderer.apply {
                this.dark = dark
                paper = bg
                photo = if (bg.kind == PaperKind.PHOTO) bg.photo?.let { repo.loadPhoto(nb, it, WIDTH * 2) } else null
                this.pdf = pdf
                this.pageHeight = pageHeight
                this.pageWidth = pageWidth
                drawBackground(canvas, edgeWidth = 0f)
                drawContent(canvas, content.strokes, content.fills, scale, content.images) { file -> repo.loadImage(nb, file, IMAGE_SIDE) }
            }
        } finally {
            renderer.photo?.recycle()
            renderer.photo = null
            renderer.pdf = null
            pdf?.recycle()
        }
        return bitmap
    }

    /** The PDF page under the first page, opened just for this (the library keeps no PDFs open). */
    private fun pdf(nb: Notebook, ref: PdfPageRef): Bitmap? {
        val file = repo.pdfFile(nb, ref.file)?.takeIf { it.isFile } ?: return null
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                if (ref.index !in 0 until pdf.pageCount) return null
                pdf.openPage(ref.index).use { page ->
                    val h = (WIDTH * page.height.toFloat() / page.width).toInt().coerceIn(1, WIDTH * 4)
                    val bitmap = Bitmap.createBitmap(WIDTH, h, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    val k = WIDTH.toFloat() / page.width
                    page.render(bitmap, null, Matrix().apply { setScale(k, k) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    return bitmap
                }
            }
        }
    }

    private companion object {
        const val WIDTH = 300
        const val IMAGE_SIDE = 256
        const val COVER_ASPECT = 0.72f
        const val CACHE_BYTES = 24 * 1024 * 1024
    }
}

/** First page of [nb] for its glass cover; null until it is drawn (the cover shows plain colour). */
@Composable
fun rememberFirstPage(nb: Notebook, dark: Boolean): ImageBitmap? {
    val previews = AppServices.get(LocalContext.current).previews
    val key = previews.key(nb, dark)
    var image by remember(nb.id, dark) { mutableStateOf(previews.cached(nb, dark)) }
    LaunchedEffect(key) { previews.request(nb, dark) { if (it != null) image = it } }
    return image
}
