package dev.inknotes.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PaperKind
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * Pictures of what 0.32 adds: the zoom window (with pen lines injected as stylus input), the
 * export dialog and the photo editor. Like [BarsScreenshots], for looking at, not a check.
 */
@RunWith(AndroidJUnit4::class)
class V032Screenshots {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(800)
        device.takeScreenshot(File(dir, "$name.png"))
    }

    private fun click(text: String, timeout: Long = 15_000) {
        val o = device.wait(Until.findObject(By.text(text)), timeout)
        if (o == null) {
            shot("fail-$text".replace(Regex("[^\\p{L}0-9-]"), "_"))
            error("no «$text» on screen")
        }
        o.click()
    }

    private fun clickDesc(start: String, timeout: Long = 10_000) {
        val o = device.wait(Until.findObject(By.desc(Pattern.compile(Pattern.quote(start) + ".*"))), timeout)
        if (o == null) {
            shot("fail-desc-$start".replace(Regex("[^\\p{L}0-9-]"), "_"))
            error("no «$start…» on screen")
        }
        o.click()
    }

    private fun stylus(x1: Int, y1: Int, x2: Int, y2: Int, ms: Int = 250) {
        instr.uiAutomation.executeShellCommand("input stylus swipe $x1 $y1 $x2 $y2 $ms").close()
        Thread.sleep(ms + 300L)
    }

    @Test
    fun zoomWindowExportPhotoEditor() {
        val prefs = ctx.getSharedPreferences("app", 0)
        prefs.edit().putBoolean("toolbarIntroShown", true).remove("toolbarLayout").commit()
        val repo = NotebookRepository(ctx.filesDir)
        if (repo.list().none { it.title == "Скриншоты 0.32" }) repo.create("Скриншоты 0.32", PaperKind.LINED)
        device.setOrientationLandscape()
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        click("Скриншоты 0.32", 60_000)
        device.wait(Until.hasObject(By.desc(Pattern.compile("Окно увеличения.*"))), 15_000)
        shot("11-editor")

        // The zoom window: open, write a few large strokes in it.
        clickDesc("Окно увеличения")
        shot("12-zoom-open")
        val w = device.displayWidth
        val h = device.displayHeight
        val y = (h * 0.86).toInt()
        val dy = (h * 0.05).toInt()
        var x = (w * 0.08).toInt()
        val step = (w * 0.05).toInt()
        repeat(6) {
            stylus(x, y - dy, x + step / 2, y + dy)
            stylus(x + step / 2, y + dy, x + step, y - dy)
            x += step + step / 3
        }
        shot("13-zoom-written")
        clickDesc("Окно увеличения")
        shot("14-zoom-closed")

        // Export dialog from the menu.
        device.findObject(By.desc("Ещё"))?.click()
        device.wait(Until.hasObject(By.text("Настроить панели…")), 5_000)
        shot("15-menu")
        // The menu is long: scroll it down until the item shows.
        repeat(6) {
            if (device.hasObject(By.text("Экспорт: PDF, PNG…"))) return@repeat
            device.findObject(By.scrollable(true))?.scroll(androidx.test.uiautomator.Direction.DOWN, 0.8f)
        }
        shot("15b-menu-end")
        click("Экспорт: PDF, PNG…")
        shot("16-export")
        click("Диапазон")
        shot("17-export-range")
        click("PNG, картинки")
        shot("18-export-png")
        click("Отмена")

        // The photo editor with a sheet photographed at a slant.
        val sheet = sampleSheet()
        var activity: ComponentActivity? = null
        instr.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() as? ComponentActivity
        }
        val act = activity ?: error("no activity")
        instr.runOnMainSync {
            act.setContent {
                MaterialTheme {
                    PhotoEditorDialog(sheet, PhotoMode.CORNERS, "Вставить", onCancel = {}, onDone = {})
                }
            }
        }
        device.wait(Until.hasObject(By.text("Выпрямить")), 10_000)
        shot("19-photo-corners")
        click("Перспектива")
        shot("20-photo-tilt")
        click("Убрать фон")
        device.waitForIdle()
        // The step's button has the same words as its tab: the last one is the button.
        device.findObjects(By.text("Убрать фон")).lastOrNull()?.click()
        Thread.sleep(1500)
        shot("21-photo-background")
        click("Документ")
        click("Чёрно-белый")
        Thread.sleep(1500)
        shot("22-photo-document")
        click("Обрезать")
        shot("23-photo-crop")
        device.setOrientationPortrait()
        shot("24-photo-portrait")
        device.setOrientationNatural()
    }

    /** A grey desk with a light sheet lying at a slant, lines of "text" on it. */
    private fun sampleSheet(): Bitmap {
        val bmp = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.rgb(120, 116, 110))
        val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(236, 230, 214) }
        val sheet = Path().apply {
            moveTo(260f, 120f)
            lineTo(980f, 170f)
            lineTo(1060f, 820f)
            lineTo(150f, 780f)
            close()
        }
        c.drawPath(sheet, paper)
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(40, 40, 70)
            strokeWidth = 7f
        }
        for (k in 0 until 8) {
            val t = 0.15f + k * 0.09f
            val x0 = 260f + (150f - 260f) * t + 60f
            val y0 = 120f + (780f - 120f) * t
            val x1 = 980f + (1060f - 980f) * t - 80f
            val y1 = 170f + (820f - 170f) * t
            c.drawLine(x0, y0, x0 + (x1 - x0) * (0.6f + 0.05f * (k % 3)), y0 + (y1 - y0) * (0.6f + 0.05f * (k % 3)), ink)
        }
        return bmp
    }
}
