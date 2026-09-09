package com.ace.app.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Offline Device Voice Output provider using on-device TextToSpeech.
 */
class DeviceVoiceOutput(
    context: Context,
    private val onSpeechStart: (() -> Unit)? = null,
    private val onSpeechDone: (() -> Unit)? = null
) : TextToSpeech.OnInitListener, VoiceOutputProvider {

    override val providerType: VoiceProvider = VoiceProvider.OFFLINE_LOCAL

    private var speaker: TextToSpeech? = TextToSpeech(context.applicationContext, this)
    private var ready = false
    private var pendingSpeech: String? = null

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override suspend fun isAvailable(context: Context): Boolean {
        return true
    }

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            speaker?.language = Locale.US
            speaker?.setSpeechRate(1.0f)
            speaker?.setPitch(1.0f)
            speaker?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    mainHandler.post { onSpeechStart?.invoke() }
                }

                override fun onDone(utteranceId: String?) {
                    mainHandler.post { onSpeechDone?.invoke() }
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { onSpeechDone?.invoke() }
                }
            })

            pendingSpeech?.let {
                speak(it)
                pendingSpeech = null
            }
        }
    }

    override fun speak(text: String, generationId: Long, currentGenerationId: () -> Long) {
        if (text.isBlank()) return
        if (generationId != 0L && currentGenerationId() != generationId) return

        if (!ready) {
            pendingSpeech = text
            return
        }
        stop()
        speaker?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "ace_voice_${System.currentTimeMillis()}")
    }

    override fun stop() {
        if (ready) {
            speaker?.stop()
        }
    }

    override fun release() {
        stop()
        speaker?.shutdown()
        speaker = null
        ready = false
    }

    fun isReady(): Boolean = ready
}
