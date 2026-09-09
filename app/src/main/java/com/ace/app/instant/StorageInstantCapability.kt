package com.ace.app.instant

import android.content.Context
import android.os.Environment
import android.os.StatFs

class StorageInstantCapability : InstantCapability {
    override val id: String = "device_storage"
    override val name: String = "Device Storage Instant Intelligence"
    override val source: String = "storage_api"

    override fun confidence(command: String): Float {
        val lower = command.lowercase().trim()
        if (lower.startsWith("open ") || lower.startsWith("launch ") || lower.contains("help ") || lower.contains("strategy")) {
            return 0.0f
        }
        val words = lower.split("\\s+".toRegex())
        if (words.size > 15) return 0.0f
        if (lower.contains("storage") || lower.contains("disk space") || lower.contains("free space")) {
            return 0.95f
        }
        return 0.0f
    }

    override suspend fun execute(context: Context?, command: String): InstantResult {
        val stat = StatFs(Environment.getDataDirectory().path)
        val blockSize = stat.blockSizeLong
        val totalBlocks = stat.blockCountLong
        val availableBlocks = stat.availableBlocksLong

        val totalBytes = totalBlocks * blockSize
        val freeBytes = availableBlocks * blockSize
        val usedBytes = totalBytes - freeBytes

        val totalGb = String.format("%.1f", totalBytes / (1024.0 * 1024.0 * 1024.0))
        val freeGb = String.format("%.1f", freeBytes / (1024.0 * 1024.0 * 1024.0))
        val usedGb = String.format("%.1f", usedBytes / (1024.0 * 1024.0 * 1024.0))

        val lower = command.lowercase().trim()
        val message = when {
            lower.contains("used") -> {
                "You have used $usedGb GB of storage out of $totalGb GB."
            }
            lower.contains("enough") -> {
                if (freeBytes > 2L * 1024 * 1024 * 1024) {
                    "Yes, you have $freeGb GB of free storage available."
                } else {
                    "Storage is running low: only $freeGb GB free out of $totalGb GB."
                }
            }
            else -> {
                "You have $freeGb GB of free storage available out of $totalGb GB."
            }
        }

        return InstantResult(
            isHandled = true,
            capabilityId = id,
            message = message,
            source = source,
            outputData = mapOf("totalGb" to totalGb, "freeGb" to freeGb, "usedGb" to usedGb)
        )
    }
}
