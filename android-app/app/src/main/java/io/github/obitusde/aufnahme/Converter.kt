package io.github.obitusde.aufnahme

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.io.IOException

/**
 * Wandelt eine Aufnahme (Ogg/Opus) auf dem Handy in M4A (AAC) um, damit sie
 * auch Apps annehmen, die kein Ogg kennen (Copilot, Word, WhatsApp ...).
 * Aufgenommen wird weiter in Ogg: das bleibt bei einem Absturz lesbar.
 *
 * Das Ergebnis liegt in files/share/ und wird nach einem Tag weggeraeumt.
 */
object Converter {
    private const val AAC = MediaFormat.MIMETYPE_AUDIO_AAC
    private const val BIT_RATE = 64_000
    private const val TIMEOUT_US = 10_000L

    fun shareDir(context: Context): File = File(context.filesDir, "share").apply { mkdirs() }

    fun target(context: Context, name: String): File =
        File(shareDir(context), name.substringBeforeLast('.') + ".m4a")

    /** Alte Umwandlungen loeschen. */
    fun cleanup(context: Context, maxAgeMs: Long) {
        val limit = System.currentTimeMillis() - maxAgeMs
        shareDir(context).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    /**
     * Gibt die fertige M4A-Datei zurueck (vorhandene wird wiederverwendet).
     * onProgress bekommt 0..100. Wirft bei Fehlern.
     */
    fun toM4a(context: Context, source: File, onProgress: (Int) -> Unit): File {
        val out = target(context, source.name)
        if (out.isFile && out.length() > 0L && out.lastModified() >= source.lastModified()) return out
        val tmp = File(out.parentFile, out.name + ".tmp")
        tmp.delete()
        try {
            convert(source, tmp, onProgress)
            if (!tmp.renameTo(out)) throw IOException("Datei konnte nicht gespeichert werden.")
            return out
        } finally {
            tmp.delete()
        }
    }

    private fun convert(source: File, dest: File, onProgress: (Int) -> Unit) {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(source.absolutePath)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) throw IOException("Keine Tonspur gefunden.")
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val durationUs = if (inFormat.containsKey(MediaFormat.KEY_DURATION)) inFormat.getLong(MediaFormat.KEY_DURATION) else 0L
            var sampleRate = inFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = inFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)

            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME)!!)
            decoder = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            val mux = MediaMuxer(dest.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            var muxTrack = -1

            var enc: MediaCodec? = null
            val info = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var decodeDone = false
            var encodeDone = false
            var samplesWritten = 0L
            var lastPct = -1
            // PCM, das noch nicht in den Encoder passte
            var pending: ByteArray? = null
            var pendingOffset = 0

            fun startEncoder() {
                val f = MediaFormat.createAudioFormat(AAC, sampleRate, channels)
                f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                f.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
                val e = MediaCodec.createEncoderByType(AAC)
                e.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                e.start()
                enc = e
                encoder = e
            }

            /** Schiebt PCM in den Encoder, soweit Platz ist. true = alles untergebracht. */
            fun feedEncoder(e: MediaCodec): Boolean {
                while (true) {
                    val data = pending ?: return true
                    val idx = e.dequeueInputBuffer(TIMEOUT_US)
                    if (idx < 0) return false
                    val buf = e.getInputBuffer(idx)!!
                    buf.clear()
                    val n = minOf(buf.remaining(), data.size - pendingOffset)
                    buf.put(data, pendingOffset, n)
                    val ptsUs = samplesWritten * 1_000_000L / sampleRate
                    e.queueInputBuffer(idx, 0, n, ptsUs, 0)
                    samplesWritten += n / (2 * channels)
                    pendingOffset += n
                    if (pendingOffset >= data.size) {
                        pending = null
                        pendingOffset = 0
                    }
                }
            }

            fun drainEncoder(e: MediaCodec) {
                while (true) {
                    val idx = e.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> return
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            muxTrack = mux.addTrack(e.outputFormat)
                            mux.start()
                            muxerStarted = true
                        }
                        idx >= 0 -> {
                            val buf = e.getOutputBuffer(idx)!!
                            val config = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                            if (!config && encInfo.size > 0 && muxerStarted) {
                                buf.position(encInfo.offset)
                                buf.limit(encInfo.offset + encInfo.size)
                                mux.writeSampleData(muxTrack, buf, encInfo)
                            }
                            e.releaseOutputBuffer(idx, false)
                            if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                encodeDone = true
                                return
                            }
                        }
                    }
                }
            }

            while (!encodeDone) {
                // 1. Komprimierte Daten in den Decoder
                if (!inputDone) {
                    val idx = dec.dequeueInputBuffer(TIMEOUT_US)
                    if (idx >= 0) {
                        val buf = dec.getInputBuffer(idx)!!
                        val size = extractor.readSampleData(buf, 0)
                        if (size < 0) {
                            dec.queueInputBuffer(idx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            dec.queueInputBuffer(idx, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // 2. PCM aus dem Decoder in den Encoder
                val e0 = enc
                if (e0 != null && !feedEncoder(e0)) {
                    drainEncoder(e0)
                    continue
                }
                if (!decodeDone && pending == null) {
                    val idx = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val f = dec.outputFormat
                        sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (idx >= 0) {
                        if (enc == null) startEncoder()
                        val buf = dec.getOutputBuffer(idx)!!
                        if (info.size > 0) {
                            buf.position(info.offset)
                            buf.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buf.get(bytes)
                            pending = bytes
                            pendingOffset = 0
                        }
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        dec.releaseOutputBuffer(idx, false)
                        if (durationUs > 0) {
                            val pct = (info.presentationTimeUs * 100 / durationUs).toInt().coerceIn(0, 99)
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                        if (eos) decodeDone = true
                    }
                }

                // 3. Ende an den Encoder melden, wenn alles drin ist
                val e1 = enc
                if (e1 != null) {
                    if (feedEncoder(e1) && decodeDone && pending == null) {
                        val idx = e1.dequeueInputBuffer(TIMEOUT_US)
                        if (idx >= 0) {
                            val ptsUs = samplesWritten * 1_000_000L / sampleRate
                            e1.queueInputBuffer(idx, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            while (!encodeDone) drainEncoder(e1)
                        }
                    }
                    if (!encodeDone) drainEncoder(e1)
                } else if (decodeDone) {
                    throw IOException("Die Aufnahme enthält keinen Ton.")
                }
            }
            onProgress(100)
        } finally {
            try {
                decoder?.stop()
            } catch (e: Exception) {
                // egal
            }
            decoder?.release()
            try {
                encoder?.stop()
            } catch (e: Exception) {
                // egal
            }
            encoder?.release()
            try {
                if (muxerStarted) muxer?.stop()
            } catch (e: Exception) {
                // egal
            }
            muxer?.release()
            extractor.release()
        }
    }
}
