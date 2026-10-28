package dev.inknotes.probe

import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.view.Choreographer
import android.view.Display
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ProbeActivity : ComponentActivity() {

    private val probe = Probe()
    private var inkView: InkProbeView? = null

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = logDevices("added #$deviceId")
        override fun onInputDeviceRemoved(deviceId: Int) = logDevices("removed #$deviceId")
        override fun onInputDeviceChanged(deviceId: Int) = logDevices("changed #$deviceId")
    }

    private val frameCounter = object : Choreographer.FrameCallback {
        private var windowStartNs = 0L
        private var frames = 0

        override fun doFrame(frameTimeNanos: Long) {
            if (windowStartNs == 0L) windowStartNs = frameTimeNanos
            frames++
            if (frameTimeNanos - windowStartNs >= 1_000_000_000L) {
                probe.frameRateHz = frames
                frames = 0
                windowStartNs = frameTimeNanos
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestHighestRefreshRate()
        probe.log(deviceInfo())
        probe.log(probe.describeDevices(), showOnScreen = false)
        getSystemService(InputManager::class.java).registerInputDeviceListener(deviceListener, null)

        setContent {
            MaterialTheme {
                ProbeScreen(
                    probe = probe,
                    createInkView = { ctx -> InkProbeView(ctx, probe).also { inkView = it } },
                    onFingerDrawingChanged = { inkView?.allowFingerDrawing = it },
                    onClearCanvas = { inkView?.clearStrokes() },
                    onClearLog = { probe.clearLog() },
                    onSave = ::saveReport,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Choreographer.getInstance().postFrameCallback(frameCounter)
    }

    override fun onPause() {
        Choreographer.getInstance().removeFrameCallback(frameCounter)
        super.onPause()
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(deviceListener)
        super.onDestroy()
    }

    // Pen buttons on some tablets arrive as key events from a separate Bluetooth device.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        probe.onKey(event)
        return super.dispatchKeyEvent(event)
    }

    private fun logDevices(reason: String) {
        probe.log("INPUT DEVICE $reason")
        probe.log(probe.describeDevices(), showOnScreen = false)
    }

    private fun currentDisplay(): Display =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display!! else @Suppress("DEPRECATION") windowManager.defaultDisplay

    private fun requestHighestRefreshRate() {
        val display = currentDisplay()
        val current = display.mode
        val best = display.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .maxByOrNull { it.refreshRate } ?: return
        window.attributes = window.attributes.also { it.preferredDisplayModeId = best.modeId }
    }

    private fun deviceInfo(): String {
        val display = currentDisplay()
        val modes = display.supportedModes.joinToString { m ->
            "#${m.modeId} ${m.physicalWidth}x${m.physicalHeight}@%.1f".format(Locale.US, m.refreshRate)
        }
        return "DEVICE ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}) " +
            "Android ${Build.VERSION.RELEASE} SDK ${Build.VERSION.SDK_INT} build=${Build.DISPLAY}\n" +
            "DISPLAY refresh=%.1f modes=[%s] density=%.2f".format(
                Locale.US, display.refreshRate, modes, resources.displayMetrics.density,
            )
    }

    private fun saveReport() {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(getExternalFilesDir(null), "probe-$stamp.txt")
        val text = buildString {
            appendLine("=== DEVICE ===")
            appendLine(deviceInfo())
            appendLine("current refresh: %.1f Hz".format(Locale.US, currentDisplay().refreshRate))
            appendLine()
            appendLine("=== SUMMARY ===")
            append(probe.summary())
            appendLine()
            appendLine("=== INPUT DEVICES ===")
            append(probe.describeDevices())
            appendLine()
            appendLine("=== EVENT LOG ===")
            append(probe.fullLogText())
        }
        probe.status = runCatching { file.writeText(text) }
            .fold({ "Сохранено: ${file.absolutePath}" }, { "Ошибка записи: ${it.message}" })
    }
}

@Composable
private fun ProbeScreen(
    probe: Probe,
    createInkView: (android.content.Context) -> InkProbeView,
    onFingerDrawingChanged: (Boolean) -> Unit,
    onClearCanvas: () -> Unit,
    onClearLog: () -> Unit,
    onSave: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxSize()
            .background(Color(0xFFECEFF1))
            .safeDrawingPadding()
    ) {
        AndroidView(
            factory = createInkView,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(8.dp),
        )
        Column(
            Modifier
                .width(380.dp)
                .fillMaxHeight()
                .padding(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Зонд пера", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Stat("Инструмент", probe.tool)
            Stat("Давление", "%.4f".format(Locale.US, probe.pressure))
            Stat("Наклон", "%.1f°  (макс %.1f°)".format(Locale.US, probe.tiltDeg, probe.maxTiltDeg))
            Stat("Ориентация", "%.1f°".format(Locale.US, probe.orientationDeg))
            Stat("Дистанция (hover)", "%.2f".format(Locale.US, probe.distance))
            Stat("Кнопки", probe.buttons)
            Stat("Точек пера/с", probe.penSampleRateHz.toString())
            Stat("Событий пера/с", probe.penEventRateHz.toString())
            Stat("Кадров/с", probe.frameRateHz.toString())
            Stat(
                "Давление мин..макс",
                if (probe.maxPressure == 0f) "—"
                else "%.4f .. %.4f".format(Locale.US, probe.minPressure, probe.maxPressure),
            )
            Stat("Видели инструменты", probe.toolsSeen.joinToString().ifEmpty { "—" })
            Stat("Видели кнопки", probe.buttonsSeen.joinToString().ifEmpty { "—" })
            Stat("Видели клавиши", probe.keysSeen.joinToString().ifEmpty { "—" })
            Stat("Штрихов", probe.strokeCount.toString())

            var fingerDrawing by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = fingerDrawing, onCheckedChange = {
                    fingerDrawing = it
                    onFingerDrawingChanged(it)
                })
                Spacer(Modifier.width(8.dp))
                Text("Рисовать пальцем")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onSave) { Text("Сохранить лог") }
                OutlinedButton(onClick = onClearCanvas) { Text("Холст") }
                OutlinedButton(onClick = onClearLog) { Text("Лог") }
            }
            if (probe.status.isNotEmpty()) Text(probe.status, fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text("События (без MOVE), новые сверху:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
            LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                items(probe.recent) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(170.dp), fontSize = 13.sp, color = Color(0xFF546E7A))
        Text(value, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
    }
}
