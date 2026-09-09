package com.ace.app.instant

import android.content.Context
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LocalFileInstantCapability : InstantCapability {
    override val id: String = "device_file_intelligence"
    override val name: String = "Local File Instant Intelligence"
    override val source: String = "mediastore_file_api"

    override fun confidence(command: String): Float {
        val lower = command.lowercase().trim()
        if (lower.contains("find my pdf") || lower.contains("show recent document") ||
            lower.contains("find files containing") || lower.contains("recently modified file")) {
            return 0.90f
        }
        return 0.0f
    }

    override suspend fun execute(context: Context?, command: String): InstantResult {
        if (context == null) {
            return InstantResult(
                isHandled = false,
                capabilityId = id,
                message = "Device context unavailable for file discovery.",
                source = source,
                error = "Null context"
            )
        }

        val lower = command.lowercase().trim()
        val foundFiles = mutableListOf<String>()

        try {
            val projection = arrayOf(
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )

            val selection = when {
                lower.contains("pdf") -> "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.pdf'"
                lower.contains("containing") -> {
                    val searchWord = lower.substringAfter("containing").trim().replace("\"", "").replace("'", "")
                    if (searchWord.isNotBlank()) "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%$searchWord%'"
                    else "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.pdf' OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.doc%'"
                }
                else -> "${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.pdf' OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.doc%' OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE '%.txt'"
            }

            val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC LIMIT 5"

            context.contentResolver.query(
                MediaStore.Files.getContentUri("external"),
                projection,
                selection,
                null,
                sortOrder
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val pathIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATA)
                val dateIndex = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    val path = cursor.getString(pathIndex)
                    val dateModifiedSec = cursor.getLong(dateIndex)
                    val dateStr = SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(dateModifiedSec * 1000))
                    foundFiles.add("$name ($dateStr)")
                }
            }
        } catch (_: Exception) {}

        val message = if (foundFiles.isNotEmpty()) {
            "Found ${foundFiles.size} document(s):\n" + foundFiles.joinToString("\n• ", prefix = "• ")
        } else {
            "No matching documents found in device storage."
        }

        return InstantResult(
            isHandled = true,
            capabilityId = id,
            message = message,
            source = source,
            outputData = mapOf("fileCount" to "${foundFiles.size}")
        )
    }
}
