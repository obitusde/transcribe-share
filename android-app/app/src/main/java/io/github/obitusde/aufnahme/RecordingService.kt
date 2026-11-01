package io.github.obitusde.aufnahme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.io.File
import java.util.concurrent.Executors

/**
 * Nimmt im Vordergrund-Dienst (Typ "microphone") auf. Nur so darf Android
 * bei ausgeschaltetem Bildschirm weiter aufnehmen. Der Dienst muss gestartet
 * werden, solange die App sichtbar ist - deshalb startet die Aufnahme immer
 * ueber den Knopf in der App.
 *
 * Nach dem Stopp laedt derselbe Dienst alle wartenden Aufnahmen hoch und
 * beendet sich dann.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "io.github.obitusde.aufnahme.START"
        const val ACTION_STOP = "io.github.obitusde.aufnahme.STOP"
        const val ACTION_UPLOAD = "io.github.obitusde.aufnahme.UPLOAD"

        private const val CHANNEL_RUNNING = "laufend"
        private const val CHANNEL_RESULT = "ergebnis"
        private const val NOTIFICATION_RUNNING = 1
        private const val NOTIFICATION_RESULT = 2

        @Volatile
        var recordingFile: File? = null
            private set

        @Volatile
        var startedAt: Long = 0L
            private set

        @Volatile
        var busy: Boolean = false
            private set

        @Volatile
        var status: String = ""

        fun isRecording(): Boolean = recordingFile != null

        fun intent(context: Context, action: String): Intent =
            Intent(context, RecordingService::class.java).setAction(action)
    }

    private var recorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUNNING, "Laufende Aufnahme", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Upload-Ergebnis", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                startRecording()
            }
            ACTION_STOP -> stopRecording()
            ACTION_UPLOAD -> {
                goForeground()
                uploadPending()
            }
            else -> finishIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_RUNNING,
            buildRunningNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    @Suppress("DEPRECATION")
    private fun newRecorder(): MediaRecorder =
        if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else MediaRecorder()

    private fun startRecording() {
        if (isRecording()) {
            notifyRunning()
            return
        }
        val file = Store.newFile(this)
        val r = newRecorder()
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(48_000)
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            status = "Aufnahme konnte nicht starten. " + Store.describe(e)
            finishIfIdle()
            return
        }

        recorder = r
        startedAt = SystemClock.elapsedRealtime()
        recordingFile = file
        status = "Aufnahme laeuft. Der Bildschirm darf aus."

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aufnahme:recording").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        notifyRunning()
    }

    private fun stopRecording() {
        val r = recorder
        val file = recordingFile
        recorder = null
        if (r != null) {
            try {
                r.stop()
                status = "Gespeichert: ${file?.name}"
            } catch (e: RuntimeException) {
                // stop() wirft, wenn praktisch nichts aufgenommen wurde.
                file?.delete()
                status = "Aufnahme war zu kurz und wurde verworfen."
            }
            r.release()
        }
        recordingFile = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        uploadPending()
    }

    private fun uploadPending() {
        if (busy) {
            notifyRunning()
            return
        }
        val files = Store.pending(this, recordingFile)
        if (files.isEmpty()) {
            finishIfIdle()
            return
        }
        busy = true
        notifyRunning()

        worker.execute {
            val errors = mutableListOf<String>()
            var lastNote = ""
            for (f in files) {
                status = "Lade hoch: ${f.name}"
                main.post { notifyRunning() }
                try {
                    val result = Uploader.upload(f) { done, total ->
                        status = "Lade hoch: ${f.name} (${done * 100 / total} %)"
                        main.post { notifyRunning() }
                    }
                    Store.markUploaded(this, f.name, result.note)
                    lastNote = result.note
                } catch (e: Exception) {
                    val msg = Store.describe(e)
                    Store.setNote(this, f.name, "Fehler: $msg")
                    errors += "${f.name}: $msg"
                }
            }
            Store.cleanup(this, recordingFile)

            val success = errors.isEmpty()
            status = if (success) {
                "✔ Hochgeladen. $lastNote"
            } else {
                "✘ Upload fehlgeschlagen. Die Aufnahme bleibt auf dem Handy.\n" + errors.joinToString("\n")
            }
            busy = false
            main.post {
                notifyResult(success, status)
                finishIfIdle()
            }
        }
    }

    private fun finishIfIdle() {
        if (isRecording() || busy) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun openAppIntent(): PendingIntent =
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildRunningNotification(): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        if (isRecording()) {
            val stop = PendingIntent.getService(
                this,
                1,
                intent(this, ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            b.setContentTitle("Aufnahme laeuft")
                .setContentText("Bildschirm darf aus. Stopp laedt automatisch hoch.")
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAt))
                .addAction(0, "Stopp & hochladen", stop)
        } else {
            b.setContentTitle(if (busy) "Lade hoch …" else "Aufnahme")
                .setContentText(status)
        }
        return b.build()
    }

    private fun notifyRunning() {
        if (!isRecording() && !busy) return
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_RUNNING, buildRunningNotification())
    }

    private fun notifyResult(success: Boolean, text: String) {
        val n = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(if (success) "Hochgeladen, Mail folgt" else "Upload fehlgeschlagen")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_RESULT, n)
    }

    override fun onDestroy() {
        recorder?.let {
            try {
                it.stop()
            } catch (e: RuntimeException) {
                // nichts zu retten
            }
            it.release()
        }
        recorder = null
        recordingFile = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        worker.shutdown()
        super.onDestroy()
    }
}
