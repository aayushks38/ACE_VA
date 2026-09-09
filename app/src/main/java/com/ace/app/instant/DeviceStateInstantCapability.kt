package com.ace.app.instant

import android.content.Context
import android.content.res.Configuration
import android.media.AudioManager
import android.provider.Settings

class DeviceStateInstantCapability : InstantCapability {
    override val id: String = "device_state"
    override val name: String = "Device State Instant Intelligence"
    override val source: String = "system_settings_api"

    override fun confidence(command: String): Float {
        val lower = command.lowercase().trim()
        if (lower.contains("volume") || lower.contains("brightness") || lower.contains("ringer") ||
            lower.contains("orientation") || lower.contains("silent mode")) {
            return 0.95f
        }
        return 0.0f
    }

    override suspend fun execute(context: Context?, command: String): InstantResult {
        if (context == null) {
            return InstantResult(
                isHandled = false,
                capabilityId = id,
                message = "Device context unavailable for device state.",
                source = source,
                error = "Null context"
            )
        }

        val lower = command.lowercase().trim()

        if (lower.contains("volume")) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val currentVol = audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
            val maxVol = audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
            val pct = ((currentVol / maxVol.toFloat()) * 100).toInt()
            return InstantResult(
                isHandled = true,
                capabilityId = id,
                message = "Current media volume is $pct% ($currentVol/$maxVol).",
                source = source,
                outputData = mapOf("volumePct" to "$pct")
            )
        }

        if (lower.contains("brightness")) {
            val brightness = try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            } catch (_: Exception) {
                128
            }
            val pct = ((brightness / 255.0f) * 100).toInt()
            return InstantResult(
                isHandled = true,
                capabilityId = id,
                message = "Current screen brightness is $pct% ($brightness/255).",
                source = source,
                outputData = mapOf("brightnessPct" to "$pct")
            )
        }

        if (lower.contains("ringer") || lower.contains("silent")) {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val ringerMode = when (audioManager?.ringerMode) {
                AudioManager.RINGER_MODE_SILENT -> "Silent"
                AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
                AudioManager.RINGER_MODE_NORMAL -> "Normal"
                else -> "Normal"
            }
            return InstantResult(
                isHandled = true,
                capabilityId = id,
                message = "Current ringer state is $ringerMode.",
                source = source,
                outputData = mapOf("ringerMode" to ringerMode)
            )
        }

        val orientation = context.resources.configuration.orientation
        val orientationStr = if (orientation == Configuration.ORIENTATION_LANDSCAPE) "Landscape" else "Portrait"
        return InstantResult(
            isHandled = true,
            capabilityId = id,
            message = "Device orientation is $orientationStr.",
            source = source,
            outputData = mapOf("orientation" to orientationStr)
        )
    }
}
