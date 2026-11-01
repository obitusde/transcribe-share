package io.github.obitusde.aufnahme

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private companion object {
        const val REQ_RECORD = 1
        const val REQ_UPLOAD = 2
    }

    private lateinit var timerView: TextView
    private lateinit var recordButton: Button
    private lateinit var statusView: TextView
    private lateinit var transcribeSwitch: Switch
    private lateinit var localHint: TextView
    private lateinit var optionsBox: LinearLayout
    private lateinit var contextInput: EditText
    private lateinit var recentBox: LinearLayout
    private lateinit var mailGroup: RadioGroup
    private lateinit var retryButton: Button
    private lateinit var serverView: TextView
    private lateinit var listBox: LinearLayout

    private val mailIds = mutableMapOf<String, Int>()
    private var listSignature = ""
    private val io = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Notifier.ensureChannels(this)
        Store.cleanup(this)

        val pad = dp(20)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(pad, pad, pad, pad)

        timerView = TextView(this)
        timerView.textSize = 52f
        timerView.typeface = Typeface.MONOSPACE
        timerView.gravity = Gravity.CENTER
        timerView.text = "00:00:00"
        timerView.setPadding(0, dp(8), 0, dp(16))

        recordButton = Button(this)
        recordButton.textSize = 22f
        recordButton.minHeight = dp(88)
        recordButton.isAllCaps = false
        recordButton.setOnClickListener { onRecordPressed() }

        statusView = TextView(this)
        statusView.textSize = 15f
        statusView.setPadding(0, dp(12), 0, dp(4))

        transcribeSwitch = Switch(this)
        transcribeSwitch.text = "Transkription (Upload, Mail, TXT)"
        transcribeSwitch.textSize = 17f
        transcribeSwitch.setPadding(0, dp(8), 0, dp(8))
        transcribeSwitch.isChecked = Prefs.transcribe(this)
        transcribeSwitch.setOnCheckedChangeListener { _, checked ->
            Prefs.setTranscribe(this, checked)
            updateOptionsVisibility()
        }

        localHint = TextView(this)
        localHint.textSize = 14f
        localHint.text = "Aus: Die Aufnahme bleibt nur auf dem Handy. In der Liste unten kannst du sie " +
            "mit \"Teilen\" an andere Apps geben (z. B. Claude, Gemini)."

        contextInput = EditText(this)
        contextInput.hint = "Kontext, z. B. 1:1 mit Emmy"
        contextInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        contextInput.isSingleLine = true

        recentBox = LinearLayout(this)
        recentBox.orientation = LinearLayout.HORIZONTAL
        val recentScroll = HorizontalScrollView(this)
        recentScroll.isHorizontalScrollBarEnabled = false
        recentScroll.addView(recentBox)

        val mailLabel = TextView(this)
        mailLabel.text = "Mail an:"
        mailLabel.textSize = 14f
        mailLabel.setPadding(0, dp(8), 0, 0)

        mailGroup = RadioGroup(this)
        mailGroup.orientation = RadioGroup.HORIZONTAL
        for ((key, label) in listOf(
            Prefs.MAIL_WORK to "Arbeit",
            Prefs.MAIL_PRIVATE to "Privat",
            Prefs.MAIL_NONE to "Keine"
        )) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = label
            rb.setPadding(0, 0, dp(12), 0)
            mailIds[key] = rb.id
            mailGroup.addView(rb)
        }
        mailGroup.check(mailIds[Prefs.mail(this)] ?: mailIds.getValue(Prefs.MAIL_WORK))
        mailGroup.setOnCheckedChangeListener { _, _ -> Prefs.setMail(this, selectedMail()) }

        optionsBox = LinearLayout(this)
        optionsBox.orientation = LinearLayout.VERTICAL
        optionsBox.addView(contextInput, full())
        optionsBox.addView(recentScroll, full())
        optionsBox.addView(mailLabel, full())
        optionsBox.addView(mailGroup, full())

        retryButton = Button(this)
        retryButton.text = "Wartende Aufnahmen hochladen"
        retryButton.isAllCaps = false
        retryButton.setOnClickListener {
            if (ensurePermissions(REQ_UPLOAD)) launch(RecordingService.intent(this, RecordingService.ACTION_UPLOAD))
        }

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL

        serverView = TextView(this)
        serverView.textSize = 13f
        serverView.setPadding(0, dp(24), 0, dp(4))

        val settingsButton = Button(this, null, android.R.attr.buttonStyleSmall)
        settingsButton.text = "Schlüssel einstellen"
        settingsButton.isAllCaps = false
        settingsButton.setOnClickListener { showSecretDialog() }

        root.addView(timerView, full())
        root.addView(recordButton, full())
        root.addView(statusView, full())
        root.addView(sectionTitle("Nächste Aufnahme"), full())
        root.addView(transcribeSwitch, full())
        root.addView(localHint, full())
        root.addView(optionsBox, full())
        root.addView(retryButton, full())
        root.addView(sectionTitle("Aufnahmen (nach dem Upload ${Store.KEEP_DAYS} Tage auf dem Handy)"), full())
        root.addView(listBox, full())
        root.addView(serverView, full())
        root.addView(settingsButton, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))

        val scroll = ScrollView(this)
        scroll.fitsSystemWindows = true
        scroll.addView(root)
        setContentView(scroll)

        loadActiveOptions()
        rebuildRecent()
        updateOptionsVisibility()
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresh)
        checkServer()
        if (Store.awaitingStatus(this).isNotEmpty()) StatusWorker.schedule(this, 0)
    }

    override fun onPause() {
        handler.removeCallbacks(refresh)
        saveActiveOptions()
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdown()
        super.onDestroy()
    }

    // --- Aufnahme -------------------------------------------------------

    private fun onRecordPressed() {
        if (RecordingService.isRecording()) {
            saveActiveOptions()
            startService(RecordingService.intent(this, RecordingService.ACTION_STOP))
            if (transcribeSwitch.isChecked) Prefs.addRecentContext(this, contextInput.text.toString())
            contextInput.setText("")
            rebuildRecent()
            return
        }
        if (ensurePermissions(REQ_RECORD)) launchStart()
    }

    private fun launchStart() {
        timerView.text = "00:00:00"
        val i = RecordingService.intent(this, RecordingService.ACTION_START)
        i.putExtra(RecordingService.EXTRA_MODE, currentMode())
        i.putExtra(RecordingService.EXTRA_CONTEXT, contextInput.text.toString().trim())
        i.putExtra(RecordingService.EXTRA_MAIL, selectedMail())
        launch(i)
    }

    private fun launch(i: Intent) {
        try {
            startForegroundService(i)
        } catch (e: Exception) {
            RecordingService.status = "Dienst konnte nicht starten. " + Store.describe(e)
        }
    }

    private fun currentMode(): String =
        if (transcribeSwitch.isChecked) Rec.MODE_TRANSCRIBE else Rec.MODE_LOCAL

    private fun selectedMail(): String =
        mailIds.entries.firstOrNull { it.value == mailGroup.checkedRadioButtonId }?.key ?: Prefs.MAIL_WORK

    /** Was in der App eingestellt ist, gilt fuer die laufende Aufnahme - auch beim Stopp ueber die Benachrichtigung. */
    private fun saveActiveOptions() {
        val f = RecordingService.recordingFile ?: return
        val rec = Store.get(this, f.name)
        rec.mode = currentMode()
        rec.context = contextInput.text.toString().trim()
        rec.mail = selectedMail()
        Store.save(this, rec)
    }

    private fun loadActiveOptions() {
        val f = RecordingService.recordingFile ?: return
        val rec = Store.get(this, f.name)
        transcribeSwitch.isChecked = rec.mode == Rec.MODE_TRANSCRIBE
        contextInput.setText(rec.context)
        mailIds[rec.mail]?.let { mailGroup.check(it) }
    }

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
            REQ_RECORD -> launchStart()
            REQ_UPLOAD -> launch(RecordingService.intent(this, RecordingService.ACTION_UPLOAD))
        }
    }

    // --- Anzeige ----------------------------------------------------------

    private fun render() {
        val recording = RecordingService.isRecording()
        recordButton.text = when {
            !recording -> "●  Aufnahme starten"
            transcribeSwitch.isChecked -> "■  Stopp & hochladen"
            else -> "■  Stopp"
        }
        if (recording) {
            timerView.text = formatElapsed(SystemClock.elapsedRealtime() - RecordingService.startedAt)
        }
        statusView.text = RecordingService.status.ifEmpty { "Bereit. Nach dem Start darf der Bildschirm aus." }
        retryButton.visibility =
            if (Store.pending(this).isNotEmpty() && !RecordingService.busy) View.VISIBLE else View.GONE
        serverView.text = "Server: " + Prefs.serverMessage(this)
        renderList()
    }

    private fun updateOptionsVisibility() {
        val on = transcribeSwitch.isChecked
        optionsBox.visibility = if (on) View.VISIBLE else View.GONE
        localHint.visibility = if (on) View.GONE else View.VISIBLE
    }

    private fun rebuildRecent() {
        recentBox.removeAllViews()
        for (ctx in Prefs.recentContexts(this)) {
            val b = Button(this, null, android.R.attr.buttonStyleSmall)
            b.text = ctx
            b.isAllCaps = false
            b.setOnClickListener {
                contextInput.setText(ctx)
                contextInput.setSelection(ctx.length)
            }
            recentBox.addView(b)
        }
    }

    private fun renderList() {
        val active = RecordingService.recordingFile
        val files = Store.all(this).take(20)
        val recs = files.map { Store.get(this, it.name) }
        val sig = buildString {
            append(active?.name).append(RecordingService.busy)
            for (r in recs) append('|').append(r.name).append(r.phase).append(r.note).append(r.hasTxt).append(r.context)
        }
        if (sig == listSignature) return
        listSignature = sig

        listBox.removeAllViews()
        if (files.isEmpty()) {
            val empty = TextView(this)
            empty.text = "Noch keine Aufnahmen."
            listBox.addView(empty)
            return
        }
        for (i in files.indices) listBox.addView(itemView(files[i], recs[i], files[i] == active), full())
    }

    private fun itemView(f: File, rec: Rec, isActive: Boolean): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(0, dp(10), 0, dp(10))

        val title = TextView(this)
        title.text = "${f.name}  (${Store.sizeText(f)})"
        title.setTypeface(title.typeface, Typeface.BOLD)
        box.addView(title)

        val details = TextView(this)
        details.text = Store.details(rec, isActive)
        box.addView(details)

        if (!isActive) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            fun add(label: String, action: () -> Unit) {
                val b = Button(this, null, android.R.attr.buttonStyleSmall)
                b.text = label
                b.isAllCaps = false
                b.setOnClickListener { action() }
                row.addView(b)
            }
            add("Teilen") { shareFile(f, "audio/ogg", "Aufnahme teilen") }
            val txt = Store.txtFile(this, rec.name)
            if (rec.hasTxt && txt.exists()) {
                add("TXT teilen") { shareFile(txt, "text/plain", "Transkript teilen") }
                add("TXT speichern") { saveToDownloads(txt) }
            }
            if (rec.phase == Rec.PHASE_LOCAL) add("Transkribieren") { confirmTranscribe(rec) }
            if (rec.phase != Rec.PHASE_UPLOADING) add("Löschen") { confirmDelete(f) }
            val rowScroll = HorizontalScrollView(this)
            rowScroll.isHorizontalScrollBarEnabled = false
            rowScroll.addView(row)
            box.addView(rowScroll)
        }
        return box
    }

    // --- Aktionen in der Liste ---------------------------------------------

    private fun shareFile(f: File, mime: String, title: String) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
            val send = Intent(Intent.ACTION_SEND)
            send.type = mime
            send.putExtra(Intent.EXTRA_STREAM, uri)
            send.clipData = ClipData.newRawUri(f.name, uri)
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(Intent.createChooser(send, title))
        } catch (e: Exception) {
            toast("Teilen fehlgeschlagen: " + Store.describe(e))
        }
    }

    private fun saveToDownloads(txt: File) {
        try {
            val values = ContentValues()
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, txt.name)
            values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Transkripte")
            val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Android hat keinen Speicherplatz bereitgestellt.")
            val out = contentResolver.openOutputStream(uri)
                ?: throw IOException("Datei konnte nicht geöffnet werden.")
            out.use { o -> txt.inputStream().use { it.copyTo(o) } }
            toast("Gespeichert unter Download/Transkripte/${txt.name}")
        } catch (e: Exception) {
            toast("Speichern fehlgeschlagen: " + Store.describe(e))
        }
    }

    private fun confirmTranscribe(rec: Rec) {
        val typed = contextInput.text.toString().trim()
        val context = typed.ifEmpty { rec.context }
        val mail = selectedMail()
        val message = buildString {
            append("Die Aufnahme wird hochgeladen und transkribiert.\n\n")
            append("Mail an: ").append(Store.mailLabel(mail)).append('\n')
            append("Kontext: ").append(context.ifEmpty { "(keiner)" })
            append("\n\nMail und Kontext stellst du oben bei eingeschalteter Transkription ein.")
        }
        AlertDialog.Builder(this)
            .setTitle("Transkribieren?")
            .setMessage(message)
            .setPositiveButton("Hochladen") { _, _ ->
                rec.mode = Rec.MODE_TRANSCRIBE
                rec.context = context
                rec.mail = mail
                rec.phase = Rec.PHASE_NEW
                rec.note = ""
                Store.save(this, rec)
                listSignature = ""
                if (ensurePermissions(REQ_UPLOAD)) launch(RecordingService.intent(this, RecordingService.ACTION_UPLOAD))
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    private fun confirmDelete(f: File) {
        AlertDialog.Builder(this)
            .setTitle("Aufnahme löschen?")
            .setMessage("${f.name} wird auf dem Handy gelöscht. Eine Kopie in Drive bleibt erhalten.")
            .setPositiveButton("Löschen") { _, _ ->
                Store.deleteRecording(this, f.name)
                listSignature = ""
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // --- Schluessel und Server -------------------------------------------

    private fun showSecretDialog() {
        val info = TextView(this)
        info.text = "Derselbe Schlüssel muss im Apps Script als Skripteigenschaft UPLOAD_SECRET stehen. " +
            "Beim Speichern wird er in die Zwischenablage kopiert."
        info.setPadding(0, 0, 0, dp(8))

        val input = EditText(this)
        input.hint = "Geheimer Schlüssel"
        input.isSingleLine = true
        input.setText(Prefs.secret(this))

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        box.addView(info)
        box.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Schlüssel")
            .setView(box)
            .setPositiveButton("Speichern") { _, _ ->
                val value = input.text.toString().trim()
                Prefs.setSecret(this, value)
                if (value.isNotEmpty()) {
                    getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("Schlüssel", value))
                    toast("Gespeichert und in die Zwischenablage kopiert.")
                }
                checkServer()
            }
            .setNeutralButton("Zufällig erzeugen", null)
            .setNegativeButton("Abbrechen", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { input.setText(randomSecret()) }
        }
        dialog.show()
    }

    private fun randomSecret(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun checkServer() {
        val secret = Prefs.secret(this)
        val app = applicationContext
        io.execute {
            try {
                val info = Uploader.hello(secret)
                val message = when {
                    !info.authOk ->
                        "Schlüssel fehlt oder ist falsch. Bitte unten eintragen (gleicher Wert wie UPLOAD_SECRET im Script)."
                    info.api < 2 ->
                        "altes Script. Kontext, Mailauswahl und TXT gibt es erst nach dem Script-Update. " +
                            "Bis dahin gehen alle Mails an die Arbeitsadresse."
                    else -> buildString {
                        append("aktuell ✔")
                        if (!info.secretRequired) append(". Noch ohne Schlüssel-Schutz (UPLOAD_SECRET fehlt im Script).")
                        if (!info.mailPrivate) append(". Private Adresse fehlt im Script (MAIL_PRIVATE), \"Privat\" geht an Arbeit.")
                    }
                }
                Prefs.setServer(app, info.api, message)
            } catch (e: Exception) {
                Prefs.setServer(app, Prefs.serverApi(app), "nicht erreichbar (${Store.describe(e)})")
            }
        }
    }

    // --- Hilfen -------------------------------------------------------------

    private fun sectionTitle(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.textSize = 14f
        t.setTypeface(t.typeface, Typeface.BOLD)
        t.setPadding(0, dp(24), 0, dp(4))
        return t
    }

    private fun full() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT
    )

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
