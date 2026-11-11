package dev.inknotes.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PaperKind
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Pictures of the editor's bars, menu and their settings, for checking the look on an
 * emulator (CI saves them as an artifact). Not a check of behaviour: it fails only when a
 * screen does not appear. Run on its own: `-e class dev.inknotes.ui.BarsScreenshots`.
 */
@RunWith(AndroidJUnit4::class)
class BarsScreenshots {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(700)
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

    @Test
    fun barsMenuAndSettings() {
        // As on a first start after the update: the choice of a ready-made layout comes up.
        ctx.getSharedPreferences("app", 0).edit().remove("toolbarIntroShown").remove("toolbarLayout").commit()
        val repo = NotebookRepository(ctx.filesDir)
        if (repo.list().none { it.title == "Скриншоты" }) repo.create("Скриншоты", PaperKind.GRID)
        device.setOrientationLandscape()
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        click("Скриншоты", 60_000)
        device.wait(Until.hasObject(By.text("Какие инструменты показывать?")), 10_000)
        shot("01-intro")
        click("Оставить как есть")
        shot("02-editor-full")
        device.findObject(By.desc("Ещё")).click()
        shot("03-menu")
        click("Настроить панели…")
        shot("04-settings")
        device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 30)
        shot("05-settings-scrolled")
        click("Конспекты")
        shot("06-settings-notes")
        click("Готово")
        shot("07-editor-notes")
        device.wait(Until.findObject(By.desc("Ластик")), 5_000)?.longClick()
        shot("08-long-press")
        device.pressBack()
        device.setOrientationPortrait()
        shot("09-portrait")
        device.findObject(By.desc("Ещё"))?.click()
        shot("10-portrait-menu")
        device.pressBack()
        device.setOrientationNatural()
    }
}
