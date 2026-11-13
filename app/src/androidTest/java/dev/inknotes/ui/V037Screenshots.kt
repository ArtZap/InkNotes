package dev.inknotes.ui

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The Google Drive dialog of 0.37 (in CI the build has no Google key: the setup hint). */
@RunWith(AndroidJUnit4::class)
class V037Screenshots {
    private val instr = InstrumentationRegistry.getInstrumentation()
    private val ctx = instr.targetContext
    private val device = UiDevice.getInstance(instr)
    private val dir = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }

    private fun shot(name: String) {
        device.waitForIdle()
        Thread.sleep(1200)
        device.takeScreenshot(File(dir, "$name.png"))
    }

    @Before
    fun onlyWhenAsked() = assumeTrue(InstrumentationRegistry.getArguments().getString("shots") == "1")

    @Test
    fun driveDialog() {
        ctx.getSharedPreferences("app", 0).edit().putBoolean("toolbarIntroShown", true).remove("openTabs").commit()
        device.setOrientationLandscape()
        val launch = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)!!.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        device.findObject(By.text("Wait"))?.click()
        checkNotNull(device.wait(Until.findObject(By.desc("Ещё")), 60_000)) { "no library" }.click()
        repeat(16) {
            if (device.hasObject(By.text("Google Диск…"))) return@repeat
            device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 0.4f)
        }
        checkNotNull(device.wait(Until.findObject(By.text("Google Диск…")), 5_000)) { "no menu item" }.click()
        device.wait(Until.hasObject(By.text("Google Диск")), 5_000)
        shot("41-drive-dialog")
        device.pressBack()
        device.setOrientationNatural()
    }
}
