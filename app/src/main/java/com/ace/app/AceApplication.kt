package com.ace.app

import android.app.Application
import android.util.Log
import com.ace.app.brain.GemmaBrainManager

class AceApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        Log.i("ACE_BRAIN", "ACE_BRAIN: process_start")
        Log.i("ACE_BRAIN", "ACE_BRAIN: AceApplication initialized")

        // Initialize Brain Manager and check persistent model storage asynchronously
        if (GemmaBrainManager.isModelInstalled(this)) {
            Log.i("ACE_BRAIN", "ACE_BRAIN: Persistent Gemma model detected on app launch. Initiating background runtime load...")
            GemmaBrainManager.ensureRuntimeLoadedAsync(this)
        } else {
            Log.i("ACE_BRAIN", "ACE_BRAIN: Model not found on app launch.")
        }
    }
}
