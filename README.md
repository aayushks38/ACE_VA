# ACE

> **Speak a goal. ACE gets it done.**

ACE is a **voice-first, general-purpose autonomous Android AI agent** designed to turn natural-language goals into real actions on an Android device.

Unlike a conventional voice assistant that primarily converts speech into an answer, ACE is designed around an **execution and verification loop**:

```text
UNDERSTAND → PLAN → ACT → OBSERVE → VERIFY

The goal is not simply to generate a response.

The goal is to complete a real task and truthfully report what happened.

🚀 What is ACE?

ACE allows a user to speak a goal such as:

"Turn on the flashlight."

or:

"Send the latest photo to Ravi on WhatsApp."

ACE processes the request locally through its AI reasoning layer, converts it into a structured plan, validates the plan against available Android capabilities, executes the supported actions, and attempts to verify the resulting state.

The architecture is intentionally designed around the distinction:

EXECUTED ≠ VERIFIED

An Android intent being dispatched does not automatically mean that the requested task succeeded.

ACE therefore uses explicit task states such as:

COMPLETED
PARTIAL
UNVERIFIED
BLOCKED
FAILED

This makes the agent more transparent and reliable when operating across real Android applications.

🎯 Core Vision

Traditional voice assistants commonly follow:

VOICE → ANSWER

ACE is designed to follow:

VOICE
  ↓
GOAL
  ↓
UNDERSTAND
  ↓
PLAN
  ↓
ACT
  ↓
OBSERVE
  ↓
VERIFY
  ↓
RESULT

The long-term vision is a lightweight autonomous Android agent capable of understanding a user's goal, interacting with applications, observing the device state, adapting to changing interfaces, and recovering when an action does not work as expected.

🧠 Core Architecture
┌──────────────────────────────┐
│          User Voice          │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│      Speech Recognition      │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│       On-Device Gemma        │
│          + llama.cpp         │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│       Structured Plan        │
│          / JSON              │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│       Schema Validation      │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│      Capability Registry     │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│       Agent Executor         │
│          / DAG               │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│      Android Capabilities    │
│ Apps / Intents / System /    │
│ Accessibility / Content      │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│       Observe / Verify       │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│       Truthful Result        │
└──────────────┬───────────────┘
               ↓
┌──────────────────────────────┐
│        Text-to-Speech        │
└──────────────────────────────┘
🔄 Execution Model

ACE is structured around five major phases.

1. Understand

Speech is converted into a user goal.

Example:

"Send the latest photo to Ravi on WhatsApp."

The system identifies the intent and required entities without treating the user's request as an unrestricted command to the operating system.

2. Plan

The on-device AI generates a structured execution plan.

Conceptually:

Goal:
Send the latest photo to Ravi on WhatsApp.

Plan:

1. Find latest image
2. Resolve contact "Ravi"
3. Open WhatsApp
4. Prepare sharing operation
5. Send/share image
6. Verify resulting state

The model reasons about the task.

The execution layer remains responsible for deciding what capabilities are actually available.

3. Act

The validated plan is passed to the agent executor.

Actions are mapped to registered Android capabilities.

Examples include:

FLASHLIGHT
CONTACT_LOOKUP
PHONE_CALL
WHATSAPP
FILE_DISCOVERY
CONTENT_ANALYSIS
CONTENT_SHARING
WEB_SEARCH
SYSTEM_ACTION

ACE does not allow the language model to arbitrarily invoke Android APIs.

Actions must pass through the capability layer.

4. Observe

After actions are performed, ACE can inspect the resulting state where supported.

For example:

Requested:
Turn on flashlight

Observed:
Flashlight state = ON

Or:

Requested:
Open YouTube and search for "Mr Beast"

Observed:
YouTube opened
Search interaction unavailable
Accessibility permission required
5. Verify

ACE determines whether the requested outcome has actually been demonstrated.

Example:

ACTION:
Open YouTube

RESULT:
Executed ✓

does not necessarily mean:

TASK:
Search YouTube for "Mr Beast"

RESULT:
Completed ✗

If the search could not be performed or verified, ACE should report:

PARTIAL

rather than falsely claiming completion.

🛡️ Capability-Based Execution

The AI model is not given unrestricted control of Android.

Instead, ACE uses a capability registry.

AI PLAN
   ↓
SCHEMA VALIDATION
   ↓
CAPABILITY CHECK
   ↓
EXECUTOR
   ↓
ANDROID

This provides a bounded execution layer between AI reasoning and device operations.

Unsupported capabilities are not executed.

This design also makes it possible to add new Android capabilities without rewriting the reasoning layer.

🔐 Consequential Actions

Certain operations can have meaningful real-world consequences.

Examples include:

Making a phone call
Sending/sharing content
Interacting with communication applications
Other actions that require explicit confirmation

ACE can require explicit user approval before these actions are performed.

The intended flow is:

AI PLAN
   ↓
CONSEQUENTIAL ACTION DETECTED
   ↓
USER APPROVAL
   ↓
EXECUTION
   ↓
VERIFICATION

This keeps the agent autonomous while retaining an approval boundary for consequential operations.

📊 Truthful Task States

ACE does not use a simple binary:

SUCCESS / FAILURE

Instead, execution and verification are tracked separately.

Possible states include:

COMPLETED

The required actions were successfully executed and the relevant result was verified.

PARTIAL

Some steps succeeded, but the complete requested goal was not completed.

UNVERIFIED

An action may have been executed, but ACE does not have sufficient evidence to claim the requested outcome.

BLOCKED

Execution could not continue because a required permission, capability, or user action was unavailable.

FAILED

An action or required operation failed.

This allows ACE to communicate uncertainty rather than hiding it.

🧩 Example: Flashlight

User:

"Turn on the flashlight."

ACE:

UNDERSTAND ✓
PLAN       ✓
ACT        ✓
VERIFY     ✓

STATUS: COMPLETED

The physical Android state is used as the verification evidence where supported.

🧩 Example: Multi-Step WhatsApp Task

User:

"Send the latest photo to Ravi on WhatsApp."

ACE can decompose the goal into multiple operations:

1. Discover latest photo
2. Resolve Ravi
3. Open WhatsApp
4. Prepare content sharing
5. Execute sharing
6. Verify result

This demonstrates why ACE is more than a single-command voice interface.

The task contains multiple dependent actions and requires coordination between Android capabilities.

🧩 Example: Blocked Interaction

User:

"Open YouTube and search for Mr Beast."

ACE may be able to execute:

✓ Open YouTube

while being unable to interact with the UI because the required accessibility capability is unavailable:

⏳ Search "Mr Beast"
⚠ Accessibility permission required

The correct final state is:

PARTIAL

rather than:

COMPLETED

This behavior is intentional.

🧠 On-Device AI

ACE uses an on-device Gemma model with a native llama.cpp inference backend.

Current target model:

gemma-3n-E2B-it-Q4_0.gguf

The intended device location is:

/storage/emulated/0/Download/AceModels/

The native inference architecture is:

GemmaLocalBrain
       ↓
LlamaBridge
       ↓
Native llama.cpp
       ↓
Gemma GGUF

The model is loaded through the native inference layer using a file descriptor / memory-mapped loading path where supported by the device and filesystem.

Because the model is approximately 3 GB, actual runtime memory requirements depend on:

Device RAM
Android version
Native runtime requirements
Model context configuration
Available memory
Filesystem/loading behavior

Therefore, model loading and inference must be validated on the target physical hardware before claiming universal runtime support.

📦 Current Model Verification

The intended Q4_0 model has been verified to:

Exist at the expected AceModels location
Be approximately 2.97 GB
Parse successfully as GGUF
Report GGUF version 3
Report the gemma3n architecture
Report the expected Gemma 3N E2B model identity
Report Q4_0 quantization
Reach the native model-loading stage

ACE also contains graceful native initialization error handling so that model-loading or memory failures can transition the brain into an error/unavailable state rather than unnecessarily crashing the entire application.

📱 Android Compatibility

Current Android configuration:

minSdk    = 26
targetSdk = 35

The Release APK contains native libraries for:

arm64-v8a
x86_64
ARM64

ARM64 support allows the Release build to target modern physical Android phones using the ARM64 architecture.

x86_64

x86_64 support is retained for compatible Android emulators.

⚡ 16 KB Page-Size Compatibility

The native libllama_jni.so included in the Release APK has been inspected for ELF LOAD segment alignment.

Verified alignment:

0x4000

which equals:

16,384 bytes

Therefore the compiled native library has the required 16 KB ELF segment alignment.

🎙️ Voice Interface

ACE is intentionally designed as a voice-first experience.

The primary interaction is a central voice orb.

The intended interaction state flow is:

IDLE
  ↓
LISTENING
  ↓
THINKING
  ↓
EXECUTING
  ↓
VERIFYING
  ↓
SPEAKING
  ↓
IDLE

ACE does not require a wake word.

The user activates the voice interaction directly and speaks naturally.

There is no traditional text prompt field on the main ACE experience.

🔊 Voice Output

ACE supports:

Rime TTS Proxy
       ↓
Android TextToSpeech fallback

The Rime API credential remains server-side and is not packaged into the Android APK.

The application uses the configured proxy rather than embedding the secret credential in the client.

The UI is intended to reflect the actual active speech provider instead of displaying an online provider merely because it is configured.

🗣️ Progress Narration

For longer tasks, ACE can provide useful progress updates after the user's speech has been finalized and execution has started.

For example:

"Finding the latest photo."

"Finding Ravi."

"Opening WhatsApp."

"Sending the photo."

Progress narration is intended to communicate meaningful execution state rather than provide unnecessary acknowledgement immediately after the user taps the orb.

The final response is generated after the execution/verification process.

🛠️ Android Capabilities

ACE currently includes or is designed around capabilities including:

Contacts

Uses Android contact APIs to resolve contacts.

ContactsContract
Phone Calls

Supports direct Android calling through:

ACTION_CALL

Consequential calling can require explicit approval.

WhatsApp

Supports WhatsApp hand-off and content-sharing workflows where the required Android capabilities are available.

Files

ACE can discover files through Android storage mechanisms including:

MediaStore
Documents

Selected content can be accessed through Android content URIs and analyzed through ContentResolver.

Content Sharing

Content can be shared through Android content URIs / FileProvider where appropriate.

Web Search

Web search functionality can use the application's network layer.

Accessibility

Accessibility-assisted interaction can be used for UI operations that cannot be completed through direct Android APIs or intents.

Accessibility is treated as a capability requirement for the specific UI interaction rather than a universal requirement for every ACE task.

🏗️ Project Structure
app/
├── src/
│   └── main/
│       ├── cpp/
│       │   └── llama.cpp native runtime
│       │
│       └── java/com/ace/app/
│           │
│           ├── agent/
│           │   ├── plan model
│           │   ├── capability registry
│           │   └── executor
│           │
│           ├── brain/
│           │   ├── GemmaLocalBrain
│           │   ├── LlamaBridge
│           │   └── model repository
│           │
│           ├── ui/
│           │   ├── home/
│           │   └── model/
│           │
│           └── voice/
│               ├── speech recognition
│               └── TTS
│
└── server/
    └── Rime TTS proxy
🧱 Major Components
agent

Responsible for:

Structured task plans
Capability definitions
Validation
Execution
Step dependencies
Task state
Verification
brain

Responsible for:

Local Gemma inference
Native bridge
llama.cpp integration
Model discovery
Model loading
Brain readiness state

Important classes include:

GemmaLocalBrain
LlamaBridge
ModelRepository
voice

Responsible for:

Speech recognition
Voice state
TTS
Progress narration
Provider state
ui

Responsible for:

Voice orb
Listening state
Thinking/execution state
Approval UI
Model setup
Task status
server

Contains the Rime TTS proxy.

The server is responsible for keeping the Rime credential away from the Android client.

🔨 Building ACE
Requirements

ACE requires an Android development environment containing:

Android SDK
Android Gradle Plugin / Gradle
Android NDK
CMake
Debug Build
./gradlew assembleDebug

Windows:

gradlew.bat assembleDebug
Release Build
./gradlew assembleRelease

Windows:

gradlew.bat assembleRelease
Install Debug
./gradlew installDebug

or:

gradlew.bat installDebug
📦 APK Locations

Debug:

app/build/outputs/apk/debug/app-debug.apk

Release:

app/build/outputs/apk/release/app-release.apk

The generated APK contains the native ACE inference library.

📁 Model Distribution

The multi-GB GGUF model is intentionally not committed to the Git repository.

The expected model is:

gemma-3n-E2B-it-Q4_0.gguf

and the intended device directory is:

/storage/emulated/0/Download/AceModels/

The model should be distributed separately from the application APK.

This keeps the repository and APK manageable while allowing the model to be provisioned independently.

🔒 Security Principles

ACE follows several security-oriented principles:

Bounded Capabilities

The model does not receive unrestricted Android control.

Explicit Approval

Consequential operations can require user confirmation.

Local Reasoning

The core reasoning model is designed to run locally on the Android device.

Server-Side Credentials

External service credentials such as the Rime API key are kept outside the APK.

Honest State Reporting

ACE avoids claiming success without sufficient execution or verification evidence.

🧪 Verification Philosophy

ACE treats verification as a first-class part of agent execution.

A conventional automation system may do:

ACTION SENT
     ↓
SUCCESS

ACE aims for:

ACTION REQUESTED
       ↓
ACTION EXECUTED?
       ↓
OBSERVE RESULT
       ↓
RESULT VERIFIED?
       ↓
FINAL STATE

This distinction becomes increasingly important as tasks become more complex.

For example:

Open WhatsApp

being successful does not prove:

Send this specific image to this specific person

was successful.

ACE therefore tracks individual steps and their evidence.

🌐 Future Direction

The current architecture is designed to evolve from capability-driven automation toward adaptive visual agents.

The next major capability is:

SCREEN
  ↓
VISUAL OBSERVATION
  ↓
UNDERSTANDING
  ↓
ACTION
  ↓
SCREEN OBSERVATION
  ↓
VERIFICATION

This would allow ACE to reason from the actual visual state of an Android application instead of relying exclusively on predefined application actions.

The longer-term goal is an agent that can:

Understand natural voice goals.
Reason locally.
Generate multi-step plans.
Execute Android actions.
Observe application state.
Understand visual context.
Detect failures.
Adapt its plan.
Retry when appropriate.
Ask the user for help when necessary.
Report a truthful final result.
🗺️ Roadmap
Phase 1 — Voice Agent
Voice-first UI
Speech recognition
Local AI reasoning
Structured plans
Android capabilities
TTS
Phase 2 — Reliable Execution
Multi-step workflows
Capability validation
Consequential-action approval
Execution tracking
Verification states
Better failure handling
Phase 3 — Visual Perception
Android screen observation
Visual UI understanding
Screen-grounded actions
Adaptive interaction
UI-change resilience
Phase 4 — Self-Correcting Agent
PLAN
 ↓
ACT
 ↓
OBSERVE
 ↓
VERIFY
 ↓
SUCCESS?
 ├── YES → DONE
 └── NO
      ↓
   REPLAN
      ↓
     ACT

This moves ACE toward a general-purpose autonomous Android agent rather than a collection of hard-coded commands.

🏆 Why ACE?

ACE focuses on a problem that goes beyond voice recognition:

How can an AI agent reliably turn a natural-language goal into a real, verified action on a device?

The key differentiators are:

VOICE-FIRST
    +
ON-DEVICE REASONING
    +
STRUCTURED PLANNING
    +
BOUNDED ANDROID CAPABILITIES
    +
MULTI-STEP EXECUTION
    +
OBSERVATION
    +
VERIFICATION
    +
TRUTHFUL FAILURE STATES

The central idea is simple:

A useful agent should not only know what the user wants.
It should know whether it actually accomplished it.

📈 ACE vs Traditional Voice Assistants
Capability	Traditional Voice Assistant	ACE
Voice interaction	✓	✓
Natural-language goals	✓	✓
AI reasoning	✓	✓
Local reasoning	Varies	Designed for on-device
Structured task planning	Limited	✓
Multi-step Android execution	Limited	✓
Capability boundary	Varies	✓
Consequential-action approval	Varies	✓
Observation	Limited	Architecture supports it
Verification	Limited	Core design principle
Partial/blocked states	Limited	✓
Adaptive visual agent	Roadmap	Roadmap
📌 Current Status

ACE currently has the following major components:

Voice-first Android interface
Voice orb interaction
Speech recognition
On-device Gemma/llama.cpp architecture
Structured task planning
Plan/schema validation
Capability-based execution
Android system actions
App interactions
Contact lookup
Calling
WhatsApp workflows
File discovery
Content URI handling
Content sharing
Web search
TTS
Explicit approval flow for consequential actions
Execution state tracking
Verification architecture
Graceful model initialization failure handling
ARM64 native library
x86_64 native library
16 KB native ELF alignment

The Q4_0 model has been verified through the native GGUF loading/parsing stage.

End-to-end model inference and complex task execution should be validated on the target physical device before being represented as universally supported.

⚠️ Known Constraints

The Q4_0 model is approximately 3 GB and therefore has significant memory requirements.

Actual performance and successful inference depend on the physical Android device and available memory.

Some application interactions may require Android Accessibility permissions.

Some actions can be executed through direct Android APIs while others require UI-level interaction.

Not every Android application exposes enough programmatic interfaces for reliable automation.

ACE therefore intentionally reports:

BLOCKED
PARTIAL
UNVERIFIED
FAILED

when the available evidence is insufficient to claim successful completion.

🎥 Demo Concept

A representative ACE demonstration follows:

USER:
"Turn on the flashlight."

        ↓

ACE understands the goal.

        ↓

ACE creates a structured plan.

        ↓

ACE executes the Android capability.

        ↓

ACE observes the resulting state.

        ↓

ACE verifies the flashlight is on.

        ↓

ACE speaks:

"Flashlight is on."

A more complex demonstration:

USER:
"Send the latest photo to Ravi on WhatsApp."

        ↓

Find latest photo
        ↓
Resolve Ravi
        ↓
Open WhatsApp
        ↓
Prepare content
        ↓
Execute sharing
        ↓
Observe
        ↓
Verify
        ↓
Report truthful result

The important part is not merely that ACE performs actions.

The important part is that ACE attempts to determine whether the requested outcome actually occurred.

🧭 Design Principle

ACE is built around one rule:

DO NOT CONFUSE ACTION WITH OUTCOME.

An autonomous agent should be able to say:

"I completed it."

when it has evidence.

It should also be able to say:

"I could only complete part of it."

"I need permission to continue."

"I performed the action, but I could not verify the result."

"The action failed."

That honesty is a core part of ACE's architecture.

🚀 Final Vision

ACE aims to become a lightweight, privacy-conscious autonomous agent for Android.

The future interaction should be as simple as:

User:
"ACE, take care of this."

ACE:
Understand.
Plan.
Act.
Observe.
Verify.

Done.

The interface stays simple.

The intelligence stays local where possible.

The execution remains bounded.

And the result remains truthful.

ACE

ACE doesn't just answer. ACE acts.
