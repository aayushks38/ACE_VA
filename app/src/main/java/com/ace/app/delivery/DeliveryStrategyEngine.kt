package com.ace.app.delivery

enum class DeliveryCapability {
    DIRECT_RECIPIENT_NAVIGATION,
    ATTACHMENT_SHARING,
    COMBINED_RECIPIENT_ATTACHMENT
}

data class PlatformDeliveryCapabilities(
    val supportsDirectRecipientNavigation: Boolean,
    val supportsAttachmentSharing: Boolean,
    val supportsCombinedRecipientAttachment: Boolean
)

sealed class DeliveryStrategy {
    data object DirectRecipientNavigation : DeliveryStrategy()
    data object GenericAttachmentShare : DeliveryStrategy()
    data class CombinedDelivery(
        val recipient: ResolvedRecipient,
        val attachment: ResolvedAttachment
    ) : DeliveryStrategy()
    data class GuidedDelivery(
        val recipient: ResolvedRecipient,
        val attachment: ResolvedAttachment
    ) : DeliveryStrategy()
}

object DeliveryStrategyEngine {

    fun getCapabilities(targetApp: TargetApp?): PlatformDeliveryCapabilities {
        return when (targetApp) {
            TargetApp.WHATSAPP -> PlatformDeliveryCapabilities(
                supportsDirectRecipientNavigation = true,
                supportsAttachmentSharing = true,
                supportsCombinedRecipientAttachment = false
            )
            TargetApp.SMS -> PlatformDeliveryCapabilities(
                supportsDirectRecipientNavigation = true,
                supportsAttachmentSharing = true,
                supportsCombinedRecipientAttachment = true
            )
            TargetApp.TELEGRAM -> PlatformDeliveryCapabilities(
                supportsDirectRecipientNavigation = false,
                supportsAttachmentSharing = true,
                supportsCombinedRecipientAttachment = false
            )
            else -> PlatformDeliveryCapabilities(
                supportsDirectRecipientNavigation = false,
                supportsAttachmentSharing = true,
                supportsCombinedRecipientAttachment = false
            )
        }
    }

    fun selectStrategy(
        parsedIntent: ParsedDeliveryIntent,
        recipient: ResolvedRecipient?,
        attachment: ResolvedAttachment?
    ): DeliveryStrategy {
        val caps = getCapabilities(parsedIntent.targetApp)

        return when {
            attachment == null && recipient != null -> {
                DeliveryStrategy.DirectRecipientNavigation
            }
            attachment != null && recipient == null -> {
                DeliveryStrategy.GenericAttachmentShare
            }
            attachment != null && recipient != null -> {
                if (caps.supportsCombinedRecipientAttachment) {
                    DeliveryStrategy.CombinedDelivery(recipient, attachment)
                } else {
                    DeliveryStrategy.GuidedDelivery(recipient, attachment)
                }
            }
            else -> DeliveryStrategy.GenericAttachmentShare
        }
    }
}
