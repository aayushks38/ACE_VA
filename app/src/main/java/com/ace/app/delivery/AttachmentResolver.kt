package com.ace.app.delivery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File

object AttachmentResolver {

    private const val TAG = "ACE_ATTACHMENT"

    fun resolveAttachment(context: Context, typeStr: String?, queryName: String?): ResolvedAttachment? {
        val cleanType = (typeStr ?: "").lowercase().trim()
        val cleanQuery = (queryName ?: "").lowercase().trim()

        Log.i(TAG, "ACE_ATTACHMENT: resolving type='$cleanType' query='$cleanQuery'")

        return when {
            cleanType.contains("screenshot") || cleanQuery.contains("screenshot") -> {
                resolveLatestScreenshot(context) ?: resolveLatestImage(context)
            }
            cleanType.contains("pdf") || cleanQuery.contains("pdf") -> {
                resolveLatestPdf(context)
            }
            cleanType.contains("scan") || cleanQuery.contains("scan") -> {
                resolveScannerOutput(context) ?: resolveLatestPdf(context) ?: resolveLatestImage(context)
            }
            cleanType.contains("video") || cleanQuery.contains("video") || cleanType.contains("mp4") -> {
                resolveLatestVideo(context)
            }
            cleanType.contains("doc") || cleanType.contains("file") || cleanQuery.contains("doc") -> {
                resolveLatestPdf(context) ?: resolveLatestImage(context)
            }
            else -> {
                // Default: Latest gallery photo/image
                resolveLatestImage(context) ?: resolveLatestScreenshot(context) ?: resolveLatestPdf(context)
            }
        }
    }

    fun resolveLatestImage(context: Context): ResolvedAttachment? {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE
            )
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"
            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val idIdx = it.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameIdx = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIdx = it.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val mimeIdx = it.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)

                    val imageId = if (idIdx >= 0) it.getLong(idIdx) else return null
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "latest_photo.jpg" else "latest_photo.jpg"
                    val size = if (sizeIdx >= 0) it.getLong(sizeIdx) else null
                    val mime = if (mimeIdx >= 0) it.getString(mimeIdx) ?: "image/jpeg" else "image/jpeg"

                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)

                    if (validateUri(context, contentUri)) {
                        Log.i(TAG, "ACE_ATTACHMENT: type=LATEST_IMAGE uri=$contentUri mime=$mime name=$name valid=true")
                        return ResolvedAttachment(
                            uri = contentUri,
                            displayName = name,
                            mimeType = mime,
                            sizeBytes = size,
                            source = AttachmentSource.GALLERY,
                            isValid = true
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACE_ATTACHMENT: resolveLatestImage failed: ${e.message}")
        }
        return null
    }

    fun resolveLatestScreenshot(context: Context): ResolvedAttachment? {
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_MODIFIED,
                MediaStore.Images.Media.SIZE,
                MediaStore.Images.Media.MIME_TYPE
            )
            val selection = "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ? OR ${MediaStore.Images.Media.DATA} LIKE ?"
            val selectionArgs = arrayOf("%screenshot%", "%Screenshots%")
            val sortOrder = "${MediaStore.Images.Media.DATE_MODIFIED} DESC"

            val cursor = context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, selectionArgs, sortOrder
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val idIdx = it.getColumnIndex(MediaStore.Images.Media._ID)
                    val nameIdx = it.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIdx = it.getColumnIndex(MediaStore.Images.Media.SIZE)
                    val mimeIdx = it.getColumnIndex(MediaStore.Images.Media.MIME_TYPE)

                    val imageId = if (idIdx >= 0) it.getLong(idIdx) else return null
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "screenshot.png" else "screenshot.png"
                    val size = if (sizeIdx >= 0) it.getLong(sizeIdx) else null
                    val mime = if (mimeIdx >= 0) it.getString(mimeIdx) ?: "image/png" else "image/png"

                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, imageId)

                    if (validateUri(context, contentUri)) {
                        Log.i(TAG, "ACE_ATTACHMENT: type=SCREENSHOT uri=$contentUri mime=$mime name=$name valid=true")
                        return ResolvedAttachment(
                            uri = contentUri,
                            displayName = name,
                            mimeType = mime,
                            sizeBytes = size,
                            source = AttachmentSource.SCREENSHOTS,
                            isValid = true
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACE_ATTACHMENT: resolveLatestScreenshot failed: ${e.message}")
        }
        return null
    }

    fun resolveLatestVideo(context: Context): ResolvedAttachment? {
        try {
            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DATE_MODIFIED,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.MIME_TYPE
            )
            val sortOrder = "${MediaStore.Video.Media.DATE_MODIFIED} DESC"
            val cursor = context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null, sortOrder
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val idIdx = it.getColumnIndex(MediaStore.Video.Media._ID)
                    val nameIdx = it.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME)
                    val sizeIdx = it.getColumnIndex(MediaStore.Video.Media.SIZE)
                    val mimeIdx = it.getColumnIndex(MediaStore.Video.Media.MIME_TYPE)

                    val videoId = if (idIdx >= 0) it.getLong(idIdx) else return null
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "video.mp4" else "video.mp4"
                    val size = if (sizeIdx >= 0) it.getLong(sizeIdx) else null
                    val mime = if (mimeIdx >= 0) it.getString(mimeIdx) ?: "video/mp4" else "video/mp4"

                    val contentUri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, videoId)

                    if (validateUri(context, contentUri)) {
                        Log.i(TAG, "ACE_ATTACHMENT: type=VIDEO uri=$contentUri mime=$mime name=$name valid=true")
                        return ResolvedAttachment(
                            uri = contentUri,
                            displayName = name,
                            mimeType = mime,
                            sizeBytes = size,
                            source = AttachmentSource.VIDEOS,
                            isValid = true
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACE_ATTACHMENT: resolveLatestVideo failed: ${e.message}")
        }
        return null
    }

    fun resolveLatestPdf(context: Context): ResolvedAttachment? {
        try {
            val filesUri = MediaStore.Files.getContentUri("external")
            val projection = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.MIME_TYPE
            )
            val selection = "${MediaStore.Files.FileColumns.MIME_TYPE} = ? OR ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("application/pdf", "%.pdf")
            val sortOrder = "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"

            val cursor = context.contentResolver.query(
                filesUri, projection, selection, selectionArgs, sortOrder
            )

            cursor?.use {
                if (it.moveToFirst()) {
                    val idIdx = it.getColumnIndex(MediaStore.Files.FileColumns._ID)
                    val nameIdx = it.getColumnIndex(MediaStore.Files.FileColumns.DISPLAY_NAME)
                    val sizeIdx = it.getColumnIndex(MediaStore.Files.FileColumns.SIZE)
                    val mimeIdx = it.getColumnIndex(MediaStore.Files.FileColumns.MIME_TYPE)

                    val pdfId = if (idIdx >= 0) it.getLong(idIdx) else return null
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "document.pdf" else "document.pdf"
                    val size = if (sizeIdx >= 0) it.getLong(sizeIdx) else null
                    val mime = if (mimeIdx >= 0) it.getString(mimeIdx) ?: "application/pdf" else "application/pdf"

                    val contentUri = ContentUris.withAppendedId(filesUri, pdfId)

                    if (validateUri(context, contentUri)) {
                        Log.i(TAG, "ACE_ATTACHMENT: type=PDF uri=$contentUri mime=$mime name=$name valid=true")
                        return ResolvedAttachment(
                            uri = contentUri,
                            displayName = name,
                            mimeType = "application/pdf",
                            sizeBytes = size,
                            source = AttachmentSource.DOCUMENTS,
                            isValid = true
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACE_ATTACHMENT: resolveLatestPdf failed: ${e.message}")
        }
        return null
    }

    fun resolveScannerOutput(context: Context): ResolvedAttachment? {
        // Check local app storage files for scanned document
        try {
            val appFilesDir = context.filesDir
            val scanFiles = appFilesDir.listFiles { f ->
                f.isFile && (f.name.contains("scan", ignoreCase = true) || f.extension.equals("pdf", ignoreCase = true))
            }?.sortedByDescending { it.lastModified() }

            val newest = scanFiles?.firstOrNull()
            if (newest != null && newest.exists() && newest.length() > 0) {
                val uri = androidx.core.content.FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    newest
                )
                val mime = if (newest.extension.equals("pdf", ignoreCase = true)) "application/pdf" else "image/jpeg"
                Log.i(TAG, "ACE_ATTACHMENT: type=SCANNER uri=$uri mime=$mime name=${newest.name} valid=true")
                return ResolvedAttachment(
                    uri = uri,
                    displayName = newest.name,
                    mimeType = mime,
                    sizeBytes = newest.length(),
                    source = AttachmentSource.SCANNER,
                    isValid = true
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACE_ATTACHMENT: resolveScannerOutput error: ${e.message}")
        }
        return null
    }

    fun validateUri(context: Context, uri: Uri): Boolean {
        return try {
            if (uri.scheme != "content") return false
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } catch (e: Exception) {
            Log.w(TAG, "ACE_ATTACHMENT: validateUri failed for $uri: ${e.message}")
            false
        }
    }
}
