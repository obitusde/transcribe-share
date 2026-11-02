package io.github.obitusde.aufnahme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** Alle Benachrichtigungen an einer Stelle. */
object Notifier {
    const val CH_RUNNING = "laufend"
    private const val CH_STATUS = "status"
    private const val CH_RESULT = "ergebnis"

    const val ID_RUNNING = 1
    private const val ID_UPLOAD = 2
    private const val ID_STATUS = 3

    fun ensureChannels(context: Context) {
        val nm = manager(context)
        nm.createNotificationChannel(
            NotificationChannel(CH_RUNNING, "Laufende Aufnahme", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "Transkription läuft", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "Ergebnisse", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun openApp(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    fun uploadFailed(context: Context, text: String) {
        post(context, ID_UPLOAD, result(context, "Upload fehlgeschlagen", text))
    }

    fun importFailed(context: Context, text: String) {
        post(context, ID_UPLOAD, result(context, "Übernahme fehlgeschlagen", text))
    }

    /**
     * Laufende Transkriptionen mit laufendem Balken (AssemblyAI meldet keinen
     * Prozentwert) und "seit X Min.". Leere Liste nimmt die Meldung weg.
     */
    fun transcribing(context: Context, recs: List<Rec>) {
        if (recs.isEmpty()) {
            manager(context).cancel(ID_STATUS)
            return
        }
        val text = recs.joinToString("\n") { r -> Store.label(r) + Store.since(r) }
        val n = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle("Transkription läuft")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(0, 0, true)
            .setContentIntent(openApp(context))
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        post(context, ID_STATUS, n)
    }

    fun finished(context: Context, r: Rec) {
        val title = if (r.phase == Rec.PHASE_DONE) "Transkript fertig" else "Transkription fehlgeschlagen"
        post(context, 1000 + (r.name.hashCode() and 0xFFFF), result(context, title, Store.label(r) + ": " + r.note))
    }

    /** Aufnahme gespeichert, aber noch nicht entschieden (z. B. per Benachrichtigung gestoppt). */
    fun decide(context: Context, r: Rec) {
        val text = Store.label(r) + ". Tippe hier: transkribieren, lokal behalten, teilen oder löschen."
        post(context, decideId(r.name), result(context, "Aufnahme gespeichert – was soll damit passieren?", text))
    }

    fun cancelDecide(context: Context, name: String) {
        manager(context).cancel(decideId(name))
    }

    private fun decideId(name: String): Int = 50_000 + (name.hashCode() and 0xFFFF)

    private fun result(context: Context, title: String, text: String): Notification =
        NotificationCompat.Builder(context, CH_RESULT)
            .setSmallIcon(R.drawable.ic_stat_mic)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build()

    fun post(context: Context, id: Int, n: Notification) {
        manager(context).notify(id, n)
    }

    private fun manager(context: Context): NotificationManager =
        context.getSystemService(NotificationManager::class.java)
}
