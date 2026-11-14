package dev.inknotes.ui

import dev.inknotes.data.tr
import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A study timer: work for a while, a short break, again (every fourth break longer). One for
 * the whole app, whatever notebook is open; at the end of each part it beeps and buzzes.
 */
object StudyTimer {
    enum class Phase(val title: String) { FOCUS(tr("Учёба", "Study")), BREAK(tr("Перерыв", "Break")) }

    var phase by mutableStateOf<Phase?>(null)
        private set

    /** Seconds left of the part under way. */
    var left by mutableIntStateOf(0)
        private set
    var paused by mutableStateOf(false)
        private set

    /** Which round of work this is, from 1. */
    var round by mutableIntStateOf(1)
        private set
    var focusMinutes by mutableIntStateOf(25)
        private set
    var breakMinutes by mutableIntStateOf(5)
        private set

    /** A message for the screen at each change of part, and a number that changes with it. */
    var message by mutableStateOf<String?>(null)
        private set
    var messageId by mutableIntStateOf(0)
        private set

    private val handler = Handler(Looper.getMainLooper())
    private var endsAt = 0L
    private var appContext: Context? = null

    private val tick = object : Runnable {
        override fun run() {
            if (phase == null || paused) return
            val ms = endsAt - SystemClock.elapsedRealtime()
            left = ((ms + 999) / 1000).toInt().coerceAtLeast(0)
            if (ms <= 0) next() else handler.postDelayed(this, (ms % 1000).coerceAtLeast(50))
        }
    }

    fun start(context: Context, focus: Int, rest: Int) {
        appContext = context.applicationContext
        focusMinutes = focus
        breakMinutes = rest
        round = 1
        begin(Phase.FOCUS, focus * 60)
        say(tr("Учёба: $focus мин", "Study: $focus min"))
    }

    private fun begin(p: Phase, seconds: Int) {
        phase = p
        paused = false
        left = seconds
        endsAt = SystemClock.elapsedRealtime() + seconds * 1000L
        handler.removeCallbacks(tick)
        handler.post(tick)
    }

    /** The part ends: a signal, and the next one starts. */
    private fun next() {
        signal()
        if (phase == Phase.FOCUS) {
            // Every fourth break is three times as long.
            val long = round % 4 == 0
            val minutes = if (long) breakMinutes * 3 else breakMinutes
            begin(Phase.BREAK, minutes * 60)
            say(if (long) tr("Длинный перерыв: $minutes мин", "Long break: $minutes min") else tr("Перерыв: $minutes мин", "Break: $minutes min"))
        } else {
            round++
            begin(Phase.FOCUS, focusMinutes * 60)
            say(tr("Снова учёба, круг $round", "Back to study, round $round"))
        }
    }

    fun skip() {
        if (phase != null) next()
    }

    fun pause() {
        if (phase == null || paused) return
        paused = true
        left = ((endsAt - SystemClock.elapsedRealtime() + 999) / 1000).toInt().coerceAtLeast(0)
        handler.removeCallbacks(tick)
    }

    fun resume() {
        if (phase == null || !paused) return
        paused = false
        endsAt = SystemClock.elapsedRealtime() + left * 1000L
        handler.post(tick)
    }

    fun stop() {
        handler.removeCallbacks(tick)
        phase = null
        paused = false
    }

    private fun say(text: String) {
        message = text
        messageId++
    }

    private fun signal() {
        runCatching {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 600)
            handler.postDelayed({ tone.release() }, 1000)
        }
        runCatching {
            val ctx = appContext ?: return@runCatching
            val v = if (android.os.Build.VERSION.SDK_INT >= 31) {
                (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as android.os.VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                ctx.getSystemService(Context.VIBRATOR_SERVICE) as android.os.Vibrator
            }
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
        }
    }

    fun format(seconds: Int) = "%d:%02d".format(seconds / 60, seconds % 60)
}

/** The timer on the bar: the part and the time left; a tap opens it. */
@Composable
fun StudyTimerChip(onClick: () -> Unit) {
    val p = StudyTimer.phase ?: return
    val color = if (p == StudyTimer.Phase.FOCUS) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary
    TextButton(onClick = onClick, contentPadding = PaddingValues(horizontal = 8.dp)) {
        Icon(InkIcons.Timer, null, tint = color)
        Text(
            " " + StudyTimer.format(StudyTimer.left) + if (StudyTimer.paused) " ⏸" else "",
            color = color,
            fontWeight = FontWeight.SemiBold,
            fontSize = 15.sp,
        )
    }
}

@Composable
fun StudyTimerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var focus by remember { mutableIntStateOf(StudyTimer.focusMinutes) }
    var rest by remember { mutableIntStateOf(StudyTimer.breakMinutes) }
    val p = StudyTimer.phase
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(InkIcons.Timer, null) },
        title = { Text(if (p == null) tr("Таймер учёбы", "Study timer") else tr("${p.title}, круг ${StudyTimer.round}", "${p.title}, round ${StudyTimer.round}")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (p == null) {
                    Text(tr("Учитесь без отвлечений, потом короткий перерыв; каждый четвёртый перерыв длиннее. В конце каждой части — сигнал.", "Study without distractions, then take a short break; every fourth break is longer. Each part ends with a signal."), fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((f, r) in listOf(25 to 5, 50 to 10, 90 to 15)) {
                            FilledTonalButton(onClick = { focus = f; rest = r }) { Text("$f / $r") }
                        }
                    }
                    Stepper(tr("Учёба, мин", "Study, min"), focus, 5..180) { focus = it }
                    Stepper(tr("Перерыв, мин", "Break, min"), rest, 1..60) { rest = it }
                } else {
                    Text(
                        StudyTimer.format(StudyTimer.left),
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        if (StudyTimer.paused) Button(onClick = StudyTimer::resume) { Text(tr("Продолжить", "Continue")) } else OutlinedButton(onClick = StudyTimer::pause) { Text(tr("Пауза", "Pause")) }
                        OutlinedButton(onClick = StudyTimer::skip) { Text(if (p == StudyTimer.Phase.FOCUS) tr("К перерыву", "To break") else tr("К учёбе", "To study")) }
                    }
                }
            }
        },
        confirmButton = {
            if (p == null) {
                Button(onClick = {
                    StudyTimer.start(context, focus, rest)
                    onDismiss()
                }) { Text(tr("Начать", "Start")) }
            } else {
                TextButton(onClick = onDismiss) { Text(tr("Готово", "Done")) }
            }
        },
        dismissButton = {
            if (p == null) {
                TextButton(onClick = onDismiss) { Text(tr("Отмена", "Cancel")) }
            } else {
                TextButton(onClick = {
                    StudyTimer.stop()
                    onDismiss()
                }) { Text(tr("Остановить", "Stop"), color = MaterialTheme.colorScheme.error) }
            }
        },
    )
}
