package dev.inknotes.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PageSize
import dev.inknotes.data.PaperKind
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** The canvas of 0.38: chosen for a new notebook, written up to its edge, grown, panned to the new room. */
@RunWith(AndroidJUnit4::class)
class V038Screenshots {
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
        device.findObject(By.text("Wait"))?.click()
        val o = device.wait(Until.findObject(By.text(text)), timeout)
        if (o == null) {
            shot("fail-$text".replace(Regex("[^\\p{L}0-9-]"), "_"))
            error("no «$text» on screen")
        }
        o.click()
    }

    private fun menu(title: String, index: Int = 0) {
        device.findObjects(By.desc("Ещё")).getOrNull(index)?.click()
        device.wait(Until.hasObject(By.text("Настроить панели…")), 5_000)
        device.findObject(By.scrollable(true))?.let { list -> repeat(4) { list.scroll(Direction.UP, 1f) } }
        repeat(16) {
            if (device.hasObject(By.text(title))) return@repeat
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.4f)
        }
        click(title)
        device.wait(Until.gone(By.text("Настроить панели…")), 5_000)
        Thread.sleep(1000)
    }

    private fun shell(cmd: String, wait: Long = 700) {
        instr.uiAutomation.executeShellCommand(cmd).close()
        Thread.sleep(wait)
    }

    @Before
    fun onlyWhenAsked() = assumeTrue(InstrumentationRegistry.getArguments().getString("shots") == "1")

    @Test
    fun canvasGrows() {
        ctx.getSharedPreferences("app", 0).edit().putBoolean("toolbarIntroShown", true).remove("toolbarLayout").remove("openTabs").commit()
        val repo = NotebookRepository(ctx.filesDir)
        repo.list().filter { it.title == "Холст 0.38" }.forEach(repo::delete)
        repo.create("Холст 0.38", PaperKind.DOTS, pageSize = PageSize.CANVAS)
        device.setOrientationLandscape()
        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))

        // The choice in a new notebook's settings.
        click("Тетрадь", 60_000)
        click("Дополнительные настройки ▾")
        click("Холст ∞")
        shot("61-new-canvas-choice")
        device.pressBack()
        Thread.sleep(800)

        click("Холст 0.38", 30_000)
        device.wait(Until.hasObject(By.desc(Pattern.compile("Добавить страницу.*"))), 15_000)
        Thread.sleep(1500)
        val w = device.displayWidth
        val h = device.displayHeight
        shot("62-canvas-new")
        // Lines written up to the right edge: the canvas grows past them, the zoom stays.
        shell("input stylus swipe ${(w * 0.35).toInt()} ${(h * 0.45).toInt()} ${(w * 0.93).toInt()} ${(h * 0.5).toInt()} 600", 1500)
        shell("input stylus swipe ${(w * 0.35).toInt()} ${(h * 0.55).toInt()} ${(w * 0.93).toInt()} ${(h * 0.6).toInt()} 600", 2500)
        shot("63-canvas-grown")
        // The new room, brought in with a finger, and written on.
        shell("input swipe ${(w * 0.8).toInt()} ${(h * 0.75).toInt()} ${(w * 0.3).toInt()} ${(h * 0.75).toInt()} 500", 1500)
        shell("input stylus swipe ${(w * 0.3).toInt()} ${(h * 0.5).toInt()} ${(w * 0.6).toInt()} ${(h * 0.4).toInt()} 500", 2500)
        shot("64-canvas-panned")
        device.setOrientationNatural()
    }
}
