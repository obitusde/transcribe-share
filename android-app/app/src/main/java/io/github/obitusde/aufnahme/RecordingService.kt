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
 * ueber den Knopf in der App.
 *
 * Nach dem Stopp laedt derselbe Dienst alle wartenden Aufnahmen hoch (nur
 * die mit Transkription "an") und beendet sich dann. Den Fortschritt der
 * Transkription verfolgt danach der StatusWorker.
 *
 * Ausserdem laedt er Audio von geteilten Links herunter (ACTION_IMPORT_URL),
 * damit ein grosser Podcast-Download nicht abbricht, wenn die App in den
 * Hintergrund geht.
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "io.github.obitusde.aufnahme.START"
        const val ACTION_STOP = "io.github.obitusde.aufnahme.STOP"
        const val ACTION_UPLOAD = "io.github.obitusde.aufnahme.UPLOAD"
        const val ACTION_IMPORT_URL = "io.github.obitusde.aufnahme.IMPORT_URL"

        const val EXTRA_URL = "url"

        const val EXTRA_MODE = "mode"
        const val EXTRA_CONTEXT = "context"
        const val EXTRA_MAIL = "mail"

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
        var importing: Boolean = false
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

    /** Fortschritt fuer die Benachrichtigung, -1 = unbekannt. */
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
                startRecording(intent)
            }
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

    private fun startRecording(intent: Intent) {
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

        val rec = Rec(file.name)
        rec.mode = intent.getStringExtra(EXTRA_MODE) ?: Rec.MODE_TRANSCRIBE
        rec.context = intent.getStringExtra(EXTRA_CONTEXT) ?: ""
        rec.mail = intent.getStringExtra(EXTRA_MAIL) ?: Prefs.MAIL_WORK
        rec.phase = Rec.PHASE_RECORDING
        Store.save(this, rec)

        recorder = r
        startedAt = SystemClock.elapsedRealtime()
        recordingFile = file
        status = "Aufnahme läuft. Der Bildschirm darf aus."

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
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null

        if (file != null) {
            if (kept && file.length() > 0L) {
                // Optionen frisch lesen: die App kann sie waehrend der Aufnahme geaendert haben.
                val rec = Store.get(this, file.name)
                val local = rec.mode == Rec.MODE_LOCAL
                rec.phase = if (local) Rec.PHASE_LOCAL else Rec.PHASE_NEW
                Store.save(this, rec)
                status = if (local) "Gespeichert, nur auf dem Handy: ${file.name}" else "Gespeichert: ${file.name}"
            } else {
                Store.deleteRecording(this, file.name)
                status = "Aufnahme war zu kurz und wurde verworfen."
            }
        }
        uploadPending()
    }

    private fun importUrl(intent: Intent) {
        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finishIfIdle()
            return
        }
        val mode = intent.getStringExtra(EXTRA_MODE) ?: Rec.MODE_TRANSCRIBE
        val context = intent.getStringExtra(EXTRA_CONTEXT) ?: ""
        val mail = intent.getStringExtra(EXTRA_MAIL) ?: Prefs.MAIL_WORK

        importing = true
        progressPct = -1
        status = "Lade Datei vom Link …"
        notifyRunning()

        worker.execute {
            try {
                val imported = Importer.download(this, url) { done, total ->
                    progressPct = if (total > 0) (done * 100 / total).toInt() else -1
                    status = "Lade Datei vom Link: " + Store.sizeText(done) +
                        (if (total > 0) " von " + Store.sizeText(total) else "")
                    main.post { notifyRunning() }
                }
                val rec = Rec(imported.file.name)
                rec.title = imported.title
                rec.mode = mode
                rec.context = context
                rec.mail = mail
                rec.phase = if (mode == Rec.MODE_LOCAL) Rec.PHASE_LOCAL else Rec.PHASE_NEW
                Store.save(this, rec)
                status = "Übernommen: ${imported.title}"
            } catch (e: Exception) {
                status = "Link konnte nicht übernommen werden. " + Store.describe(e)
                val text = status
                main.post { Notifier.importFailed(this, text) }
            } finally {
                importing = false
                progressPct = -1
                main.post { uploadPending() }
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
                Store.save(this, rec)
                val label = rec.title.ifBlank { f.name }
                status = "Lade hoch: $label"
                progressPct = 0
                main.post { notifyRunning() }
                try {
                    val res = Uploader.upload(f, rec.context, rec.mail, secret) { done, total ->
                        val pct = (done * 100 / total).toInt()
                        rec.note = "Wird hochgeladen ($pct %)"
                        rec.progress = pct
                        Store.save(this, rec)
                        progressPct = pct
                        status = "Lade hoch: $label ($pct %)"
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
                    errors += "${f.name}: ${Store.describe(e)}"
                }
                Store.save(this, rec)
            }
            Store.cleanup(this)

            status = if (errors.isEmpty()) {
                "✔ Hochgeladen. Transkription läuft."
            } else {
                "✘ Upload fehlgeschlagen. Die Aufnahme bleibt auf dem Handy.\n" + errors.joinToString("\n")
            }
            busy = false
            progressPct = -1
            if (started.isNotEmpty()) StatusWorker.schedule(this, 30)

            main.post {
                if (errors.isNotEmpty()) Notifier.uploadFailed(this, status)
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

    private fun buildRunningNotification(): Notification {
        val b = NotificationCompat.Builder(this, Notifier.CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentIntent(Notifier.openApp(this))
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
            b.setContentTitle("Aufnahme läuft")
                .setContentText("Bildschirm darf aus.")
                .setUsesChronometer(true)
                .setShowWhen(true)
                .setWhen(System.currentTimeMillis() - (SystemClock.elapsedRealtime() - startedAt))
                .addAction(0, "Stopp", stop)
        } else {
            b.setContentTitle(
                when {
                    busy -> "Lade hoch …"
                    importing -> "Lade Datei vom Link …"
                    else -> "Aufnahme"
                }
            )
                .setContentText(status)
            if (busy || importing) {
                val pct = progressPct
                b.setProgress(100, pct.coerceAtLeast(0), pct < 0)
            }
        }
        return b.build()
    }

    private fun notifyRunning() {
        if (!isRecording() && !busy && !importing) return
        Notifier.post(this, Notifier.ID_RUNNING, buildRunningNotification())
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
