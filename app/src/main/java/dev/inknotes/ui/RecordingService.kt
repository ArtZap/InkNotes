package dev.inknotes.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.inknotes.R

/**
 * Keeps a sound recording going while the app is not on screen (screen off, another app
 * opened beside it): without a foreground service Android gives an app in the background only
 * silence from the microphone and may stop it any time. The recorder itself stays with the
 * editor ([EditorController]); this only holds the app in the foreground, with a notification
 * that says a recording is on and can stop it.
 */
class RecordingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // From the notification: the editor stops and saves the recording, then stops this.
            val stop = onStopRequested
            if (stop != null) Handler(Looper.getMainLooper()).post(stop) else stopSelf()
            return START_NOT_STICKY
        }
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val started = runCatching {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(title),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
            )
        }.isSuccess
        if (!started) stopSelf()
        // Killed by the system, it is not restarted: the recorder went with the process.
        return START_NOT_STICKY
    }

    private fun notification(title: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Запись звука", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Пока идёт запись лекции"
                    setShowBadge(false)
                },
            )
        }
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            flags,
        )
        val stop = PendingIntent.getService(this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP), flags)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_tile_note)
            .setContentTitle("Идёт запись звука")
            .setContentText(title.ifEmpty { "InkNotes" })
            .setOngoing(true)
            .setUsesChronometer(true)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null as android.graphics.drawable.Icon?, "Остановить", stop).build())
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val CHANNEL = "recording"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "dev.inknotes.STOP_RECORDING"
        private const val EXTRA_TITLE = "title"

        /** Set by the editor that records: called on the main thread when "Остановить" is pressed. */
        @Volatile
        var onStopRequested: (() -> Unit)? = null

        /** Called while the app is on screen (Android 14 lets a microphone service start only then). */
        fun start(context: Context, title: String) {
            runCatching {
                ContextCompat.startForegroundService(context, Intent(context, RecordingService::class.java).putExtra(EXTRA_TITLE, title))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RecordingService::class.java)) }
        }
    }
}
