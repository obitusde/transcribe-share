package io.github.obitusde.aufnahme

import android.app.Notification
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
 * ueber den Knopf in der App. Pause, Weiter und Stopp gehen auch ueber die
 * Benachrichtigung.
 *
 * Nach dem Stopp passiert NICHTS automatisch: Die Aufnahme ist "offen", bis
 * in der App entschieden wird (Transkribieren, lokal behalten, Teilen,
 * Loeschen). Bei "Transkribieren" laedt derselbe Dienst hoch
 * (ACTION_UPLOAD); den Fortschritt der Transkription verfolgt danach der
 * StatusWorker.
 *
 * Ausserdem laedt er Audio von geteilten Links herunter (ACTION_IMPORT_URL),
 * damit ein grosser Podcast-Download nicht abbricht, wenn die App in den
 * Hintergrund geht.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "io.github.obitusde.aufnahme.START"
        const val ACTION_PAUSE = "io.github.obitusde.aufnahme.PAUSE"
        const val ACTION_RESUME = "io.github.obitusde.aufnahme.RESUME"
        const val ACTION_STOP = "io.github.obitusde.aufnahme.STOP"
        const val ACTION_UPLOAD = "io.github.obitusde.aufnahme.UPLOAD"
        const val ACTION_IMPORT_URL = "io.github.obitusde.aufnahme.IMPORT_URL"

        const val EXTRA_URL = "url"

        @Volatile
        var recordingFile: File? = null
            private set

        /** elapsedRealtime beim Start. */
        @Volatile
        private var startedAt: Long = 0L

        /** elapsedRealtime beim Pausieren, 0 = laeuft. */
        @Volatile
        private var pausedAt: Long = 0L

        /** Summe aller bisherigen Pausen in ms. */
        @Volatile
        private var pausedTotal: Long = 0L

        @Volatile
        var busy: Boolean = false
            private set

        @Volatile
        var importing: Boolean = false
            private set

        /** Fortschritt des Link-Downloads in Prozent, -1 = unbekannt. */
        @Volatile
        var importPct: Int = -1
            private set

        /** Kurze Meldung fuer die App (Fehler, Upload, Link-Download). */
        @Volatile
        var status: String = ""
            private set

        /** Wann die Meldung kam (elapsedRealtime). Die App blendet alte Meldungen aus. */
        @Volatile
        var statusAt: Long = 0L
            private set

        fun report(text: String) {
            status = text
            statusAt = SystemClock.elapsedRealtime()
        }

        fun isRecording(): Boolean = recordingFile != null

        fun isPaused(): Boolean = recordingFile != null && pausedAt > 0L

        /** Aufgenommene Zeit ohne Pausen. */
        fun elapsedMs(): Long {
            if (recordingFile == null) return 0L
            val end = if (pausedAt > 0L) pausedAt else SystemClock.elapsedRealtime()
            return (end - startedAt - pausedTotal).coerceAtLeast(0L)
        }

        fun intent(context: Context, action: String): Intent =
            Intent(context, RecordingService::class.java).setAction(action)
    }

    private var recorder: MediaRecorder? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Upload-Fortschritt fuer die Benachrichtigung, -1 = unbekannt. */
    @Volatile
    private var progressPct = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                goForeground()
                startRecording()
            }
            ACTION_PAUSE -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP -> stopRecording()
            ACTION_UPLOAD -> {
                goForeground()
                uploadPending()
            }
            ACTION_IMPORT_URL -> {
                goForeground()
                importUrl(intent)
            }
            else -> finishIfIdle()
        }
        return START_NOT_STICKY
    }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this,
            Notifier.ID_RUNNING,
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
            report("Aufnahme konnte nicht starten. " + Store.describe(e))
            finishIfIdle()
            return
        }

        val rec = Rec(file.name)
        rec.phase = Rec.PHASE_RECORDING
        rec.createdAt = System.currentTimeMillis()
        Store.save(this, rec)

        recorder = r
        startedAt = SystemClock.elapsedRealtime()
        pausedAt = 0L
        pausedTotal = 0L
        recordingFile = file
        report("")

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Aufnahme:recording").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
        notifyRunning()
    }

    private fun pauseRecording() {
        val r = recorder
        if (r == null || pausedAt > 0L) {
            finishIfIdle()
            return
        }
        try {
            r.pause()
            pausedAt = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            report("Pause ging nicht. " + Store.describe(e))
        }
        notifyRunning()
    }

    private fun resumeRecording() {
        val r = recorder
        if (r == null || pausedAt == 0L) {
            finishIfIdle()
            return
        }
        try {
            r.resume()
            pausedTotal += SystemClock.elapsedRealtime() - pausedAt
            pausedAt = 0L
        } catch (e: Exception) {
            report("Weiter ging nicht. " + Store.describe(e))
        }
        notifyRunning()
    }

    private fun stopRecording() {
        val r = recorder
        val file = recordingFile
        val duration = elapsedMs()
        recorder = null
        var kept = false
        if (r != null) {
            try {
                r.stop()
                kept = true
            } catch (e: RuntimeException) {
                // stop() wirft, wenn praktisch nichts aufgenommen wurde.
            }
            r.release()
        }
        recordingFile = null
        pausedAt = 0L
        pausedTotal = 0L
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        if (file != null) {
            if (kept && file.length() > 0L) {
                val rec = Store.get(this, file.name)
                rec.phase = Rec.PHASE_OPEN
                rec.durationMs = duration
                rec.sizeBytes = file.length()
                Store.save(this, rec)
                Prefs.setFocus(this, rec.name)
                report("")
                if (!MainActivity.visible) Notifier.decide(this, rec)
            } else {
                Store.deleteRecording(this, file.name)
                report("Aufnahme war zu kurz und wurde verworfen.")
            }
        }
        finishIfIdle()
    }

    private fun importUrl(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank() || importing) {
            finishIfIdle()
            return
        }

        importing = true
        importPct = -1
        report("Lade Datei vom Link …")
        notifyRunning()

        worker.execute {
            try {
                val imported = Importer.download(this, url) { done, total ->
                    importPct = if (total > 0) (done * 100 / total).toInt() else -1
                    report(
                        "Lade Datei vom Link: " + Store.sizeText(done) +
                            (if (total > 0) " von " + Store.sizeText(total) else "")
                    )
                    main.post { notifyRunning() }
                }
                val rec = Store.newImported(this, imported)
                Prefs.setFocus(this, rec.name)
                report("")
                main.post { if (!MainActivity.visible) Notifier.decide(this, rec) }
            } catch (e: Exception) {
                report("Link konnte nicht übernommen werden. " + Store.describe(e))
                val text = status
                main.post { Notifier.importFailed(this, text) }
            } finally {
                importing = false
                importPct = -1
                main.post { finishIfIdle() }
            }
        }
    }

    private fun uploadPending() {
        if (busy) {
            notifyRunning()
            return
        }
        val files = Store.pending(this)
        if (files.isEmpty()) {
            finishIfIdle()
            return
        }
        busy = true
        notifyRunning()

        worker.execute {
            val secret = Prefs.secret(this)
            val errors = mutableListOf<String>()
            val started = mutableListOf<Rec>()
            for (f in files) {
                val rec = Store.get(this, f.name)
                rec.phase = Rec.PHASE_UPLOADING
                rec.note = "Wird hochgeladen …"
                rec.progress = 0
                Store.save(this, rec)
                val label = Store.label(rec)
                report("Lade hoch: $label")
                progressPct = 0
                main.post { notifyRunning() }
                try {
                    val res = Uploader.upload(f, rec.context, rec.mail, secret) { done, total ->
                        val pct = (done * 100 / total).toInt()
                        rec.note = "Wird hochgeladen ($pct %)"
                        rec.progress = pct
                        Store.save(this, rec)
                        progressPct = pct
                        main.post { notifyRunning() }
                    }
                    rec.fileId = res.fileId
                    rec.driveName = res.fileName
                    rec.uploadedAt = System.currentTimeMillis()
                    rec.phase = Rec.PHASE_TRANSCRIBING
                    rec.note = res.note
                    rec.progress = -1
                    started += rec
                } catch (e: Exception) {
                    rec.phase = Rec.PHASE_UPLOAD_FAILED
                    rec.note = Store.describe(e)
                    rec.progress = -1
                    errors += "${Store.label(rec)}: ${Store.describe(e)}"
                }
                Store.save(this, rec)
            }
            Store.cleanup(this)

            report(
                if (errors.isEmpty()) "" else "Upload fehlgeschlagen. Die Aufnahme bleibt auf dem Handy."
            )
            busy = false
            progressPct = -1
            if (started.isNotEmpty()) StatusWorker.schedule(this, 30)

            main.post {
                if (errors.isNotEmpty()) Notifier.uploadFailed(this, errors.joinToString("\n"))
                if (started.isNotEmpty()) Notifier.transcribing(this, started)
                finishIfIdle()
            }
        }
    }

    private fun finishIfIdle() {
        if (isRecording() || busy || importing) return
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun action(code: Int, action: String): PendingIntent =
        PendingIntent.getService(
            this,
            code,
            intent(this, action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildRunningNotification(): Notification {
        val b = NotificationCompat.Builder(this, Notifier.CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentIntent(Notifier.openApp(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)

        when {
            isPaused() -> b.setContentTitle("Aufnahme pausiert")
                .setContentText("Bei " + formatElapsed(elapsedMs()) + ". Tippe auf Weiter.")
                .setUsesChronometer(false)
                .setShowWhen(false)
                .addAction(0, "Weiter", action(2, ACTION_RESUME))
                .addAction(0, "Stopp", action(1, ACTION_STOP))
            isRecording() -> b.setContentTitle("Aufnahme läuft")
                .setContentText("Bildschirm darf aus.")
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - elapsedMs())
                .addAction(0, "Pause", action(3, ACTION_PAUSE))
                .addAction(0, "Stopp", action(1, ACTION_STOP))
            busy -> {
                val pct = progressPct
                b.setContentTitle("Lade hoch …")
                    .setContentText(if (pct >= 0) "$pct %" else "")
                    .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            }
            importing -> {
                val pct = importPct
                b.setContentTitle("Lade Datei vom Link …")
                    .setContentText(status)
                    .setProgress(100, pct.coerceAtLeast(0), pct < 0)
            }
            else -> b.setContentTitle("Aufnahme")
        }
        return b.build()
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return String.format(java.util.Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private fun notifyRunning() {
        if (!isRecording() && !busy && !importing) return
        Notifier.post(this, Notifier.ID_RUNNING, buildRunningNotification())
    }

    override fun onDestroy() {
        val file = recordingFile
        val duration = elapsedMs()
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
        pausedAt = 0L
        pausedTotal = 0L
        if (file != null && file.length() > 0L) {
            val rec = Store.get(this, file.name)
            rec.phase = Rec.PHASE_OPEN
            rec.durationMs = duration
            Store.save(this, rec)
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        worker.shutdown()
        super.onDestroy()
    }
}
