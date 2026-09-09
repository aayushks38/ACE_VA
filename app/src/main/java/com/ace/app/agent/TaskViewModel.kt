package com.ace.app.agent

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ace.app.brain.BrainResult
import com.ace.app.brain.ModelHandle
import com.ace.app.brain.model.ModelRepository
import com.ace.app.brain.BrainState
import com.ace.app.brain.model.ModelDiscoveryState
import com.ace.app.voice.VoiceManager
import com.ace.app.voice.VoiceProvider
import com.ace.app.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

import com.ace.app.brain.BrainProvider
import com.ace.app.brain.LocalBrain
import com.ace.app.voice.AceProgressSpeaker

data class TaskUiState(
    val activeTask: AgentTask? = null,
    val lastHeard: String? = null,
    val announcement: String = "",
    val voiceState: VoiceState = VoiceState.IDLE,
    val voiceProvider: VoiceProvider = VoiceProvider.DEVICE_FALLBACK,
    val attachmentName: String? = null,
    val attachmentUri: String? = null,
    val hasGreetedOnLaunch: Boolean = false,
    val isBrainReady: Boolean = false,
    val brainStatusText: String = "Brain: Brain Unavailable",
    val currentActionLabel: String = ""
)

class TaskViewModel(application: Application) : AndroidViewModel(application) {
    private val brain: LocalBrain = BrainProvider.getBrain()
    private val executor = AgentExecutor(application.applicationContext)
    private val commandRouter = AceCommandRouter()

    private val _uiState = MutableStateFlow(TaskUiState())
    val uiState: StateFlow<TaskUiState> = _uiState.asStateFlow()

    private val currentGeneration = AtomicLong(0)
    private var executionJob: Job? = null

    var voiceManager: VoiceManager? = null
        private set

    init {
        initializeBrain()
        observeBrainRuntimeState()
        registerGoalReceiver()
    }

    private val pendingGoal = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private fun observeBrainRuntimeState() {
        viewModelScope.launch {
            com.ace.app.brain.GemmaBrainManager.runtimeState.collect { runtimeState ->
                when (runtimeState) {
                    com.ace.app.brain.BrainRuntimeState.READY -> {
                        _uiState.value = _uiState.value.copy(
                            isBrainReady = true,
                            brainStatusText = "🟢 Local AI ready",
                            announcement = if (_uiState.value.announcement.contains("Preparing")) "I'm ACE, your autonomous agent. What would you like me to do?" else _uiState.value.announcement
                        )
                        val queued = pendingGoal.getAndSet(null)
                        if (!queued.isNullOrBlank()) {
                            android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: Gemma is now READY! Auto-executing queued goal='$queued'")
                            submitVoiceGoal(queued)
                        }
                    }
                    com.ace.app.brain.BrainRuntimeState.LOADING -> {
                        _uiState.value = _uiState.value.copy(
                            isBrainReady = false,
                            brainStatusText = "🟡 Preparing local AI..."
                        )
                    }
                    com.ace.app.brain.BrainRuntimeState.ERROR -> {
                        _uiState.value = _uiState.value.copy(
                            isBrainReady = false,
                            brainStatusText = "🔴 Local AI unavailable"
                        )
                    }
                    com.ace.app.brain.BrainRuntimeState.NOT_LOADED -> {
                        val installed = com.ace.app.brain.GemmaBrainManager.isModelInstalled(getApplication())
                        _uiState.value = _uiState.value.copy(
                            isBrainReady = false,
                            brainStatusText = if (installed) "🟡 Preparing local AI..." else "🔴 Local AI unavailable"
                        )
                    }
                }
            }
        }
    }


    private fun registerGoalReceiver() {
        try {
            val filter = android.content.IntentFilter().apply {
                addAction("com.ace.app.SUBMIT_GOAL")
                addAction("com.ace.app.ACTION_SUBMIT_GOAL")
            }
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: android.content.Intent?) {
                    val goal = intent?.getStringExtra("goal")
                    if (!goal.isNullOrBlank()) {
                        submitVoiceGoal(goal)
                    }
                }
            }
            androidx.core.content.ContextCompat.registerReceiver(
                getApplication(),
                receiver,
                filter,
                androidx.core.content.ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {}
    }

    private fun initializeBrain() {
        val context = getApplication<Application>().applicationContext
        if (com.ace.app.brain.GemmaBrainManager.isModelInstalled(context)) {
            com.ace.app.brain.GemmaBrainManager.ensureRuntimeLoadedAsync(context)
        }
    }

    private suspend fun ensureBrainLoaded(context: Context): Boolean {
        return com.ace.app.brain.GemmaBrainManager.ensureRuntimeLoaded(context)
    }

    fun initializeVoiceManager(manager: VoiceManager) {
        this.voiceManager = manager
        if (!_uiState.value.hasGreetedOnLaunch) {
            _uiState.value = _uiState.value.copy(
                hasGreetedOnLaunch = true,
                voiceProvider = manager.currentProvider
            )
            // Attach AceProgressSpeaker to this voice manager
            AceProgressSpeaker.attach(manager, currentGeneration.get())
            // No startup greeting — ACE is silent until user taps
        }
    }

    fun setVoiceState(state: VoiceState) {
        _uiState.value = _uiState.value.copy(
            voiceState = state,
            voiceProvider = voiceManager?.currentProvider ?: VoiceProvider.DEVICE_FALLBACK
        )
    }

    fun setVoiceProvider(provider: VoiceProvider) {
        _uiState.value = _uiState.value.copy(voiceProvider = provider)
    }

    fun setAttachment(name: String?, uri: String? = null) {
        _uiState.value = _uiState.value.copy(attachmentName = name, attachmentUri = uri)
    }

    fun handleSpokenInput(spokenText: String) {
        val cleanText = spokenText.trim()
        if (cleanText.isBlank()) return

        android.util.Log.i("ACE_TASK", "ACE_TASK: spoken input received=$cleanText")

        val state = _uiState.value
        val currentTask = state.activeTask

        if (currentTask != null && currentTask.status == TaskStatus.WAITING_FOR_APPROVAL) {
            if (VoiceManager.isApprovalIntent(cleanText)) {
                approve()
                return
            } else if (VoiceManager.isCancellationIntent(cleanText)) {
                cancel()
                return
            }
        }

        if (cleanText.contains("diagnostic", ignoreCase = true)) {
            runDiagnostics()
            return
        }

        submitVoiceGoal(cleanText)
    }

    private fun runDiagnostics() {
        viewModelScope.launch {
            val context = getApplication<Application>().applicationContext
            val micPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val speechAvail = android.speech.SpeechRecognizer.isRecognitionAvailable(context)
            val brainIdentity = System.identityHashCode(brain)
            val brainState = brain.getBrainState()
            val jniAvail = com.ace.app.brain.native.LlamaBridge.isAvailable()
            val discovery = ModelRepository.discoverModel(context)
            val modelFound = discovery.state == ModelDiscoveryState.MODEL_FOUND
            val brainReady = brain.isReady()

            val diagLog = """
                === ACE DIAGNOSTICS ===
                1. RECORD_AUDIO Permission: ${if (micPerm) "GRANTED" else "DENIED"}
                2. SpeechRecognizer Available: $speechAvail
                3. TaskViewModel Connection: ACTIVE
                4. Brain Instance Identity: $brainIdentity
                5. Brain State: $brainState
                6. Native JNI Bridge: ${if (jniAvail) "AVAILABLE" else "UNAVAILABLE"}
                7. Model File Discovery: ${if (modelFound) "FOUND (${discovery.sizeBytes / (1024*1024)} MB)" else "NOT FOUND"}
                8. Brain Model Ready: ${if (brainReady) "READY" else "NOT READY"}
                =======================
            """.trimIndent()

            android.util.Log.i("ACE_TASK", "ACE_TASK: $diagLog")
            android.util.Log.i("ACE_VOICE", "ACE_VOICE: System Diagnostics complete")

            val summary = "Diagnostics complete. Brain state is $brainState, native runtime is ${if (jniAvail) "ready" else "unavailable"}, model is ${if (brainReady) "loaded" else "not loaded"}."
            _uiState.value = _uiState.value.copy(announcement = summary)
            voiceManager?.speak(summary, currentGeneration.get()) { currentGeneration.get() }
        }
    }

    fun submitVoiceGoal(goal: String) {
        val cleanGoal = goal.trim()
        if (cleanGoal.isBlank()) return

        val generationId = AceTaskSessionManager.startNewSession(cleanGoal, brain, voiceManager, executionJob)
        currentGeneration.set(generationId)
        // Clear pending progress speech from any previous task
        AceProgressSpeaker.clear(generationId)
        voiceManager?.let { AceProgressSpeaker.attach(it, generationId) }

        if (cleanGoal.contains("TEST_STALE_CALLBACK", ignoreCase = true)) {
            android.util.Log.i("ACE_TASK", "ACE_TASK: Triggering simulated stale callback test")
            viewModelScope.launch {
                val staleGenId = 999L
                kotlinx.coroutines.delay(500)
                AceTaskSessionManager.validateOrDiscard(staleGenId, "SimulatedStaleCallback")
            }
        }

        val startMs = System.currentTimeMillis()
        AceConversationContext.update(cleanGoal)
        android.util.Log.i("ACE_TASK", "ACE_TASK: Received voice command = $cleanGoal")

        when (val route = commandRouter.route(cleanGoal, brainAvailable = brain.isReady())) {
            is CommandRoute.Fast -> {
                val finishMs = System.currentTimeMillis()
                val routeStr = route.result.route.name
                android.util.Log.i("ACE_PERF", "ACE_PERF: route=$routeStr start_ms=$startMs finish_ms=$finishMs duration_ms=${finishMs - startMs}")
                android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: FAST_ACTION capability plan selected")
                android.util.Log.i("ACE_SESSION", "ACE_SESSION: state_transition=IDLE→EXECUTING")
                _uiState.value = _uiState.value.copy(announcement = "Executing task...")
                executePlan(cleanGoal, route.plan, generationId, brainRequired = route.result.brainRequired, brainAvailable = route.result.brainAvailable)
            }

            is CommandRoute.Workflow -> {
                val finishMs = System.currentTimeMillis()
                val routeStr = route.result.route.name
                android.util.Log.i("ACE_PERF", "ACE_PERF: route=$routeStr start_ms=$startMs finish_ms=$finishMs duration_ms=${finishMs - startMs}")
                android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: WORKFLOW capability plan selected")
                android.util.Log.i("ACE_SESSION", "ACE_SESSION: state_transition=IDLE→EXECUTING")
                _uiState.value = _uiState.value.copy(announcement = "Executing task...")
                executePlan(cleanGoal, route.plan, generationId, brainRequired = route.result.brainRequired, brainAvailable = route.result.brainAvailable)
            }

            is CommandRoute.DeepBrain -> {
                android.util.Log.i("ACE_INFERENCE", "ACE_INFERENCE: starting generation")

                viewModelScope.launch {
                    val context = getApplication<Application>().applicationContext
                    if (!brain.isReady()) {
                        android.util.Log.i("ACE_ROUTER", "ACE_ROUTER: Gemma loading... Preserved complex user goal='$cleanGoal' in queue.")
                        pendingGoal.set(cleanGoal)
                        _uiState.value = _uiState.value.copy(
                            lastHeard = cleanGoal,
                            announcement = "Preparing local AI..."
                        )
                        com.ace.app.brain.GemmaBrainManager.ensureRuntimeLoadedAsync(context)
                        return@launch
                    }

                    _uiState.value = _uiState.value.copy(
                        announcement = "ACE is thinking..."
                    )
                    android.util.Log.i("ACE_BRAIN", "ACE_BRAIN: state=READY")
                    android.util.Log.i("ACE_BRAIN", "ACE_BRAIN: deep_generation_started")
                    android.util.Log.i("ACE_BRAIN", "ACE_BRAIN: submitting goal='$cleanGoal'")

                    val brainResult = brain.generate(cleanGoal, _uiState.value.attachmentName.orEmpty(), generationId)
                    val finishMs = System.currentTimeMillis()
                    android.util.Log.i("ACE_PERF", "ACE_PERF: route=DEEP_BRAIN start_ms=$startMs finish_ms=$finishMs duration_ms=${finishMs - startMs}")

                    if (!AceTaskSessionManager.validateOrDiscard(generationId, "GemmaLocalBrain.generate")) return@launch

                    when (brainResult) {
                        is BrainResult.Success -> {
                            val plan = brainResult.plan

                            if (plan.clarificationNeeded || plan.steps.isEmpty()) {
                                val question = plan.clarificationQuestion
                                    ?: "Could you clarify what you'd like me to do?"
                                _uiState.value = _uiState.value.copy(
                                    activeTask = null,
                                    lastHeard = cleanGoal,
                                    announcement = question
                                )
                                voiceManager?.speak(question, generationId) { AceTaskSessionManager.getCurrentGenerationId() }
                                return@launch
                            }

                            executePlan(cleanGoal, plan, generationId, brainResult.rawReasoning)
                        }

                        is BrainResult.Error -> {
                            val errMsg = brainResult.message
                            _uiState.value = _uiState.value.copy(announcement = errMsg)
                            voiceManager?.speak(errMsg, generationId) { AceTaskSessionManager.getCurrentGenerationId() }
                        }

                        is BrainResult.Cancelled -> {
                            android.util.Log.i("ACE_TASK", "ACE_TASK: previous task marked CANCELLED")
                        }
                    }
                }
            }
        }
    }

    private fun executePlan(cleanGoal: String, plan: AgentPlan, generationId: Long, summaryReasoning: String? = null, brainRequired: Boolean = false, brainAvailable: Boolean = false) {
        val category = when (plan.intent.lowercase()) {
            "communication" -> TaskCategory.COMMUNICATION
            "document" -> TaskCategory.DOCUMENT
            "research" -> TaskCategory.RESEARCH
            else -> TaskCategory.GENERAL
        }

        val task = AgentTask(
            goal = cleanGoal,
            category = category,
            status = TaskStatus.PLANNING,
            summary = summaryReasoning ?: "Executing action plan for: $cleanGoal",
            steps = plan.steps,
            requiresApproval = plan.requiresApproval,
            attachmentName = _uiState.value.attachmentName,
            attachmentUri = _uiState.value.attachmentUri
        )

        _uiState.value = _uiState.value.copy(
            activeTask = task,
            lastHeard = cleanGoal,
            announcement = cleanGoal,
            currentActionLabel = ""
        )

        // No "Understood. Working on it." — "Yes?" already acknowledged the user.
        // Progress speech will fire as each step starts.

        executionJob = viewModelScope.launch {
            executor.executeTask(
                task = task,
                onStepUpdated = { updatedTask ->
                    if (AceTaskSessionManager.isCurrentGeneration(generationId)) {
                        _uiState.value = _uiState.value.copy(activeTask = updatedTask)
                    }
                },
                onApprovalRequested = { details ->
                    if (AceTaskSessionManager.isCurrentGeneration(generationId)) {
                        val spokenPrompt = "${details.title}. ${details.description} Authorization required to proceed."
                        _uiState.value = _uiState.value.copy(announcement = spokenPrompt)
                        voiceManager?.speak(spokenPrompt, generationId) { AceTaskSessionManager.getCurrentGenerationId() }
                    }
                },
                generationId = generationId,
                onProgressSpeech = { capabilityId, params ->
                    if (AceTaskSessionManager.isCurrentGeneration(generationId)) {
                        // Update the UI action label (human-readable, not internal ID)
                        val label = friendlyActionLabel(capabilityId, params)
                        if (label != null) {
                            _uiState.value = _uiState.value.copy(currentActionLabel = label)
                        }
                        AceProgressSpeaker.speakActionStarted(capabilityId, params, generationId)
                    }
                },
                brainRequired = brainRequired,
                brainAvailable = brainAvailable
            ).also { finalTask ->
                if (AceTaskSessionManager.validateOrDiscard(generationId, "TaskViewModel.executePlan")) {
                    val response = com.ace.app.voice.AssistantResponseComposer.compose(cleanGoal, finalTask)
                    _uiState.value = _uiState.value.copy(
                        announcement = response.displayText,
                        currentActionLabel = ""
                    )
                    // Use AceProgressSpeaker for deduplication against last progress phrase
                    AceProgressSpeaker.speakTaskCompleted(response.spokenText, generationId)
                    AceConversationContext.update(cleanGoal, response.displayText, task = finalTask)
                }
            }
        }
        AceTaskSessionManager.setActiveJob(executionJob)
    }

    fun approve() {
        val task = _uiState.value.activeTask ?: return
        if (task.status != TaskStatus.WAITING_FOR_APPROVAL) return

        val generationId = currentGeneration.incrementAndGet()
        AceProgressSpeaker.clear(generationId)
        voiceManager?.let { AceProgressSpeaker.attach(it, generationId) }

        executionJob?.cancel()
        voiceManager?.stopSpeaking()

        android.util.Log.i("ACE_APPROVAL", "ACE_APPROVAL: User approved task '${task.goal}'")
        _uiState.value = _uiState.value.copy(announcement = "Approved. Continuing execution...")
        voiceManager?.speak("Approved. Continuing.", generationId) { currentGeneration.get() }

        executionJob = viewModelScope.launch {
            executor.resumeExecutionAfterApproval(
                task = task,
                onStepUpdated = { updatedTask ->
                    if (currentGeneration.get() == generationId) {
                        _uiState.value = _uiState.value.copy(activeTask = updatedTask)
                    }
                },
                generationId = generationId,
                onProgressSpeech = { capabilityId, params ->
                    if (currentGeneration.get() == generationId) {
                        val label = friendlyActionLabel(capabilityId, params)
                        if (label != null) {
                            _uiState.value = _uiState.value.copy(currentActionLabel = label)
                        }
                        AceProgressSpeaker.speakActionStarted(capabilityId, params, generationId)
                    }
                }
            ).also { finalTask ->
                if (currentGeneration.get() == generationId) {
                    val response = com.ace.app.voice.AssistantResponseComposer.compose(task.goal, finalTask)
                    _uiState.value = _uiState.value.copy(
                        announcement = response.displayText,
                        currentActionLabel = ""
                    )
                    AceProgressSpeaker.speakTaskCompleted(response.spokenText, generationId)
                }
            }
        }
    }

    fun cancel() {
        val generationId = currentGeneration.incrementAndGet()
        AceProgressSpeaker.clear(generationId)

        executionJob?.cancel()
        voiceManager?.stopSpeaking()

        viewModelScope.launch {
            if (brain.getBrainState() == BrainState.GENERATING) {
                brain.cancel()
            }
        }

        val task = _uiState.value.activeTask
        val updatedTask = task?.copy(
            status = TaskStatus.CANCELLED,
            summary = "Task cancelled by user.",
            completedAt = System.currentTimeMillis()
        )

        _uiState.value = _uiState.value.copy(
            activeTask = updatedTask,
            announcement = "Cancelled.",
            currentActionLabel = ""
        )

        voiceManager?.speak("Cancelled.", generationId) { currentGeneration.get() }
    }

    fun clearTask() {
        _uiState.value = _uiState.value.copy(activeTask = null, lastHeard = null, currentActionLabel = "")
    }

    /**
     * Converts a capabilityId to a short human-readable label for the UI action label.
     * Returns null for instant/silent capabilities.
     */
    private fun friendlyActionLabel(capabilityId: String, params: Map<String, String>): String? {
        val id = capabilityId.lowercase()
        val app = params["appName"] ?: params["app"] ?: ""
        val query = params["query"] ?: params["text"] ?: ""
        val goal = params["goal"] ?: ""
        return when {
            id.contains("open_app") || id.contains("ui_open") -> {
                val name = app.ifBlank { goal.split(" ").lastOrNull() ?: "" }
                    .replaceFirstChar { it.uppercaseChar() }
                if (name.isNotBlank()) "Opening $name..." else "Opening app..."
            }
            id.contains("search") -> if (query.isNotBlank()) "Searching for $query..." else "Searching..."
            id.contains("file_discover") || id.contains("file_discovery") -> "Finding the file..."
            id.contains("contact") -> "Looking up contact..."
            id.contains("app_share") || id.contains("file_share") -> "Preparing file..."
            id.contains("send") -> "Sending..."
            id.contains("media_playback") || id.contains("play_media") -> "Playing..."
            id.contains("web_search") -> "Searching the web..."
            id.contains("settings") -> "Adjusting settings..."
            id.contains("battery") || id.contains("flashlight") || id.contains("time") -> null
            else -> null
        }
    }
}
