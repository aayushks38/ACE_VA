package com.ace.app.delivery

import android.content.Context
import android.util.Log

object SmartDeliveryEngine {

    private const val TAG = "ACE_DELIVERY"

    private val adapters: List<DeliveryAdapter> = listOf(
        WhatsAppDeliveryAdapter(),
        SmsDeliveryAdapter(),
        TelegramDeliveryAdapter(),
        GenericShareDeliveryAdapter()
    )

    fun executeDelivery(context: Context, params: Map<String, String>, goal: String): DeliveryResult {
        // 1. Central Intent Parser
        val parsed = DeliveryRequestParser.parse(goal)
        val targetApp = parsed.targetApp ?: when {
            (params["targetApp"] ?: params["app"] ?: "").lowercase().contains("whatsapp") -> TargetApp.WHATSAPP
            (params["targetApp"] ?: params["app"] ?: "").lowercase().contains("sms") -> TargetApp.SMS
            (params["targetApp"] ?: params["app"] ?: "").lowercase().contains("telegram") -> TargetApp.TELEGRAM
            else -> TargetApp.GENERIC_SHARE
        }

        val recipientQuery = parsed.recipientName ?: params["contact"] ?: params["recipient"] ?: params["contactName"]
        val attachmentTypeStr = parsed.attachmentType?.name ?: params["fileType"] ?: params["attachmentType"]
        val attachmentQuery = parsed.attachmentQuery ?: params["fileName"] ?: params["query"]

        Log.i(TAG, "ACE_DELIVERY request_id=req_${System.currentTimeMillis()} action=${parsed.action} app=$targetApp recipient=${recipientQuery ?: "none"} attachment=${attachmentTypeStr ?: "none"}")

        val request = DeliveryRequest(
            attachmentType = attachmentTypeStr,
            queryAttachmentName = attachmentQuery,
            recipientQuery = recipientQuery,
            targetApp = targetApp,
            messageText = params["message"] ?: params["text"] ?: params["summary"],
            originalGoal = goal
        )

        // 2. Resolve Attachment if requested in parsed intent
        val attachment: ResolvedAttachment? = if (parsed.attachmentType != null) {
            val resolved = AttachmentResolver.resolveAttachment(context, attachmentTypeStr, attachmentQuery)
            if (resolved == null) {
                Log.w("ACE_ATTACHMENT", "ACE_ATTACHMENT resolved=false status=ATTACHMENT_NOT_FOUND query='$attachmentQuery'")
                return DeliveryResult(
                    status = DeliveryStatus.ATTACHMENT_NOT_FOUND,
                    stage = DeliveryStage.FAILED,
                    message = "I couldn't find the requested photo or file on your device.",
                    targetApp = targetApp,
                    verification = DeliveryVerification(
                        attachmentResolved = false,
                        recipientResolved = !recipientQuery.isNullOrBlank(),
                        targetAppAvailable = true,
                        targetIntentLaunched = false,
                        recipientConversationConfirmed = false,
                        attachmentConfirmedInComposer = false,
                        sendConfirmed = false
                    ),
                    phase = DeliveryPhase.FAILED,
                    executionState = DeliveryExecutionState(attachmentResolved = false, userActionRequired = false)
                )
            }
            Log.i("ACE_ATTACHMENT", "ACE_ATTACHMENT resolved=true mime=${resolved.mimeType} valid=${resolved.isValid}")
            resolved
        } else {
            Log.i("ACE_ATTACHMENT", "ACE_ATTACHMENT resolved=false reason=no_attachment_requested")
            null
        }

        // 3. Resolve Recipient if specified
        var recipient: ResolvedRecipient? = null
        if (!recipientQuery.isNullOrBlank()) {
            when (val recResult = RecipientResolver.resolveRecipient(context, recipientQuery)) {
                is RecipientResolutionResult.Single -> {
                    recipient = recResult.recipient
                    Log.i("ACE_RECIPIENT", "ACE_RECIPIENT resolved=true name='${recipient.displayName}' num='${recipient.normalizedPhoneNumber}'")
                }
                is RecipientResolutionResult.Ambiguous -> {
                    val namesList = recResult.matches.joinToString(", ") { it.first }
                    Log.w("ACE_RECIPIENT", "ACE_RECIPIENT resolved=false status=RECIPIENT_AMBIGUOUS count=${recResult.matches.size}")
                    return DeliveryResult(
                        status = DeliveryStatus.RECIPIENT_AMBIGUOUS,
                        stage = DeliveryStage.FAILED,
                        message = "I found multiple contacts matching '$recipientQuery': [$namesList]. Which one do you mean?",
                        ambiguousContacts = recResult.matches.map { it.first },
                        attachment = attachment,
                        targetApp = targetApp,
                        verification = DeliveryVerification(
                            attachmentResolved = attachment != null,
                            recipientResolved = false,
                            targetAppAvailable = true,
                            targetIntentLaunched = false,
                            recipientConversationConfirmed = false,
                            attachmentConfirmedInComposer = false,
                            sendConfirmed = false
                        ),
                        phase = DeliveryPhase.FAILED,
                        executionState = DeliveryExecutionState(recipientResolved = false, userActionRequired = true)
                    )
                }
                is RecipientResolutionResult.NotFound -> {
                    Log.w("ACE_RECIPIENT", "ACE_RECIPIENT resolved=false status=RECIPIENT_NOT_FOUND query='$recipientQuery'")
                    return DeliveryResult(
                        status = DeliveryStatus.RECIPIENT_NOT_FOUND,
                        stage = DeliveryStage.FAILED,
                        message = "I couldn't find a contact named '$recipientQuery'.",
                        attachment = attachment,
                        targetApp = targetApp,
                        verification = DeliveryVerification(
                            attachmentResolved = attachment != null,
                            recipientResolved = false,
                            targetAppAvailable = true,
                            targetIntentLaunched = false,
                            recipientConversationConfirmed = false,
                            attachmentConfirmedInComposer = false,
                            sendConfirmed = false
                        ),
                        phase = DeliveryPhase.FAILED,
                        executionState = DeliveryExecutionState(recipientResolved = false, userActionRequired = false)
                    )
                }
            }
        }

        // 4. Select Delivery Strategy based on Platform Capabilities
        val strategy = DeliveryStrategyEngine.selectStrategy(parsed, recipient, attachment)
        val caps = DeliveryStrategyEngine.getCapabilities(targetApp)

        val strategyName = when (strategy) {
            is DeliveryStrategy.DirectRecipientNavigation -> "DIRECT_RECIPIENT_NAVIGATION"
            is DeliveryStrategy.GenericAttachmentShare -> "GENERIC_ATTACHMENT_SHARE"
            is DeliveryStrategy.CombinedDelivery -> "COMBINED_DELIVERY"
            is DeliveryStrategy.GuidedDelivery -> "GUIDED_DELIVERY"
        }

        Log.i("ACE_STRATEGY", "ACE_STRATEGY strategy=$strategyName combined_supported=${caps.supportsCombinedRecipientAttachment}")

        // 5. Dispatch to Platform Delivery Adapter
        val adapter = adapters.firstOrNull { it.canHandle(request) } ?: GenericShareDeliveryAdapter()
        val result = adapter.deliver(context, request, attachment, recipient)

        val recipientTargeted = (strategy is DeliveryStrategy.DirectRecipientNavigation || strategy is DeliveryStrategy.CombinedDelivery)
        val userActionReq = (strategy is DeliveryStrategy.GuidedDelivery)

        Log.i("ACE_DELIVERY_VERIFY", "ACE_DELIVERY_VERIFY attachment_resolved=${attachment != null} recipient_resolved=${recipient != null} handoff_completed=true recipient_targeted=$recipientTargeted user_action_required=$userActionReq")

        return result.copy(
            phase = if (userActionReq) DeliveryPhase.USER_ACTION_REQUIRED else DeliveryPhase.COMPLETED,
            executionState = DeliveryExecutionState(
                attachmentResolved = attachment != null,
                recipientResolved = recipient != null,
                appResolved = true,
                recipientTargeted = recipientTargeted,
                attachmentAttached = attachment != null,
                handoffCompleted = true,
                userActionRequired = userActionReq
            )
        )
    }
}

