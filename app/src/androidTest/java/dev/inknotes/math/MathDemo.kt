package dev.inknotes.math

import android.content.Intent
import android.graphics.Bitmap
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.inknotes.ui.MainActivity
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: writes formulas with an injected stylus into whatever notebook is open in the
 * app, then saves a screenshot. Runs only on request:
 * `am instrument -w -e demo 220,560 -e class dev.inknotes.math.MathDemo …` where the value is
 * where to tap in the library to open the notebook to write in.
 */
@RunWith(AndroidJUnit4::class)
class MathDemo {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation

    @Test
    fun demo() {
        val arg = InstrumentationRegistry.getArguments().getString("demo")
        assumeTrue(arg != null)
        val context = instrumentation.targetContext
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        SystemClock.sleep(3000)
        val (tx, ty) = arg!!.split(",").map { it.toFloat() }
        tap(tx, ty)
        SystemClock.sleep(2500)
        // A fresh page for each run ("add page" in the toolbar).
        InstrumentationRegistry.getArguments().getString("page")?.split(",")?.map { it.toFloat() }?.let { (px, py) ->
            tap(px, py)
            SystemClock.sleep(1500)
        }

        val h = 64f
        when (InstrumentationRegistry.getArguments().getString("set")) {
            "graphs" -> graphs(h)
            "ineq" -> inequalities(h)
            else -> formulas(h)
        }
        SystemClock.sleep(6000)
        val shot: Bitmap = automation.takeScreenshot()
        val dir = context.getExternalFilesDir(null)
        File(dir, "math-demo.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** `-e set ineq`: x² < 4 and 2x − 4 ≥ 0. */
    private fun inequalities(h: Float) {
        val y = 1050f
        write("x", 1050f, y, h)
        write("2", 1100f, y - 22f, h * 0.55f)
        stroke(listOf(1180f to y + 4f, 1140f to y + 32f, 1180f to y + 60f))
        write("4", 1205f, y, h)
        write("2x-4", 1050f, y + 110f, h)
        stroke(listOf(1300f to y + 114f, 1340f to y + 140f, 1300f to y + 166f))
        stroke(listOf(1300f to y + 182f, 1342f to y + 182f))
        write("0", 1365f, y + 110f, h)
    }

    /** `-e set graphs`: lines that get a graph. */
    private fun graphs(h: Float) {
        // y = x² − 3
        write("y=x", 120f, 320f, h)
        write("2", 290f, 300f, h * 0.55f)
        write("-3", 330f, 320f, h)
        // y = 2x + 1, lower down: its graph goes beside it as well.
        write("y=2x+1", 120f, 820f, h)
    }

    private fun formulas(h: Float) {
        write("12+30=", 120f, 300f, h)
        write("x=5", 120f, 420f, h)
        write("2x+1=", 520f, 420f, h)
        // 6 over 3 plus 2 squared
        write("6", 140f, 540f, h)
        stroke(listOf(120f to 618f, 200f to 618f))
        write("3", 140f, 632f, h)
        write("+2", 220f, 588f, h)
        write("2", 316f, 566f, h * 0.55f)
        write("=", 360f, 588f, h)
        // { x+y=3 ; x−y=1
        stroke((0..30).map { k ->
            val t = k / 30f
            val x = if (t < 0.5f) 30f * (1 - 2 * t) * (1 - 2 * t) else 30f * (2 * t - 1) * (2 * t - 1)
            val bump = if (t in 0.45f..0.55f) -14f else 0f
            1020f + x + bump to 520f + 190f * t
        })
        write("x+y=3", 1070f, 530f, h)
        write("x-y=1", 1070f, 630f, h)
        // (y³)'
        write("(y", 120f, 760f, h)
        write("3", 220f, 742f, h * 0.55f)
        write(")'=", 252f, 760f, h)
        // |1 2; 3 4| =
        stroke(listOf(1000f to 740f, 1000f to 900f))
        write("1", 1025f, 748f, h * 0.8f)
        write("2", 1085f, 748f, h * 0.8f)
        write("3", 1025f, 830f, h * 0.8f)
        write("4", 1085f, 830f, h * 0.8f)
        stroke(listOf(1140f to 740f, 1140f to 900f))
        write("=", 1165f, 795f, h)
        // (1 2; 3 4)·2 =
        stroke((0..20).map { k -> val t = k / 20f; 1340f + 22f * (2 * t - 1) * (2 * t - 1) to 735f + 170f * t })
        write("1", 1375f, 748f, h * 0.8f)
        write("2", 1435f, 748f, h * 0.8f)
        write("3", 1375f, 830f, h * 0.8f)
        write("4", 1435f, 830f, h * 0.8f)
        stroke((0..20).map { k -> val t = k / 20f; 1505f - 22f * (2 * t - 1) * (2 * t - 1) to 735f + 170f * t })
        write("2", 1530f, 788f, h)
        write("=", 1580f, 788f, h)
        // ∫ from 0 to 1 of x² dx =
        stroke((0..30).map { k ->
            val t = k / 30f
            val x = when {
                t < 0.2f -> 14f + 60f * (0.2f - t)
                t > 0.8f -> 14f - 60f * (t - 0.8f)
                else -> 14f
            }
            120f + x + (if (t < 0.12f) -(0.12f - t) * 80f else 0f) to 900f + 170f * t
        })
        write("1", 160f, 880f, h * 0.5f)
        write("0", 160f, 1050f, h * 0.5f)
        write("x", 195f, 955f, h)
        write("2", 240f, 935f, h * 0.55f)
        write("dx=", 275f, 955f, h)
    }

    private fun write(text: String, x: Float, top: Float, h: Float) {
        for (s in TestGlyphs.write(text, x, top, h).first) stroke(s)
    }

    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        inject(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0), InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER)
        inject(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0), InputDevice.SOURCE_TOUCHSCREEN, MotionEvent.TOOL_TYPE_FINGER)
    }

    /** One pen stroke through [points] (screen pixels), densified, about 120 points a second. */
    private fun stroke(points: List<Pair<Float, Float>>) {
        val dense = ArrayList<Pair<Float, Float>>()
        for (i in points.indices) {
            if (i == 0) {
                dense += points[0]
                continue
            }
            val (ax, ay) = points[i - 1]
            val (bx, by) = points[i]
            val n = maxOf(1, (Math.hypot((bx - ax).toDouble(), (by - ay).toDouble()) / 6).toInt())
            for (k in 1..n) dense += (ax + (bx - ax) * k / n) to (ay + (by - ay) * k / n)
        }
        val down = SystemClock.uptimeMillis()
        var t = down
        for ((i, p) in dense.withIndex()) {
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                dense.lastIndex -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            inject(MotionEvent.obtain(down, t, action, p.first, p.second, 0), InputDevice.SOURCE_STYLUS, MotionEvent.TOOL_TYPE_STYLUS)
            t += 8
            SystemClock.sleep(8)
        }
        SystemClock.sleep(60)
    }

    private fun inject(e: MotionEvent, source: Int, tool: Int) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = tool })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = e.x; y = e.y; pressure = 0.6f; size = 1f })
        val ev = MotionEvent.obtain(e.downTime, e.eventTime, e.action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
        automation.injectInputEvent(ev, true)
        ev.recycle()
        e.recycle()
    }
}
