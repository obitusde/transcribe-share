package io.github.obitusde.aufnahme

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * Uebernimmt Audio, das aus anderen Apps an diese App geteilt wird:
 *
 *   - Dateien (z. B. aus dem Google Recorder oder einer heruntergeladenen
 *     Podcast-Folge) werden in den App-Speicher kopiert.
 *   - Links werden heruntergeladen. Zeigt der Link auf eine Webseite statt
 *     auf eine Audiodatei, wird auf der Seite nach einer Audiodatei gesucht
 *     (og:audio, RSS-enclosure, audio/source-Tag, .mp3/.m4a-Link).
 *
 * Erlaubt sind nur Formate, die das Apps Script transkribiert:
 * mp3, m4a, ogg, wav.
 */
object Importer {
    class Imported(val file: File, val title: String)

    private const val UNSUPPORTED =
        "Dieses Format kann das Script nicht transkribieren. M\u00f6glich sind mp3, m4a, ogg und wav."
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android) Aufnahme-App"
    private const val HTML_LIMIT = 3_000_000

    fun extForMime(mime: String?): String? =
        when (mime?.substringBefore(';')?.trim()?.lowercase()) {
            "audio/mpeg", "audio/mp3", "audio/mpeg3", "audio/x-mpeg" -> "mp3"
            "audio/mp4", "audio/x-m4a", "audio/m4a" -> "m4a"
            "audio/ogg", "audio/opus", "application/ogg", "audio/x-opus+ogg" -> "ogg"
            "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
            else -> null
        }

    fun extForName(name: String?): String? {
        val e = name?.substringBefore('?')?.substringAfterLast('.', "")?.lowercase() ?: return null
        return when (e) {
            "mp3", "m4a", "ogg", "wav" -> e
            "opus", "oga" -> "ogg"
            else -> null
        }
    }

    fun displayName(context: Context, uri: Uri): String? {
        val fromProvider = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        return fromProvider ?: uri.lastPathSegment
    }

    /** Kopiert eine geteilte Datei (content://) in den App-Speicher. */
    fun copyFromUri(context: Context, uri: Uri, mimeHint: String?): Imported {
        val name = displayName(context, uri)
        val ext = extForName(name)
            ?: extForMime(context.contentResolver.getType(uri))
            ?: extForMime(mimeHint)
            ?: throw Uploader.UploadException("$UNSUPPORTED (${name ?: mimeHint ?: "unbekannt"})")
        val target = Store.newFile(context, ext)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Die geteilte Datei konnte nicht gelesen werden.")
        input.use { i -> target.outputStream().use { o -> i.copyTo(o) } }
        if (target.length() == 0L) {
            target.delete()
            throw Uploader.UploadException("Die geteilte Datei ist leer.")
        }
        return Imported(target, name ?: target.name)
    }

    /** Laedt eine Audiodatei von einem Link herunter. */
    fun download(context: Context, link: String, onProgress: (Long, Long) -> Unit): Imported {
        var current = link
        for (attempt in 0..1) {
            val c = openFollowing(current)
            try {
                val type = c.contentType
                val isText = type != null && type.lowercase().startsWith("text/")
                val ext = extForMime(type) ?: if (isText) null else extForName(c.url.path)
                if (ext != null) return save(context, c, ext, onProgress)

                if (isText && attempt == 0) {
                    val html = readLimited(c)
                    current = findAudioLink(html, c.url)
                        ?: throw Uploader.UploadException(
                            "Auf der Seite wurde keine Audiodatei gefunden. " +
                                "Lade die Folge in deiner Podcast-App herunter und teile die Datei statt des Links."
                        )
                    continue
                }
                throw Uploader.UploadException(
                    "Der Link zeigt nicht auf eine Audiodatei (${type ?: "unbekannter Typ"}). $UNSUPPORTED"
                )
            } finally {
                c.disconnect()
            }
        }
        throw Uploader.UploadException("Keine Audiodatei gefunden.")
    }

    private fun openFollowing(link: String): HttpURLConnection {
        var url = URL(link)
        for (hop in 0 until 8) {
            val c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 30_000
            c.readTimeout = 120_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            val code = c.responseCode
            if (code in 300..399) {
                val location = c.getHeaderField("Location")
                c.disconnect()
                if (location == null) throw IOException("Weiterleitung ohne Ziel (HTTP $code).")
                url = URL(url, location)
                continue
            }
            if (code != 200) {
                c.disconnect()
                throw IOException("Link nicht abrufbar (HTTP $code).")
            }
            return c
        }
        throw IOException("Zu viele Weiterleitungen.")
    }

    private fun save(
        context: Context,
        c: HttpURLConnection,
        ext: String,
        onProgress: (Long, Long) -> Unit
    ): Imported {
        val total = c.contentLengthLong
        val target = Store.newFile(context, ext)
        try {
            c.inputStream.use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport >= 1_048_576L) {
                            lastReport = done
                            onProgress(done, total)
                        }
                    }
                    onProgress(done, total)
                }
            }
        } catch (e: Exception) {
            target.delete()
            throw e
        }
        if (target.length() == 0L) {
            target.delete()
            throw Uploader.UploadException("Die Datei hinter dem Link ist leer.")
        }
        return Imported(target, titleFrom(c))
    }

    private fun titleFrom(c: HttpURLConnection): String {
        val disposition = c.getHeaderField("Content-Disposition")
        val fromHeader = disposition?.let {
            Regex("filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1)
        }
        val raw = fromHeader ?: c.url.path.substringAfterLast('/')
        val decoded = try {
            URLDecoder.decode(raw, "UTF-8")
        } catch (e: Exception) {
            raw
        }
        return decoded.ifBlank { "Podcast" }
    }

    private fun readLimited(c: HttpURLConnection): String {
        val bytes = c.inputStream.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(32 * 1024)
            while (out.size() < HTML_LIMIT) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private val AUDIO_PATTERNS = listOf(
        Regex("<meta[^>]+property=[\"']og:audio(?::secure_url|:url)?[\"'][^>]*content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
        Regex("<meta[^>]+content=[\"']([^\"']+)[\"'][^>]*property=[\"']og:audio(?::secure_url|:url)?[\"']", RegexOption.IGNORE_CASE),
        Regex("<enclosure[^>]+url=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE),
        Regex("<(?:audio|source)[^>]+src=[\"']([^\"']+\\.(?:mp3|m4a|ogg|opus|wav)(?:\\?[^\"']*)?)[\"']", RegexOption.IGNORE_CASE),
        Regex("(https?:(?:\\\\?/){2}[^\"'\\s<>]+?\\.(?:mp3|m4a)(?:\\?[^\"'\\s<>]*)?)(?=[\"'\\s<>])", RegexOption.IGNORE_CASE)
    )

    private fun findAudioLink(html: String, base: URL): String? {
        for (p in AUDIO_PATTERNS) {
            val hit = p.find(html)?.groupValues?.get(1) ?: continue
            val cleaned = hit.replace("&amp;", "&").replace("\\/", "/")
            return try {
                URL(base, cleaned).toString()
            } catch (e: Exception) {
                continue
            }
        }
        return null
    }
}
