package dev.inknotes.probe

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Not a test: plays a script of taps, finger swipes and pen strokes into the app as it is on
 * screen (used to film the promo). Runs only on request:
 * `am instrument -w -e script promo1.txt -e class dev.inknotes.probe.ScriptPlayer …`; the script is
 * read from the app's external files dir. One command a line:
 * `sleep MS`, `tap X Y`, `long X Y`, `swipe MS X Y X Y …` (finger), `pen MS X Y X Y …`
 * (stylus; MS is the time for the whole stroke), `hold MS HOLD X Y …` (pen, kept down HOLD ms at
 * the end), `pinch MS CX CY R0 R1` (two fingers), `two X Y` / `three X Y` (multi-finger taps).
 */
@RunWith(AndroidJUnit4::class)
class ScriptPlayer {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation = instrumentation.uiAutomation

    @Test
    fun play() {
        val name = InstrumentationRegistry.getArguments().getString("script")
        assumeTrue(name != null)
        val context = instrumentation.targetContext
        automation.serviceInfo = automation.serviceInfo.apply { flags = flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        val file = File(context.getExternalFilesDir(null), name!!)
        // The instrumentation restarts the app: open it again, past the splash.
        context.startActivity(
            android.content.Intent(context, dev.inknotes.ui.MainActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        SystemClock.sleep(InstrumentationRegistry.getArguments().getString("wait")?.toLong() ?: 3500L)
        for (raw in file.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.startsWith("labels ")) {
                val out = StringBuilder()
                fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
                    node ?: return
                    val t = node.text ?: node.contentDescription
                    if (t != null) {
                        val r = android.graphics.Rect()
                        node.getBoundsInScreen(r)
                        out.append("${r.centerX()},${r.centerY()}\t$t\n")
                    }
                    for (i in 0 until node.childCount) walk(node.getChild(i))
                }
                for (w in automation.windows) walk(w.root)
                if (out.isEmpty()) walk(automation.rootInActiveWindow)
                File(context.getExternalFilesDir(null), line.removePrefix("labels ") + ".txt").writeText(out.toString())
                continue
            }
            if (line.startsWith("shot ")) {
                val b = automation.takeScreenshot()
                File(context.getExternalFilesDir(null), line.removePrefix("shot ") + ".png").outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                continue
            }
            if (line.startsWith("backif ")) {
                // Back, only while LABEL shows (closes a dialog, never leaves the app).
                if (find(line.removePrefix("backif ")).isNotEmpty()) key(android.view.KeyEvent.KEYCODE_BACK, 0)
                continue
            }
            if (line.startsWith("trytext ")) {
                // Taps LABEL if it shows up within 1.5 s, else goes on.
                val label = line.removePrefix("trytext ")
                val end = SystemClock.uptimeMillis() + 1500
                var box: android.graphics.Rect? = null
                while (box == null && SystemClock.uptimeMillis() < end) {
                    box = find(label).firstOrNull()
                    if (box == null) SystemClock.sleep(150)
                }
                box?.let { fingers(listOf(listOf(it.exactCenterX() to it.exactCenterY())), 60) }
                continue
            }
            if (line.startsWith("text ") || line.startsWith("longtext ") || line.startsWith("wait ")) {
                // `text [N] LABEL`: tap the Nth (from 0) element showing LABEL; the run stops if none.
                val cmd = line.substringBefore(' ')
                var rest = line.substringAfter(' ')
                val n = rest.substringBefore(' ').toIntOrNull()?.also { rest = rest.substringAfter(' ') } ?: 0
                val deadline = SystemClock.uptimeMillis() + 8000
                var box: android.graphics.Rect? = null
                var scrolls = 0
                while (box == null && SystemClock.uptimeMillis() < deadline) {
                    box = find(rest).getOrNull(n)?.takeIf { it.bottom < 1150 }
                    if (box == null && cmd != "wait" && scrolls < 4 && SystemClock.uptimeMillis() > deadline - 6500) {
                        // Below the fold: scroll the list up a little and look again.
                        fingers(listOf(listOf(1000f to 1000f, 1000f to 600f)), 400)
                        SystemClock.sleep(600)
                        scrolls++
                    } else if (box == null) SystemClock.sleep(200)
                }
                checkNotNull(box) { "not on screen: $rest" }
                when (cmd) {
                    "text" -> fingers(listOf(listOf(box.exactCenterX() to box.exactCenterY())), 60)
                    "longtext" -> fingers(listOf(listOf(box.exactCenterX() to box.exactCenterY(), box.exactCenterX() to box.exactCenterY())), 900)
                }
                continue
            }
            if (line.startsWith("paste ")) {
                paste(line.removePrefix("paste "))
                continue
            }
            val w = line.split(Regex("\\s+"))
            val n = w.drop(1).map { it.toFloat() }
            when (w[0]) {
                "sleep" -> SystemClock.sleep(n[0].toLong())
                "tap" -> fingers(listOf(listOf(n[0] to n[1])), 60)
                "long" -> fingers(listOf(listOf(n[0] to n[1], n[0] to n[1])), 900)
                "two" -> fingers(listOf(listOf(n[0] to n[1]), listOf(n[0] + 120 to n[1])), 80)
                "three" -> fingers(listOf(listOf(n[0] to n[1]), listOf(n[0] + 120 to n[1]), listOf(n[0] + 240 to n[1])), 80)
                "swipe" -> fingers(listOf(points(n.drop(1))), n[0].toLong())
                "pinch" -> {
                    val (cx, cy, r0, r1) = n.drop(1)
                    val steps = 20
                    val a = (0..steps).map { k -> val r = r0 + (r1 - r0) * k / steps; cx - r to cy }
                    val b = (0..steps).map { k -> val r = r0 + (r1 - r0) * k / steps; cx + r to cy }
                    fingers(listOf(a, b), n[0].toLong())
                }
                "pen" -> pen(points(n.drop(1)), n[0].toLong(), 0)
                "hold" -> pen(points(n.drop(2)), n[0].toLong(), n[1].toLong())
                "key" -> key(n[0].toInt(), n.getOrElse(1) { 0f }.toInt())
                else -> error("unknown command: $line")
            }
        }
    }

    /** Screen boxes of the elements whose text or description is [label], top to bottom. */
    private fun find(label: String): List<android.graphics.Rect> {
        val out = ArrayList<android.graphics.Rect>()
        fun walk(node: android.view.accessibility.AccessibilityNodeInfo?) {
            node ?: return
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) {
                val r = android.graphics.Rect()
                node.getBoundsInScreen(r)
                if (!r.isEmpty && r.top >= 0 && r.bottom <= 1200) out += r
            }
            for (i in 0 until node.childCount) walk(node.getChild(i))
        }
        // Top window first (a dialog over the screen), then the rest.
        for (w in automation.windows.sortedByDescending { it.layer }) {
            walk(w.root)
            if (out.isNotEmpty()) break
        }
        if (out.isEmpty()) walk(automation.rootInActiveWindow)
        return out.distinct().sortedWith(compareBy({ it.top }, { it.left }))
    }

    /** Puts [text] on the clipboard and pastes it into the focused field (any alphabet). */
    private fun paste(text: String) {
        instrumentation.runOnMainSync {
            val cm = instrumentation.targetContext.getSystemService(android.content.ClipboardManager::class.java)
            cm.setPrimaryClip(android.content.ClipData.newPlainText("promo", text))
        }
        SystemClock.sleep(150)
        key(android.view.KeyEvent.KEYCODE_V, android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_CTRL_LEFT_ON)
    }

    private fun key(code: Int, meta: Int) {
        val t = SystemClock.uptimeMillis()
        for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
            automation.injectInputEvent(android.view.KeyEvent(t, SystemClock.uptimeMillis(), action, code, 0, meta, android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD), true)
        }
        SystemClock.sleep(60)
    }

    private fun points(v: List<Float>) = v.chunked(2).map { it[0] to it[1] }

    /** A stylus stroke through [pts] over [ms], then held still for [hold] ms before lifting. */
    private fun pen(pts: List<Pair<Float, Float>>, ms: Long, hold: Long) {
        val dense = densify(pts, 4f)
        val down = SystemClock.uptimeMillis()
        val step = (ms.toFloat() / dense.size).coerceAtLeast(4f)
        val start = SystemClock.uptimeMillis()
        for ((i, p) in dense.withIndex()) {
            val t = start + (i * step).toLong()
            while (SystemClock.uptimeMillis() < t) SystemClock.sleep(1)
            val action = if (i == 0) MotionEvent.ACTION_DOWN else MotionEvent.ACTION_MOVE
            send(down, action, listOf(p), MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS, pressure = 0.55f + 0.2f * kotlin.math.sin(i * 0.15f))
        }
        val last = dense.last()
        if (hold > 0) {
            val end = SystemClock.uptimeMillis() + hold
            while (SystemClock.uptimeMillis() < end) {
                send(down, MotionEvent.ACTION_MOVE, listOf(last), MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS)
                SystemClock.sleep(16)
            }
        }
        send(down, MotionEvent.ACTION_UP, listOf(last), MotionEvent.TOOL_TYPE_STYLUS, InputDevice.SOURCE_STYLUS)
        SystemClock.sleep(20)
    }

    /** Fingers moving together along their paths (equal length) over [ms]. */
    private fun fingers(paths: List<List<Pair<Float, Float>>>, ms: Long) {
        val src = InputDevice.SOURCE_TOUCHSCREEN
        val tool = MotionEvent.TOOL_TYPE_FINGER
        val down = SystemClock.uptimeMillis()
        val dense = paths.map { if (it.size > 1) densify(it, 12f) else it }
        val count = dense.maxOf { it.size }
        fun at(i: Int) = dense.map { it[minOf(i, it.lastIndex)] }
        send(down, MotionEvent.ACTION_DOWN, at(0).take(1), tool, src)
        for (k in 1 until paths.size) send(down, MotionEvent.ACTION_POINTER_DOWN or (k shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), at(0).take(k + 1), tool, src)
        val step = ms / maxOf(1, count)
        for (i in 1 until count) {
            SystemClock.sleep(step)
            send(down, MotionEvent.ACTION_MOVE, at(i), tool, src)
        }
        if (count == 1) SystemClock.sleep(ms)
        for (k in paths.size - 1 downTo 1) send(down, MotionEvent.ACTION_POINTER_UP or (k shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), at(count - 1).take(k + 1), tool, src)
        send(down, MotionEvent.ACTION_UP, at(count - 1).take(1), tool, src)
        SystemClock.sleep(30)
    }

    private fun densify(points: List<Pair<Float, Float>>, gap: Float): List<Pair<Float, Float>> {
        val dense = ArrayList<Pair<Float, Float>>()
        dense += points[0]
        for (i in 1 until points.size) {
            val (ax, ay) = points[i - 1]
            val (bx, by) = points[i]
            val n = maxOf(1, (Math.hypot((bx - ax).toDouble(), (by - ay).toDouble()) / gap).toInt())
            for (k in 1..n) dense += (ax + (bx - ax) * k / n) to (ay + (by - ay) * k / n)
        }
        return dense
    }

    private fun send(down: Long, action: Int, pts: List<Pair<Float, Float>>, tool: Int, source: Int, pressure: Float = 0.6f) {
        val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = tool } }
        val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].first; y = pts[i].second; this.pressure = pressure; size = 1f } }
        val ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
        automation.injectInputEvent(ev, true)
        ev.recycle()
    }
}
