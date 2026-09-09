package com.ace.app.voice

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.ace.app.utils.NetworkUtils
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class VoiceProvider {
    ONLINE_RIME,
    OFFLINE_LOCAL,
    DEVICE_FALLBACK,
    UNAVAILABLE
}

class RimeVoiceOutput(
    private val context: Context,
    private val serverUrl: String = "http://10.0.2.2:3000",
    private val onSpeechStart: (() -> Unit)? = null,
    private val onSpeechDone: (() -> Unit)? = null,
    private val onProviderChanged: ((VoiceProvider) -> Unit)? = null
) : VoiceOutputProvider {

    override val providerType: VoiceProvider = VoiceProvider.ONLINE_RIME

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var fallbackTts: DeviceVoiceOutput? = DeviceVoiceOutput(context, onSpeechStart, onSpeechDone)
    private var mediaPlayer: MediaPlayer? = null
    private var activeCall: Call? = null
    private var activeAudioFile: File? = null

    var currentProvider: VoiceProvider = VoiceProvider.OFFLINE_LOCAL
        private set(value) {
            field = value
            mainHandler.post { onProviderChanged?.invoke(value) }
        }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    override suspend fun isAvailable(context: Context): Boolean {
        if (!NetworkUtils.isNetworkAvailable(context)) {
            return false
        }
        return NetworkUtils.isRimeServerReachable(serverUrl)
    }

    override fun speak(text: String, generationId: Long, currentGenerationId: () -> Long) {
        if (text.isBlank()) return
        stop()

        scope.launch {
            // 1. Check network connectivity and Rime server status
            val available = isAvailable(context)

            if (!available) {
                currentProvider = VoiceProvider.OFFLINE_LOCAL
                mainHandler.post {
                    if (generationId == 0L || currentGenerationId() == generationId) {
                        fallbackTts?.speak(text, generationId, currentGenerationId)
                    }
                }
                return@launch
            }

            // 2. Request Rime audio generation from server proxy
            try {
                val jsonPayload = JSONObject().apply {
                    put("text", text)
                    put("speaker", "abbie")
                }.toString()

                val request = Request.Builder()
                    .url("$serverUrl/voice")
                    .post(jsonPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                    .build()

                val call = httpClient.newCall(request)
                activeCall = call

                val response = call.execute()

                if (generationId != 0L && currentGenerationId() != generationId) {
                    response.close()
                    return@launch
                }

                if (response.isSuccessful && response.body != null) {
                    val audioBytes = response.body!!.bytes()
                    val tempFile = File(context.cacheDir, "rime_temp_${System.currentTimeMillis()}.wav")
                    FileOutputStream(tempFile).use { it.write(audioBytes) }
                    activeAudioFile = tempFile

                    if (generationId != 0L && currentGenerationId() != generationId) {
                        tempFile.delete()
                        return@launch
                    }

                    mainHandler.post {
                        if (generationId == 0L || currentGenerationId() == generationId) {
                            playAudioFile(tempFile, generationId, currentGenerationId)
                        } else {
                            tempFile.delete()
                        }
                    }
                } else {
                    response.close()
                    currentProvider = VoiceProvider.OFFLINE_LOCAL
                    mainHandler.post {
                        if (generationId == 0L || currentGenerationId() == generationId) {
                            fallbackTts?.speak(text, generationId, currentGenerationId)
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) return@launch
                currentProvider = VoiceProvider.OFFLINE_LOCAL
                mainHandler.post {
                    if (generationId == 0L || currentGenerationId() == generationId) {
                        fallbackTts?.speak(text, generationId, currentGenerationId)
                    }
                }
            }
        }
    }

    private fun playAudioFile(file: File, generationId: Long, currentGenerationId: () -> Long) {
        try {
            stopMediaPlayer()
            currentProvider = VoiceProvider.ONLINE_RIME

            val player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnPreparedListener { mp ->
                    if (generationId == 0L || currentGenerationId() == generationId) {
                        onSpeechStart?.invoke()
                        mp.start()
                    } else {
                        stopMediaPlayer()
                    }
                }
                setOnCompletionListener {
                    onSpeechDone?.invoke()
                    file.delete()
                }
                setOnErrorListener { _, _, _ ->
                    onSpeechDone?.invoke()
                    file.delete()
                    true
                }
                prepareAsync()
            }
            mediaPlayer = player
        } catch (e: Exception) {
            currentProvider = VoiceProvider.OFFLINE_LOCAL
            fallbackTts?.speak("Notice: Audio playback failed.", generationId, currentGenerationId)
        }
    }

    override fun stop() {
        activeCall?.cancel()
        activeCall = null

        stopMediaPlayer()

        activeAudioFile?.delete()
        activeAudioFile = null

        fallbackTts?.stop()
    }

    private fun stopMediaPlayer() {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                it.release()
            }
        } catch (ignored: Exception) {}
        mediaPlayer = null
    }

    override fun release() {
        stop()
        scope.cancel()
        fallbackTts?.release()
        fallbackTts = null
    }
}
