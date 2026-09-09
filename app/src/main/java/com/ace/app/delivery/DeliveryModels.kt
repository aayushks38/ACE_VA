package com.ace.app.delivery

import android.net.Uri

enum class DeliveryStage {
    REQUEST_RECEIVED,
    ATTACHMENT_RESOLVED,
    RECIPIENT_RESOLVED,
    TARGET_APP_RESOLVED,
    TARGET_INTENT_LAUNCHED,
    GENERIC_SHARE_FLOW_OPENED,
    RECIPIENT_CONVERSATION_OPENED,
    ATTACHMENT_HANDOFF_COMPLETED,
    AWAITING_USER_ACTION,
    SENT_CONFIRMED,
    FAILED
}

enum class DeliveryStatus {
    SUCCESSFULLY_SENT,
    HANDOFF_COMPLETED,
    OPENED_TARGET_COMPOSER,
    AWAITING_USER_ACTION,
    RECIPIENT_NOT_FOUND,
    RECIPIENT_AMBIGUOUS,
    ATTACHMENT_NOT_FOUND,
    TARGET_APP_NOT_INSTALLED,
    FAILED
}

enum class DeliveryStepStatus {
    COMPLETED,
    PENDING_USER_ACTION,
    NOT_SUPPORTED,
    FAILED,
    SKIPPED
}

data class DeliveryExecutionStep(
    val id: String,
    val title: String,
    val description: String,
    val status: DeliveryStepStatus,
    val details: String? = null
)

data class DeliveryCapabilities(
    val supportsRecipientTargeting: Boolean,
    val supportsAttachmentSharing: Boolean,
    val supportsCombinedRecipientAndAttachment: Boolean,
    val supportsSendConfirmation: Boolean
)

data class DeliveryVerification(
    val attachmentResolved: Boolean,
    val recipientResolved: Boolean,
    val targetAppAvailable: Boolean,
    val targetIntentLaunched: Boolean,
    val recipientConversationConfirmed: Boolean?,
    val attachmentConfirmedInComposer: Boolean?,
    val sendConfirmed: Boolean?
)

enum class AttachmentSource {
    GALLERY,
    SCREENSHOTS,
    VIDEOS,
    DOWNLOADS,
    DOCUMENTS,
    SCANNER,
    STORAGE,
    USER_SELECTED
}

data class ResolvedAttachment(
    val uri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
    val source: AttachmentSource,
    val isValid: Boolean
)

fun ResolvedAttachment.toFriendlyDescription(): String {
    val nameLower = displayName.lowercase()
    return when {
        mimeType.startsWith("image/") -> {
            when {
                nameLower.contains("screenshot") -> "your latest screenshot"
                else -> "your latest photo"
            }
        }
        mimeType.startsWith("video/") -> "your latest video"
        mimeType.contains("pdf") -> {
            when {
                nameLower.contains("scan") -> "your scanned document"
                else -> "the PDF document"
            }
        }
        else -> "the file"
    }
}

enum class RecipientSource {
    CONTACTS,
    DIRECT_PHONE_NUMBER,
    USER_CLARIFICATION
}

data class ResolvedRecipient(
    val displayName: String?,
    val phoneNumber: String,
    val normalizedPhoneNumber: String,
    val contactId: Long?,
    val source: RecipientSource
)

enum class TargetApp {
    WHATSAPP,
    SMS,
    TELEGRAM,
    GENERIC_SHARE
}

data class DeliveryRequest(
    val attachmentType: String?,
    val queryAttachmentName: String?,
    val recipientQuery: String?,
    val targetApp: TargetApp?,
    val messageText: String?,
    val originalGoal: String
)

enum class DeliveryPhase {
    ATTACHMENT_RESOLUTION,
    RECIPIENT_RESOLUTION,
    TARGET_APP_RESOLUTION,
    DELIVERY_HANDOFF,
    USER_ACTION_REQUIRED,
    COMPLETED,
    FAILED
}

data class DeliveryExecutionState(
    val attachmentResolved: Boolean = false,
    val recipientResolved: Boolean = false,
    val appResolved: Boolean = false,
    val recipientTargeted: Boolean = false,
    val attachmentAttached: Boolean = false,
    val handoffCompleted: Boolean = false,
    val userActionRequired: Boolean = false
)

data class DeliveryResult(
    val status: DeliveryStatus,
    val stage: DeliveryStage,
    val message: String,
    val attachment: ResolvedAttachment? = null,
    val recipient: ResolvedRecipient? = null,
    val targetApp: TargetApp? = null,
    val ambiguousContacts: List<String> = emptyList(),
    val verification: DeliveryVerification? = null,
    val executionSteps: List<DeliveryExecutionStep> = emptyList(),
    val phase: DeliveryPhase = DeliveryPhase.COMPLETED,
    val executionState: DeliveryExecutionState? = null
)


