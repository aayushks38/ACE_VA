package com.ace.app.instant

import android.content.Context

interface InstantCapability {
    val id: String
    val name: String
    val source: String

    fun canHandle(command: String): Boolean {
        return confidence(command) >= 0.75f
    }

    fun confidence(command: String): Float
    suspend fun execute(context: Context?, command: String): InstantResult
}
