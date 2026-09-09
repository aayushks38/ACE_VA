package com.ace.app.delivery

enum class DeliveryAction {
    SEND,
    SHARE,
    OPEN_CHAT
}

enum class AttachmentType {
    IMAGE,
    SCREENSHOT,
    PDF,
    VIDEO,
    SCAN,
    DOCUMENT,
    OTHER
}

data class ParsedDeliveryIntent(
    val action: DeliveryAction,
    val attachmentType: AttachmentType?,
    val attachmentQuery: String?,
    val recipientName: String?,
    val targetApp: TargetApp?
)

object DeliveryRequestParser {

    fun parse(goal: String): ParsedDeliveryIntent {
        val lower = goal.lowercase().trim()

        val isExplicitChatOnly = (lower.startsWith("open ") || lower.contains("chat with")) &&
                !lower.contains("photo") && !lower.contains("image") && !lower.contains("pdf") &&
                !lower.contains("video") && !lower.contains("document") && !lower.contains("file") && !lower.contains("scan")

        val action = when {
            isExplicitChatOnly -> DeliveryAction.OPEN_CHAT
            lower.contains("share") -> DeliveryAction.SHARE
            else -> DeliveryAction.SEND
        }

        val hasMediaKeyword = lower.contains("photo") || lower.contains("picture") || lower.contains("gallery") ||
                lower.contains("image") || lower.contains("screenshot") || lower.contains("pdf") ||
                lower.contains("video") || lower.contains("scan") || lower.contains("doc") ||
                lower.contains("file") || lower.contains("presentation")

        val attachmentType = when {
            lower.contains("screenshot") -> AttachmentType.SCREENSHOT
            lower.contains("photo") || lower.contains("picture") || lower.contains("gallery") || lower.contains("image") -> AttachmentType.IMAGE
            lower.contains("pdf") -> AttachmentType.PDF
            lower.contains("video") -> AttachmentType.VIDEO
            lower.contains("scan") -> AttachmentType.SCAN
            lower.contains("doc") || lower.contains("file") || lower.contains("presentation") -> AttachmentType.DOCUMENT
            hasMediaKeyword -> AttachmentType.OTHER
            else -> null
        }

        val recipientName = extractRecipientName(goal)

        val targetApp = when {
            lower.contains("whatsapp") -> TargetApp.WHATSAPP
            lower.contains("telegram") -> TargetApp.TELEGRAM
            lower.contains("sms") || lower.contains("text message") -> TargetApp.SMS
            else -> null
        }

        val attachmentQuery = extractAttachmentQuery(goal)

        return ParsedDeliveryIntent(
            action = action,
            attachmentType = attachmentType,
            attachmentQuery = attachmentQuery,
            recipientName = recipientName,
            targetApp = targetApp
        )
    }

    private fun extractRecipientName(goal: String): String? {
        val lower = goal.lowercase()
        if (lower.contains(" chat with ")) {
            val raw = lower.substringAfter(" chat with ").substringBefore(" on ").substringBefore(" via ").trim()
            return cleanName(raw)
        }
        if (lower.contains(" to ")) {
            val raw = lower.substringAfter(" to ").substringBefore(" on ").substringBefore(" via ").substringBefore(" using ").trim()
            return cleanName(raw)
        }
        if (lower.contains(" with ")) {
            val raw = lower.substringAfter(" with ").substringBefore(" on ").substringBefore(" via ").trim()
            return cleanName(raw)
        }
        return null
    }

    private fun cleanName(raw: String): String? {
        val cleaned = raw.replace(Regex("""[.,!?]"""), "").trim()
        if (cleaned.isBlank() || cleaned == "whatsapp" || cleaned == "telegram" || cleaned == "sms" || cleaned == "gallery" || cleaned == "the") return null
        return cleaned.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun extractAttachmentQuery(goal: String): String? {
        val lower = goal.lowercase()
        if (lower.contains("presentation")) return "presentation"
        return null
    }
}
