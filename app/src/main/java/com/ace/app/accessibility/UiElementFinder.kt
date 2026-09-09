package com.ace.app.accessibility

import android.util.Log

class UiElementFinder {

    fun findElement(snapshot: UiSnapshot, targetRoleOrText: String): CandidateMatch? {
        val lowerTarget = targetRoleOrText.lowercase().trim()
        if (snapshot.nodes.isEmpty()) return null

        var bestMatch: CandidateMatch? = null
        var maxScore = 0.0f
        val isSearchTarget = lowerTarget.contains("search") || lowerTarget == "search_field" || lowerTarget == "search_button"

        val semanticVariations = listOf(
            "search", "find", "explore", "search songs", "search music", "search videos",
            "search products", "search restaurants", "search places", "search location",
            "search dishes", "search items", "search here", "type to search"
        )

        for (uiNode in snapshot.nodes) {
            val text = uiNode.text.lowercase().trim()
            val desc = uiNode.contentDescription.lowercase().trim()
            val hint = uiNode.hintText.lowercase().trim()
            val resId = uiNode.resourceId.lowercase().trim()

            var score = 0.0f
            var strategy = "None"
            val matchedLabel = uiNode.text.ifBlank { uiNode.contentDescription }
                .ifBlank { uiNode.hintText }
                .ifBlank { uiNode.resourceId }
                .ifBlank { "UI Control" }

            if (isSearchTarget) {
                when {
                    // Strategy 1: Exact visible text match
                    text == "search" -> {
                        score = 1.00f
                        strategy = "Strategy 1: Exact Text Match"
                    }
                    // Strategy 2: Content description match
                    desc == "search" || desc.contains("search button") || desc.contains("search youtube") -> {
                        score = 0.95f
                        strategy = "Strategy 2: Content Description Match"
                    }
                    // Strategy 3: Hint text match
                    hint.contains("search") -> {
                        score = 0.90f
                        strategy = "Strategy 3: Hint Text Match"
                    }
                    // Strategy 4: Semantic variations
                    semanticVariations.any { text.contains(it) || desc.contains(it) || resId.contains(it) } -> {
                        score = 0.80f
                        if (uiNode.isClickable || uiNode.isEditable) score += 0.05f
                        strategy = "Strategy 4: Semantic Variations Match"
                    }
                    // Strategy 5: Editable field
                    uiNode.isEditable -> {
                        score = 0.70f
                        strategy = "Strategy 5: Visible Editable Field"
                    }
                }
            } else {
                when {
                    text == lowerTarget -> {
                        score = 1.00f
                        strategy = "Exact Text Match"
                    }
                    desc == lowerTarget -> {
                        score = 0.95f
                        strategy = "Content Description Match"
                    }
                    hint == lowerTarget -> {
                        score = 0.90f
                        strategy = "Hint Text Match"
                    }
                    text.contains(lowerTarget) || desc.contains(lowerTarget) || hint.contains(lowerTarget) -> {
                        score = 0.75f
                        strategy = "Partial Text Match"
                    }
                    resId.contains(lowerTarget) -> {
                        score = 0.50f
                        strategy = "Resource ID Match"
                    }
                }
            }

            if (score > 0.0f) {
                Log.i("ACE_UI_SCORE", "ACE_UI_SCORE: candidate=\"$matchedLabel\" strategy=\"$strategy\" score=${"%.2f".format(score)}")
            }

            if (score > maxScore) {
                maxScore = score
                bestMatch = CandidateMatch(uiNode, matchedLabel, score, strategy)
            }
        }

        if (bestMatch != null && maxScore >= 0.30f) {
            val formattedConfidence = "%.2f".format(maxScore)
            Log.i("ACE_UI_FIND", "ACE_UI_FIND: target=$targetRoleOrText candidate=\"${bestMatch.candidateText}\" strategy=\"${bestMatch.strategyUsed}\" confidence=$formattedConfidence")
            return bestMatch.copy(confidence = maxScore)
        } else {
            Log.w("ACE_UI_FIND", "ACE_UI_FIND: target=$targetRoleOrText candidate_found=false candidates=${snapshot.nodes.size}")
            return null
        }
    }
}
