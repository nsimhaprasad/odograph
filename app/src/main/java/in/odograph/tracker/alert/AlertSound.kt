package `in`.odograph.tracker.alert

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.speech.tts.TextToSpeech
import java.util.Locale

enum class AlertMode { VISUAL_ONLY, CHIME, VOICE }

/**
 * Plays the overspeed alert through the car's speakers.
 *
 * Two things matter more than the sound itself. First, audio focus: the box is usually playing
 * music through the same projection link, so the alert requests transient focus that lets the
 * media duck rather than stopping it. Second, everything is best-effort — a missing TTS voice or
 * a refused focus request must never affect recording, so every call is wrapped.
 */
class AlertSound(private val ctx: Context) {

    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var focusRequest: AudioFocusRequest? = null

    fun prepare(mode: AlertMode) {
        if (mode != AlertMode.VOICE || tts != null) return
        runCatching {
            tts = TextToSpeech(ctx) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    runCatching { tts?.language = Locale.getDefault() }
                    runCatching { tts?.setAudioAttributes(attributes) }
                }
            }
        }
    }

    fun play(mode: AlertMode) {
        if (mode == AlertMode.VISUAL_ONLY) return
        runCatching {
            requestFocus()
            when (mode) {
                AlertMode.VOICE -> if (ttsReady) speak() else chime()
                AlertMode.CHIME -> chime()
                AlertMode.VISUAL_ONLY -> Unit
            }
        }
    }

    private fun speak() {
        tts?.speak("Slow down", TextToSpeech.QUEUE_FLUSH, null, "odograph-overspeed")
    }

    /** Two short rising notes: distinct from a notification, unmistakable at road noise. */
    private fun chime() {
        val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 90)
        tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
        Thread {
            Thread.sleep(500)
            runCatching { tone.release() }
            abandonFocus()
        }.start()
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(attributes)
                .build()
            focusRequest = req
            audio.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audio.requestAudioFocus(
                null, AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    private fun abandonFocus() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audio.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION") audio.abandonAudioFocus(null)
            }
        }
    }

    fun release() {
        runCatching { tts?.shutdown() }
        tts = null
        ttsReady = false
    }
}
