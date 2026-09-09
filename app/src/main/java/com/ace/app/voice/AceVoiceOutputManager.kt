package com.ace.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class AceVoiceOutputManager(
    private val context: Context,
    private val onSpeechStart: (() -> Unit)? = null,
    private val onSpeechDone: (() -> Unit)? = null
) : TextToSpeech.OnInitListener {

    companion object {
        const val DEFAULT_SPEECH_RATE: Float = 0.88f
        const val DEFAULT_PITCH: Float = 1.0f
        private const val TAG_TTS = "ACE_TTS"
        private const val TAG_VOICE = "ACE_VOICE"
    }

    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingText: String? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var focusRequest: AudioFocusRequest? = null

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            Log.i(TAG_VOICE, "ACE_VOICE: tts_initialized=true")
            tts?.language = Locale.US
            tts?.setSpeechRate(DEFAULT_SPEECH_RATE)
            tts?.setPitch(DEFAULT_PITCH)

            val audioAttributes = AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .build()

            tts?.setAudioAttributes(audioAttributes)

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i(TAG_TTS, "ACE_TTS: speaking_started=true")
                    Log.i(TAG_TTS, "ACE_TTS: utterance_id=${utteranceId ?: "default"}")
                    mainHandler.post {
                        onSpeechStart?.invoke()
                    }
                }

                override fun onDone(utteranceId: String?) {
                    Log.i(TAG_TTS, "ACE_TTS: speaking_completed=true")
                    abandonAudioFocus()
                    mainHandler.post {
                        onSpeechDone?.invoke()
                    }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    Log.w(TAG_TTS, "ACE_TTS: speaking_error utterance_id=$utteranceId")
                    abandonAudioFocus()
                    mainHandler.post {
                        onSpeechDone?.invoke()
                    }
                }
            })

            pendingText?.let {
                speak(it)
                pendingText = null
            }
        } else {
            Log.e(TAG_VOICE, "ACE_VOICE: tts_initialized=false status=$status")
        }
    }

    fun speak(text: String, generationId: Long = 0L, isCurrentGen: (Long) -> Boolean = { true }) {
        val clean = text.trim()
        if (clean.isBlank()) return
        if (generationId != 0L && !isCurrentGen(generationId)) {
            Log.w(TAG_TTS, "ACE_TTS: Speech request discarded due to generation mismatch (generationId=$generationId)")
            return
        }

        val safeText = AssistantResponseComposer.enforceTtsSafetyFilter(clean)

        if (!ready) {
            pendingText = safeText
            return
        }

        stop()
        requestAudioFocus()

        Log.i("ACE_TTS_FINAL", "ACE_TTS_FINAL: session=$generationId source=AceVoiceOutputManager text=\"$safeText\"")
        val utteranceId = "ace_utt_${System.currentTimeMillis()}"
        tts?.speak(safeText, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        if (ready) {
            tts?.stop()
        }
        abandonAudioFocus()
        Log.i(TAG_TTS, "ACE_TTS: speech stopped")
        Log.i(TAG_TTS, "ACE_TTS: stopped_by_barge_in=true")
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val attributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .build()

                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(attributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener {}
                    .build()

                focusRequest = request
                val result = audioManager?.requestAudioFocus(request)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.i(TAG_VOICE, "ACE_VOICE: audio_focus=GRANTED")
                }
            } else {
                @Suppress("DEPRECATION")
                val result = audioManager?.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    Log.i(TAG_VOICE, "ACE_VOICE: audio_focus=GRANTED")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG_TTS, "ACE_TTS: Audio focus request warning: ${e.message}")
        }
    }

    private fun abandonAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                focusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
                focusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    fun release() {
        stop()
        tts?.shutdown()
        tts = null
        ready = false
    }

    fun isReady(): Boolean = ready
}
