package dev.inknotes.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.inknotes.data.NotebookRepository
import dev.inknotes.data.PaperKind
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/**
 * Sound with the notes: record, write meanwhile, stop — the writing is underlined and a finger
 * tap on it plays the recording. Pictures, like [BarsScreenshots].
 */
@RunWith(AndroidJUnit4::class)
class V035Screenshots {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(1000)
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

    private fun menu(title: String) {
        device.findObject(By.desc("Ещё"))?.click()
        device.wait(Until.hasObject(By.text("Настроить панели…")), 5_000)
        repeat(8) {
            if (device.hasObject(By.text(title))) return@repeat
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.8f)
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
    fun recordWriteListen() {
        instr.uiAutomation.grantRuntimePermission(ctx.packageName, android.Manifest.permission.RECORD_AUDIO)
        ctx.getSharedPreferences("app", 0).edit().putBoolean("toolbarIntroShown", true).remove("toolbarLayout").remove("openTabs").commit()
        val repo = NotebookRepository(ctx.filesDir)
        if (repo.list().none { it.title == "Скриншоты 0.35" }) repo.create("Скриншоты 0.35", PaperKind.LINED)
        device.setOrientationLandscape()
        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        click("Скриншоты 0.35", 60_000)
        device.wait(Until.hasObject(By.desc(Pattern.compile("Добавить страницу.*"))), 15_000)
        Thread.sleep(2000)
        val w = device.displayWidth
        val h = device.displayHeight

        menu("Записать звук")
        Thread.sleep(1500)
        // A word, then another a few seconds later.
        shell("input stylus swipe ${(w * 0.25).toInt()} ${(h * 0.45).toInt()} ${(w * 0.35).toInt()} ${(h * 0.47).toInt()} 400")
        shell("input stylus swipe ${(w * 0.25).toInt()} ${(h * 0.48).toInt()} ${(w * 0.35).toInt()} ${(h * 0.45).toInt()} 400", 2500)
        shell("input stylus swipe ${(w * 0.45).toInt()} ${(h * 0.6).toInt()} ${(w * 0.6).toInt()} ${(h * 0.62).toInt()} 400", 1500)
        shot("41-recording")
        // The recording bar's own button.
        click("Стоп")
        shot("42-recorded-underlined")
        // A finger tap on the first word plays the recording from then.
        shell("input tap ${(w * 0.3).toInt()} ${(h * 0.46).toInt()}", 1500)
        shot("43-playing")
        device.setOrientationNatural()
    }
}
