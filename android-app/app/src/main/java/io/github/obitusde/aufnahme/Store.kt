package io.github.obitusde.aufnahme

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Alles, was die App ueber eine Aufnahme weiss. */
class Rec(val name: String) {
    var mode = MODE_TRANSCRIBE
    var context = ""
    var mail = Prefs.MAIL_WORK
    var phase = PHASE_NEW
    var note = ""
    var uploadedAt = 0L
    var fileId = ""
    var driveName = ""
    var hasTxt = false
    var txtTries = 0
    /** Anzeigename bei uebernommenen Dateien (z. B. Titel der Podcast-Folge). */
    var title = ""
    /** Upload-Fortschritt in Prozent, -1 = unbekannt. */
    var progress = -1

    fun toJson(): String = JSONObject()
        .put("mode", mode)
        .put("context", context)
        .put("mail", mail)
        .put("phase", phase)
        .put("note", note)
        .put("uploadedAt", uploadedAt)
        .put("fileId", fileId)
        .put("driveName", driveName)
        .put("hasTxt", hasTxt)
        .put("txtTries", txtTries)
        .put("title", title)
        .put("progress", progress)
        .toString()

    companion object {
        const val MODE_TRANSCRIBE = "transcribe"
        const val MODE_LOCAL = "local"

        const val PHASE_RECORDING = "recording"
        const val PHASE_NEW = "new"
        const val PHASE_UPLOADING = "uploading"
        const val PHASE_UPLOAD_FAILED = "upload_failed"
        const val PHASE_TRANSCRIBING = "transcribing"
        const val PHASE_DONE = "done"
        const val PHASE_FAILED = "failed"
        const val PHASE_LOCAL = "local"

        fun fromJson(name: String, raw: String?): Rec? {
            if (raw == null) return null
            return try {
                val o = JSONObject(raw)
                Rec(name).apply {
                    mode = o.optString("mode", MODE_TRANSCRIBE)
                    context = o.optString("context", "")
                    mail = o.optString("mail", Prefs.MAIL_WORK)
                    phase = o.optString("phase", PHASE_NEW)
                    note = o.optString("note", "")
                    uploadedAt = o.optLong("uploadedAt", 0L)
                    fileId = o.optString("fileId", "")
                    driveName = o.optString("driveName", "")
                    hasTxt = o.optBoolean("hasTxt", false)
                    txtTries = o.optInt("txtTries", 0)
                    title = o.optString("title", "")
                    progress = o.optInt("progress", -1)
                }
            } catch (e: JSONException) {
                null
            }
        }
    }
}

/**
 * Wo die Aufnahmen auf dem Handy liegen und in welchem Zustand sie sind.
 *
 * Loeschregel: Nur Aufnahmen, deren Upload bestaetigt ist und die fertig
 * (oder endgueltig gescheitert) sind, werden KEEP_DAYS Tage nach dem Upload
 * geloescht. Nur-lokale Aufnahmen loescht nur der Nutzer selbst.
 */
object Store {
    const val KEEP_DAYS = 7
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** Formate, die das Apps Script transkribiert (isAudioFile in Code.gs). */
    private val AUDIO_MIME = mapOf(
        "ogg" to "audio/ogg",
        "m4a" to "audio/mp4",
        "mp3" to "audio/mpeg",
        "wav" to "audio/wav"
    )

    fun mimeFor(f: File): String = AUDIO_MIME[f.extension.lowercase()] ?: "application/octet-stream"

    fun dir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    private fun txtDir(context: Context): File = File(context.filesDir, "transcripts").apply { mkdirs() }

    /** Name im selben Schema, das das Apps Script ohnehin verwendet. */
    fun newFile(context: Context, ext: String = "ogg"): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        var f = File(dir(context), "$stamp.$ext")
        var n = 2
        while (f.exists()) {
            f = File(dir(context), "$stamp-$n.$ext")
            n++
        }
        return f
    }

    fun txtFile(context: Context, name: String): File =
        File(txtDir(context), name.substringBeforeLast('.') + ".txt")

    fun all(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_MIME }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun get(context: Context, name: String): Rec {
        val p = prefs(context)
        val rec = Rec.fromJson(name, p.getString("rec_$name", null)) ?: Rec(name).also { r ->
            // Aufnahmen aus der ersten App-Version
            val legacyUpload = p.getLong("up_$name", 0L)
            if (legacyUpload > 0L) {
                r.phase = Rec.PHASE_DONE
                r.uploadedAt = legacyUpload
                r.note = p.getString("note_$name", null) ?: "Hochgeladen."
            }
        }
        // Zustaende reparieren, die ein Absturz hinterlassen haben kann.
        if (rec.phase == Rec.PHASE_RECORDING && RecordingService.recordingFile?.name != name) {
            rec.phase = if (rec.mode == Rec.MODE_LOCAL) Rec.PHASE_LOCAL else Rec.PHASE_NEW
        }
        if (rec.phase == Rec.PHASE_UPLOADING && !RecordingService.busy) {
            rec.phase = Rec.PHASE_UPLOAD_FAILED
            rec.note = "Upload wurde unterbrochen."
        }
        return rec
    }

    fun save(context: Context, rec: Rec) {
        prefs(context).edit().putString("rec_${rec.name}", rec.toJson()).commit()
    }

    /** Aufnahmen, die hochgeladen werden sollen und noch nicht sind. */
    fun pending(context: Context): List<File> {
        val active = RecordingService.recordingFile
        return all(context).filter { f ->
            if (f == active || f.length() == 0L) return@filter false
            val r = get(context, f.name)
            r.mode == Rec.MODE_TRANSCRIBE &&
                (r.phase == Rec.PHASE_NEW || r.phase == Rec.PHASE_UPLOAD_FAILED)
        }
    }

    fun needsStatus(context: Context, r: Rec): Boolean =
        r.phase == Rec.PHASE_TRANSCRIBING ||
            (r.phase == Rec.PHASE_DONE && !r.hasTxt && r.fileId.isNotEmpty() &&
                r.txtTries < 5 && Prefs.serverApi(context) >= 2)

    fun awaitingStatus(context: Context): List<Rec> =
        all(context).map { get(context, it.name) }.filter { needsStatus(context, it) }

    fun deleteRecording(context: Context, name: String) {
        File(dir(context), name).delete()
        txtFile(context, name).delete()
        prefs(context).edit()
            .remove("rec_$name")
            .remove("up_$name")
            .remove("note_$name")
            .commit()
    }

    fun cleanup(context: Context) {
        val now = System.currentTimeMillis()
        val active = RecordingService.recordingFile
        for (f in all(context)) {
            if (f == active) continue
            val r = get(context, f.name)
            val finished = r.phase == Rec.PHASE_DONE || r.phase == Rec.PHASE_FAILED
            val expired = finished && r.uploadedAt > 0L && r.uploadedAt < now - KEEP_DAYS * DAY_MS
            val emptyLeftover = f.length() == 0L && f.lastModified() < now - DAY_MS
            if (expired || emptyLeftover) deleteRecording(context, f.name)
        }
    }

    fun sizeText(f: File): String = sizeText(f.length())

    fun sizeText(bytes: Long): String =
        String.format(Locale.GERMANY, "%.1f MB", bytes / 1_048_576.0)

    fun mailLabel(mail: String): String = when (mail) {
        Prefs.MAIL_PRIVATE -> "Privat"
        Prefs.MAIL_NONE -> "keine"
        else -> "Arbeit"
    }

    fun phaseText(r: Rec): String = when (r.phase) {
        Rec.PHASE_NEW -> "Wartet auf Upload"
        Rec.PHASE_UPLOADING -> r.note.ifEmpty { "Wird hochgeladen …" }
        Rec.PHASE_UPLOAD_FAILED -> "✘ Upload fehlgeschlagen. " + r.note
        Rec.PHASE_TRANSCRIBING -> "⏳ " + r.note.ifEmpty { "Transkription läuft …" } + since(r)
        Rec.PHASE_DONE -> "✔ " + r.note.ifEmpty { "Fertig." }
        Rec.PHASE_FAILED -> "✘ " + r.note
        Rec.PHASE_LOCAL -> "Nur auf dem Handy (nicht übertragen)"
        else -> r.note
    }

    /** " (seit 3 Min.)" fuer laufende Transkriptionen. */
    fun since(r: Rec): String {
        if (r.uploadedAt <= 0L) return ""
        val minutes = (System.currentTimeMillis() - r.uploadedAt) / 60_000L
        return if (minutes < 1) " (seit gerade eben)" else " (seit $minutes Min.)"
    }

    fun details(r: Rec, isActive: Boolean): String {
        val lines = mutableListOf<String>()
        lines += if (isActive) "● nimmt gerade auf" else phaseText(r)
        if (r.context.isNotBlank()) lines += "Kontext: ${r.context}"
        if (r.mode == Rec.MODE_TRANSCRIBE && r.phase != Rec.PHASE_LOCAL) lines += "Mail: " + mailLabel(r.mail)
        return lines.joinToString("\n")
    }

    /** Nie eine leere Fehlermeldung. */
    fun describe(e: Throwable): String =
        if (e is Uploader.UploadException) e.message ?: "Upload-Fehler"
        else "${e.javaClass.simpleName}: ${e.message ?: "(keine Meldung)"}"

    private fun prefs(context: Context) =
        context.getSharedPreferences("aufnahmen", Context.MODE_PRIVATE)
}
