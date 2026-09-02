package com.ace.app.ui.home

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.widget.Toast

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.imePadding

import androidx.core.content.ContextCompat

import com.ace.app.R
import com.ace.app.ui.components.AceBackground


// ============================================================
// COLORS
// ============================================================

private val AceInputBackground = Color(0xFF171022)
private val AceInputInner = Color(0xFF211735)
private val AceInputGlow = Color(0xFF9D6BFF)
private val AcePurple = Color(0xFF8C52FF)
private val AceLightPurple = Color(0xFFB99AFF)
private val AceWhite = Color(0xFFF8F7FF)
private val AceGray = Color(0xFF9C98B0)


// ============================================================
// CHAT MESSAGE
// ============================================================

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)


// ============================================================
// HOME SCREEN
// ============================================================

@Composable
fun HomeScreen(
    onProfileClick: () -> Unit
) {

    val context = LocalContext.current


    // ========================================================
    // STATES
    // ========================================================

    var inputText by remember {
        mutableStateOf("")
    }

    var messages by remember {
        mutableStateOf(emptyList<ChatMessage>())
    }

    var workModeEnabled by remember {
        mutableStateOf(false)
    }

    var showAttachmentMenu by remember {
        mutableStateOf(false)
    }

    var isListening by remember {
        mutableStateOf(false)
    }


    // ========================================================
    // FILE PICKER
    // ========================================================

    val filePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {

                Toast.makeText(
                    context,
                    "File selected",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }


    // ========================================================
    // IMAGE PICKER
    // ========================================================

    val imagePickerLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri ->

            if (uri != null) {

                Toast.makeText(
                    context,
                    "Image selected",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }


    // ========================================================
    // CAMERA
    // ========================================================

    val cameraLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.TakePicturePreview()
        ) { bitmap ->

            if (bitmap != null) {

                Toast.makeText(
                    context,
                    "Photo captured",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }


    // ========================================================
    // MICROPHONE PERMISSION
    // ========================================================

    val microphonePermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { granted ->

            if (granted) {

                startAceSpeechRecognition(
                    context = context,

                    onResult = { result ->
                        inputText = result
                    },

                    onListeningChanged = { listening ->
                        isListening = listening
                    }
                )

            } else {

                Toast.makeText(
                    context,
                    "Microphone permission denied",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }


    // ========================================================
    // START MICROPHONE
    // ========================================================

    fun startMicrophone() {

        val permission =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            )

        if (
            permission ==
            PackageManager.PERMISSION_GRANTED
        ) {

            startAceSpeechRecognition(
                context = context,

                onResult = { result ->
                    inputText = result
                },

                onListeningChanged = { listening ->
                    isListening = listening
                }
            )

        } else {

            microphonePermissionLauncher.launch(
                Manifest.permission.RECORD_AUDIO
            )
        }
    }


    // ========================================================
    // SEND MESSAGE
    // ========================================================

    fun sendMessage() {

        val text = inputText.trim()

        if (text.isEmpty()) {
            return
        }

        messages =
            messages + ChatMessage(
                text = text,
                isUser = true
            )

        messages =
            messages + ChatMessage(
                text = "ACE received your message.",
                isUser = false
            )

        inputText = ""

        showAttachmentMenu = false
    }


    // ========================================================
    // MAIN SCREEN
    // ========================================================

    AceBackground {

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
        ) {


            // =================================================
            // TOP BAR
            // =================================================

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp)
                    .padding(
                        horizontal = 20.dp
                    ),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {


                // =================================================
                // ACE TITLE
                // =================================================

                Text(
                    text = "ACE",

                    color = AceWhite,

                    fontSize = 23.sp,

                    fontWeight = FontWeight.Bold,

                    modifier =
                        Modifier.weight(1f)
                )


                // =================================================
                // WORK MODE
                // =================================================

                Row(
                    modifier = Modifier
                        .clip(
                            RoundedCornerShape(30.dp)
                        )
                        .background(
                            brush = Brush.horizontalGradient(
                                colors =
                                    if (workModeEnabled)
                                        listOf(
                                            Color(0xFF9D6BFF),
                                            Color(0xFF6C3EFF)
                                        )
                                    else
                                        listOf(
                                            Color(0xFF3D246B),
                                            Color(0xFF25163F)
                                        )
                            )
                        )
                        .border(
                            width = 1.dp,
                            color =
                                if (workModeEnabled)
                                    Color(0xFFC4A5FF)
                                else
                                    AcePurple.copy(alpha = 0.8f),
                            shape = RoundedCornerShape(30.dp)
                        )
                        .clickable {
                            workModeEnabled = !workModeEnabled
                        }
                        .padding(
                            horizontal = 16.dp,
                            vertical = 9.dp
                        ),

                    verticalAlignment = Alignment.CenterVertically,

                    horizontalArrangement = Arrangement.Center
                ) {

                    Text(
                        text = "Work Mode",
                        color = AceWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium
                    )

                    if (workModeEnabled) {

                        Spacer(
                            modifier = Modifier.width(7.dp)
                        )

                        Text(
                            text = "✓",

                            color = AceWhite,

                            fontSize = 15.sp,

                            fontWeight = FontWeight.Bold
                        )
                    }
                }


                Spacer(
                    modifier =
                        Modifier.width(12.dp)
                )


                // =================================================
                // SETTINGS BUTTON
                // =================================================

                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                colors = listOf(
                                    Color(0xFF4B2A7A),
                                    Color(0xFF26143F),
                                    Color(0xFF120A20)
                                )
                            )
                        )
                        .border(
                            width = 1.dp,
                            brush = Brush.linearGradient(
                                colors = listOf(
                                    AceLightPurple.copy(alpha = 0.9f),
                                    AcePurple.copy(alpha = 0.6f),
                                    AceLightPurple.copy(alpha = 0.35f)
                                )
                            ),
                            shape = CircleShape
                        )
                        .clickable {
                            onProfileClick()
                        },

                    contentAlignment = Alignment.Center
                ) {

                    Icon(
                        painter = painterResource(
                            id = R.drawable.ic_settings
                        ),

                        contentDescription = "Settings",

                        tint = AceLightPurple,

                        modifier = Modifier.size(21.dp)
                    )
                }
            }


            // =================================================
            // HEADER DIVIDER
            // =================================================

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(
                        AcePurple.copy(
                            alpha = 0.25f
                        )
                    )
            )


            // =================================================
            // CENTER CONTENT
            // =================================================

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {

                if (messages.isEmpty()) {


                    // =============================================
                    // EMPTY HOME SCREEN
                    // =============================================

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(
                                horizontal = 24.dp
                            ),

                        horizontalAlignment =
                            Alignment.CenterHorizontally,

                        verticalArrangement =
                            Arrangement.Center
                    ) {


                        // =========================================
                        // ACE LOGO
                        // =========================================

                        Image(
                            painter =
                                painterResource(
                                    id = R.drawable.ace_logo
                                ),

                            contentDescription =
                                "ACE Logo",

                            contentScale =
                                ContentScale.Fit,

                            modifier =
                                Modifier.size(500.dp)
                        )


                        // =========================================
                        // MAIN TEXT
                        // =========================================

                        Column(
                            modifier = Modifier
                                .offset(y = (-150).dp),

                            horizontalAlignment =
                                Alignment.CenterHorizontally
                        ) {

                            Text(
                                text =
                                    "How can I help you today?",

                                color = AceWhite,

                                fontSize = 23.sp,

                                fontWeight =
                                    FontWeight.Medium
                            )


                            Spacer(
                                modifier =
                                    Modifier.height(10.dp)
                            )


                            Text(
                                text =
                                    "Ask ACE to explain, create, analyze or help you work.",

                                color = AceGray,

                                fontSize = 13.sp
                            )
                        }
                    }
                } else {


                    // =============================================
                    // CHAT SCREEN
                    // =============================================

                    LazyColumn(
                        modifier =
                            Modifier.fillMaxSize(),

                        contentPadding =
                            PaddingValues(18.dp),

                        verticalArrangement =
                            Arrangement.spacedBy(12.dp)
                    ) {

                        items(messages) { message ->

                            ChatBubble(
                                message = message
                            )
                        }
                    }
                }
            }


            // =================================================
            // ATTACHMENT MENU
            // =================================================

            if (showAttachmentMenu) {

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 24.dp,
                            vertical = 8.dp
                        ),

                    horizontalArrangement =
                        Arrangement.spacedBy(8.dp)
                ) {

                    SmallActionButton(
                        text = "Camera"
                    ) {

                        cameraLauncher.launch(null)
                    }


                    SmallActionButton(
                        text = "File"
                    ) {

                        filePickerLauncher.launch(
                            arrayOf("*/*")
                        )
                    }


                    SmallActionButton(
                        text = "Image"
                    ) {

                        imagePickerLauncher.launch(
                            "image/*"
                        )
                    }
                }
            }


            // =================================================
            // PREMIUM INPUT BAR
            // =================================================

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = 4.dp,
                        top = 8.dp,
                        end = 4.dp,
                        bottom = 14.dp
                    )
                    .height(64.dp)
                    .clip(
                        RoundedCornerShape(32.dp)
                    )
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF12101C),
                                Color(0xFF211735),
                                Color(0xFF171020)
                            )
                        )
                    )
                    .border(
                        width = 1.dp,

                        color =
                            AcePurple.copy(alpha = 0.75f),

                        shape =
                            RoundedCornerShape(32.dp)
                    )
                    .padding(
                        horizontal = 6.dp
                    ),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {


                // =============================================
                // PLUS BUTTON
                // =============================================

                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(
                            Color(0xFF211735)
                        )
                        .border(
                            width = 1.dp,

                            color =
                                AcePurple.copy(
                                    alpha = 0.25f
                                ),

                            shape = CircleShape
                        )
                        .clickable {

                            showAttachmentMenu =
                                !showAttachmentMenu
                        },

                    contentAlignment =
                        Alignment.Center
                ) {

                    Text(
                        text = "+",

                        color = AceWhite,

                        fontSize = 28.sp,

                        fontWeight =
                            FontWeight.Light
                    )
                }


                // =============================================
                // TEXT FIELD
                // =============================================

                BasicTextField(
                    value = inputText,

                    onValueChange = {
                        inputText = it
                    },

                    modifier = Modifier
                        .weight(1f)
                        .padding(
                            horizontal = 12.dp
                        ),

                    singleLine = true,

                    textStyle = TextStyle(
                        color = AceWhite,
                        fontSize = 15.sp
                    ),

                    cursorBrush =
                        SolidColor(AceInputGlow),

                    keyboardOptions =
                        KeyboardOptions(
                            imeAction = ImeAction.Send
                        ),

                    keyboardActions =
                        KeyboardActions(
                            onSend = {
                                sendMessage()
                            }
                        ),

                    decorationBox = { innerTextField ->

                        Box(
                            contentAlignment =
                                Alignment.CenterStart
                        ) {

                            if (inputText.isEmpty()) {

                                Text(
                                    text =
                                        if (isListening)
                                            "Listening..."
                                        else
                                            "Ask ACE anything...",

                                    color =
                                        if (isListening)
                                            AceLightPurple
                                        else
                                            AceGray.copy(
                                                alpha = 0.9f
                                            ),

                                    fontSize = 15.sp
                                )
                            }

                            innerTextField()
                        }
                    }
                )


                // =============================================
                // SEND BUTTON
                // =============================================

                if (inputText.isNotBlank()) {

                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.radialGradient(
                                    colors = listOf(
                                        AceInputGlow,
                                        AcePurple
                                    )
                                )
                            )
                            .border(
                                width = 1.dp,

                                color =
                                    AceLightPurple.copy(
                                        alpha = 0.7f
                                    ),

                                shape =
                                    CircleShape
                            )
                            .clickable {

                                sendMessage()
                            },

                        contentAlignment =
                            Alignment.Center
                    ) {

                        Text(
                            text = "➤",

                            color = AceWhite,

                            fontSize = 19.sp,

                            fontWeight =
                                FontWeight.Bold
                        )
                    }

                } else {


                    // =============================================
                    // MICROPHONE BUTTON
                    // =============================================

                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .clip(CircleShape)
                            .background(
                                if (isListening)
                                    AcePurple.copy(
                                        alpha = 0.28f
                                    )
                                else
                                    Color(0xFF211735)
                            )
                            .border(
                                width = 1.dp,

                                color =
                                    if (isListening)
                                        AceLightPurple
                                    else
                                        AcePurple.copy(
                                            alpha = 0.3f
                                        ),

                                shape =
                                    CircleShape
                            )
                            .clickable {

                                startMicrophone()
                            },

                        contentAlignment =
                            Alignment.Center
                    ) {

                        Image(
                            painter =
                                painterResource(
                                    id = R.drawable.mic_icon
                                ),

                            contentDescription =
                                "Voice input",

                            contentScale =
                                ContentScale.Fit,

                            modifier =
                                Modifier.size(
                                    if (isListening)
                                        28.dp
                                    else
                                        24.dp
                                )
                        )
                    }
                }
            }
        }
    }
}


// ============================================================
// SPEECH RECOGNITION
// ============================================================

fun startAceSpeechRecognition(
    context: Context,

    onResult: (String) -> Unit,

    onListeningChanged: (Boolean) -> Unit
) {

    if (
        !SpeechRecognizer
            .isRecognitionAvailable(context)
    ) {

        Toast.makeText(
            context,
            "Speech recognition is not available",
            Toast.LENGTH_SHORT
        ).show()

        return
    }


    val speechRecognizer =
        SpeechRecognizer
            .createSpeechRecognizer(context)


    val speechIntent =
        Intent(
            RecognizerIntent.ACTION_RECOGNIZE_SPEECH
        ).apply {

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )

            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "en-US"
            )

            putExtra(
                RecognizerIntent.EXTRA_PARTIAL_RESULTS,
                true
            )
        }


    speechRecognizer.setRecognitionListener(

        object : RecognitionListener {


            override fun onReadyForSpeech(
                params: Bundle?
            ) {

                onListeningChanged(true)
            }


            override fun onBeginningOfSpeech() {

                onListeningChanged(true)
            }


            override fun onRmsChanged(
                rmsdB: Float
            ) {
            }


            override fun onBufferReceived(
                buffer: ByteArray?
            ) {
            }


            override fun onEndOfSpeech() {

                onListeningChanged(false)
            }


            override fun onError(
                error: Int
            ) {

                onListeningChanged(false)

                speechRecognizer.destroy()
            }


            override fun onResults(
                results: Bundle?
            ) {

                val matches =
                    results?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                if (!matches.isNullOrEmpty()) {

                    onResult(
                        matches[0]
                    )
                }

                onListeningChanged(false)

                speechRecognizer.destroy()
            }


            override fun onPartialResults(
                partialResults: Bundle?
            ) {

                val matches =
                    partialResults?.getStringArrayList(
                        SpeechRecognizer.RESULTS_RECOGNITION
                    )

                if (!matches.isNullOrEmpty()) {

                    onResult(
                        matches[0]
                    )
                }
            }


            override fun onEvent(
                eventType: Int,
                params: Bundle?
            ) {
            }
        }
    )


    speechRecognizer.startListening(
        speechIntent
    )
}


// ============================================================
// CHAT BUBBLE
// ============================================================

@Composable
fun ChatBubble(
    message: ChatMessage
) {

    Row(
        modifier =
            Modifier.fillMaxWidth(),

        horizontalArrangement =
            if (message.isUser)
                Arrangement.End
            else
                Arrangement.Start
    ) {

        Box(
            modifier = Modifier
                .widthIn(
                    max = 300.dp
                )
                .clip(
                    RoundedCornerShape(18.dp)
                )
                .background(
                    if (message.isUser)
                        AcePurple.copy(
                            alpha = 0.22f
                        )
                    else
                        AceInputBackground
                )
                .border(
                    width = 1.dp,

                    color =
                        AcePurple.copy(
                            alpha = 0.5f
                        ),

                    shape =
                        RoundedCornerShape(18.dp)
                )
                .padding(
                    horizontal = 16.dp,
                    vertical = 12.dp
                )
        ) {

            Text(
                text = message.text,

                color = AceWhite,

                fontSize = 15.sp
            )
        }
    }
}


// ============================================================
// SMALL ACTION BUTTON
// ============================================================

@Composable
fun SmallActionButton(
    text: String,
    onClick: () -> Unit
) {

    Box(
        modifier = Modifier
            .clip(
                RoundedCornerShape(20.dp)
            )
            .background(
                AceInputInner
            )
            .border(
                width = 1.dp,

                color =
                    AcePurple.copy(
                        alpha = 0.7f
                    ),

                shape =
                    RoundedCornerShape(20.dp)
            )
            .clickable {

                onClick()
            }
            .padding(
                horizontal = 16.dp,
                vertical = 10.dp
            ),

        contentAlignment =
            Alignment.Center
    ) {

        Text(
            text = text,

            color = AceWhite,

            fontSize = 13.sp
        )
    }
}