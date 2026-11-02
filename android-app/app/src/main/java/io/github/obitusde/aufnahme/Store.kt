package io.github.obitusde.aufnahme

import android.content.Context
import android.media.MediaMetadataRetriever
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Alles, was die App ueber eine Aufnahme weiss. */
class Rec(val name: String) {
    var mode = MODE_TRANSCRIBE
    var context = ""
    var mail = Prefs.MAIL_WORK
    var phase = PHASE_OPEN
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
    /** Wann aufgenommen bzw. uebernommen (ms), 0 = aus dem Dateinamen lesen. */
    var createdAt = 0L
    /** Laenge der Aufnahme in ms, -1 = unbekannt. */
    var durationMs = -1L
    /** Groesse der Audiodatei, bleibt nach dem Loeschen fuer die Anzeige stehen. */
    var sizeBytes = 0L
    /** Audiodatei wurde nach fertigem Transkript vom Handy geloescht. */
    var audioDeleted = false
    /** Ueber "Teilen" an eine andere App gegeben. */
    var shared = false

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
        .put("createdAt", createdAt)
        .put("durationMs", durationMs)
        .put("sizeBytes", sizeBytes)
        .put("audioDeleted", audioDeleted)
        .put("shared", shared)
        .toString()

    companion object {
        const val MODE_TRANSCRIBE = "transcribe"
        const val MODE_LOCAL = "local"

        const val PHASE_RECORDING = "recording"
        /** Aufgenommen, aber noch nicht entschieden, was damit passiert. */
        const val PHASE_OPEN = "open"
        /** Transkribieren gewaehlt, wartet auf den Upload. */
        const val PHASE_NEW = "new"
        const val PHASE_UPLOADING = "uploading"
        const val PHASE_UPLOAD_FAILED = "upload_failed"
        const val PHASE_TRANSCRIBING = "transcribing"
        const val PHASE_DONE = "done"
        const val PHASE_FAILED = "failed"
        /** Lokal behalten gewaehlt. */
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
                    createdAt = o.optLong("createdAt", 0L)
                    durationMs = o.optLong("durationMs", -1L)
                    sizeBytes = o.optLong("sizeBytes", 0L)
                    audioDeleted = o.optBoolean("audioDeleted", false)
                    shared = o.optBoolean("shared", false)
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
 * Loeschregeln auf dem Handy:
 *  - Sobald das Transkript als .txt auf dem Handy liegt, wird die Audiodatei
 *    geloescht. Das Transkript bleibt, bis der Nutzer es loescht.
 *  - Konnte kein .txt geholt werden (oder ist die Transkription gescheitert),
 *    wird die Aufnahme KEEP_DAYS Tage nach dem Upload geloescht.
 *  - Offene und lokal behaltene Aufnahmen loescht nur der Nutzer selbst.
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

    private val WEEKDAYS = arrayOf("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa")

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

    fun audioFile(context: Context, name: String): File = File(dir(context), name)

    fun hasAudio(context: Context, r: Rec): Boolean = audioFile(context, r.name).let { it.isFile && it.length() > 0L }

    fun txtFile(context: Context, name: String): File =
        File(txtDir(context), name.substringBeforeLast('.') + ".txt")

    fun hasTxt(context: Context, r: Rec): Boolean = r.hasTxt && txtFile(context, r.name).isFile

    private fun audioFiles(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in AUDIO_MIME }
            ?: emptyList()

    /** Alle Eintraege: Aufnahmen auf dem Handy und Transkripte, deren Audio schon weg ist. Neueste zuerst. */
    fun entries(context: Context): List<Rec> {
        val names = LinkedHashSet<String>()
        audioFiles(context).forEach { names += it.name }
        prefs(context).all.keys.filter { it.startsWith("rec_") }.forEach { names += it.removePrefix("rec_") }
        return names.map { get(context, it) }
            .filter { it.phase == Rec.PHASE_RECORDING || hasAudio(context, it) || hasTxt(context, it) }
            .sortedByDescending { createdAt(it) }
    }

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
            rec.phase = Rec.PHASE_OPEN
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

    /** Aufnahmen, fuer die Transkribieren gewaehlt wurde und die noch nicht hochgeladen sind. */
    fun pending(context: Context): List<File> {
        val active = RecordingService.recordingFile
        return entries(context).filter { r ->
            r.mode == Rec.MODE_TRANSCRIBE &&
                (r.phase == Rec.PHASE_NEW || r.phase == Rec.PHASE_UPLOAD_FAILED) &&
                r.name != active?.name && hasAudio(context, r)
        }.map { audioFile(context, it.name) }
    }

    fun needsStatus(context: Context, r: Rec): Boolean =
        r.phase == Rec.PHASE_TRANSCRIBING ||
            (r.phase == Rec.PHASE_DONE && !r.hasTxt && r.fileId.isNotEmpty() &&
                r.txtTries < 5 && Prefs.serverApi(context) >= 2)

    fun awaitingStatus(context: Context): List<Rec> =
        entries(context).filter { needsStatus(context, it) }

    /** Eintrag komplett weg: Audio, Transkript und Status. */
    fun deleteRecording(context: Context, name: String) {
        audioFile(context, name).delete()
        txtFile(context, name).delete()
        prefs(context).edit()
            .remove("rec_$name")
            .remove("up_$name")
            .remove("note_$name")
            .commit()
        if (Prefs.focus(context) == name) Prefs.setFocus(context, "")
    }

    /** Nur die Audiodatei loeschen, Transkript und Eintrag bleiben. */
    fun deleteAudio(context: Context, rec: Rec) {
        val f = audioFile(context, rec.name)
        if (f.isFile && rec.sizeBytes <= 0L) rec.sizeBytes = f.length()
        f.delete()
        rec.audioDeleted = true
        save(context, rec)
    }

    fun cleanup(context: Context) {
        val now = System.currentTimeMillis()
        val active = RecordingService.recordingFile
        val p = prefs(context)
        for (f in audioFiles(context)) {
            if (f == active) continue
            if (p.getString("rec_${f.name}", null) != null) continue
            if (f.length() == 0L) {
                if (f.lastModified() < now - DAY_MS) f.delete()
            } else {
                // Aeltere App-Versionen: Eintrag anlegen, damit die Regeln unten greifen.
                save(context, get(context, f.name))
            }
        }
        for (key in p.all.keys.filter { it.startsWith("rec_") }) {
            val name = key.removePrefix("rec_")
            if (name == active?.name) continue
            val r = get(context, name)
            val audio = hasAudio(context, r)
            val txt = hasTxt(context, r)
            when {
                // Transkript liegt auf dem Handy: Audio wird nicht mehr gebraucht.
                r.phase == Rec.PHASE_DONE && txt && audio -> deleteAudio(context, r)
                // Weder Audio noch Transkript: nichts mehr anzuzeigen.
                !audio && !txt -> deleteRecording(context, name)
                // Fertig oder gescheitert, aber kein Transkript zu holen: nach KEEP_DAYS weg.
                !txt && (r.phase == Rec.PHASE_DONE || r.phase == Rec.PHASE_FAILED) &&
                    !needsStatus(context, r) && r.uploadedAt > 0L &&
                    r.uploadedAt < now - KEEP_DAYS * DAY_MS -> deleteRecording(context, name)
            }
        }
    }

    /** Fehlende Laenge, Groesse und Zeitpunkt nachtragen (z. B. bei uebernommenen Dateien). Nicht im UI-Thread aufrufen. */
    fun fillMeta(context: Context) {
        for (r in entries(context)) {
            if (r.phase == Rec.PHASE_RECORDING) continue
            val f = audioFile(context, r.name)
            if (!f.isFile) continue
            var changed = false
            if (r.sizeBytes != f.length()) {
                r.sizeBytes = f.length()
                changed = true
            }
            if (r.durationMs < 0L) {
                r.durationMs = measureDuration(f)
                if (r.durationMs < 0L) r.durationMs = 0L
                changed = true
            }
            if (changed) save(context, r)
        }
    }

    /** Eintrag fuer eine an die App geteilte Datei: offen, bis entschieden wird. */
    fun newImported(context: Context, imported: Importer.Imported): Rec {
        val rec = Rec(imported.file.name)
        rec.title = imported.title
        rec.phase = Rec.PHASE_OPEN
        rec.createdAt = System.currentTimeMillis()
        rec.sizeBytes = imported.file.length()
        rec.durationMs = measureDuration(imported.file).coerceAtLeast(0L)
        save(context, rec)
        return rec
    }

    fun measureDuration(f: File): Long {
        val m = MediaMetadataRetriever()
        return try {
            m.setDataSource(f.absolutePath)
            m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: -1L
        } catch (e: Exception) {
            -1L
        } finally {
            try {
                m.release()
            } catch (e: Exception) {
                // egal
            }
        }
    }

    // --- Anzeige -------------------------------------------------------------

    fun createdAt(r: Rec): Long {
        if (r.createdAt > 0L) return r.createdAt
        val stamp = r.name.take(19)
        return try {
            SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).parse(stamp)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    /** "Mo, 02.11.2026 · 10:27 Uhr" */
    fun whenText(r: Rec): String {
        val t = createdAt(r)
        if (t <= 0L) return r.name
        val cal = Calendar.getInstance()
        cal.timeInMillis = t
        val day = WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return day + ", " + SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale.GERMANY).format(Date(t)) + " Uhr"
    }

    /** "Mo, 02.11. 10:27" fuer Benachrichtigungen. */
    fun shortWhen(r: Rec): String {
        val t = createdAt(r)
        if (t <= 0L) return r.name
        val cal = Calendar.getInstance()
        cal.timeInMillis = t
        val day = WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        return day + ", " + SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(t))
    }

    /** Titel fuer uebernommene Dateien, sonst Datum und Uhrzeit. */
    fun label(r: Rec): String = r.title.ifBlank { shortWhen(r) }

    fun durationText(ms: Long): String {
        if (ms < 0L) return ""
        val s = ms / 1000
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.GERMANY, "%d:%02d:%02d Std.", h, m, sec)
        else String.format(Locale.GERMANY, "%d:%02d Min.", m, sec)
    }

    /** "15:12 Min. · 14,2 MB" */
    fun sizeLine(context: Context, r: Rec): String {
        val parts = mutableListOf<String>()
        if (r.durationMs > 0L) parts += durationText(r.durationMs)
        val f = audioFile(context, r.name)
        val bytes = if (f.isFile) f.length() else r.sizeBytes
        if (bytes > 0L) parts += sizeText(bytes)
        return parts.joinToString(" · ")
    }

    fun sizeText(bytes: Long): String =
        String.format(Locale.GERMANY, "%.1f MB", bytes / 1_048_576.0)

    fun mailLabel(mail: String): String = when (mail) {
        Prefs.MAIL_PRIVATE -> "Privat"
        Prefs.MAIL_NONE -> "keine"
        else -> "Arbeit"
    }

    /** Was mit der Mail passiert ist, z. B. "Mail an Arbeit". */
    fun mailDone(r: Rec): String =
        if (r.mail == Prefs.MAIL_NONE) "keine Mail" else "Mail an " + mailLabel(r.mail)

    /** " (seit 3 Min.)" fuer laufende Transkriptionen. */
    fun since(r: Rec): String {
        if (r.uploadedAt <= 0L) return ""
        val minutes = (System.currentTimeMillis() - r.uploadedAt) / 60_000L
        return if (minutes < 1) " (seit gerade eben)" else " (seit $minutes Min.)"
    }

    /** Nie eine leere Fehlermeldung. */
    fun describe(e: Throwable): String =
        if (e is Uploader.UploadException) e.message ?: "Upload-Fehler"
        else "${e.javaClass.simpleName}: ${e.message ?: "(keine Meldung)"}"

    private fun prefs(context: Context) =
        context.getSharedPreferences("aufnahmen", Context.MODE_PRIVATE)
}
