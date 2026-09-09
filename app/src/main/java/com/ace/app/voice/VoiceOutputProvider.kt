package com.ace.app.voice

import android.content.Context

interface VoiceOutputProvider {
    val providerType: VoiceProvider
    suspend fun isAvailable(context: Context): Boolean
    fun speak(text: String, generationId: Long = 0, currentGenerationId: () -> Long = { 0 })
    fun stop()
    fun release()
}
