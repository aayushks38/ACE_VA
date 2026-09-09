package com.ace.app.accessibility

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.ace.app.agent.ActionResultStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class UniversalAppInteractionEngine(
    private val observationEngine: UiObservationEngine = UiObservationEngine(),
    private val elementFinder: UiElementFinder = UiElementFinder(),
    private val verificationEngine: UiVerificationEngine = UiVerificationEngine()
) {

    suspend fun executeInAppSearch(
        context: Context,
        targetApp: String,
        query: String
    ): VerificationResult = withContext(Dispatchers.Main) {
        val service = AceAccessibilityService.getInstance()
        if (service == null || !AceAccessibilityService.isServiceEnabled(context)) {
            Log.w("ACE_UI_ERROR", "ACE_UI_ERROR: Accessibility service is disabled.")
            return@withContext VerificationResult(
                isVerified = false,
                status = ActionResultStatus.NEEDS_USER_ACTION,
                summary = "ACE Accessibility Service is required to interact inside $targetApp. Please enable ACE in Accessibility Settings."
            )
        }

        // 1. Launch Target App
        Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=OPEN_APP target_app=$targetApp")
        val launched = service.launchApp(context, targetApp)
        if (!launched) {
            Log.e("ACE_UI_ERROR", "ACE_UI_ERROR: Failed to launch app package for '$targetApp'")
            return@withContext VerificationResult(
                isVerified = false,
                status = ActionResultStatus.FAILED,
                summary = "Could not launch application '$targetApp'.",
                evidenceText = "App launch failed"
            )
        }

        // Wait for target app window to render
        delay(1500)

        // Capture initial UI Snapshot
        var snapshot = observationEngine.captureSnapshot()
        Log.i("ACE_UI_OBSERVE", "ACE_UI_OBSERVE: package=${snapshot.packageName} nodes=${snapshot.nodes.size}")

        // Check for Interruptions / Dialogs
        val dialogResult = inspectDialogs(snapshot)
        if (dialogResult.type == InterruptionType.SENSITIVE_INTERRUPTION) {
            Log.w("ACE_UI_DIALOG", "ACE_UI_DIALOG: type=SENSITIVE_INTERRUPTION action=PAUSE_FOR_USER candidate=\"${dialogResult.actionButtonText}\"")
            return@withContext VerificationResult(
                isVerified = false,
                status = ActionResultStatus.NEEDS_USER_ACTION,
                summary = "Action paused: Sensitive UI dialog ('${dialogResult.actionButtonText}') requires user confirmation.",
                evidenceText = "Sensitive dialog detected"
            )
        } else if (dialogResult.type == InterruptionType.SAFE_INTERRUPTION && dialogResult.actionButtonNode != null) {
            Log.i("ACE_UI_DIALOG", "ACE_UI_DIALOG: type=SAFE_INTERRUPTION action=AUTO_DISMISS candidate=\"${dialogResult.actionButtonText}\"")
            service.clickText(dialogResult.actionButtonText)
            delay(800)
            snapshot = observationEngine.captureSnapshot()
            Log.i("ACE_UI_OBSERVE", "ACE_UI_OBSERVE: package=${snapshot.packageName} nodes=${snapshot.nodes.size}")
        }

        // 2. Adaptive Search Execution Loop (OBSERVE -> FIND -> ACT -> WAIT -> OBSERVE AGAIN -> VERIFY -> RECOVER)
        val maxActionAttempts = 3
        val maxScrollAttempts = 3
        var actionAttempt = 0
        var scrollAttempt = 0

        var searchMatch = elementFinder.findElement(snapshot, "SEARCH")

        // If search control not found in initial view, attempt controlled scrolling
        while (searchMatch == null && scrollAttempt < maxScrollAttempts && snapshot.nodes.any { it.isScrollable }) {
            scrollAttempt++
            Log.i("ACE_UI_SCROLL", "ACE_UI_SCROLL: attempt=$scrollAttempt max_scroll_attempts=$maxScrollAttempts direction=FORWARD")
            service.scroll(forward = true)
            delay(800)
            snapshot = observationEngine.captureSnapshot()
            Log.i("ACE_UI_OBSERVE", "ACE_UI_OBSERVE: package=${snapshot.packageName} nodes=${snapshot.nodes.size}")
            searchMatch = elementFinder.findElement(snapshot, "SEARCH")
        }

        if (searchMatch != null) {
            if (searchMatch.node.isEditable) {
                // Input box is already visible and editable
                Log.i("ACE_UI_FIND", "ACE_UI_FIND: target=EDITABLE_SEARCH candidate=\"${searchMatch.candidateText}\" strategy=\"${searchMatch.strategyUsed}\" confidence=${"%.2f".format(searchMatch.confidence)}")
                Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=TYPE_TEXT text=\"$query\" candidate=\"${searchMatch.candidateText}\"")
                val typed = service.typeText(query, searchMatch.candidateText)
                delay(600)

                Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=SUBMIT_SEARCH")
                service.clickText("Search")
                service.clickText("Go")
                delay(1200)
            } else {
                // Search button/icon needs to be clicked first
                Log.i("ACE_UI_FIND", "ACE_UI_FIND: target=SEARCH_CONTROL candidate=\"${searchMatch.candidateText}\" strategy=\"${searchMatch.strategyUsed}\" confidence=${"%.2f".format(searchMatch.confidence)}")
                Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=CLICK candidate=\"${searchMatch.candidateText}\"")
                val nodeRef = searchMatch.node.nodeRef
                if (nodeRef != null) {
                    nodeRef.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                } else {
                    service.clickText(searchMatch.candidateText)
                }

                // WAIT FOR UI UPDATE & OBSERVE AGAIN
                delay(1000)
                snapshot = observationEngine.captureSnapshot()
                Log.i("ACE_UI_OBSERVE", "ACE_UI_OBSERVE: package=${snapshot.packageName} nodes=${snapshot.nodes.size}")

                val editableMatch = elementFinder.findElement(snapshot, "SEARCH")
                if (editableMatch != null) {
                    Log.i("ACE_UI_FIND", "ACE_UI_FIND: target=EDITABLE_SEARCH candidate=\"${editableMatch.candidateText}\" strategy=\"${editableMatch.strategyUsed}\" confidence=${"%.2f".format(editableMatch.confidence)}")
                    Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=TYPE_TEXT text=\"$query\" candidate=\"${editableMatch.candidateText}\"")
                    service.typeText(query, editableMatch.candidateText)
                    delay(600)

                    Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=SUBMIT_SEARCH")
                    service.clickText("Search")
                    service.clickText("Go")
                    delay(1200)
                } else {
                    // Fallback recovery typing attempt
                    Log.w("ACE_FALLBACK", "ACE_FALLBACK: trigger=EDITABLE_FIELD_NOT_FOUND reason=\"Search input node hidden\" attempt=1 max_attempts=$maxActionAttempts")
                    Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=TYPE_TEXT text=\"$query\"")
                    service.typeText(query, "Search")
                    delay(1200)
                }
            }
        } else {
            // Direct type attempt if search control node score was low
            Log.w("ACE_FALLBACK", "ACE_FALLBACK: trigger=SEARCH_CONTROL_NOT_FOUND reason=\"No candidate score > 0.30\" attempt=1 max_attempts=$maxActionAttempts")
            Log.i("ACE_UI_ACTION", "ACE_UI_ACTION: action=TYPE_TEXT text=\"$query\"")
            service.typeText(query, "Search")
            delay(1200)
        }

        // Re-observe UI to verify actual query/results
        snapshot = observationEngine.captureSnapshot()
        Log.i("ACE_UI_OBSERVE", "ACE_UI_OBSERVE: package=${snapshot.packageName} nodes=${snapshot.nodes.size}")

        val verifyResult = verificationEngine.verifySearchGoal(targetApp, query, snapshot)
        Log.i("ACE_UI_VERIFY", "ACE_UI_VERIFY: package=${snapshot.packageName} target_app=$targetApp query_entered=\"$query\" result_verified=${verifyResult.isVerified}")
        return@withContext verifyResult
    }

    suspend fun executeFileSelection(context: Context, fileNameOrType: String): VerificationResult = withContext(Dispatchers.Main) {
        Log.i("ACE_UI_FILE", "ACE_UI_FILE: action=FILE_DISCOVERY target=\"$fileNameOrType\" status=STARTED")
        val service = AceAccessibilityService.getInstance()
        if (service == null || !AceAccessibilityService.isServiceEnabled(context)) {
            Log.w("ACE_UI_FILE", "ACE_UI_FILE: status=FAILED reason=\"Accessibility Service Disabled\"")
            return@withContext VerificationResult(false, ActionResultStatus.NEEDS_USER_ACTION, "Accessibility service disabled")
        }

        var snapshot = observationEngine.captureSnapshot()
        Log.i("ACE_UI_OBSERVE", "ACE_UI_OBSERVE: package=${snapshot.packageName} nodes=${snapshot.nodes.size}")

        val uploadButton = elementFinder.findElement(snapshot, "Upload")
            ?: elementFinder.findElement(snapshot, "Choose file")
            ?: elementFinder.findElement(snapshot, "Browse")

        if (uploadButton != null) {
            Log.i("ACE_UI_FILE", "ACE_UI_FILE: candidate=\"${uploadButton.candidateText}\" action=CLICK")
            service.clickText(uploadButton.candidateText)
            delay(1000)
            snapshot = observationEngine.captureSnapshot()
        }

        val fileMatch = elementFinder.findElement(snapshot, fileNameOrType)
        if (fileMatch != null) {
            Log.i("ACE_UI_FILE", "ACE_UI_FILE: candidate=\"${fileMatch.candidateText}\" status=SUCCESS")
            service.clickText(fileMatch.candidateText)
            return@withContext VerificationResult(true, ActionResultStatus.SUCCESS, "File '$fileNameOrType' selected successfully.")
        }

        Log.w("ACE_UI_FILE", "ACE_UI_FILE: target=\"$fileNameOrType\" status=PARTIAL message=\"File picker active, awaiting user selection\"")
        return@withContext VerificationResult(false, ActionResultStatus.PARTIAL, "File picker active for '$fileNameOrType'. Awaiting selection.")
    }

    private fun inspectDialogs(snapshot: UiSnapshot): DialogCheckResult {
        val safeKeywords = listOf("allow", "cancel", "not now", "got it", "continue", "accept", "close", "ok", "dismiss")
        val sensitiveKeywords = listOf("pay", "buy", "place order", "submit payment", "delete account", "send money", "confirm purchase")

        for (node in snapshot.nodes) {
            val text = node.text.lowercase().trim()
            val desc = node.contentDescription.lowercase().trim()
            val label = if (text.isNotBlank()) text else desc

            if (label.isNotBlank()) {
                if (sensitiveKeywords.any { label == it || label.contains(it) }) {
                    return DialogCheckResult(InterruptionType.SENSITIVE_INTERRUPTION, label, node)
                }
                if (safeKeywords.any { label == it || label.contains(it) } && node.isClickable) {
                    return DialogCheckResult(InterruptionType.SAFE_INTERRUPTION, label, node)
                }
            }
        }
        return DialogCheckResult(InterruptionType.NONE)
    }
}
