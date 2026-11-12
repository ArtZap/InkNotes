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

/** Two notebooks side by side, and a quick note over the lock screen. Pictures, like [BarsScreenshots]. */
@RunWith(AndroidJUnit4::class)
class V036Screenshots {
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
    fun sideBySideAndQuickNote() {
        ctx.getSharedPreferences("app", 0).edit().putBoolean("toolbarIntroShown", true).remove("toolbarLayout").remove("openTabs").commit()
        val repo = NotebookRepository(ctx.filesDir)
        if (repo.list().none { it.title == "Скриншоты 0.36" }) repo.create("Скриншоты 0.36", PaperKind.GRID)
        if (repo.list().none { it.title == "Конспект рядом" }) repo.create("Конспект рядом", PaperKind.LINED)
        device.setOrientationLandscape()
        ctx.startActivity(ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        click("Скриншоты 0.36", 60_000)
        device.wait(Until.hasObject(By.desc(Pattern.compile("Добавить страницу.*"))), 15_000)
        Thread.sleep(1500)
        val w = device.displayWidth
        val h = device.displayHeight

        menu("Открыть рядом…")
        shot("51-side-picker")
        click("Конспект рядом")
        Thread.sleep(2000)
        shot("52-side-by-side")
        // The line between them dragged to the right.
        shell("input swipe ${w / 2} ${(h * 0.7).toInt()} ${(w * 0.65).toInt()} ${(h * 0.7).toInt()} 600", 1200)
        shot("53-side-resized")
        menu("Поменять местами", index = 1)
        shot("54-side-swapped")
        // Writing in each pane. (Last: on the CI emulator a page view resized after the pen
        // wrote in it sometimes takes its graphics down; tablets don't show this.)
        android.util.Log.i("InkTest", "left 1")
        shell("input stylus swipe ${(w * 0.15).toInt()} ${(h * 0.5).toInt()} ${(w * 0.4).toInt()} ${(h * 0.55).toInt()} 400", 1500)
        android.util.Log.i("InkTest", "left 2")
        shell("input stylus swipe ${(w * 0.15).toInt()} ${(h * 0.6).toInt()} ${(w * 0.4).toInt()} ${(h * 0.6).toInt()} 400", 1500)
        shot("55a-left-written")
        android.util.Log.i("InkTest", "right 1")
        shell("input stylus swipe ${(w * 0.7).toInt()} ${(h * 0.5).toInt()} ${(w * 0.9).toInt()} ${(h * 0.45).toInt()} 400", 1500)
        android.util.Log.i("InkTest", "right 2")
        shell("input stylus swipe ${(w * 0.7).toInt()} ${(h * 0.6).toInt()} ${(w * 0.9).toInt()} ${(h * 0.6).toInt()} 400", 1500)
        android.util.Log.i("InkTest", "written")
        shot("55-side-written")

        // A quick note over the lock screen.
        device.pressHome()
        shell("input keyevent KEYCODE_SLEEP", 1500)
        ctx.startActivity(QuickNotes.intent(ctx))
        Thread.sleep(3000)
        shot("56-quick-note-locked")
        shell("input stylus swipe ${(w * 0.2).toInt()} ${(h * 0.5).toInt()} ${(w * 0.5).toInt()} ${(h * 0.52).toInt()} 400", 1500)
        shot("57-quick-note-written")
        device.pressBack()
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard", 1500)
        device.setOrientationNatural()
    }
}
