package com.ace.app.instant

import android.content.Context
import android.os.Build
import android.provider.Settings

class PhoneInfoInstantCapability : InstantCapability {
    override val id: String = "device_info"
    override val name: String = "Phone Info Instant Intelligence"
    override val source: String = "build_version_api"

    override fun confidence(command: String): Float {
        val lower = command.lowercase().trim()
        if (lower.contains("android version") || lower.contains("phone model") || lower.contains("device name") ||
            lower.contains("what phone") || lower.contains("device model")) {
            return 0.95f
        }
        return 0.0f
    }

    override suspend fun execute(context: Context?, command: String): InstantResult {
        val lower = command.lowercase().trim()
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        val androidVersion = Build.VERSION.RELEASE
        val apiLevel = Build.VERSION.SDK_INT
        val deviceName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            try {
                if (context != null) Settings.Global.getString(context.contentResolver, "device_name") ?: "$manufacturer $model"
                else "$manufacturer $model"
            } catch (_: Exception) {
                "$manufacturer $model"
            }
        } else {
            "$manufacturer $model"
        }

        val message = when {
            lower.contains("android version") -> {
                "This phone is running Android $androidVersion (API level $apiLevel)."
            }
            lower.contains("model") -> {
                "This phone model is $manufacturer $model."
            }
            else -> {
                "Device name is $deviceName ($manufacturer $model running Android $androidVersion)."
            }
        }

        return InstantResult(
            isHandled = true,
            capabilityId = id,
            message = message,
            source = source,
            outputData = mapOf(
                "androidVersion" to androidVersion,
                "apiLevel" to "$apiLevel",
                "manufacturer" to manufacturer,
                "model" to model,
                "deviceName" to deviceName
            )
        )
    }
}
