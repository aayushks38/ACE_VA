package com.ace.app.delivery

import android.content.Context
import android.database.Cursor
import android.provider.ContactsContract
import android.util.Log

sealed class RecipientResolutionResult {
    data class Single(val recipient: ResolvedRecipient) : RecipientResolutionResult()
    data class Ambiguous(val matches: List<Pair<String, String>>) : RecipientResolutionResult()
    object NotFound : RecipientResolutionResult()
}

object RecipientResolver {

    private const val TAG = "ACE_RECIPIENT"

    fun resolveRecipient(context: Context, queryStr: String?): RecipientResolutionResult {
        val rawQuery = (queryStr ?: "").trim()
        if (rawQuery.isBlank()) return RecipientResolutionResult.NotFound

        Log.i(TAG, "ACE_RECIPIENT: query='$rawQuery'")

        // 1. Direct phone number check (+91XXXXXXXXXX or raw digits >= 8 digits)
        val isDirectNumber = rawQuery.startsWith("+") || rawQuery.matches(Regex("""^\d{8,15}$"""))
        if (isDirectNumber) {
            val normalized = normalizePhoneNumber(rawQuery)
            Log.i(TAG, "ACE_RECIPIENT: direct phone number resolved='$normalized'")
            return RecipientResolutionResult.Single(
                ResolvedRecipient(
                    displayName = rawQuery,
                    phoneNumber = rawQuery,
                    normalizedPhoneNumber = normalized,
                    contactId = null,
                    source = RecipientSource.DIRECT_PHONE_NUMBER
                )
            )
        }

        // 2. Android Contacts Lookup via ContactsContract
        val hasPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.READ_CONTACTS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasPermission) {
            Log.w(TAG, "ACE_RECIPIENT: READ_CONTACTS permission missing")
            // Fallback for permission missing: treat as unverified name query
            return RecipientResolutionResult.NotFound
        }

        try {
            val contentResolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            )

            val cursor: Cursor? = contentResolver.query(
                uri, projection,
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$rawQuery%"), null
            )

            val matches = mutableListOf<Triple<Long, String, String>>()
            cursor?.use {
                val idIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val nameIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val numIdx = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)

                while (it.moveToNext()) {
                    val id = if (idIdx >= 0) it.getLong(idIdx) else 0L
                    val name = if (nameIdx >= 0) it.getString(nameIdx) ?: "" else ""
                    val num = if (numIdx >= 0) it.getString(numIdx) ?: "" else ""
                    if (name.isNotBlank() && num.isNotBlank()) {
                        matches.add(Triple(id, name, num))
                    }
                }
            }

            if (matches.isEmpty()) {
                Log.w(TAG, "ACE_RECIPIENT: matches=0 for query='$rawQuery'")
                return RecipientResolutionResult.NotFound
            }

            // Check exact name match first
            val exactMatches = matches.filter { it.second.equals(rawQuery, ignoreCase = true) }
            if (exactMatches.size == 1) {
                val single = exactMatches.first()
                val normalized = normalizePhoneNumber(single.third)
                Log.i(TAG, "ACE_RECIPIENT: exact contact match='${single.second}' num='$normalized'")
                return RecipientResolutionResult.Single(
                    ResolvedRecipient(
                        displayName = single.second,
                        phoneNumber = single.third,
                        normalizedPhoneNumber = normalized,
                        contactId = single.first,
                        source = RecipientSource.CONTACTS
                    )
                )
            }

            // Check unique contact display names
            val distinctNames = matches.map { it.second }.distinct()
            if (distinctNames.size == 1) {
                val single = matches.first()
                val normalized = normalizePhoneNumber(single.third)
                Log.i(TAG, "ACE_RECIPIENT: single distinct match='${single.second}' num='$normalized'")
                return RecipientResolutionResult.Single(
                    ResolvedRecipient(
                        displayName = single.second,
                        phoneNumber = single.third,
                        normalizedPhoneNumber = normalized,
                        contactId = single.first,
                        source = RecipientSource.CONTACTS
                    )
                )
            }

            // Ambiguous multiple contact matches
            val ambiguousList = matches.map { it.second to it.third }.distinctBy { it.first }
            Log.w(TAG, "ACE_RECIPIENT: ambiguous contact matches count=${ambiguousList.size} names=${ambiguousList.map { it.first }}")
            return RecipientResolutionResult.Ambiguous(ambiguousList)

        } catch (e: Exception) {
            Log.e(TAG, "ACE_RECIPIENT: query exception: ${e.message}")
            return RecipientResolutionResult.NotFound
        }
    }

    fun normalizePhoneNumber(raw: String): String {
        var clean = raw.replace(Regex("""[^\d+]"""), "")
        if (clean.startsWith("0")) {
            clean = clean.substring(1)
        }
        return clean
    }
}
