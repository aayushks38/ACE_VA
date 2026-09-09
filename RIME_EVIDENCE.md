# ACE — System Verification Evidence & Honesty Statement

> **Authoring-environment honesty note.** This document is written from static
> source review. The Android toolchain (SDK, NDK, CMake, Gradle, `adb`) was **not
> available** in the environment where this code was completed, so the app was
> **not compiled, installed, or run** here. Every claim below is labelled either
> **[SOURCE-VERIFIED]** (confirmed by reading the code in this repo) or
> **[NOT VERIFIED]** (requires a real Android build/device to confirm). No build
> logs, token counts, latencies, emulator sessions, or test-run results are
> asserted as having happened. Truthful "not verified" is preferred over a
> fabricated pass.

---

## 1. Architecture Summary

ACE is a voice-first, general-purpose autonomous agent (not a keyword chatbot).
The runtime pipeline is:

```
Microphone
  → SpeechRecognizer (android.speech)                     [SOURCE-VERIFIED]
  → GemmaLocalBrain  (on-device Gemma via llama.cpp JNI)   [SOURCE-VERIFIED code; inference NOT VERIFIED at runtime]
  → structured JSON plan
  → JSON extraction + schema parsing (parseModelOutputToPlan)   [SOURCE-VERIFIED]
  → capability allowlist check (CapabilityRegistry.isRegistered) [SOURCE-VERIFIED]
  → AgentExecutor (parallel roots + sequential dependent DAG)    [SOURCE-VERIFIED]
  → AgentCapability implementations (real Android APIs)          [SOURCE-VERIFIED]
  → verification pass + truthful summary                         [SOURCE-VERIFIED]
  → TTS (Rime online proxy, else on-device TextToSpeech)         [SOURCE-VERIFIED]
```

Design guarantees enforced in code:

- **No keyword "intelligence."** There is no `if (text.contains("call"))` routing
  as the primary planner. Intent → plan is produced by the brain and then
  validated. The only keyword matching remaining is a narrow yes/no confirmation
  parser used *after* a plan already reached the approval gate
  (`VoiceManager.isApprovalIntent` / `isCancellationIntent`), and it is
  token-based to avoid false matches. **[SOURCE-VERIFIED]**
- **Allowlist before execution.** `parseModelOutputToPlan` drops any step whose
  `capabilityId` is not in `CapabilityRegistry`. Unknown/hallucinated capabilities
  never execute. **[SOURCE-VERIFIED]**
- **No fabricated success.** Capabilities return truthful `isSuccess = false` when
  preconditions fail (missing permission, no contact match, unreadable file,
  offline). `DocumentAnalysisCapability` reports only observed facts (name, MIME,
  byte size) read through `ContentResolver`. **[SOURCE-VERIFIED]**

---

## 2. On-Device Gemma Brain (Real Inference)

- **Native runtime**: `app/src/main/cpp/llama_jni.cpp` links against upstream
  `llama.cpp` (fetched/built by `app/src/main/cpp/CMakeLists.txt` via
  `FetchContent`). It performs real model load, tokenization, decode loop, sampler
  chain, detokenization, cancellation, and teardown using the stable llama.cpp C
  API. **[SOURCE-VERIFIED]**
- **Model load without a 4 GB copy**: the model is opened by path or via a `dup`'d
  file descriptor (`/proc/self/fd/<fd>`) with mmap, so the GGUF is never read into
  a Kotlin `ByteArray`. **[SOURCE-VERIFIED]**
- **Honest readiness**: `GemmaLocalBrain.isReady()` is true **only** after a real
  native model load succeeds. If the native library is missing or the model fails
  to load, the brain reports `MODEL_PRESENT_NO_RUNTIME` and refuses to generate —
  it never fakes a plan. **[SOURCE-VERIFIED]**
- **Runtime status**: whether Gemma 3n actually loads and generates on a device is
  **[NOT VERIFIED]** here, because building the native library and running on an
  ARM device/emulator was not possible in the authoring environment. Gemma 3n
  (`gemma3n` GGUF arch) requires a recent llama.cpp; the exact build tag should be
  pinned and validated on a real build. See the warning block in `CMakeLists.txt`.

---

## 3. Rime Voice Integration

### Components
- **Server proxy**: `server/server.js` (Express) — **[SOURCE-VERIFIED]**
- **Env config**: `server/.env.example` (placeholders); real `server/.env` is
  git-ignored. **[SOURCE-VERIFIED]**
- **Android client**: `app/src/main/java/com/ace/app/voice/RimeVoiceOutput.kt` — **[SOURCE-VERIFIED]**
- **Provider state / UI badge**: `VoiceManager.kt`, `HomeScreen.kt` — **[SOURCE-VERIFIED]**

### Backend endpoints (from reading `server/server.js`) — [SOURCE-VERIFIED]
1. `GET /health` → `{ status, rimeConfigured, timestamp }`.
2. `POST /voice` → validates that `text` is present (400 if not), enforces an
   optional client secret, and returns `503` with a truthful message when
   `RIME_API_KEY` is unconfigured; otherwise proxies to Rime with the key held
   server-side and streams the audio back.

> Endpoint responses above are described from the source, **not** from an executed
> test run. Actually starting the server and calling it is **[NOT VERIFIED]** here.

### API-key security — [SOURCE-VERIFIED]
`RIME_API_KEY` lives only in `server/.env` (git-ignored). It is never compiled
into or shipped inside the APK. The Android client only knows the proxy URL
(`http://10.0.2.2:3000` for the emulator's host loopback), permitted narrowly via
`res/xml/network_security_config.xml` (no global cleartext).

### Truthful provider reporting — [SOURCE-VERIFIED]
`RimeVoiceOutput.currentProvider` starts at `OFFLINE_LOCAL`, is set to
`ONLINE_RIME` **only** inside `playAudioFile` (i.e. when real audio bytes returned
from the proxy are actually being played), and reverts to `OFFLINE_LOCAL` on any
network/server/exception path. The UI badge therefore never claims "Rime" unless
Rime audio is genuinely playing.

---

## 4. Interruption, Cancellation & Generation Fencing — [SOURCE-VERIFIED]

- **Barge-in**: tapping the orb or submitting a new goal calls
  `VoiceManager.stopSpeaking()` / `startListening()`, which immediately stops the
  `MediaPlayer`, cancels the active OkHttp call, and stops device TTS.
- **Generation fencing**: `TaskViewModel` holds `currentGeneration = AtomicLong`.
  Each new input/interruption increments it; every async callback checks
  `currentGeneration.get() == generationId` before touching UI, speech, or task
  state, so stale work is discarded.
- **Network cancellation**: `WebSearchCapability` and `RimeVoiceOutput` wrap OkHttp
  in `suspendCancellableCoroutine` and call `call.cancel()` on cancellation.

---

## 5. Capabilities (all real Android integrations) — [SOURCE-VERIFIED code]

| Capability | Real mechanism | Truthful-failure behavior |
|---|---|---|
| `contact_lookup` | `ContactsContract` query, `READ_CONTACTS` checked | fails on no permission / no match / ambiguous |
| `phone_dialer` | `ACTION_CALL`, `CALL_PHONE` checked | fails on missing permission / number |
| `whatsapp_call` | WhatsApp package intent | fails if WhatsApp not installed |
| `file_discovery` | app `filesDir` + `MediaStore` query | fails if file not found |
| `document_analysis` | reads content URI via `ContentResolver` (name/MIME/size) | fails if URI/file unreadable |
| `app_share` | `ACTION_SEND` + `FileProvider`/content URI | reports only "opened share sheet" |
| `web_search` | OkHttp GET (DuckDuckGo HTML) | fails when offline / non-2xx |
| `text_reasoning` | local synthesis of step outputs | — |

Consequential capabilities (`phone_dialer`, `whatsapp_call`, `app_share`) are
flagged `requiresApproval` and gated behind explicit user approval before
execution. **[SOURCE-VERIFIED]**

Whether each intent actually fires on a device (a real call is placed, WhatsApp
opens, the share sheet appears) is **[NOT VERIFIED]** here.

---

## 6. What still needs a real build to verify

Run on a machine with the Android SDK/NDK/CMake:

```
./gradlew clean assembleDebug        # compile Kotlin + native llama.cpp
./gradlew installDebug               # install on a device/emulator (arm64 recommended)
```

Then, on-device, confirm: (a) the app launches; (b) selecting the Gemma GGUF via
SAF registers it and the brain reaches `READY`; (c) a spoken goal produces a valid
JSON plan and executes; (d) barge-in stops audio; (e) consequential actions prompt
for approval; (f) with the Rime proxy running and keyed, the badge shows
"Voice: Rime (Online)". None of (a)–(f) are asserted as passing in this document.

---

## 7. Known Limitations

- Native inference is implemented but **not runtime-verified** in the authoring
  environment (no Android toolchain / device). Status:
  `REAL_INFERENCE_IMPLEMENTED_NOT_RUNTIME_VERIFIED`.
- Gemma 3n requires a recent llama.cpp; the `LLAMA_CPP_TAG` should be pinned to a
  known-good tag and validated by a real build.
- Rime TTS requires `RIME_API_KEY` in `server/.env` and `npm start` in `server/`;
  when unconfigured/unreachable the app falls back to on-device TTS and reports the
  fallback truthfully.
