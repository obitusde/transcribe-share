package io.github.obitusde.aufnahme

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Fragt im Hintergrund nach, wie weit die Transkriptionen sind - auch wenn
 * die App geschlossen ist. Plant sich jede Minute neu, solange noch etwas
 * laeuft, und holt fertige Transkripte als .txt aufs Handy.
 */
class StatusWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    companion object {
        private const val WORK_NAME = "transkript-status"
        /** So lange darf "Fehler"/"unbekannt" noch heissen: FolderWatcher startet erst nach bis zu 12 Min. */
        private const val GRACE_MS = 20L * 60 * 1000
        private const val GIVE_UP_MS = 3L * 60 * 60 * 1000

        fun schedule(context: Context, delaySeconds: Long) {
            val request = OneTimeWorkRequestBuilder<StatusWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    override fun doWork(): Result {
        val c = applicationContext
        Notifier.ensureChannels(c)
        val secret = Prefs.secret(c)
        val now = System.currentTimeMillis()
        val running = mutableListOf<Rec>()
        var again = false

        for (rec in Store.awaitingStatus(c)) {
            val before = rec.phase

            if (rec.phase == Rec.PHASE_TRANSCRIBING) {
                val age = now - rec.uploadedAt
                try {
                    val state = Uploader.status(rec.driveName.ifEmpty { rec.name })
                    if (state == "done") {
                        rec.phase = Rec.PHASE_DONE
                        rec.note = doneNote(c, rec)
                    } else if (state == "transcribing") {
                        rec.note = "Transkription läuft …"
                    } else if (age > GRACE_MS && state == "error") {
                        rec.phase = Rec.PHASE_FAILED
                        rec.note = "Transkription fehlgeschlagen. Details stehen in der Fehler-Mail."
                    } else if (age > GRACE_MS) {
                        rec.phase = Rec.PHASE_FAILED
                        rec.note = "Status unbekannt. Bitte Mail prüfen."
                    } else {
                        rec.note = "Wartet auf den Start der Transkription …"
                    }
                } catch (e: Exception) {
                    rec.note = "Status gerade nicht abrufbar, neuer Versuch folgt."
                }
                if (rec.phase == Rec.PHASE_TRANSCRIBING && age > GIVE_UP_MS) {
                    rec.phase = Rec.PHASE_FAILED
                    rec.note = "Kein Ergebnis nach 3 Stunden. Bitte Mail prüfen."
                }
            }

            if (rec.phase == Rec.PHASE_DONE && !rec.hasTxt && rec.fileId.isNotEmpty() &&
                Prefs.serverApi(c) >= 2 && rec.txtTries < 5
            ) {
                rec.txtTries++
                try {
                    val text = Uploader.transcript(rec.fileId, secret)
                    if (text != null) {
                        Store.txtFile(c, rec.name).writeText(text)
                        rec.hasTxt = true
                        // Transkript ist sicher auf dem Handy: Aufnahme wird nicht mehr gebraucht.
                        Store.deleteAudio(c, rec)
                    }
                } catch (e: Exception) {
                    // naechster Versuch beim naechsten Lauf
                }
            }

            Store.save(c, rec)
            if (rec.phase == Rec.PHASE_TRANSCRIBING) running += rec
            if (Store.needsStatus(c, rec)) again = true
            if (before == Rec.PHASE_TRANSCRIBING && rec.phase != before) Notifier.finished(c, rec)
        }

        Notifier.transcribing(c, running)
        if (again) schedule(c, 60)
        return Result.success()
    }

    private fun doneNote(c: Context, r: Rec): String =
        if (Prefs.serverApi(c) < 2) {
            "Fertig. Mail verschickt."
        } else {
            when (r.mail) {
                Prefs.MAIL_NONE -> "Fertig. Keine Mail."
                Prefs.MAIL_PRIVATE -> "Fertig. Mail an Privat."
                else -> "Fertig. Mail an Arbeit."
            }
        }
}
