package io.github.obitusde.aufnahme

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.net.Uri
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
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Ablauf:
 *  1. Oben gibt es nur den Aufnahmeknopf (waehrend der Aufnahme: Pause/Weiter und Stopp).
 *  2. Nach dem Stopp erscheint oben die Entscheidung fuer genau diese Aufnahme:
 *     Transkribieren (mit Kontext und Mail), lokal behalten, teilen oder loeschen.
 *     "Spaeter entscheiden" laesst sie als offen in der Liste stehen.
 *  3. Unten die Liste: offen, in Arbeit, Transkripte, lokal behaltene Aufnahmen.
 */
class MainActivity : Activity() {

    companion object {
        private const val REQ_PERMISSIONS = 1
        private const val STATUS_SHOW_MS = 20_000L
        private val URL_PATTERN = Regex("https?://\\S+")

        /** Ob die App gerade im Vordergrund ist (dann keine Entscheidungs-Benachrichtigung). */
        @Volatile
        var visible = false
            private set
    }

    /** Was nach erteilter Erlaubnis passieren soll. */
    private var afterPermission: (() -> Unit)? = null

    private lateinit var ui: Ui
    private lateinit var scroll: ScrollView
    private lateinit var topBox: LinearLayout
    private lateinit var bannerBox: LinearLayout
    private lateinit var bannerText: TextView
    private lateinit var bannerBar: ProgressBar
    private lateinit var listBox: LinearLayout
    private lateinit var serverView: TextView
    private lateinit var usageView: TextView
    private lateinit var archiveView: TextView

    private var timerView: TextView? = null
    private var topKey = ""
    private var listSignature = ""

    // Eingaben im Entscheidungsbereich
    private var contextInput: EditText? = null
    private var mailGroup: RadioGroup? = null
    private val mailIds = mutableMapOf<String, Int>()

    @Volatile
    private var copying = false

    @Volatile
    private var converting = false

    @Volatile
    private var convertPct = 0

    private val player = Player()

    /** Ein Abspiel-Balken in einer Karte. inTop = im Entscheidungsbereich oben. */
    private class PlayerRow(
        val name: String,
        val file: File,
        val durationMs: Int,
        val inTop: Boolean,
        val button: android.widget.Button,
        val seek: SeekBar,
        val time: TextView
    ) {
        var dragging = false
    }

    private val playerRows = mutableListOf<PlayerRow>()
    private var buildingTop = false

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
        ui = Ui(this)
        Notifier.ensureChannels(this)
        actionBar?.hide()
        window.statusBarColor = ui.bg
        window.navigationBarColor = ui.bg
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !ui.dark
            isAppearanceLightNavigationBars = !ui.dark
        }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(ui.dp(16), ui.dp(12), ui.dp(16), ui.dp(28))

        val header = ui.text("Aufnahme", 26f, ui.text, bold = true)
        header.setPadding(ui.dp(4), ui.dp(4), 0, ui.dp(12))
        root.addView(header, ui.full())

        topBox = LinearLayout(this)
        topBox.orientation = LinearLayout.VERTICAL
        root.addView(topBox, ui.full())

        bannerBox = ui.card(background = ui.accentSoft)
        bannerText = ui.text("", 14f, ui.text)
        bannerBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        tint(bannerBar)
        bannerBox.addView(bannerText, ui.full())
        bannerBox.addView(bannerBar, ui.margins(ui.full(), t = 8))
        bannerBox.visibility = View.GONE
        root.addView(bannerBox, ui.margins(ui.full(), t = 12))

        listBox = LinearLayout(this)
        listBox.orientation = LinearLayout.VERTICAL
        root.addView(listBox, ui.full())

        val footer = LinearLayout(this)
        footer.orientation = LinearLayout.VERTICAL
        footer.setPadding(ui.dp(4), ui.dp(28), ui.dp(4), 0)
        serverView = ui.text("", 13f, ui.sub)
        usageView = ui.text("", 13f, ui.sub)
        archiveView = ui.text("", 13f, ui.sub)
        val versionView = ui.text("Version " + versionName(), 13f, ui.sub)
        footer.addView(serverView, ui.full())
        footer.addView(usageView, ui.margins(ui.full(), t = 4))
        footer.addView(archiveView, ui.margins(ui.full(), t = 4))
        footer.addView(versionView, ui.margins(ui.full(), t = 4))
        val keyButton = ui.button("Schlüssel einstellen", Ui.Kind.OUTLINE) { showSecretDialog() }
        footer.addView(keyButton, ui.margins(ui.wrap(), t = 12))
        root.addView(footer, ui.full())

        scroll = ScrollView(this)
        scroll.fitsSystemWindows = true
        scroll.setBackgroundColor(ui.bg)
        scroll.addView(root)
        setContentView(scroll)

        if (savedInstanceState == null) handleShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    override fun onResume() {
        super.onResume()
        visible = true
        val focus = Prefs.focus(this)
        if (focus.isNotEmpty()) Notifier.cancelDecide(this, focus)
        topKey = ""
        listSignature = ""
        handler.post(refresh)
        checkServer()
        val app = applicationContext
        io.execute {
            Store.cleanup(app)
            Store.fillMeta(app)
            if (Store.awaitingStatus(app).isNotEmpty()) StatusWorker.schedule(app, 0)
        }
    }

    override fun onPause() {
        visible = false
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onStop() {
        player.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player.release()
        io.shutdown()
        super.onDestroy()
    }

    // --- Anzeige ----------------------------------------------------------

    private fun render() {
        val recording = RecordingService.isRecording()
        val paused = RecordingService.isPaused()
        val focus = focusRec()
        val key = when {
            recording -> "rec:$paused"
            focus != null -> "decide:${focus.name}:${focus.phase}"
            else -> "idle"
        }
        if (key != topKey) {
            topKey = key
            topBox.removeAllViews()
            playerRows.removeAll { it.inTop }
            contextInput = null
            mailGroup = null
            timerView = null
            buildingTop = true
            topBox.addView(
                if (focus != null && !recording) decisionCard(focus) else recorderCard(recording, paused),
                ui.full()
            )
            buildingTop = false
        }
        timerView?.text = formatElapsed(RecordingService.elapsedMs())

        renderBanner()
        renderList(focus)
        updatePlayers()

        serverView.text = "Server: " + Prefs.serverMessage(this)
        usageView.text = usageText()
        usageView.visibility = if (usageView.text.isEmpty()) View.GONE else View.VISIBLE
        archiveView.text = archiveText()
        archiveView.visibility = if (archiveView.text.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Die Aufnahme, fuer die oben entschieden wird - oder null. */
    private fun focusRec(): Rec? {
        val name = Prefs.focus(this)
        if (name.isEmpty()) return null
        val rec = Store.get(this, name)
        val ok = (rec.phase == Rec.PHASE_OPEN || rec.phase == Rec.PHASE_LOCAL) && Store.hasAudio(this, rec)
        if (!ok) {
            Prefs.setFocus(this, "")
            return null
        }
        return rec
    }

    private fun renderBanner() {
        val status = RecordingService.status
        val fresh = status.isNotEmpty() && SystemClock.elapsedRealtime() - RecordingService.statusAt < STATUS_SHOW_MS
        val importing = RecordingService.importing
        val show = importing || copying || converting || fresh
        bannerBox.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        bannerText.text = when {
            converting -> "Wandle in M4A um … $convertPct %"
            status.isNotEmpty() -> status
            copying -> "Übernehme Datei …"
            else -> "Lade Datei vom Link …"
        }
        val busy = importing || copying || converting
        bannerBar.visibility = if (busy) View.VISIBLE else View.GONE
        val pct = if (converting) convertPct else RecordingService.importPct
        bannerBar.isIndeterminate = !(importing || converting) || pct < 0
        if (!bannerBar.isIndeterminate) bannerBar.progress = pct
    }

    // --- Oben: Aufnahme ------------------------------------------------------

    private fun recorderCard(recording: Boolean, paused: Boolean): View {
        val c = ui.card()
        c.setPadding(ui.dp(18), ui.dp(18), ui.dp(18), ui.dp(18))

        val badge = when {
            paused -> ui.badge("❚❚  Pausiert", ui.orange, ui.orangeSoft)
            recording -> ui.badge("●  Aufnahme läuft", ui.red, ui.redSoft)
            else -> ui.badge("Bereit", ui.sub, ui.greySoft)
        }
        val badgeParams = ui.wrap()
        badgeParams.gravity = Gravity.CENTER_HORIZONTAL
        c.addView(badge, badgeParams)

        val timer = ui.text("00:00:00", 54f, when {
            paused -> ui.orange
            recording -> ui.red
            else -> ui.sub
        })
        timer.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        timer.gravity = Gravity.CENTER
        timer.setPadding(0, ui.dp(6), 0, ui.dp(14))
        timerView = timer
        c.addView(timer, ui.full())

        if (!recording) {
            c.addView(ui.button("●   Aufnahme starten", Ui.Kind.RECORD, big = true) {
                withPermissions { launchStart() }
            }, ui.full())
            c.addView(hint("Danach entscheidest du: transkribieren, nur lokal behalten oder teilen."), ui.margins(ui.full(), t = 12))
        } else {
            val pause = if (paused) {
                ui.button("▶  Weiter", Ui.Kind.RECORD, big = true) { serviceAction(RecordingService.ACTION_RESUME) }
            } else {
                ui.button("❚❚  Pause", Ui.Kind.TONAL, big = true) { serviceAction(RecordingService.ACTION_PAUSE) }
            }
            val stop = ui.button("■  Stopp", Ui.Kind.PRIMARY, big = true) { serviceAction(RecordingService.ACTION_STOP) }
            c.addView(ui.row(pause, stop), ui.full())
            c.addView(
                hint("Der Bildschirm darf aus. Pause und Stopp gehen auch über die Benachrichtigung."),
                ui.margins(ui.full(), t = 12)
            )
        }
        return c
    }

    private fun hint(s: String): TextView {
        val t = ui.text(s, 13f, ui.sub)
        t.gravity = Gravity.CENTER
        return t
    }

    private fun launchStart() {
        player.release()
        RecordingService.report("")
        launch(RecordingService.intent(this, RecordingService.ACTION_START))
    }

    private fun serviceAction(action: String) {
        try {
            startService(RecordingService.intent(this, action))
        } catch (e: Exception) {
            RecordingService.report("Ging nicht: " + Store.describe(e))
        }
    }

    private fun launch(i: Intent) {
        try {
            startForegroundService(i)
        } catch (e: Exception) {
            RecordingService.report("Dienst konnte nicht starten. " + Store.describe(e))
        }
    }

    // --- Oben: Entscheidung -------------------------------------------------

    private fun decisionCard(rec: Rec): View {
        val isLocal = rec.phase == Rec.PHASE_LOCAL
        val c = ui.card(stripe = if (isLocal) ui.grey else ui.orange)

        val badge = if (isLocal) ui.badge("Lokal behalten", ui.grey, ui.greySoft)
        else ui.badge("⚠  Noch nicht entschieden", ui.orange, ui.orangeSoft)
        c.addView(badge, ui.wrap())

        c.addView(
            ui.text(if (isLocal) "Was soll mit der Aufnahme passieren?" else "Aufnahme gespeichert – was soll damit passieren?", 19f, ui.text, bold = true),
            ui.margins(ui.full(), t = 10)
        )
        addIdentity(c, rec)

        addPlayer(c, rec)

        c.addView(divider(), ui.margins(ui.full(), t = 16, b = 14))

        c.addView(ui.text("Transkribieren", 17f, ui.text, bold = true), ui.full())
        val days = Prefs.archiveDays(this)
        c.addView(
            ui.text(
                "Upload zu Google Drive → AssemblyAI → Mail und TXT. Sobald das TXT auf dem Handy ist, " +
                    "wird die Aufnahme hier gelöscht" + (if (days > 0) ", in Drive nach $days Tagen." else "."),
                13f, ui.sub
            ),
            ui.margins(ui.full(), t = 2, b = 10)
        )

        val input = EditText(this)
        input.hint = "Kontext (optional), z. B. 1:1 mit Emmy"
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.isSingleLine = true
        input.imeOptions = EditorInfo.IME_ACTION_DONE
        input.setTextColor(ui.text)
        input.setHintTextColor(ui.sub)
        input.textSize = 16f
        input.background = ui.rounded(ui.field, 12, ui.line)
        input.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        input.setText(rec.context)
        contextInput = input
        c.addView(input, ui.full())

        val recent = Prefs.recentContexts(this)
        if (recent.isNotEmpty()) {
            val chips = FlowLayout(this, ui.dp(8))
            for (ctx in recent) {
                val chip = ui.button(ctx, Ui.Kind.OUTLINE) {
                    input.setText(ctx)
                    input.setSelection(ctx.length)
                }
                chip.textSize = 14f
                chip.setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
                chip.minHeight = ui.dp(38)
                chip.minimumHeight = ui.dp(38)
                chip.setPadding(ui.dp(14), 0, ui.dp(14), 0)
                chips.addView(chip)
            }
            c.addView(ui.text("Zuletzt verwendet:", 13f, ui.sub), ui.margins(ui.full(), t = 10, b = 6))
            c.addView(chips, ui.full())
        }

        c.addView(ui.text("Mail an:", 13f, ui.sub), ui.margins(ui.full(), t = 12))
        val group = RadioGroup(this)
        group.orientation = RadioGroup.HORIZONTAL
        mailIds.clear()
        for ((key, label) in listOf(
            Prefs.MAIL_WORK to "Arbeit",
            Prefs.MAIL_PRIVATE to "Privat",
            Prefs.MAIL_NONE to "Keine"
        )) {
            val rb = RadioButton(this)
            rb.id = View.generateViewId()
            rb.text = label
            rb.textSize = 16f
            rb.setTextColor(ui.text)
            rb.buttonTintList = ColorStateList.valueOf(ui.accent)
            rb.setPadding(0, 0, ui.dp(14), 0)
            mailIds[key] = rb.id
            group.addView(rb)
        }
        group.check(mailIds[Prefs.mail(this)] ?: mailIds.getValue(Prefs.MAIL_WORK))
        mailGroup = group
        c.addView(group, ui.full())

        c.addView(ui.button("Transkribieren", Ui.Kind.PRIMARY, big = true) { decideTranscribe(rec) }, ui.margins(ui.full(), t = 12))

        c.addView(divider(), ui.margins(ui.full(), t = 18, b = 14))
        c.addView(ui.text("Oder ohne Transkription", 17f, ui.text, bold = true), ui.full())
        c.addView(
            ui.text(
                "Die Aufnahme bleibt nur auf dem Handy. Mit Teilen gibst du sie z. B. an Claude oder Gemini. " +
                    "Für Copilot, Word oder WhatsApp: „Als M4A teilen“.",
                13f, ui.sub
            ),
            ui.margins(ui.full(), t = 2, b = 10)
        )
        val keep = ui.button("Lokal behalten", Ui.Kind.TONAL) { decideLocal(rec, shared = false) }
        val share = ui.button("Teilen …", Ui.Kind.TONAL) {
            shareAudio(rec)
            decideLocal(rec, shared = true)
        }
        val m4a = ui.button("Als M4A teilen", Ui.Kind.TONAL) {
            decideLocal(rec, shared = true)
            shareAsM4a(rec)
        }
        if (!isLocal) c.addView(ui.row(keep), ui.full())
        c.addView(
            if (isOgg(rec)) ui.row(share, m4a) else ui.row(share),
            ui.margins(ui.full(), t = if (isLocal) 0 else 10)
        )
        val delete = ui.button("Löschen", Ui.Kind.DANGER) { confirmDelete(rec) }
        val later = ui.button(if (isLocal) "Abbrechen" else "Später entscheiden", Ui.Kind.OUTLINE) { closeFocus(rec) }
        c.addView(ui.row(delete, later), ui.margins(ui.full(), t = 10))
        return c
    }

    private fun divider(): View {
        val v = View(this)
        v.setBackgroundColor(ui.line)
        v.minimumHeight = ui.dp(1)
        return v
    }

    private fun selectedMail(): String {
        val g = mailGroup ?: return Prefs.mail(this)
        return mailIds.entries.firstOrNull { it.value == g.checkedRadioButtonId }?.key ?: Prefs.MAIL_WORK
    }

    private fun decideTranscribe(rec: Rec) {
        val ctx = contextInput?.text?.toString()?.trim() ?: rec.context
        val mail = selectedMail()
        val r = Store.get(this, rec.name)
        r.mode = Rec.MODE_TRANSCRIBE
        r.context = ctx
        r.mail = mail
        r.phase = Rec.PHASE_NEW
        r.note = ""
        r.progress = -1
        Store.save(this, r)
        Prefs.setMail(this, mail)
        Prefs.addRecentContext(this, ctx)
        closeFocus(r)
        withPermissions { launch(RecordingService.intent(this, RecordingService.ACTION_UPLOAD)) }
    }

    private fun decideLocal(rec: Rec, shared: Boolean) {
        val r = Store.get(this, rec.name)
        r.mode = Rec.MODE_LOCAL
        r.phase = Rec.PHASE_LOCAL
        if (shared) r.shared = true
        Store.save(this, r)
        closeFocus(r)
    }

    /** Entscheidungsbereich schliessen: oben wieder nur der Aufnahmeknopf. */
    private fun closeFocus(rec: Rec) {
        Prefs.setFocus(this, "")
        Notifier.cancelDecide(this, rec.name)
        hideKeyboard()
        topKey = ""
        listSignature = ""
        render()
    }

    private fun openFocus(rec: Rec) {
        Prefs.setFocus(this, rec.name)
        topKey = ""
        listSignature = ""
        render()
        scroll.post { scroll.smoothScrollTo(0, 0) }
    }

    private fun hideKeyboard() {
        val v = currentFocus ?: return
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(v.windowToken, 0)
        v.clearFocus()
    }

    // --- Unten: Liste ---------------------------------------------------------

    private fun renderList(focus: Rec?) {
        val active = RecordingService.recordingFile?.name
        val recs = Store.entries(this).filter { it.name != focus?.name && it.name != active }
        val sig = buildString {
            append(RecordingService.busy).append(Prefs.serverApi(this@MainActivity)).append(Prefs.archiveDays(this@MainActivity))
            for (r in recs) {
                append('|').append(r.name).append(r.phase).append(r.note).append(r.hasTxt).append(r.shared)
                append(r.context).append(r.progress).append(Store.since(r)).append(r.durationMs)
                append(r.title).append(r.topic).append(Store.hasAudio(this@MainActivity, r))
            }
        }
        if (sig == listSignature) return
        listSignature = sig

        listBox.removeAllViews()
        playerRows.removeAll { !it.inTop }
        val open = recs.filter { it.phase == Rec.PHASE_OPEN }
        val working = recs.filter {
            it.phase in setOf(Rec.PHASE_NEW, Rec.PHASE_UPLOADING, Rec.PHASE_UPLOAD_FAILED, Rec.PHASE_TRANSCRIBING, Rec.PHASE_FAILED)
        }
        val done = recs.filter { it.phase == Rec.PHASE_DONE }
        val local = recs.filter { it.phase == Rec.PHASE_LOCAL }

        if (recs.isEmpty()) {
            listBox.addView(section("Noch keine Aufnahmen", ui.sub), ui.full())
            return
        }
        if (open.isNotEmpty()) {
            listBox.addView(section("⚠  Noch nicht entschieden (${open.size})", ui.orange), ui.full())
            open.forEach { addItem(openItem(it)) }
        }
        if (working.isNotEmpty()) {
            listBox.addView(section("In Arbeit (${working.size})", ui.accent), ui.full())
            working.forEach { addItem(workItem(it)) }
        }
        if (done.isNotEmpty()) {
            listBox.addView(section("Transkripte (${done.size})", ui.green), ui.full())
            done.forEach { addItem(doneItem(it)) }
        }
        if (local.isNotEmpty()) {
            listBox.addView(section("Lokal behaltene Aufnahmen (${local.size})", ui.grey), ui.full())
            local.forEach { addItem(localItem(it)) }
        }
    }

    private fun addItem(v: View) {
        listBox.addView(v, ui.margins(ui.full(), b = 10))
    }

    private fun section(s: String, color: Int): TextView {
        val t = ui.text(s, 15f, color, bold = true)
        t.setPadding(ui.dp(4), ui.dp(22), 0, ui.dp(8))
        return t
    }

    /** Datum/Uhrzeit gross, darunter Titel (bei uebernommenen Dateien) und Dauer/Groesse. */
    private fun addIdentity(box: LinearLayout, r: Rec) {
        if (r.topic.isNotBlank()) {
            box.addView(ui.text(r.topic, 17f, ui.text, bold = true), ui.margins(ui.full(), t = 6))
            box.addView(ui.text(Store.whenText(r), 14f, ui.text), ui.margins(ui.full(), t = 2))
        } else {
            box.addView(ui.text(Store.whenText(r), 16f, ui.text, bold = true), ui.margins(ui.full(), t = 6))
        }
        if (r.title.isNotBlank()) box.addView(ui.text(r.title, 14f, ui.text), ui.margins(ui.full(), t = 2))
        val size = if (Store.hasAudio(this, r)) Store.sizeLine(this, r) else Store.durationText(r.durationMs)
        if (size.isNotEmpty()) box.addView(ui.text(size, 13f, ui.sub), ui.margins(ui.full(), t = 2))
    }

    private fun statusLine(box: LinearLayout, s: String, color: Int) {
        box.addView(ui.text(s, 14f, color, bold = true), ui.margins(ui.full(), t = 8))
    }

    private fun contextLine(box: LinearLayout, r: Rec) {
        if (r.context.isNotBlank()) box.addView(ui.text("Kontext: " + r.context, 14f, ui.sub), ui.margins(ui.full(), t = 2))
    }

    private fun buttons(box: LinearLayout, vararg items: Pair<String, Pair<Ui.Kind, () -> Unit>>) {
        if (items.isEmpty()) return
        val flow = FlowLayout(this, ui.dp(8))
        for ((label, spec) in items) {
            val b = ui.button(label, spec.first, onClick = spec.second)
            b.textSize = 14f
            b.minHeight = ui.dp(40)
            b.minimumHeight = ui.dp(40)
            b.setPadding(ui.dp(14), 0, ui.dp(14), 0)
            flow.addView(b)
        }
        box.addView(flow, ui.margins(ui.full(), t = 10))
    }

    private fun progress(box: LinearLayout, pct: Int) {
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal)
        tint(bar)
        bar.max = 100
        bar.isIndeterminate = pct < 0
        if (pct >= 0) bar.progress = pct
        box.addView(bar, ui.margins(ui.full(), t = 6))
    }

    private fun tint(bar: ProgressBar) {
        bar.progressTintList = ColorStateList.valueOf(ui.accent)
        bar.indeterminateTintList = ColorStateList.valueOf(ui.accent)
        bar.progressBackgroundTintList = ColorStateList.valueOf(ui.line)
    }

    private fun openItem(r: Rec): View {
        val c = ui.card(stripe = ui.orange)
        c.addView(ui.badge("⚠  Noch nicht entschieden", ui.orange, ui.orangeSoft), ui.wrap())
        addIdentity(c, r)
        addPlayer(c, r)
        buttons(
            c,
            "Entscheiden" to (Ui.Kind.PRIMARY to { openFocus(r) }),
            "Löschen" to (Ui.Kind.DANGER to { confirmDelete(r) })
        )
        return c
    }

    private fun workItem(r: Rec): View {
        val failed = r.phase == Rec.PHASE_UPLOAD_FAILED || r.phase == Rec.PHASE_FAILED
        val c = ui.card(stripe = if (failed) ui.red else ui.accent)
        addIdentity(c, r)
        when (r.phase) {
            Rec.PHASE_NEW -> {
                statusLine(c, "Wartet auf den Upload", ui.accent)
                contextLine(c, r)
                if (!RecordingService.busy) {
                    buttons(c, "Jetzt hochladen" to (Ui.Kind.PRIMARY to { upload() }))
                }
            }
            Rec.PHASE_UPLOADING -> {
                statusLine(c, r.note.ifEmpty { "Wird hochgeladen …" }, ui.accent)
                progress(c, r.progress)
                contextLine(c, r)
            }
            Rec.PHASE_TRANSCRIBING -> {
                statusLine(c, "⏳ " + r.note.ifEmpty { "Transkription läuft …" } + Store.since(r), ui.accent)
                progress(c, -1)
                c.addView(ui.text("Danach: " + Store.mailDone(r) + " · TXT aufs Handy", 13f, ui.sub), ui.margins(ui.full(), t = 4))
                contextLine(c, r)
            }
            Rec.PHASE_UPLOAD_FAILED -> {
                statusLine(c, "✘ Upload fehlgeschlagen", ui.red)
                if (r.note.isNotBlank()) c.addView(ui.text(r.note, 13f, ui.sub), ui.margins(ui.full(), t = 2))
                contextLine(c, r)
                buttons(
                    c,
                    "Erneut hochladen" to (Ui.Kind.PRIMARY to { upload() }),
                    "Lokal behalten" to (Ui.Kind.TONAL to { decideLocal(r, shared = false) }),
                    "Löschen" to (Ui.Kind.DANGER to { confirmDelete(r) })
                )
            }
            else -> {
                statusLine(c, "✘ " + r.note.ifEmpty { "Transkription fehlgeschlagen." }, ui.red)
                contextLine(c, r)
                val items = mutableListOf<Pair<String, Pair<Ui.Kind, () -> Unit>>>()
                if (Store.hasAudio(this, r)) {
                    addPlayer(c, r)
                    items += "Teilen" to (Ui.Kind.TONAL to { shareAudio(r) })
                    if (isOgg(r)) items += "Als M4A teilen" to (Ui.Kind.TONAL to { shareAsM4a(r) })
                    items += "Lokal behalten" to (Ui.Kind.TONAL to { decideLocal(r, shared = false) })
                }
                items += "Löschen" to (Ui.Kind.DANGER to { confirmDelete(r) })
                buttons(c, *items.toTypedArray())
            }
        }
        return c
    }

    private fun doneItem(r: Rec): View {
        val c = ui.card(stripe = ui.green)
        addIdentity(c, r)
        statusLine(c, "✔ Transkribiert · " + Store.mailDone(r), ui.green)
        val hasTxt = Store.hasTxt(this, r)
        val audio = Store.hasAudio(this, r)
        val info = when {
            hasTxt && !audio -> "TXT auf dem Handy · Aufnahme gelöscht"
            hasTxt -> "TXT auf dem Handy"
            Store.needsStatus(this, r) -> "TXT wird geholt …"
            else -> "Kein TXT auf dem Handy (nur per Mail)"
        }
        c.addView(ui.text(info, 13f, ui.sub), ui.margins(ui.full(), t = 2))
        contextLine(c, r)
        if (audio) addPlayer(c, r)
        val items = mutableListOf<Pair<String, Pair<Ui.Kind, () -> Unit>>>()
        if (hasTxt) {
            items += "Lesen" to (Ui.Kind.PRIMARY to { showTranscript(r) })
            items += "TXT teilen" to (Ui.Kind.TONAL to { shareTxt(r) })
            items += "TXT speichern" to (Ui.Kind.TONAL to { saveToDownloads(Store.txtFile(this, r.name)) })
        }
        if (audio) items += "Audio teilen" to (Ui.Kind.OUTLINE to { shareAudio(r) })
        if (audio && isOgg(r)) items += "Als M4A teilen" to (Ui.Kind.OUTLINE to { shareAsM4a(r) })
        items += "Löschen" to (Ui.Kind.DANGER to { confirmDelete(r) })
        buttons(c, *items.toTypedArray())
        return c
    }

    private fun localItem(r: Rec): View {
        val c = ui.card(stripe = ui.grey)
        addIdentity(c, r)
        statusLine(c, "✔ Lokal behalten" + (if (r.shared) " · geteilt" else ""), ui.grey)
        contextLine(c, r)
        addPlayer(c, r)
        val items = mutableListOf<Pair<String, Pair<Ui.Kind, () -> Unit>>>()
        items += "Teilen" to (Ui.Kind.TONAL to { shareAudio(r) })
        if (isOgg(r)) items += "Als M4A teilen" to (Ui.Kind.TONAL to { shareAsM4a(r) })
        items += "Transkribieren" to (Ui.Kind.OUTLINE to { openFocus(r) })
        items += "Löschen" to (Ui.Kind.DANGER to { confirmDelete(r) })
        buttons(c, *items.toTypedArray())
        return c
    }

    // --- Aktionen ---------------------------------------------------------------

    private fun upload() {
        listSignature = ""
        withPermissions { launch(RecordingService.intent(this, RecordingService.ACTION_UPLOAD)) }
    }

    private fun shareAudio(r: Rec) {
        val f = Store.audioFile(this, r.name)
        shareFile(f, Store.mimeFor(f), "Aufnahme teilen")
        markShared(r)
    }

    private fun markShared(r: Rec) {
        val fresh = Store.get(this, r.name)
        if (fresh.phase == Rec.PHASE_LOCAL && !fresh.shared) {
            fresh.shared = true
            Store.save(this, fresh)
        }
    }

    private fun isOgg(r: Rec): Boolean = r.name.substringAfterLast('.').lowercase() == "ogg"

    /** Ogg auf dem Handy in M4A umwandeln (fuer Copilot, Word, WhatsApp) und dann teilen. */
    private fun shareAsM4a(r: Rec) {
        if (converting) {
            toast("Es wird schon eine Aufnahme umgewandelt.")
            return
        }
        val src = Store.audioFile(this, r.name)
        if (!src.isFile) {
            toast("Die Aufnahme ist nicht mehr auf dem Handy.")
            return
        }
        converting = true
        convertPct = 0
        val app = applicationContext
        io.execute {
            try {
                val out = Converter.toM4a(app, src) { convertPct = it }
                runOnUiThread {
                    converting = false
                    if (!isFinishing && !isDestroyed) {
                        shareFile(out, "audio/mp4", "Aufnahme teilen (M4A)")
                        markShared(r)
                    }
                }
            } catch (e: Exception) {
                converting = false
                RecordingService.report("Umwandeln in M4A ging nicht. " + Store.describe(e))
            }
        }
    }

    // --- Abspielen in der App ---------------------------------------------------

    private fun addPlayer(box: LinearLayout, r: Rec) {
        val f = Store.audioFile(this, r.name)
        if (!f.isFile) return
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL

        val button = ui.button("▶", Ui.Kind.TONAL) {}
        button.textSize = 18f
        button.setPadding(0, 0, 0, 0)
        val seek = SeekBar(this)
        seek.progressTintList = ColorStateList.valueOf(ui.accent)
        seek.thumbTintList = ColorStateList.valueOf(ui.accent)
        seek.progressBackgroundTintList = ColorStateList.valueOf(ui.line)
        val duration = r.durationMs.toInt().coerceAtLeast(0)
        seek.max = duration.coerceAtLeast(1)
        val time = ui.text(timeText(0, duration), 13f, ui.sub)

        val pr = PlayerRow(r.name, f, duration, buildingTop, button, seek, time)
        button.setOnClickListener {
            player.toggle(r.name, f, if (player.current == r.name) 0 else seek.progress)
            if (player.error.isNotEmpty()) toast(player.error)
            updatePlayers()
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) time.text = timeText(progress, bar.max)
            }

            override fun onStartTrackingTouch(bar: SeekBar) {
                pr.dragging = true
            }

            override fun onStopTrackingTouch(bar: SeekBar) {
                pr.dragging = false
                player.seek(r.name, f, bar.progress)
                if (player.error.isNotEmpty()) toast(player.error)
                updatePlayers()
            }
        })

        row.addView(button, LinearLayout.LayoutParams(ui.dp(52), ui.dp(46)))
        row.addView(seek, ui.weighted())
        row.addView(time, ui.wrap())
        box.addView(row, ui.margins(ui.full(), t = 10))
        playerRows += pr
    }

    /** Knopf, Balken und Zeit der Abspiel-Zeilen auf den aktuellen Stand bringen. */
    private fun updatePlayers() {
        for (pr in playerRows) {
            val playing = player.isPlaying(pr.name)
            pr.button.text = if (playing) "❚❚" else "▶"
            val loaded = player.current == pr.name
            val duration = player.duration(pr.name).takeIf { it > 0 } ?: pr.durationMs
            if (duration > 0 && pr.seek.max != duration) pr.seek.max = duration
            if (!pr.dragging && loaded) {
                val pos = player.position(pr.name)
                pr.seek.progress = pos
                pr.time.text = timeText(pos, duration)
            }
        }
    }

    private fun timeText(posMs: Int, durationMs: Int): String {
        fun fmt(ms: Int): String {
            val s = ms / 1000
            return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
            else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }
        return if (durationMs > 0) fmt(posMs) + " / " + fmt(durationMs) else fmt(posMs)
    }

    private fun shareTxt(r: Rec) {
        shareFile(Store.txtFile(this, r.name), "text/plain", "Transkript teilen")
    }

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

    private fun showTranscript(r: Rec) {
        val text = try {
            Store.txtFile(this, r.name).readText()
        } catch (e: Exception) {
            toast("Transkript nicht lesbar: " + Store.describe(e))
            return
        }
        val t = TextView(this)
        t.text = text
        t.textSize = 15f
        t.setTextIsSelectable(true)
        t.setLineSpacing(0f, 1.2f)
        t.setPadding(ui.dp(22), ui.dp(8), ui.dp(22), ui.dp(8))
        val sv = ScrollView(this)
        sv.addView(t)
        AlertDialog.Builder(this)
            .setTitle(r.topic.ifBlank { Store.whenText(r) })
            .setView(sv)
            .setPositiveButton("Teilen") { _, _ -> shareTxt(r) }
            .setNegativeButton("Schließen", null)
            .show()
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

    private fun confirmDelete(r: Rec) {
        val days = Prefs.archiveDays(this)
        val message = when (r.phase) {
            Rec.PHASE_DONE ->
                "Transkript und Eintrag werden vom Handy gelöscht. In Google Drive bleibt das TXT" +
                    (if (days > 0) "; die Aufnahme dort wird $days Tage nach dem Upload gelöscht." else ".")
            Rec.PHASE_TRANSCRIBING, Rec.PHASE_FAILED ->
                "Die Aufnahme wird vom Handy gelöscht. Die Kopie in Google Drive wird trotzdem fertig verarbeitet."
            else -> "Die Aufnahme wird endgültig vom Handy gelöscht. Sie war nie in Google Drive."
        }
        AlertDialog.Builder(this)
            .setTitle("Löschen?")
            .setMessage(Store.whenText(r) + "\n\n" + message)
            .setPositiveButton("Löschen") { _, _ ->
                if (player.current == r.name) player.release()
                Store.deleteRecording(this, r.name)
                Notifier.cancelDecide(this, r.name)
                topKey = ""
                listSignature = ""
                render()
            }
            .setNegativeButton("Abbrechen", null)
            .show()
    }

    // --- Erlaubnisse ------------------------------------------------------------

    /**
     * Der Vordergrund-Dienst hat den Typ "microphone" und braucht deshalb die
     * Mikrofon-Erlaubnis - auch fuer Upload und Link-Download.
     */
    private fun withPermissions(action: () -> Unit) {
        val wanted = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) {
            action()
            return
        }
        afterPermission = action
        requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = afterPermission
        afterPermission = null
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            RecordingService.report("Ohne Mikrofon-Erlaubnis kann die App nicht aufnehmen.")
            return
        }
        action?.invoke()
    }

    // --- Schluessel und Server -----------------------------------------------

    private fun showSecretDialog() {
        val info = TextView(this)
        info.text = "Derselbe Schlüssel muss im Apps Script als Skripteigenschaft UPLOAD_SECRET stehen. " +
            "Beim Speichern wird er in die Zwischenablage kopiert."
        info.setPadding(0, 0, 0, ui.dp(8))

        val input = EditText(this)
        input.hint = "Geheimer Schlüssel"
        input.isSingleLine = true
        input.setText(Prefs.secret(this))

        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0)
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
                        append(
                            when {
                                info.api >= 4 -> "aktuell ✔"
                                info.api == 3 -> "funktioniert (Version 3). Für den neuen Mail-Betreff mit Titel DirectUpload.gs und Code.gs aktualisieren."
                                else -> "funktioniert (Version 2). Für Titel im Betreff und Löschen in Drive DirectUpload.gs und Code.gs aktualisieren."
                            }
                        )
                        if (!info.secretRequired) append(" Noch ohne Schlüssel-Schutz (UPLOAD_SECRET fehlt im Script).")
                        if (!info.mailPrivate) append(" Private Adresse fehlt im Script (MAIL_PRIVATE), \"Privat\" geht an Arbeit.")
                    }
                }
                Prefs.setServer(app, info.api, message)
                if (info.authOk) Prefs.setArchiveDays(app, info.archiveDays)
                if (info.api >= 2 && info.authOk) Prefs.setUsage(app, info.month, info.monthSeconds)
            } catch (e: Exception) {
                Prefs.setServer(app, Prefs.serverApi(app), "nicht erreichbar (${Store.describe(e)})")
            }
        }
    }

    // --- An die App geteilt: Datei oder Link --------------------------------

    /** Geteilte Dateien und Links werden uebernommen und sind dann "offen" - wie eine neue Aufnahme. */
    private fun handleShare(intent: Intent?) {
        if (intent == null || intent.action != Intent.ACTION_SEND) return
        // Nur einmal verarbeiten, auch wenn die Activity neu erstellt wird.
        setIntent(Intent(this, MainActivity::class.java))

        val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        if (stream != null) {
            importFile(stream, intent.type)
            return
        }

        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val link = URL_PATTERN.find(text)?.value?.trimEnd('.', ',', ')', ']', '>', '"', '\'')
        if (link == null) {
            toast("Kein Link und keine Audiodatei gefunden.")
            return
        }
        withPermissions {
            val i = RecordingService.intent(this, RecordingService.ACTION_IMPORT_URL)
            i.putExtra(RecordingService.EXTRA_URL, link)
            launch(i)
        }
    }

    private fun importFile(uri: Uri, mimeHint: String?) {
        copying = true
        RecordingService.report("")
        val app = applicationContext
        io.execute {
            try {
                val imported = Importer.copyFromUri(app, uri, mimeHint)
                val rec = Store.newImported(app, imported)
                Prefs.setFocus(app, rec.name)
            } catch (e: Exception) {
                RecordingService.report("Datei konnte nicht übernommen werden. " + Store.describe(e))
            } finally {
                copying = false
            }
        }
    }

    // --- Texte -------------------------------------------------------------------

    private fun usageText(): String {
        val month = Prefs.usageMonth(this)
        if (month.isEmpty() || Prefs.serverApi(this) < 2) return ""
        val label = try {
            val date = SimpleDateFormat("yyyy-MM", Locale.US).parse(month)
            if (date == null) month else SimpleDateFormat("MMMM yyyy", Locale.GERMAN).format(date)
        } catch (e: Exception) {
            month
        }
        val minutes = (Prefs.usageSeconds(this) + 59) / 60
        return "Transkribiert im $label: $minutes Min."
    }

    private fun archiveText(): String {
        val days = Prefs.archiveDays(this)
        return when {
            days > 0 -> "Google Drive: Aufnahmen werden $days Tage nach dem Upload gelöscht (auch alte vom Recorder). Transkripte bleiben."
            Prefs.serverApi(this) == 2 -> "Google Drive: Aufnahmen bleiben im Archiv, bis das Script auf Version 3 ist."
            else -> ""
        }
    }

    private fun versionName(): String = try {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun formatElapsed(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
    }
}
