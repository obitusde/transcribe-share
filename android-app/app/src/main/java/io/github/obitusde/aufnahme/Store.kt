package io.github.obitusde.aufnahme

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Wo die Aufnahmen auf dem Handy liegen und welche schon in Drive sind.
 *
 * Regel: Eine Datei wird nur geloescht, wenn ihr Upload bestaetigt ist und
 * das mindestens KEEP_DAYS Tage her ist. Nicht hochgeladene Dateien bleiben.
 */
object Store {
    const val KEEP_DAYS = 7
    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun dir(context: Context): File = File(context.filesDir, "recordings").apply { mkdirs() }

    /** Name im selben Schema, das das Apps Script ohnehin verwendet. */
    fun newFile(context: Context): File {
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(dir(context), "$stamp.ogg")
    }

    fun all(context: Context): List<File> =
        dir(context).listFiles()
            ?.filter { it.isFile && it.name.endsWith(".ogg") }
            ?.sortedByDescending { it.name }
            ?: emptyList()

    fun pending(context: Context, active: File?): List<File> =
        all(context).filter { it != active && it.length() > 0L && uploadedAt(context, it.name) == null }

    fun uploadedAt(context: Context, name: String): Long? =
        prefs(context).getLong("up_$name", 0L).takeIf { it > 0L }

    fun note(context: Context, name: String): String? =
        prefs(context).getString("note_$name", null)

    fun markUploaded(context: Context, name: String, note: String) {
        prefs(context).edit()
            .putLong("up_$name", System.currentTimeMillis())
            .putString("note_$name", note)
            .commit()
    }

    fun setNote(context: Context, name: String, note: String) {
        prefs(context).edit().putString("note_$name", note).commit()
    }

    fun cleanup(context: Context, active: File?) {
        val now = System.currentTimeMillis()
        val cutoff = now - KEEP_DAYS * DAY_MS
        val editor = prefs(context).edit()
        for (f in all(context)) {
            if (f == active) continue
            val up = uploadedAt(context, f.name)
            val expired = up != null && up < cutoff
            val emptyLeftover = f.length() == 0L && f.lastModified() < now - DAY_MS
            if ((expired || emptyLeftover) && f.delete()) {
                editor.remove("up_${f.name}").remove("note_${f.name}")
            }
        }
        editor.apply()
    }

    fun summary(context: Context, f: File, active: File?): String {
        val size = String.format(Locale.GERMANY, "%.1f MB", f.length() / 1_048_576.0)
        val state = when {
            f == active -> "nimmt gerade auf"
            uploadedAt(context, f.name) != null -> "✔ in Drive. " + (note(context, f.name) ?: "")
            else -> note(context, f.name) ?: "wartet auf Upload"
        }
        return "${f.name}  ($size)\n$state"
    }

    /** Nie eine leere Fehlermeldung. */
    fun describe(e: Throwable): String =
        if (e is Uploader.UploadException) e.message ?: "Upload-Fehler"
        else "${e.javaClass.simpleName}: ${e.message ?: "(keine Meldung)"}"

    private fun prefs(context: Context) =
        context.getSharedPreferences("aufnahmen", Context.MODE_PRIVATE)
}
