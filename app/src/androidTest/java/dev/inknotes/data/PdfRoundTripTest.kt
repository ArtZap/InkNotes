package dev.inknotes.data

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Import a generated PDF, write on it, export, and read the exported PDF back. */
@RunWith(AndroidJUnit4::class)
class PdfRoundTripTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = NotebookRepository(File(context.cacheDir, "pdf-test").apply { deleteRecursively() })

    @After
    fun cleanUp() {
        File(context.cacheDir, "pdf-test").deleteRecursively()
    }

    @Test
    fun importWriteExport() {
        val source = File(context.cacheDir, "source.pdf")
        writeSourcePdf(source)

        val latch = CountDownLatch(1)
        var imported: Notebook? = null
        repo.importPdfAsync("Лекция", Uri.fromFile(source), context.contentResolver) {
            imported = it
            latch.countDown()
        }
        assertTrue(latch.await(20, TimeUnit.SECONDS))
        val nb = assertNotNull(imported).let { imported!! }
        assertEquals(2, nb.pageIds.size)
        val slide = nb.pdfPages.getValue(nb.pageIds[1])
        assertEquals(960f, slide.width, 0.5f)
        assertEquals(540f, slide.height, 0.5f)

        // Ink line across the A4 page at y = 700 (page units) and a hatched square below it.
        val page1 = nb.pageIds[0]
        val fill = PageFill(floatArrayOf(100f, 900f, 400f, 900f, 400f, 1200f, 100f, 1200f), FillPattern.CROSS, 0xFFD32F2F.toInt())
        repo.savePageAsync(nb, page1, PageContent(listOf(line(100f, 700f, 900f)), listOf(fill)))

        val out = File(context.cacheDir, "export.pdf")
        var progress = 0
        out.outputStream().use { PdfExporter.export(repo, repo.list().first { it.id == nb.id }, it) { done, _ -> progress = done } }
        assertEquals(2, progress)

        ParcelFileDescriptor.open(out, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { pdf ->
                assertEquals(2, pdf.pageCount)
                pdf.openPage(1).use { assertEquals(960, it.width); assertEquals(540, it.height) }
                val bmp = pdf.openPage(0).use { page ->
                    Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888).also {
                        it.eraseColor(Color.WHITE)
                        page.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
                File(context.getExternalFilesDir(null), "test-export-page1.png").outputStream().use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                // Page units → bitmap pixels: 1000 units = page width.
                val k = bmp.width / 1000f
                val inkRow = (700 * k).toInt()
                val darkOnInkRow = (0 until bmp.width).count { x -> isDark(bmp.getPixel(x, inkRow)) }
                assertTrue("ink line missing from export ($darkOnInkRow dark px)", darkOnInkRow > bmp.width / 3)
                val redInFill = (0 until 200).count { i ->
                    val c = bmp.getPixel(((110 + i) * k).toInt(), ((910 + i) * k).toInt())
                    Color.red(c) > 150 && Color.green(c) < 120
                }
                assertTrue("hatch missing from export ($redInFill red px)", redInFill > 10)
                // Text from the source PDF survives (dark pixels in the title area).
                val titleArea = (0 until bmp.width / 2).count { x -> isDark(bmp.getPixel(x, (120 * k).toInt())) }
                assertTrue("source PDF content missing ($titleArea)", titleArea > 0)
            }
        }
    }

    private fun isDark(c: Int) = Color.red(c) < 90 && Color.green(c) < 90 && Color.blue(c) < 90

    private fun line(x0: Float, y: Float, x1: Float): PageStroke {
        val batch = MutableStrokeInputBatch()
        for (i in 0..40) batch.add(InputToolType.STYLUS, x0 + (x1 - x0) * i / 40, y, i * 8L)
        return PageStroke(InkKind.PEN, Stroke(InkBrushes.create(InkKind.PEN, Color.BLACK, 4f), batch))
    }

    /** Page 1: A4 with a heading bar; page 2: a 16:9 slide. */
    private fun writeSourcePdf(file: File) {
        val doc = PdfDocument()
        val paint = Paint().apply { color = Color.BLACK; textSize = 28f }
        doc.startPage(PdfDocument.PageInfo.Builder(595, 842, 1).create()).also { p ->
            p.canvas.drawRect(40f, 60f, 300f, 90f, paint)
            p.canvas.drawText("Lecture 1", 40f, 140f, paint)
            doc.finishPage(p)
        }
        doc.startPage(PdfDocument.PageInfo.Builder(960, 540, 2).create()).also { p ->
            p.canvas.drawColor(0xFF2B4C7E.toInt())
            doc.finishPage(p)
        }
        file.outputStream().use(doc::writeTo)
        doc.close()
    }
}
