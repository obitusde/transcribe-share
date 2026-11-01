package io.github.obitusde.aufnahme

import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

/**
 * Spricht dieselbe Schnittstelle wie die PWA (apps-script/DirectUpload.gs):
 *
 *   1. initUpload      -> Apps Script eroeffnet eine Drive-Upload-Session
 *   2. PUT der Bytes   -> direkt zu Drive, in 8-MB-Stuecken, mit Wiederholung
 *   3. completeUpload  -> Apps Script startet die Transkription
 *
 * Kein API-Key in der App. Scheitert nur Schritt 3, liegt die Datei schon in
 * Drive und FolderWatcher.gs startet sie spaetestens nach 10 Minuten.
 */
object Uploader {
    const val SCRIPT_URL =
        "https://script.google.com/macros/s/AKfycbxkkdYTJi24p-f0BY9M3SbnpPYEBQcxjclpJ3K1pUKbolUqb4DpuaM7XfAwE5KRHWc8ig/exec"

    private const val MIME = "audio/ogg"
    private const val CHUNK_SIZE = 8 * 1024 * 1024 // Drive verlangt Vielfache von 256 KiB
    private const val MAX_RETRIES = 5
    private const val FALLBACK_NOTE = "Transkription startet automatisch (spaetestens in 10 Min.)."

    class UploadException(message: String, cause: Throwable? = null) : Exception(message, cause)

    class Result(val fileId: String, val note: String)

    private sealed class Step {
        class Done(val fileId: String) : Step()
        class Partial(val next: Long) : Step()
    }

    fun upload(file: File, onProgress: (Long, Long) -> Unit): Result {
        val total = file.length()
        if (total <= 0L) throw UploadException("Datei ist leer.")

        val init = retrying(3) {
            postJson(
                JSONObject()
                    .put("action", "initUpload")
                    .put("filename", file.name)
                    .put("mimeType", MIME)
                    .put("size", total)
            )
        }
        if (init.optString("status") != "ok") {
            throw UploadException("Apps Script lehnte den Upload ab: " + init.optString("message", init.toString()))
        }
        val uploadUrl = init.optString("uploadUrl")
        if (uploadUrl.isEmpty()) throw UploadException("Apps Script lieferte keine Upload-Adresse.")

        val fileId = putFile(uploadUrl, file, total, onProgress)

        // Ab hier ist die Datei sicher in Drive.
        return try {
            val done = postJson(JSONObject().put("action", "completeUpload").put("fileId", fileId))
            val t = done.optJSONObject("transcription")
            val note = when {
                t == null -> FALLBACK_NOTE
                t.optString("status") == "processing" -> "Transkription laeuft, Mail folgt."
                else -> "Transkription: " + t.optString("message", t.optString("status"))
            }
            Result(fileId, note)
        } catch (e: Exception) {
            Result(fileId, FALLBACK_NOTE)
        }
    }

    private fun putFile(url: String, file: File, total: Long, onProgress: (Long, Long) -> Unit): String {
        var offset = 0L
        var failures = 0
        val buffer = ByteArray(CHUNK_SIZE)
        val raf = RandomAccessFile(file, "r")
        try {
            while (true) {
                val step: Step = try {
                    if (offset >= total) {
                        queryStatus(url, total)
                    } else {
                        val len = min(CHUNK_SIZE.toLong(), total - offset).toInt()
                        raf.seek(offset)
                        raf.readFully(buffer, 0, len)
                        putChunk(url, buffer, len, offset, total)
                    }
                } catch (e: IOException) {
                    failures++
                    if (failures > MAX_RETRIES) {
                        throw UploadException(
                            "Upload nach $MAX_RETRIES Wiederholungen abgebrochen (${e.javaClass.simpleName}: ${e.message}).", e
                        )
                    }
                    Thread.sleep(2000L shl (failures - 1))
                    try {
                        queryStatus(url, total)
                    } catch (again: IOException) {
                        Step.Partial(offset)
                    }
                }

                when (step) {
                    is Step.Done -> {
                        onProgress(total, total)
                        return step.fileId
                    }
                    is Step.Partial -> {
                        if (step.next >= total) {
                            throw UploadException("Drive hat alle Bytes, meldet aber keinen Abschluss.")
                        }
                        if (step.next > offset) failures = 0
                        offset = step.next
                        onProgress(offset, total)
                    }
                }
            }
        } finally {
            raf.close()
        }
    }

    private fun putChunk(url: String, buffer: ByteArray, len: Int, offset: Long, total: Long): Step {
        val c = open(url, "PUT")
        c.doOutput = true
        c.setFixedLengthStreamingMode(len)
        c.setRequestProperty("Content-Type", MIME)
        c.setRequestProperty("Content-Range", "bytes $offset-${offset + len - 1}/$total")
        c.outputStream.use { it.write(buffer, 0, len) }
        return readStep(c)
    }

    /** Fragt Drive, wie viele Bytes schon angekommen sind. */
    private fun queryStatus(url: String, total: Long): Step {
        val c = open(url, "PUT")
        c.doOutput = true
        c.setFixedLengthStreamingMode(0)
        c.setRequestProperty("Content-Range", "bytes */$total")
        c.outputStream.close()
        return readStep(c)
    }

    private fun readStep(c: HttpURLConnection): Step {
        try {
            val code = c.responseCode
            return when {
                code == 200 || code == 201 -> {
                    val body = readBody(c)
                    val id = try { JSONObject(body).optString("id") } catch (e: JSONException) { "" }
                    if (id.isEmpty()) throw UploadException("Drive meldete Erfolg, aber keine Datei-ID.")
                    Step.Done(id)
                }
                code == 308 -> {
                    val range = c.getHeaderField("Range")
                    Step.Partial(if (range == null) 0L else range.substringAfterLast('-').trim().toLong() + 1)
                }
                code >= 500 || code == 429 -> throw IOException("Drive HTTP $code")
                else -> throw UploadException("Drive lehnte den Upload ab (HTTP $code): ${readBody(c).take(200)}")
            }
        } finally {
            c.disconnect()
        }
    }

    /**
     * Apps Script beantwortet POST mit einer Weiterleitung (302) auf
     * googleusercontent.com, wo das Ergebnis per GET abgeholt wird.
     */
    private fun postJson(payload: JSONObject): JSONObject {
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        var c = open(SCRIPT_URL, "POST")
        c.doOutput = true
        c.setFixedLengthStreamingMode(bytes.size)
        c.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
        c.outputStream.use { it.write(bytes) }

        var code = c.responseCode
        var hops = 0
        while (code in 300..399 && hops < 5) {
            val location = c.getHeaderField("Location") ?: break
            c.disconnect()
            c = open(location, "GET")
            code = c.responseCode
            hops++
        }

        val body = readBody(c)
        c.disconnect()
        if (code != 200) throw IOException("Apps Script HTTP $code: ${body.take(200)}")
        return try {
            JSONObject(body)
        } catch (e: JSONException) {
            throw IOException("Apps Script antwortete nicht mit JSON: ${body.take(200)}")
        }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = false
            useCaches = false
        }

    private fun readBody(c: HttpURLConnection): String {
        val stream = try {
            if (c.responseCode >= 400) c.errorStream else c.inputStream
        } catch (e: IOException) {
            c.errorStream
        }
        return stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
    }

    private inline fun <T> retrying(attempts: Int, block: () -> T): T {
        var last: IOException? = null
        for (i in 1..attempts) {
            try {
                return block()
            } catch (e: IOException) {
                last = e
                if (i < attempts) Thread.sleep(2000L * i)
            }
        }
        throw last ?: IOException("Unbekannter Netzwerkfehler")
    }
}
