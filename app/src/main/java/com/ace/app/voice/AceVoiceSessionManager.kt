package com.ace.app.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

enum class VoiceSessionState(val statusText: String) {
    IDLE("I'm ACE, your autonomous agent. What would you like me to do?"),
    LISTENING("🎤 Listening..."),
    PROCESSING("🧠 ACE is thinking..."),
    EXECUTING("⚡ Executing task..."),
    SPEAKING("🔊 ACE is speaking..."),
    FOLLOW_UP_LISTENING("🎤 Listening for follow-up...")
}

class AceVoiceSessionManager(
    private val context: Context,
    private val onStateChanged: (VoiceSessionState) -> Unit,
    private val onSpeechRecognized: (String) -> Unit
) {
    companion object {
        private const val TAG_SESSION = "ACE_SESSION"
        const val FOLLOW_UP_TIMEOUT_MS = 6000L
    }

    private var currentState: VoiceSessionState = VoiceSessionState.IDLE
    private val mainHandler = Handler(Looper.getMainLooper())
    private var followUpRunnable: Runnable? = null

    val voiceOutputManager = AceVoiceOutputManager(
        context = context,
        onSpeechStart = {
            updateState(VoiceSessionState.SPEAKING)
        },
        onSpeechDone = {
            if (currentState == VoiceSessionState.SPEAKING) {
                startFollowUpListening()
            } else if (currentState != VoiceSessionState.LISTENING && currentState != VoiceSessionState.PROCESSING) {
                updateState(VoiceSessionState.IDLE)
            }
        }
    )

    fun getCurrentState(): VoiceSessionState = currentState

    fun updateState(newState: VoiceSessionState) {
        mainHandler.post {
            if (currentState != newState) {
                currentState = newState
                Log.i(TAG_SESSION, "ACE_SESSION: state=$newState")
                onStateChanged(newState)
            }
        }
    }

    fun speakResponse(text: String, generationId: Long = 0L, isCurrentGen: (Long) -> Boolean = { true }) {
        cancelFollowUpTimer()
        voiceOutputManager.speak(text, generationId, isCurrentGen)
    }

    fun stopSpeaking() {
        cancelFollowUpTimer()
        voiceOutputManager.stop()
        if (currentState == VoiceSessionState.SPEAKING || currentState == VoiceSessionState.FOLLOW_UP_LISTENING) {
            updateState(VoiceSessionState.IDLE)
        }
    }

    fun startFollowUpListening() {
        cancelFollowUpTimer()
        Log.i(TAG_SESSION, "ACE_SESSION: starting follow-up listening window")
        updateState(VoiceSessionState.FOLLOW_UP_LISTENING)

        val runnable = Runnable {
            if (currentState == VoiceSessionState.FOLLOW_UP_LISTENING) {
                Log.i(TAG_SESSION, "ACE_SESSION: follow-up window timed out -> IDLE")
                updateState(VoiceSessionState.IDLE)
            }
        }
        followUpRunnable = runnable
        mainHandler.postDelayed(runnable, FOLLOW_UP_TIMEOUT_MS)
    }

    fun cancelFollowUpTimer() {
        followUpRunnable?.let { mainHandler.removeCallbacks(it) }
        followUpRunnable = null
    }

    fun onUserBargeInDetected() {
        cancelFollowUpTimer()
        Log.i("ACE_INTERRUPT", "ACE_INTERRUPT: barge-in speech detected while speaking/listening")
        voiceOutputManager.stop()
        updateState(VoiceSessionState.LISTENING)
    }

    fun handleRecognizedSpeech(text: String) {
        cancelFollowUpTimer()
        val clean = text.trim()
        if (clean.isNotBlank()) {
            updateState(VoiceSessionState.PROCESSING)
            onSpeechRecognized(clean)
        } else {
            updateState(VoiceSessionState.IDLE)
        }
    }

    fun release() {
        cancelFollowUpTimer()
        voiceOutputManager.release()
    }
}
