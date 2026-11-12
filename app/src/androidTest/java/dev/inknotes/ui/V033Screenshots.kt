package dev.inknotes.ui

import android.content.Intent
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
import dev.inknotes.data.Toolbars
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** Pictures of what 0.33 adds: protractor, compass, the study timer (opened by Ctrl+T), formulas in print. */
@RunWith(AndroidJUnit4::class)
class V033Screenshots {
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

    private fun shell(cmd: String, wait: Long = 400) {
        instr.uiAutomation.executeShellCommand(cmd).close()
        Thread.sleep(wait)
    }

    /**
     * Only on the CI emulator (`-e shots 1`): the test makes its own notebooks and changes the
     * tool bars, which must not happen to a real tablet's notebooks and settings.
     */
    @Before
    fun onlyWhenAsked() = assumeTrue(InstrumentationRegistry.getArguments().getString("shots") == "1")

    @Test
    fun geometryTimerFormulas() {
        ctx.getSharedPreferences("app", 0).edit()
            .putBoolean("toolbarIntroShown", true).remove("openTabs")
            .putString("toolbarLayout", Toolbars.Preset.MATH.layout.encode())
            .commit()
        val repo = NotebookRepository(ctx.filesDir)
        if (repo.list().none { it.title == "Скриншоты 0.33" }) repo.create("Скриншоты 0.33", PaperKind.GRID)
        device.setOrientationLandscape()
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        click("Скриншоты 0.33", 60_000)
        device.wait(Until.hasObject(By.desc(Pattern.compile("Транспортир.*"))), 15_000)
        shot("31-math-bar")

        clickDesc("Транспортир")
        shot("32-protractor")
        clickDesc("Транспортир")

        val w = device.displayWidth
        val h = device.displayHeight
        clickDesc("Циркуль")
        shell("input stylus swipe ${(w * 0.3).toInt()} ${(h * 0.55).toInt()} ${(w * 0.4).toInt()} ${(h * 0.55).toInt()} 400", 900)
        shell("input stylus swipe ${(w * 0.62).toInt()} ${(h * 0.5).toInt()} ${(w * 0.66).toInt()} ${(h * 0.62).toInt()} 400", 900)
        shot("33-compass")
        clickDesc("Циркуль")

        // The timer by its key: Ctrl+T.
        shell("input keycombination KEYCODE_CTRL_LEFT KEYCODE_T", 1200)
        shot("34-timer-dialog")
        click("50 / 10")
        click("Начать")
        shot("35-timer-running")

        // Ctrl+Z after a pen line takes it back.
        shell("input stylus swipe ${(w * 0.2).toInt()} ${(h * 0.8).toInt()} ${(w * 0.5).toInt()} ${(h * 0.75).toInt()} 300", 900)
        shot("36-before-undo")
        shell("input keycombination KEYCODE_CTRL_LEFT KEYCODE_Z", 1000)
        shot("37-after-ctrl-z")

        // The formula window, with what handwriting is read into.
        var activity: ComponentActivity? = null
        instr.runOnMainSync {
            activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).firstOrNull() as? ComponentActivity
        }
        val act = activity ?: error("no activity")
        instr.runOnMainSync {
            act.setContent {
                MaterialTheme {
                    FormulaDialog(
                        initial = dev.inknotes.math.Typeset.latex("x_{1,2}=\\frac{-b\\pm\\sqrt{b^{2}-4ac}}{2a}"),
                        readFailed = false,
                        onDismiss = {}, onCopyLatex = {}, onCopyPicture = {}, onReplace = {}, onBelow = {},
                    )
                }
            }
        }
        device.wait(Until.hasObject(By.text("Заменить почерк")), 10_000)
        shot("38-formula")
        instr.runOnMainSync {
            act.setContent {
                MaterialTheme {
                    FormulaDialog(
                        initial = dev.inknotes.math.Typeset.latex("\\det{\\mat{a&b\\\\c&d}}+\\int_{0}^{\\pi}sinxdx+(\\frac{1}{2})^{3}"),
                        readFailed = false,
                        onDismiss = {}, onCopyLatex = {}, onCopyPicture = {}, onReplace = {}, onBelow = {},
                    )
                }
            }
        }
        shot("39-formula-matrix")
        device.setOrientationNatural()
    }
}
