package com.ace.app.agent

import android.util.Log

object AceConversationContext {
    private var lastGoal: String? = null
    private var lastResponse: String? = null
    private var activeApp: String? = null
    private var activeTask: AgentTask? = null

    fun update(goal: String, response: String? = null, app: String? = null, task: AgentTask? = null) {
        lastGoal = goal
        if (!response.isNullOrBlank()) lastResponse = response
        if (!app.isNullOrBlank()) activeApp = app
        if (task != null) activeTask = task
        Log.i("ACE_CONTEXT", "ACE_CONTEXT: context updated goal=\"$goal\" app=\"${activeApp ?: "none"}\"")
    }

    fun clearCancelledContext() {
        activeTask = null
        Log.i("ACE_CONTEXT", "ACE_CONTEXT: cancelled task context cleared")
    }

    fun getActiveApp(): String? = activeApp
    fun getLastGoal(): String? = lastGoal
    fun getLastResponse(): String? = lastResponse
    fun getActiveTask(): AgentTask? = activeTask
}
