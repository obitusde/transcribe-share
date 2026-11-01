package io.github.obitusde.aufnahme

import android.content.Context
import org.json.JSONArray
import org.json.JSONException

/**
 * Einstellungen der App. Liegen nur auf dem Handy und bleiben bei Updates
 * erhalten (solange die App nicht deinstalliert wird).
 *
 * Mailadressen stehen hier bewusst NICHT: Die App kennt nur "Arbeit",
 * "Privat" oder "keine". Die echten Adressen stehen in den
 * Skripteigenschaften des Apps Script.
 */
object Prefs {
    const val MAIL_WORK = "work"
    const val MAIL_PRIVATE = "private"
    const val MAIL_NONE = "none"

    private const val MAX_RECENT = 8

    private fun p(context: Context) =
        context.getSharedPreferences("einstellungen", Context.MODE_PRIVATE)

    fun secret(context: Context): String = p(context).getString("secret", null) ?: ""

    fun setSecret(context: Context, value: String) {
        p(context).edit().putString("secret", value.trim()).apply()
    }

    fun transcribe(context: Context): Boolean = p(context).getBoolean("transcribe", true)

    fun setTranscribe(context: Context, value: Boolean) {
        p(context).edit().putBoolean("transcribe", value).apply()
    }

    fun mail(context: Context): String = p(context).getString("mail", null) ?: MAIL_WORK

    fun setMail(context: Context, value: String) {
        p(context).edit().putString("mail", value).apply()
    }

    fun recentContexts(context: Context): List<String> {
        val raw = p(context).getString("recent", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }
        } catch (e: JSONException) {
            emptyList()
        }
    }

    fun addRecentContext(context: Context, value: String) {
        val t = value.trim()
        if (t.isEmpty()) return
        val list = (listOf(t) + recentContexts(context).filter { it != t }).take(MAX_RECENT)
        p(context).edit().putString("recent", JSONArray(list).toString()).apply()
    }

    /** 0 = unbekannt, 1 = altes Script, 2 = Script mit Kontext/Mailauswahl/TXT. */
    fun serverApi(context: Context): Int = p(context).getInt("serverApi", 0)

    fun serverMessage(context: Context): String =
        p(context).getString("serverMessage", null) ?: "wird geprüft …"

    fun setServer(context: Context, api: Int, message: String) {
        p(context).edit().putInt("serverApi", api).putString("serverMessage", message).apply()
    }
}
