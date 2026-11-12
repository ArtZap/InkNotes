package dev.inknotes.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PdfText
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Pictures of what 0.34 adds: PDF text picked with the pen and marked, the highlighter laid
 * along a line, the PDF page dialog, a turned page with a margin, the dark PDF. Like
 * [BarsScreenshots], for looking at, not a check.
 */
@RunWith(AndroidJUnit4::class)
class V034Screenshots {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(1200)
        device.takeScreenshot(File(dir, "$name.png"))
    }

    private fun click(text: String, timeout: Long = 15_000) {
        // The emulator's launcher sometimes stops answering: its dialog covers the screen.
        device.findObject(By.text("Wait"))?.click()
        val o = device.wait(Until.findObject(By.text(text)), timeout)
        if (o == null) {
            shot("fail-$text".replace(Regex("[^\\p{L}0-9-]"), "_"))
            error("no «$text» on screen")
        }
        o.click()
    }

    /** An item of the "⋮" menu, scrolled to. */
    private fun menu(title: String) {
        device.findObject(By.desc("Ещё"))?.click()
        device.wait(Until.hasObject(By.text("Настроить панели…")), 5_000)
        // From the top, in small steps: a big one can pass the item.
        device.findObject(By.scrollable(true))?.let { list -> repeat(4) { list.scroll(Direction.UP, 1f) } }
        repeat(16) {
            if (device.hasObject(By.text(title))) return@repeat
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.4f)
        }
        click(title)
        // Until the menu is gone, a touch only closes it.
        device.wait(Until.gone(By.text("Настроить панели…")), 5_000)
        Thread.sleep(1000)
    }

    private fun stylus(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 400) {
        instr.uiAutomation.executeShellCommand("input stylus swipe $x1 $y1 $x2 $y2 $ms").close()
        Thread.sleep(ms + 500L)
    }

    @Before
    fun onlyWhenAsked() = assumeTrue(InstrumentationRegistry.getArguments().getString("shots") == "1")

    @Test
    fun pdfTextPagesDark() {
        val prefs = ctx.getSharedPreferences("app", 0)
        prefs.edit().putBoolean("toolbarIntroShown", true).remove("openTabs").remove("toolbarLayout").putBoolean("pdfDark", false).commit()
        val repo = NotebookRepository(ctx.filesDir)
        if (repo.list().none { it.title == "Скриншоты 0.34" }) {
            val pdf = samplePdf()
            val done = CountDownLatch(1)
            instr.runOnMainSync {
                repo.importPdfAsync("Скриншоты 0.34", Uri.fromFile(pdf), ctx.contentResolver, outline = { PdfText.outlineOf(ctx, it) }) { done.countDown() }
            }
            check(done.await(60, TimeUnit.SECONDS)) { "PDF not imported" }
        }
        device.setOrientationLandscape()
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        click("Скриншоты 0.34", 60_000)
        device.wait(Until.hasObject(By.desc(Pattern.compile("Добавить страницу.*"))), 15_000)
        Thread.sleep(3000)
        shot("31-pdf-editor")

        val w = device.displayWidth
        val h = device.displayHeight

        // Words picked with the pen, then marked.
        menu("Текст PDF")
        stylus((w * 0.2).toInt(), (h * 0.5).toInt(), (w * 0.6).toInt(), (h * 0.62).toInt())
        shot("32-pdf-text-picked")
        click("Маркером")
        shot("33-pdf-text-marked")
        menu("Текст PDF")

        // The highlighter along a line: a slanting pass lands level on the words.
        device.findObject(By.desc("Маркер"))?.click()
        stylus((w * 0.25).toInt(), (h * 0.74).toInt(), (w * 0.55).toInt(), (h * 0.76).toInt())
        shot("34-highlighter-on-line")

        // The PDF page dialog: a margin on the right, a quarter turn.
        menu("Страница PDF…")
        shot("35-pdf-page-dialog")
        click("Справа")
        click("↻ 90°")
        Thread.sleep(1500)
        shot("36-pdf-page-turned")
        click("Готово")
        shot("37-pdf-turned-margin")

        menu("Тёмный PDF")
        Thread.sleep(2000)
        shot("38-pdf-dark")
        menu("Тёмный PDF")
        device.setOrientationNatural()
    }

    /** Two A4 pages of printed text: a heading and lines of words. */
    private fun samplePdf(): File {
        val doc = PdfDocument()
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val words = (
            "Конспект лекции по физике: закон сохранения энергии гласит, что полная механическая энергия " +
                "замкнутой системы тел, между которыми действуют только силы тяготения и упругости, остаётся " +
                "неизменной. Кинетическая энергия равна половине произведения массы на квадрат скорости, " +
                "потенциальная энергия тела над Землёй равна mgh. Energy is conserved in a closed system; " +
                "the sum of kinetic and potential energy stays the same while only conservative forces act."
            ).split(" ")
        for (n in 0 until 2) {
            val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, n + 1).create())
            val c = page.canvas
            text.textSize = 22f
            text.isFakeBoldText = true
            c.drawText("Глава ${n + 1}. Энергия", 50f, 70f, text)
            text.textSize = 13f
            text.isFakeBoldText = false
            var y = 110f
            var k = n * 7
            while (y < 800f) {
                val line = StringBuilder()
                while (true) {
                    val next = words[k % words.size]
                    if (text.measureText("$line $next") > 495f) break
                    if (line.isNotEmpty()) line.append(' ')
                    line.append(next)
                    k++
                }
                c.drawText(line.toString(), 50f, y, text)
                y += 22f
            }
            doc.finishPage(page)
        }
        val file = File(ctx.cacheDir, "sample-0.34.pdf")
        file.outputStream().use(doc::writeTo)
        doc.close()
        return file
    }
}
