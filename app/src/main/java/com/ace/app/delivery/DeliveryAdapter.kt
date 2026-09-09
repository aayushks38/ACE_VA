package com.ace.app.delivery

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.*

interface DeliveryAdapter {
    val targetApp: TargetApp
    fun canHandle(request: DeliveryRequest): Boolean
    fun deliver(context: Context, request: DeliveryRequest, attachment: ResolvedAttachment?, recipient: ResolvedRecipient?): DeliveryResult
    fun supportedCapabilities(): DeliveryCapabilities
}

class WhatsAppDeliveryAdapter : DeliveryAdapter {

    private val TAG = "ACE_WHATSAPP"

    override val targetApp: TargetApp = TargetApp.WHATSAPP

    override fun canHandle(request: DeliveryRequest): Boolean {
        return request.targetApp == TargetApp.WHATSAPP
    }

    override fun supportedCapabilities(): DeliveryCapabilities {
        return DeliveryCapabilities(
            supportsRecipientTargeting = true,
            supportsAttachmentSharing = true,
            supportsCombinedRecipientAndAttachment = false, // Consumer WhatsApp ignores JID extra on ACTION_SEND
            supportsSendConfirmation = false
        )
    }

    override fun deliver(
        context: Context,
        request: DeliveryRequest,
        attachment: ResolvedAttachment?,
        recipient: ResolvedRecipient?
    ): DeliveryResult {
        Log.i(TAG, "ACE_WHATSAPP: deliver attachment=${attachment?.displayName} recipient=${recipient?.displayName} num=${recipient?.normalizedPhoneNumber}")

        // 1. Check if WhatsApp is installed
        val isInstalled = try {
            context.packageManager.getPackageInfo("com.whatsapp", 0)
            true
        } catch (e: Exception) {
            false
        }

        if (!isInstalled) {
            Log.w(TAG, "ACE_WHATSAPP: WhatsApp is not installed on device")
            return DeliveryResult(
                status = DeliveryStatus.TARGET_APP_NOT_INSTALLED,
                stage = DeliveryStage.FAILED,
                message = "WhatsApp is not installed on this device.",
                attachment = attachment,
                recipient = recipient,
                targetApp = TargetApp.WHATSAPP,
                verification = DeliveryVerification(
                    attachmentResolved = attachment != null,
                    recipientResolved = recipient != null,
                    targetAppAvailable = false,
                    targetIntentLaunched = false,
                    recipientConversationConfirmed = false,
                    attachmentConfirmedInComposer = false,
                    sendConfirmed = false
                )
            )
        }

        try {
            if (attachment != null && attachment.isValid) {
                // Media Sharing: Consumer WhatsApp opens Contact Chooser (Select contact screen)
                Log.i(TAG, "ACE_WHATSAPP: combined_recipient_media_supported=false")
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = attachment.mimeType
                    putExtra(Intent.EXTRA_STREAM, attachment.uri)
                    clipData = android.content.ClipData.newRawUri("ACE Attachment", attachment.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION

                    if (!request.messageText.isNullOrBlank()) {
                        putExtra(Intent.EXTRA_TEXT, request.messageText)
                    }
                }


                context.startActivity(intent)

                val accService = com.ace.app.accessibility.AceAccessibilityService.getInstance()
                val isAccEnabled = com.ace.app.accessibility.AceAccessibilityService.isServiceEnabled(context)

                if (accService != null && isAccEnabled && recipient != null && !recipient.displayName.isNullOrBlank()) {
                    Log.i(TAG, "ACE_WHATSAPP: Accessibility Service ENABLED -> Automating full contact selection and send for '${recipient.displayName}'")
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        try {
                            // Wait for WhatsApp contact picker to fully render
                            kotlinx.coroutines.delay(1800)

                            // Click the search icon in the contact picker
                            val searchOpened = accService.clickId("com.whatsapp:id/menuitem_search") ||
                                    accService.clickId("com.whatsapp:id/search_button") ||
                                    accService.clickText("Search")
                            Log.i(TAG, "ACE_WHATSAPP: Search opened: $searchOpened")

                            // Wait for search input field to appear
                            kotlinx.coroutines.delay(800)

                            // Type recipient name into search
                            accService.typeText(recipient.displayName)
                            Log.i(TAG, "ACE_WHATSAPP: Typed '${recipient.displayName}' into search")

                            // Wait for search results to populate
                            kotlinx.coroutines.delay(1200)

                            // Click the matching contact name
                            var contactClicked = accService.clickText(recipient.displayName)
                            if (!contactClicked) {
                                // Scroll down and retry once if not immediately visible
                                accService.scroll(true)
                                kotlinx.coroutines.delay(500)
                                contactClicked = accService.clickText(recipient.displayName)
                            }
                            Log.i(TAG, "ACE_WHATSAPP: Clicked contact '${recipient.displayName}': $contactClicked")

                            // Wait for WhatsApp to open the conversation with media attached
                            kotlinx.coroutines.delay(1800)

                            // Tap the Send button
                            val sendClicked = accService.clickId("com.whatsapp:id/send") ||
                                    accService.clickId("com.whatsapp:id/fab") ||
                                    accService.clickId("com.whatsapp:id/send_btn") ||
                                    accService.clickText("Send")
                            Log.i(TAG, "ACE_WHATSAPP: Tapped Send for '${recipient.displayName}': $sendClicked")
                        } catch (e: Exception) {
                            Log.w(TAG, "ACE_WHATSAPP: Accessibility automation note: ${e.message}")
                        }
                    }
                }

                val friendlyAtt = attachment.toFriendlyDescription()
                val msg = if (recipient != null) {
                    if (isAccEnabled) {
                        "I've opened WhatsApp and am selecting ${recipient.displayName} to send $friendlyAtt."
                    } else {
                        "I've opened WhatsApp with $friendlyAtt ready. Please select ${recipient.displayName} to send."
                    }
                } else {
                    "I've opened WhatsApp with $friendlyAtt ready."
                }

                val steps = listOf(
                    DeliveryExecutionStep("find_attachment", "Find $friendlyAtt", "Identified valid image/file from MediaStore", DeliveryStepStatus.COMPLETED),
                    DeliveryExecutionStep("resolve_recipient", "Resolve recipient ${recipient?.displayName ?: "contact"}", if (recipient != null) "Contact '${recipient.displayName}' resolved" else "No recipient specified", if (recipient != null) DeliveryStepStatus.COMPLETED else DeliveryStepStatus.SKIPPED),
                    DeliveryExecutionStep("launch_whatsapp", "Confirm WhatsApp", "WhatsApp share intent launched", DeliveryStepStatus.COMPLETED),
                    DeliveryExecutionStep("select_contact", "Select ${recipient?.displayName ?: "recipient"} in WhatsApp", "Consumer WhatsApp requires recipient selection", DeliveryStepStatus.PENDING_USER_ACTION),
                    DeliveryExecutionStep("send_action", "Tap Send", "Pending user send action in WhatsApp", DeliveryStepStatus.PENDING_USER_ACTION)
                )

                steps.forEach { step ->
                    Log.i("ACE_DELIVERY_STEP", "ACE_DELIVERY_STEP: id=${step.id} status=${step.status} desc=${step.description}")
                }
                Log.i("ACE_DELIVERY_VERIFY", "ACE_DELIVERY_VERIFY: attachment_resolved=true recipient_resolved=${recipient != null} target_app_opened=true recipient_targeted=false attachment_handed_off=true send_confirmed=false requires_user_action=true")

                return DeliveryResult(
                    status = DeliveryStatus.OPENED_TARGET_COMPOSER,
                    stage = DeliveryStage.GENERIC_SHARE_FLOW_OPENED,
                    message = msg,
                    attachment = attachment,
                    recipient = recipient,
                    targetApp = TargetApp.WHATSAPP,
                    verification = DeliveryVerification(
                        attachmentResolved = true,
                        recipientResolved = recipient != null,
                        targetAppAvailable = true,
                        targetIntentLaunched = true,
                        recipientConversationConfirmed = null,
                        attachmentConfirmedInComposer = true,
                        sendConfirmed = null
                    ),
                    executionSteps = steps
                )
            } else if (recipient != null && recipient.normalizedPhoneNumber.isNotBlank()) {
                val url = "https://api.whatsapp.com/send?phone=${recipient.normalizedPhoneNumber}" +
                        if (!request.messageText.isNullOrBlank()) "&text=${Uri.encode(request.messageText)}" else ""

                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }

                context.startActivity(intent)

                val steps = listOf(
                    DeliveryExecutionStep("resolve_recipient", "Resolve recipient ${recipient.displayName}", "Contact '${recipient.displayName}' resolved", DeliveryStepStatus.COMPLETED),
                    DeliveryExecutionStep("launch_chat", "Open ${recipient.displayName}'s conversation", "WhatsApp direct chat opened", DeliveryStepStatus.COMPLETED)
                )

                steps.forEach { step ->
                    Log.i("ACE_DELIVERY_STEP", "ACE_DELIVERY_STEP: id=${step.id} status=${step.status} desc=${step.description}")
                }
                Log.i("ACE_DELIVERY_VERIFY", "ACE_DELIVERY_VERIFY: attachment_resolved=false recipient_resolved=true target_app_opened=true recipient_targeted=true attachment_handed_off=false send_confirmed=null requires_user_action=false")

                return DeliveryResult(
                    status = DeliveryStatus.HANDOFF_COMPLETED,
                    stage = DeliveryStage.RECIPIENT_CONVERSATION_OPENED,
                    message = "I've opened ${recipient.displayName}'s WhatsApp conversation.",
                    recipient = recipient,
                    targetApp = TargetApp.WHATSAPP,
                    verification = DeliveryVerification(
                        attachmentResolved = false,
                        recipientResolved = true,
                        targetAppAvailable = true,
                        targetIntentLaunched = true,
                        recipientConversationConfirmed = true,
                        attachmentConfirmedInComposer = false,
                        sendConfirmed = null
                    ),
                    executionSteps = steps
                )
            } else {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    if (!request.messageText.isNullOrBlank()) {
                        putExtra(Intent.EXTRA_TEXT, request.messageText)
                    }
                    setPackage("com.whatsapp")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)

                val steps = listOf(
                    DeliveryExecutionStep("launch_whatsapp", "Open WhatsApp", "WhatsApp launched", DeliveryStepStatus.COMPLETED)
                )

                return DeliveryResult(
                    status = DeliveryStatus.HANDOFF_COMPLETED,
                    stage = DeliveryStage.GENERIC_SHARE_FLOW_OPENED,
                    message = "I've opened WhatsApp.",
                    targetApp = TargetApp.WHATSAPP,
                    verification = DeliveryVerification(
                        attachmentResolved = false,
                        recipientResolved = false,
                        targetAppAvailable = true,
                        targetIntentLaunched = true,
                        recipientConversationConfirmed = null,
                        attachmentConfirmedInComposer = null,
                        sendConfirmed = null
                    ),
                    executionSteps = steps
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ACE_WHATSAPP: delivery failed: ${e.message}")
            return DeliveryResult(
                status = DeliveryStatus.FAILED,
                stage = DeliveryStage.FAILED,
                message = "Could not open WhatsApp: ${e.message}",
                attachment = attachment,
                recipient = recipient,
                targetApp = TargetApp.WHATSAPP,
                verification = DeliveryVerification(
                    attachmentResolved = attachment != null,
                    recipientResolved = recipient != null,
                    targetAppAvailable = true,
                    targetIntentLaunched = false,
                    recipientConversationConfirmed = false,
                    attachmentConfirmedInComposer = false,
                    sendConfirmed = false
                )
            )
        }
    }
}

class SmsDeliveryAdapter : DeliveryAdapter {

    private val TAG = "ACE_SMS"

    override val targetApp: TargetApp = TargetApp.SMS

    override fun canHandle(request: DeliveryRequest): Boolean {
        return request.targetApp == TargetApp.SMS
    }

    override fun supportedCapabilities(): DeliveryCapabilities {
        return DeliveryCapabilities(
            supportsRecipientTargeting = true,
            supportsAttachmentSharing = true,
            supportsCombinedRecipientAndAttachment = true,
            supportsSendConfirmation = false
        )
    }

    override fun deliver(
        context: Context,
        request: DeliveryRequest,
        attachment: ResolvedAttachment?,
        recipient: ResolvedRecipient?
    ): DeliveryResult {
        Log.i(TAG, "ACE_SMS: deliver attachment=${attachment?.displayName} recipient=${recipient?.displayName}")

        try {
            if (attachment != null && attachment.isValid) {
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = attachment.mimeType
                    putExtra(Intent.EXTRA_STREAM, attachment.uri)
                    if (recipient != null) {
                        putExtra("address", recipient.phoneNumber)
                    }
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)

                val friendlyAtt = attachment.toFriendlyDescription()
                val msg = if (recipient != null) {
                    "I've attached $friendlyAtt and prepared SMS for ${recipient.displayName}."
                } else {
                    "I've attached $friendlyAtt and prepared SMS composer."
                }

                val steps = listOf(
                    DeliveryExecutionStep("find_attachment", "Find $friendlyAtt", "Identified file from MediaStore", DeliveryStepStatus.COMPLETED),
                    DeliveryExecutionStep("resolve_recipient", "Resolve recipient ${recipient?.displayName ?: "contact"}", if (recipient != null) "Contact '${recipient.displayName}' resolved" else "No recipient specified", if (recipient != null) DeliveryStepStatus.COMPLETED else DeliveryStepStatus.SKIPPED),
                    DeliveryExecutionStep("launch_sms", "Open SMS composer", "SMS MMS intent launched with recipient and stream", DeliveryStepStatus.COMPLETED)
                )

                return DeliveryResult(
                    status = DeliveryStatus.HANDOFF_COMPLETED,
                    stage = DeliveryStage.ATTACHMENT_HANDOFF_COMPLETED,
                    message = msg,
                    attachment = attachment,
                    recipient = recipient,
                    targetApp = TargetApp.SMS,
                    verification = DeliveryVerification(
                        attachmentResolved = true,
                        recipientResolved = recipient != null,
                        targetAppAvailable = true,
                        targetIntentLaunched = true,
                        recipientConversationConfirmed = recipient != null,
                        attachmentConfirmedInComposer = true,
                        sendConfirmed = null
                    ),
                    executionSteps = steps
                )
            } else {
                val uriStr = if (recipient != null) "smsto:${recipient.phoneNumber}" else "smsto:"
                val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(uriStr)).apply {
                    if (!request.messageText.isNullOrBlank()) {
                        putExtra("sms_body", request.messageText)
                    }
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)

                val msg = if (recipient != null) {
                    "I've opened SMS composer for ${recipient.displayName}."
                } else {
                    "I've opened SMS app."
                }

                val steps = listOf(
                    DeliveryExecutionStep("resolve_recipient", "Resolve recipient ${recipient?.displayName ?: "contact"}", if (recipient != null) "Contact '${recipient.displayName}' resolved" else "No recipient specified", if (recipient != null) DeliveryStepStatus.COMPLETED else DeliveryStepStatus.SKIPPED),
                    DeliveryExecutionStep("launch_sms", "Open SMS composer", "SMS intent launched", DeliveryStepStatus.COMPLETED)
                )

                return DeliveryResult(
                    status = DeliveryStatus.HANDOFF_COMPLETED,
                    stage = DeliveryStage.RECIPIENT_CONVERSATION_OPENED,
                    message = msg,
                    recipient = recipient,
                    targetApp = TargetApp.SMS,
                    verification = DeliveryVerification(
                        attachmentResolved = false,
                        recipientResolved = recipient != null,
                        targetAppAvailable = true,
                        targetIntentLaunched = true,
                        recipientConversationConfirmed = recipient != null,
                        attachmentConfirmedInComposer = false,
                        sendConfirmed = null
                    ),
                    executionSteps = steps
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "ACE_SMS: delivery failed: ${e.message}")
            return DeliveryResult(
                status = DeliveryStatus.FAILED,
                stage = DeliveryStage.FAILED,
                message = "Could not open SMS app: ${e.message}",
                attachment = attachment,
                recipient = recipient,
                targetApp = TargetApp.SMS,
                verification = DeliveryVerification(
                    attachmentResolved = attachment != null,
                    recipientResolved = recipient != null,
                    targetAppAvailable = true,
                    targetIntentLaunched = false,
                    recipientConversationConfirmed = false,
                    attachmentConfirmedInComposer = false,
                    sendConfirmed = false
                )
            )
        }
    }
}

class TelegramDeliveryAdapter : DeliveryAdapter {

    private val TAG = "ACE_TELEGRAM"

    override val targetApp: TargetApp = TargetApp.TELEGRAM

    override fun canHandle(request: DeliveryRequest): Boolean {
        return request.targetApp == TargetApp.TELEGRAM
    }

    override fun supportedCapabilities(): DeliveryCapabilities {
        return DeliveryCapabilities(
            supportsRecipientTargeting = false,
            supportsAttachmentSharing = true,
            supportsCombinedRecipientAndAttachment = false,
            supportsSendConfirmation = false
        )
    }

    override fun deliver(
        context: Context,
        request: DeliveryRequest,
        attachment: ResolvedAttachment?,
        recipient: ResolvedRecipient?
    ): DeliveryResult {
        Log.i(TAG, "ACE_TELEGRAM: deliver attachment=${attachment?.displayName} recipient=${recipient?.displayName}")

        val isInstalled = try {
            context.packageManager.getPackageInfo("org.telegram.messenger", 0)
            true
        } catch (e: Exception) {
            false
        }

        if (!isInstalled) {
            return DeliveryResult(
                status = DeliveryStatus.TARGET_APP_NOT_INSTALLED,
                stage = DeliveryStage.FAILED,
                message = "Telegram is not installed on this device.",
                attachment = attachment,
                recipient = recipient,
                targetApp = TargetApp.TELEGRAM,
                verification = DeliveryVerification(
                    attachmentResolved = attachment != null,
                    recipientResolved = recipient != null,
                    targetAppAvailable = false,
                    targetIntentLaunched = false,
                    recipientConversationConfirmed = false,
                    attachmentConfirmedInComposer = false,
                    sendConfirmed = false
                )
            )
        }

        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = attachment?.mimeType ?: "text/plain"
                if (attachment != null && attachment.isValid) {
                    putExtra(Intent.EXTRA_STREAM, attachment.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (!request.messageText.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_TEXT, request.messageText)
                }
                setPackage("org.telegram.messenger")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            context.startActivity(intent)

            val friendlyAtt = attachment?.toFriendlyDescription()
            val msg = if (recipient != null && friendlyAtt != null) {
                "I've opened Telegram with $friendlyAtt ready. Please select ${recipient.displayName}."
            } else if (friendlyAtt != null) {
                "I've opened Telegram with $friendlyAtt ready."
            } else {
                "I've opened Telegram."
            }

            val steps = listOf(
                DeliveryExecutionStep("find_attachment", "Find ${friendlyAtt ?: "attachment"}", "Identified file from MediaStore", if (attachment != null) DeliveryStepStatus.COMPLETED else DeliveryStepStatus.SKIPPED),
                DeliveryExecutionStep("resolve_recipient", "Resolve recipient ${recipient?.displayName ?: "contact"}", if (recipient != null) "Contact '${recipient.displayName}' resolved" else "No recipient specified", if (recipient != null) DeliveryStepStatus.COMPLETED else DeliveryStepStatus.SKIPPED),
                DeliveryExecutionStep("launch_telegram", "Open Telegram", "Telegram share intent launched", DeliveryStepStatus.COMPLETED)
            )

            return DeliveryResult(
                status = DeliveryStatus.HANDOFF_COMPLETED,
                stage = DeliveryStage.GENERIC_SHARE_FLOW_OPENED,
                message = msg,
                attachment = attachment,
                recipient = recipient,
                targetApp = TargetApp.TELEGRAM,
                verification = DeliveryVerification(
                    attachmentResolved = attachment != null,
                    recipientResolved = recipient != null,
                    targetAppAvailable = true,
                    targetIntentLaunched = true,
                    recipientConversationConfirmed = null,
                    attachmentConfirmedInComposer = attachment != null,
                    sendConfirmed = null
                ),
                executionSteps = steps
            )
        } catch (e: Exception) {
            Log.e(TAG, "ACE_TELEGRAM: delivery failed: ${e.message}")
            return DeliveryResult(
                status = DeliveryStatus.FAILED,
                stage = DeliveryStage.FAILED,
                message = "Could not open Telegram: ${e.message}",
                targetApp = TargetApp.TELEGRAM,
                verification = DeliveryVerification(
                    attachmentResolved = attachment != null,
                    recipientResolved = recipient != null,
                    targetAppAvailable = true,
                    targetIntentLaunched = false,
                    recipientConversationConfirmed = false,
                    attachmentConfirmedInComposer = false,
                    sendConfirmed = false
                )
            )
        }
    }
}

class GenericShareDeliveryAdapter : DeliveryAdapter {

    override val targetApp: TargetApp = TargetApp.GENERIC_SHARE

    override fun canHandle(request: DeliveryRequest): Boolean = true

    override fun supportedCapabilities(): DeliveryCapabilities {
        return DeliveryCapabilities(
            supportsRecipientTargeting = false,
            supportsAttachmentSharing = true,
            supportsCombinedRecipientAndAttachment = false,
            supportsSendConfirmation = false
        )
    }

    override fun deliver(
        context: Context,
        request: DeliveryRequest,
        attachment: ResolvedAttachment?,
        recipient: ResolvedRecipient?
    ): DeliveryResult {
        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = attachment?.mimeType ?: "text/plain"
                if (attachment != null && attachment.isValid) {
                    putExtra(Intent.EXTRA_STREAM, attachment.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                if (!request.messageText.isNullOrBlank()) {
                    putExtra(Intent.EXTRA_TEXT, request.messageText)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }

            val chooser = Intent.createChooser(sendIntent, "Share via ACE").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(chooser)

            val friendlyAtt = attachment?.toFriendlyDescription()
            val msg = if (friendlyAtt != null) {
                "I've prepared $friendlyAtt and opened the share sheet."
            } else {
                "I've opened the Android share sheet."
            }

            val steps = listOf(
                DeliveryExecutionStep("find_attachment", "Find ${friendlyAtt ?: "file"}", "Identified file from MediaStore", if (attachment != null) DeliveryStepStatus.COMPLETED else DeliveryStepStatus.SKIPPED),
                DeliveryExecutionStep("open_share", "Open Android Share Sheet", "Generic Android ACTION_SEND chooser launched", DeliveryStepStatus.COMPLETED)
            )

            return DeliveryResult(
                status = DeliveryStatus.HANDOFF_COMPLETED,
                stage = DeliveryStage.GENERIC_SHARE_FLOW_OPENED,
                message = msg,
                attachment = attachment,
                recipient = recipient,
                targetApp = TargetApp.GENERIC_SHARE,
                verification = DeliveryVerification(
                    attachmentResolved = attachment != null,
                    recipientResolved = recipient != null,
                    targetAppAvailable = true,
                    targetIntentLaunched = true,
                    recipientConversationConfirmed = null,
                    attachmentConfirmedInComposer = attachment != null,
                    sendConfirmed = null
                ),
                executionSteps = steps
            )
        } catch (e: Exception) {
            return DeliveryResult(
                status = DeliveryStatus.FAILED,
                stage = DeliveryStage.FAILED,
                message = "Failed to launch Android share sheet: ${e.message}",
                targetApp = TargetApp.GENERIC_SHARE,
                verification = DeliveryVerification(
                    attachmentResolved = attachment != null,
                    recipientResolved = recipient != null,
                    targetAppAvailable = true,
                    targetIntentLaunched = false,
                    recipientConversationConfirmed = false,
                    attachmentConfirmedInComposer = false,
                    sendConfirmed = false
                )
            )
        }
    }
}

