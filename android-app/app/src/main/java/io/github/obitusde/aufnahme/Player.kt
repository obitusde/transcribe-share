package io.github.obitusde.aufnahme

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

/**
 * Spielt eine Aufnahme direkt in der App ab. Immer nur eine gleichzeitig;
 * startet eine andere, hoert die vorherige auf.
 */
class Player {
    private var mp: MediaPlayer? = null

    /** Name der Aufnahme, die gerade geladen ist ("" = keine). */
    var current = ""
        private set

    /** Fehlertext fuer die Anzeige, "" = alles gut. */
    var error = ""
        private set

    fun isPlaying(name: String): Boolean =
        current == name && try {
            mp?.isPlaying == true
        } catch (e: IllegalStateException) {
            false
        }

    fun position(name: String): Int =
        if (current != name) 0 else try {
            mp?.currentPosition ?: 0
        } catch (e: IllegalStateException) {
            0
        }

    fun duration(name: String): Int =
        if (current != name) 0 else try {
            mp?.duration ?: 0
        } catch (e: IllegalStateException) {
            0
        }

    /** Abspielen bzw. Pause; bei einer anderen Aufnahme ab startMs. */
    fun toggle(name: String, file: File, startMs: Int = 0) {
        val p = mp
        if (p != null && current == name) {
            try {
                if (p.isPlaying) p.pause() else p.start()
            } catch (e: IllegalStateException) {
                release()
            }
            return
        }
        play(name, file, startMs)
    }

    fun seek(name: String, file: File, ms: Int) {
        val p = mp
        if (p != null && current == name) {
            try {
                p.seekTo(ms)
            } catch (e: IllegalStateException) {
                release()
            }
        } else {
            play(name, file, ms)
        }
    }

    private fun play(name: String, file: File, startMs: Int) {
        release()
        error = ""
        val p = MediaPlayer()
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            p.setDataSource(file.absolutePath)
            p.prepare()
            if (startMs > 0) p.seekTo(startMs)
            p.setOnCompletionListener {
                try {
                    it.seekTo(0)
                } catch (e: IllegalStateException) {
                    // egal
                }
            }
            p.start()
            mp = p
            current = name
        } catch (e: Exception) {
            p.release()
            error = "Abspielen ging nicht: " + Store.describe(e)
            current = name
        }
    }

    fun pause() {
        try {
            mp?.let { if (it.isPlaying) it.pause() }
        } catch (e: IllegalStateException) {
            release()
        }
    }

    fun release() {
        try {
            mp?.release()
        } catch (e: Exception) {
            // egal
        }
        mp = null
        current = ""
    }
}
