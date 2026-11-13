package dev.inknotes.probe

import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.ArrayDeque
import java.util.Locale

/**
 * Collects everything the system reports about stylus/touch/key input and keeps
 * both a live summary (Compose state) and a full text log for export.
 */
class Probe {
    // Live values of the last stylus/touch sample.
    var tool by mutableStateOf("—")
    var pressure by mutableFloatStateOf(0f)
    var tiltDeg by mutableFloatStateOf(0f)
    var orientationDeg by mutableFloatStateOf(0f)
    var distance by mutableFloatStateOf(0f)
    var buttons by mutableStateOf("—")

    // Aggregates.
    var penSampleRateHz by mutableIntStateOf(0)
    var penEventRateHz by mutableIntStateOf(0)
    var maxPressure by mutableFloatStateOf(0f)
    var minPressure by mutableFloatStateOf(Float.MAX_VALUE)
    var maxTiltDeg by mutableFloatStateOf(0f)
    var frameRateHz by mutableIntStateOf(0)
    var strokeCount by mutableIntStateOf(0)
    var status by mutableStateOf("")

    val buttonsSeen = mutableStateListOf<String>()
    val keysSeen = mutableStateListOf<String>()
    val toolsSeen = mutableStateListOf<String>()

    /** Recent non-MOVE events for the on-screen list (newest first). */
    val recent = mutableStateListOf<String>()

    /** Full log for the exported file. */
    private val fullLog = ArrayList<String>()
    private val startMs = android.os.SystemClock.uptimeMillis()

    private val penSampleTimes = ArrayDeque<Long>()
    private val penEventTimes = ArrayDeque<Long>()

    fun log(line: String, showOnScreen: Boolean = true) {
        val stamped = "%7d %s".format(Locale.US, android.os.SystemClock.uptimeMillis() - startMs, line)
        Log.d(TAG, stamped)
        if (fullLog.size >= MAX_LOG_LINES) fullLog.subList(0, MAX_LOG_LINES / 10).clear()
        fullLog += stamped
        if (showOnScreen) {
            recent.add(0, stamped)
            if (recent.size > MAX_RECENT) recent.removeRange(MAX_RECENT, recent.size)
        }
    }

    fun onMotion(e: MotionEvent, origin: String) {
        val action = e.actionMasked
        val isMove = action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_HOVER_MOVE
        val penIndex = (0 until e.pointerCount).firstOrNull { isPen(e.getToolType(it)) }

        if (penIndex != null) {
            val now = e.eventTime
            penEventTimes.addLast(now)
            for (h in 0 until e.historySize) penSampleTimes.addLast(e.getHistoricalEventTime(h))
            penSampleTimes.addLast(now)
            trimWindow(penEventTimes, now)
            trimWindow(penSampleTimes, now)
            penEventRateHz = penEventTimes.size
            penSampleRateHz = penSampleTimes.size
        }

        val i = penIndex ?: 0
        tool = toolName(e.getToolType(i))
        remember(toolsSeen, tool)
        pressure = e.getPressure(i)
        tiltDeg = Math.toDegrees(e.getAxisValue(MotionEvent.AXIS_TILT, i).toDouble()).toFloat()
        orientationDeg = Math.toDegrees(e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i).toDouble()).toFloat()
        distance = e.getAxisValue(MotionEvent.AXIS_DISTANCE, i)
        buttons = buttonNames(e.buttonState)
        if (e.buttonState != 0) buttonNames(e.buttonState).split('|').forEach { remember(buttonsSeen, it) }
        if (pressure > 0f) {
            if (pressure > maxPressure) maxPressure = pressure
            if (pressure < minPressure) minPressure = pressure
        }
        if (tiltDeg > maxTiltDeg) maxTiltDeg = tiltDeg

        log(describe(e, origin), showOnScreen = !isMove)
    }

    fun onKey(e: KeyEvent) {
        val name = KeyEvent.keyCodeToString(e.keyCode)
        if (e.action == KeyEvent.ACTION_DOWN) remember(keysSeen, name)
        log(
            "KEY ${if (e.action == KeyEvent.ACTION_DOWN) "DOWN" else "UP"} $name " +
                "scan=${e.scanCode} repeat=${e.repeatCount} meta=${e.metaState} " +
                "src=${sourceNames(e.source)} dev=\"${e.device?.name}\""
        )
    }

    fun describeDevices(): String = buildString {
        for (id in InputDevice.getDeviceIds()) {
            val d = InputDevice.getDevice(id) ?: continue
            append("device #$id \"${d.name}\" vendor=0x%04x product=0x%04x".format(d.vendorId, d.productId))
            append(" virtual=${d.isVirtual} external=${d.isExternal}")
            append(" keyboardType=${d.keyboardType}\n")
            append("    sources=${sourceNames(d.sources)}\n")
            for (r in d.motionRanges) {
                append(
                    "    axis=%s src=%s min=%.3f max=%.3f res=%.3f fuzz=%.3f\n".format(
                        Locale.US, MotionEvent.axisToString(r.axis), sourceNames(r.source),
                        r.min, r.max, r.resolution, r.fuzz,
                    )
                )
            }
        }
    }

    fun summary(): String = buildString {
        appendLine("tools seen: ${toolsSeen.joinToString()}")
        appendLine("pen samples/s: $penSampleRateHz, pen events/s: $penEventRateHz, frames/s: $frameRateHz")
        appendLine(
            "pressure seen: %.4f .. %.4f".format(
                Locale.US, if (minPressure == Float.MAX_VALUE) 0f else minPressure, maxPressure,
            )
        )
        appendLine("max tilt seen: %.1f°".format(Locale.US, maxTiltDeg))
        appendLine("buttons seen: ${buttonsSeen.joinToString().ifEmpty { "none" }}")
        appendLine("keys seen: ${keysSeen.joinToString().ifEmpty { "none" }}")
        appendLine("strokes: $strokeCount")
    }

    fun fullLogText(): String = fullLog.joinToString("\n")

    fun clearLog() {
        fullLog.clear()
        recent.clear()
        buttonsSeen.clear()
        keysSeen.clear()
        toolsSeen.clear()
        maxPressure = 0f
        minPressure = Float.MAX_VALUE
        maxTiltDeg = 0f
    }

    private fun describe(e: MotionEvent, origin: String): String = buildString {
        append(origin).append(' ').append(MotionEvent.actionToString(e.action))
        append(" hist=").append(e.historySize)
        append(" btn=").append(buttonNames(e.buttonState))
        for (i in 0 until e.pointerCount) {
            append(
                " [id%d %s x=%.1f y=%.1f p=%.4f tilt=%.1f° or=%.1f° dist=%.2f major=%.1f]".format(
                    Locale.US,
                    e.getPointerId(i),
                    toolName(e.getToolType(i)),
                    e.getX(i),
                    e.getY(i),
                    e.getPressure(i),
                    Math.toDegrees(e.getAxisValue(MotionEvent.AXIS_TILT, i).toDouble()),
                    Math.toDegrees(e.getAxisValue(MotionEvent.AXIS_ORIENTATION, i).toDouble()),
                    e.getAxisValue(MotionEvent.AXIS_DISTANCE, i),
                    e.getTouchMajor(i),
                )
            )
        }
        if (e.flags and MotionEvent.FLAG_CANCELED != 0) append(" FLAG_CANCELED")
        append(" src=").append(sourceNames(e.source))
        append(" dev=\"").append(e.device?.name).append('"')
    }

    private fun trimWindow(q: ArrayDeque<Long>, now: Long) {
        while (q.isNotEmpty() && now - q.peekFirst()!! > 1000) q.removeFirst()
    }

    private fun remember(list: MutableList<String>, value: String) {
        if (value !in list) list += value
    }

    companion object {
        const val TAG = "PenProbe"
        private const val MAX_LOG_LINES = 100_000
        private const val MAX_RECENT = 80

        fun isPen(toolType: Int) =
            toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER

        fun toolName(toolType: Int) = when (toolType) {
            MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
            MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
            MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
            MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
            else -> "UNKNOWN($toolType)"
        }

        fun buttonNames(state: Int): String {
            if (state == 0) return "0"
            val known = listOf(
                MotionEvent.BUTTON_STYLUS_PRIMARY to "STYLUS_PRIMARY",
                MotionEvent.BUTTON_STYLUS_SECONDARY to "STYLUS_SECONDARY",
                MotionEvent.BUTTON_PRIMARY to "PRIMARY",
                MotionEvent.BUTTON_SECONDARY to "SECONDARY",
                MotionEvent.BUTTON_TERTIARY to "TERTIARY",
                MotionEvent.BUTTON_BACK to "BACK",
                MotionEvent.BUTTON_FORWARD to "FORWARD",
            )
            val names = known.filter { (bit, _) -> state and bit != 0 }.map { it.second }.toMutableList()
            val unknownBits = state and known.fold(0) { acc, (bit, _) -> acc or bit }.inv()
            if (unknownBits != 0) names += "0x%x".format(unknownBits)
            return names.joinToString("|")
        }

        fun sourceNames(sources: Int): String {
            val names = listOf(
                InputDevice.SOURCE_KEYBOARD to "KEYBOARD",
                InputDevice.SOURCE_DPAD to "DPAD",
                InputDevice.SOURCE_GAMEPAD to "GAMEPAD",
                InputDevice.SOURCE_TOUCHSCREEN to "TOUCHSCREEN",
                InputDevice.SOURCE_MOUSE to "MOUSE",
                InputDevice.SOURCE_STYLUS to "STYLUS",
                InputDevice.SOURCE_BLUETOOTH_STYLUS to "BLUETOOTH_STYLUS",
                InputDevice.SOURCE_TRACKBALL to "TRACKBALL",
                InputDevice.SOURCE_MOUSE_RELATIVE to "MOUSE_RELATIVE",
                InputDevice.SOURCE_TOUCHPAD to "TOUCHPAD",
                InputDevice.SOURCE_TOUCH_NAVIGATION to "TOUCH_NAVIGATION",
                InputDevice.SOURCE_ROTARY_ENCODER to "ROTARY_ENCODER",
                InputDevice.SOURCE_JOYSTICK to "JOYSTICK",
                InputDevice.SOURCE_HDMI to "HDMI",
            ).filter { (bits, _) -> sources and bits == bits }.map { it.second }
            return names.joinToString("|").ifEmpty { "0x%x".format(sources) }
        }
    }
}
