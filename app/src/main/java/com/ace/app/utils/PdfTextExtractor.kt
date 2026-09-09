package com.ace.app.utils

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

object PdfTextExtractor {

    suspend fun extractText(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(uri) ?: ""

            contentResolver.openInputStream(uri)?.use { inputStream ->
                return@withContext processInputStream(inputStream, mimeType)
            } ?: "Unable to open input stream for document URI."
        } catch (e: Exception) {
            "Error extracting text from document: ${e.message}"
        }
    }

    suspend fun extractTextFromFile(file: File): String = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.canRead()) {
                return@withContext "File does not exist or cannot be read."
            }

            file.inputStream().use { inputStream ->
                return@withContext processInputStream(inputStream, file.extension)
            }
        } catch (e: Exception) {
            "Error extracting text from file: ${e.message}"
        }
    }

    private fun processInputStream(inputStream: InputStream, typeOrExt: String): String {
        val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
        val rawTextBuilder = StringBuilder()
        var line: String? = reader.readLine()
        var totalBytesRead = 0
        val maxBytes = 100_000 // Limit to first 100KB for on-device prompt capacity

        while (line != null && totalBytesRead < maxBytes) {
            // Filter non-printable binary characters if PDF raw stream is read
            val cleanLine = line.replace(Regex("""[\x00-\x08\x0B\x0C\x0E-\x1F]"""), " ").trim()
            if (cleanLine.isNotBlank()) {
                // If PDF text stream markers exist like (Text) Tj or TJ, extract printable strings
                if (cleanLine.contains("Tj") || cleanLine.contains("TJ")) {
                    val extractedTokens = extractPdfTextTokens(cleanLine)
                    if (extractedTokens.isNotBlank()) {
                        rawTextBuilder.append(extractedTokens).append("\n")
                    }
                } else if (!cleanLine.startsWith("%PDF") && !cleanLine.startsWith("<<") && !cleanLine.startsWith("endobj")) {
                    rawTextBuilder.append(cleanLine).append("\n")
                }
            }
            totalBytesRead += line.length
            line = reader.readLine()
        }

        val result = rawTextBuilder.toString().trim()
        return if (result.isBlank()) {
            "Document content read, but no plain text paragraphs could be extracted."
        } else {
            result
        }
    }

    private fun extractPdfTextTokens(line: String): String {
        val regex = Regex("""\(([^)]+)\)""")
        val matches = regex.findAll(line)
        val sb = StringBuilder()
        for (match in matches) {
            val text = match.groupValues[1]
            if (text.length > 1 && text.any { it.isLetterOrDigit() }) {
                sb.append(text).append(" ")
            }
        }
        return sb.toString().trim()
    }
}
