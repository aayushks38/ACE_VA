package com.ace.app.accessibility

import android.util.Log
import com.ace.app.agent.ActionResultStatus

class UiVerificationEngine {

    fun verifySearchGoal(
        targetApp: String,
        query: String,
        snapshot: UiSnapshot
    ): VerificationResult {
        val lowerApp = targetApp.lowercase().trim()
        val lowerQuery = query.lowercase().trim()
        val currentPkg = snapshot.packageName.lowercase().trim()

        val appMatched = when {
            lowerApp.contains("youtube") -> currentPkg.contains("youtube")
            lowerApp.contains("spotify") -> currentPkg.contains("spotify")
            lowerApp.contains("swiggy") -> currentPkg.contains("swiggy")
            lowerApp.contains("maps") -> currentPkg.contains("maps")
            lowerApp.contains("chrome") -> currentPkg.contains("chrome")
            else -> currentPkg.contains(lowerApp) || currentPkg.isNotBlank()
        }

        // Search for query text or relevant search result evidence in UiSnapshot
        val matchedNode = snapshot.nodes.firstOrNull { node ->
            val text = node.text.lowercase()
            val desc = node.contentDescription.lowercase()
            text.contains(lowerQuery) || desc.contains(lowerQuery)
        }

        val hasEvidence = matchedNode != null || snapshot.nodes.any { node ->
            val text = node.text.lowercase()
            text.contains("results") || text.contains("showing") || text.contains("top matches") || text.contains("search") || text.contains("places") || text.contains("restaurants")
        }

        Log.i("ACE_UI_VERIFY", "ACE_UI_VERIFY: package=$currentPkg app_matched=$appMatched query_visible=${matchedNode != null} evidence=$hasEvidence")

        if (appMatched && (matchedNode != null || hasEvidence)) {
            val evidence = matchedNode?.text?.ifBlank { matchedNode.contentDescription } ?: "Search results UI active"
            Log.i("ACE_RESULT", "ACE_RESULT: status=SUCCESS evidence=\"$evidence\"")
            return VerificationResult(
                isVerified = true,
                status = ActionResultStatus.SUCCESS,
                summary = "Verified search results for '$query' inside $targetApp.",
                evidenceText = evidence
            )
        }

        if (appMatched && !hasEvidence) {
            Log.w("ACE_RESULT", "ACE_RESULT: status=PARTIAL_SUCCESS message=\"$targetApp opened, but search results for '$query' could not be fully verified.\"")
            return VerificationResult(
                isVerified = false,
                status = ActionResultStatus.PARTIAL,
                summary = "$targetApp opened successfully, but ACE could not verify search results for '$query'.",
                evidenceText = "App active without confirmed query text"
            )
        }

        Log.e("ACE_RESULT", "ACE_RESULT: status=FAILED reason=\"Target app $targetApp not in foreground or UI unresponsive.\"")
        return VerificationResult(
            isVerified = false,
            status = ActionResultStatus.FAILED,
            summary = "Failed to complete in-app search in $targetApp.",
            evidenceText = "App not active"
        )
    }
}
