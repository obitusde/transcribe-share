package io.github.obitusde.aufnahme

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Locale

class MainActivity : Activity() {

    private companion object {
        const val REQ_RECORD = 1
        const val REQ_UPLOAD = 2
    }

    private lateinit var timerView: TextView
    private lateinit var recordButton: Button
    private lateinit var statusView: TextView
    private lateinit var retryButton: Button
    private lateinit var listView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.cleanup(this, RecordingService.recordingFile)

        val pad = dp(20)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        timerView = TextView(this).apply {
            textSize = 56f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            text = "00:00:00"
            setPadding(0, dp(24), 0, dp(24))
        }
        recordButton = Button(this).apply {
            textSize = 22f
            minHeight = dp(96)
            setOnClickListener { onRecordPressed() }
        }
        statusView = TextView(this).apply {
            textSize = 16f
            setPadding(0, dp(16), 0, dp(8))
        }
        retryButton = Button(this).apply {
            text = "Wartende Aufnahmen hochladen"
            setOnClickListener { onRetryPressed() }
        }
        val listTitle = TextView(this).apply {
            text = "Aufnahmen auf dem Handy (werden ${Store.KEEP_DAYS} Tage nach dem Upload geloescht)"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(24), 0, dp(8))
        }
        listView = TextView(this).apply { textSize = 14f }

        val full = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        root.addView(timerView, LinearLayout.LayoutParams(full))
        root.addView(recordButton, LinearLayout.LayoutParams(full))
        root.addView(statusView, LinearLayout.LayoutParams(full))
        root.addView(retryButton, LinearLayout.LayoutParams(full))
        root.addView(listTitle, LinearLayout.LayoutParams(full))
        root.addView(listView, LinearLayout.LayoutParams(full))

        val scroll = ScrollView(this).apply {
            fitsSystemWindows = true
            addView(root)
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    private fun onRecordPressed() {
        if (RecordingService.isRecording()) {
            startService(RecordingService.intent(this, RecordingService.ACTION_STOP))
            return
        }
        if (ensurePermissions(REQ_RECORD)) launch(RecordingService.ACTION_START)
    }

    private fun onRetryPressed() {
        if (ensurePermissions(REQ_UPLOAD)) launch(RecordingService.ACTION_UPLOAD)
    }

    /** true, wenn alles da ist. Sonst wird gefragt und es geht im Callback weiter. */
    private fun ensurePermissions(requestCode: Int): Boolean {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return true
        requestPermissions(missing.toTypedArray(), requestCode)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingService.status = "Ohne Mikrofon-Erlaubnis kann die App nicht aufnehmen."
            return
        }
        when (requestCode) {
            REQ_RECORD -> launch(RecordingService.ACTION_START)
            REQ_UPLOAD -> launch(RecordingService.ACTION_UPLOAD)
        }
    }

    private fun launch(action: String) {
        if (action == RecordingService.ACTION_START) timerView.text = "00:00:00"
        try {
            startForegroundService(RecordingService.intent(this, action))
        } catch (e: Exception) {
            RecordingService.status = "Dienst konnte nicht starten. " + Store.describe(e)
        }
    }

    private fun render() {
        val recording = RecordingService.isRecording()
        recordButton.text = if (recording) "■  Stopp & hochladen" else "●  Aufnahme starten"
        if (recording) {
            timerView.text = formatElapsed(SystemClock.elapsedRealtime() - RecordingService.startedAt)
        }
        statusView.text = RecordingService.status.ifEmpty {
            "Bereit. Nach dem Start darf der Bildschirm aus."
        }

        val active = RecordingService.recordingFile
        val pending = Store.pending(this, active)
        retryButton.visibility =
            if (pending.isNotEmpty() && !RecordingService.busy) View.VISIBLE else View.GONE

        listView.text = Store.all(this).take(15)
            .joinToString("\n\n") { Store.summary(this, it, active) }
            .ifEmpty { "Noch keine Aufnahmen." }
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
