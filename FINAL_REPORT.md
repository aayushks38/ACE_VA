# ACE — Final Engineering Report

**Project:** ACE — voice-first, general-purpose on-device Android AI agent
**Repository:** `E:\ACE IIT`
**Report date:** 2026-09-04
**Brain status verdict:** `REAL_INFERENCE_NOT_VERIFIED` (the reported compile blocker is fixed and the llama.cpp version is pinned + host syntax-checked; no runtime/device proof is possible here — see the note below and §18/§19, and `BUILD_FIX_REPORT.md`)

> **Honesty contract for this report.** Every material claim is tagged
> **[SOURCE-VERIFIED]** (confirmed by reading the code in this repository during
> this engagement) or **[NOT VERIFIED]** (requires a real Android build and/or
> device to confirm, which was not possible in the authoring environment). No
> build logs, compile results, token counts, latencies, emulator sessions, or
> device test outcomes are asserted as having happened. Where something could not
> be checked, it says so. A truthful "not verified" is preferred over a fabricated
> pass.

---

## 1. Executive Summary

ACE takes a spoken goal, converts it — on device — into a structured JSON plan
using a real `llama.cpp` runtime, validates that plan against a fixed capability
allowlist, executes the surviving steps through real Android APIs (with an explicit
approval gate before consequential actions), and speaks a truthful result. There
is no chat transcript and no free-text prompt box; the interface is a voice orb.

This engagement audited the entire repository (treating the code on disk as the
sole source of truth, not prior "PASS" claims), replaced a previously fake
keyword-matching "brain," implemented a genuine on-device inference path, closed
several correctness gaps (unreachable model-setup screen, dropped file attachments,
silent Rime failure, substring-based approval matching), removed dead code, and
rewrote the documentation to remove fabricated build/test claims.

**What is real and verified by source review:** the native llama.cpp inference
shim, its JNI/CMake/Gradle wiring, the model-load-without-copy path, the JSON→plan
schema validation and allowlist, the eight real Android capabilities with truthful
failure reporting, the consequential-action approval gate, end-to-end attachment
handling, truthful voice-provider reporting, generation fencing/barge-in, and
secret/model hygiene.

**What is explicitly not verified:** that the app compiles and links in a real
toolchain; that llama.cpp at the pinned commit `64a155d` loads and generates with the Gemma 3n
GGUF; and that each capability's real-world effect (a call actually placed,
WhatsApp actually opening, the share sheet actually appearing) fires on a device.
These require an Android SDK/NDK/CMake build and a device — see §3 and §19.

---

## 2. Verification Methodology & What "Verified" Means Here

The authoring environment is a Linux VM with **no Android SDK, NDK, CMake, Gradle,
clang, or `adb`**, and no attached Android device or emulator. Consequently:

- "Verified" in this report means **static source verification** — the relevant
  file was read and its symbols, signatures, and control flow were confirmed to be
  internally consistent. **[SOURCE-VERIFIED]**
- A whole-codebase static symbol-consistency audit was performed across the
  `agent`, `brain`, `brain.model`, `brain.native`, `voice`, `ui.home`, and
  `ui.model` packages. It confirmed that every referenced symbol resolves to a
  definition with a compatible shape and found **zero compile-breaking problems**
  ("STATIC CONSISTENCY: CLEAN"). This is a static check, not a compiler run.
  **[SOURCE-VERIFIED]**
- Nothing in this report claims to have been compiled, installed, or executed.
  Any behavior that depends on a build or a device is tagged **[NOT VERIFIED]**.

---

## 3. Environment Constraints — What Could Not Be Executed Here

The following were **not possible** in the authoring environment and are therefore
**[NOT VERIFIED]**:

- `./gradlew clean assembleDebug` (Kotlin + native compile/link) — no toolchain.
- `./gradlew installDebug` — no device/emulator/`adb`.
- Fetching and building `llama.cpp` via CMake `FetchContent` — no CMake/NDK and
  outbound git/network egress is restricted in this environment.
- Loading the ~3.94 GB `google_gemma-3n-E4B-it-Q4_K_M.gguf` and running a single
  real inference — requires the built native library on a device with the model.
- Any on-device behavioral test (voice capture, TTS playback, placing a call,
  opening WhatsApp, the system share sheet, Rime proxy round-trip).

This is a stated environmental limitation, not a defect in the code. The build and
device checklist to close these items is in §19.

---

## 4. Phase 0 — Repository Audit Findings

Key findings from reading the repository (the code, not prior reports):

- **The prior "brain" was fake.** The earlier native shim was hardcoded C++
  keyword matching masquerading as an LLM runtime. It has been fully replaced with
  a genuine llama.cpp implementation (see §6). **[SOURCE-VERIFIED]**
- **The model-setup screen was unreachable.** `startDestination` was hardcoded to
  `"home"`, so on Android 11+ (scoped storage) a user could never register the
  GGUF via SAF, leaving the brain permanently un-loadable. Fixed by boot-gating on
  registration (see §8). **[SOURCE-VERIFIED]**
- **File attachments were silently discarded.** The SAF picker obtained a
  `content://` URI but only a display name was propagated; the document/share
  capabilities only understood `File` paths, so they never matched. Fixed by
  threading the content URI end-to-end (see §13). **[SOURCE-VERIFIED]**
- **Rime always failed silently.** No network-security configuration existed, so
  cleartext to the local proxy (`http://10.0.2.2:3000`) was blocked on modern
  Android, causing a permanent silent fallback to device TTS. Fixed with a
  narrowly scoped config (see §14, §16). **[SOURCE-VERIFIED]**
- **Approval matching used substrings.** "no" could match inside "now"/"know";
  "ok" inside "look." For a gate that authorizes real-world actions this is a
  safety bug. Replaced with token-based matching (see §12). **[SOURCE-VERIFIED]**
- **Dead code and fabricated docs.** `TaskPlanner.kt`, `AgentTools.kt`, and
  `google-services.json.old` were unused; `README.md`/`RIME_EVIDENCE.md` contained
  false "no LLM" statements and fabricated build/test results. All addressed
  (see §17). **[SOURCE-VERIFIED]**

---

## 5. Architecture & Runtime Pipeline

```
Microphone
  → SpeechRecognizer (android.speech)                              [SOURCE-VERIFIED]
  → GemmaLocalBrain (on-device Gemma via llama.cpp JNI)            [SOURCE-VERIFIED code; inference NOT VERIFIED at runtime]
  → structured JSON plan (model output)
  → JSON extraction + schema parse (parseModelOutputToPlan)        [SOURCE-VERIFIED]
  → capability allowlist check (CapabilityRegistry.isRegistered)   [SOURCE-VERIFIED]
  → consequential-step approval gate                               [SOURCE-VERIFIED]
  → AgentExecutor (parallel roots + sequential dependent DAG)      [SOURCE-VERIFIED]
  → AgentCapability implementations (real Android APIs)            [SOURCE-VERIFIED code]
  → verification pass + truthful summary                           [SOURCE-VERIFIED]
  → TTS (Rime online proxy, else on-device TextToSpeech)           [SOURCE-VERIFIED]
```

The model plans; nothing hard-codes tasks by keyword as the primary intelligence.
The only remaining keyword logic is the narrow yes/no confirmation parser used
*after* a plan already reached the approval gate, and it is token-based (§12).

---

## 6. On-Device Gemma Brain — Real Inference Implementation

**Native shim:** `app/src/main/cpp/llama_jni.cpp` (read in full this engagement).
It implements genuine GGUF inference against the stable llama.cpp public C API:

- Model load via `llama_model_load_from_file` with `load_mode = LLAMA_LOAD_MODE_MMAP`
  (the pinned revision replaced the old `use_mmap` bool with `enum llama_load_mode`).
- Context creation via `llama_init_from_model` with a bounded context window,
  batch size, and a CPU-thread count clamped to the device's cores. GPU layers
  default to 0 (CPU-only — a safe Android default).
- Vocabulary via `llama_model_get_vocab`; a low-temperature sampler chain
  (`top_k` → `top_p` → `temp 0.20` → `dist`) tuned for stable, JSON-friendly output.
- Real tokenize → `llama_decode` prompt → token-by-token generation loop with
  `llama_sampler_sample`, EOG detection via `llama_vocab_is_eog`, detokenization
  via `llama_token_to_piece`, stop-sequence trimming, and per-token cooperative
  cancellation via an `std::atomic<bool>`.
- Teardown frees sampler, context, model, and any dup'd fd; a mutex serializes
  generation vs. teardown. **[SOURCE-VERIFIED]**

**Honest readiness in Kotlin** (`GemmaLocalBrain.kt`): `isReady()` returns true
**only** when the native runtime actually loaded the model (`BrainState.READY`). If
the native library is absent or the GGUF fails to load, the brain enters
`MODEL_PRESENT_NO_RUNTIME` and **refuses to generate** rather than fabricating a
plan. There is no keyword fallback "intelligence." **[SOURCE-VERIFIED]**

**Runtime status:** whether Gemma 3n actually loads and generates on a device is
**[NOT VERIFIED]** here (no toolchain/device). Gemma 3n's GGUF arch id is
`gemma3n`; the pinned commit `64a155d` **does** include `gemma3n` support
(`LLM_ARCH_GEMMA3N` / `"gemma3n"` in `llama-arch`, SOURCE-VERIFIED). See §18.

---

## 7. Native Build Wiring (Gradle + CMake + JNI)

- **Gradle** (`app/build.gradle.kts`): `externalNativeBuild { cmake { path =
  file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }`, with
  `ndk { abiFilters += setOf("arm64-v8a", "x86_64") }` and `cppFlags += "-std=c++17"`.
  **[SOURCE-VERIFIED]**
- **CMake** (`app/src/main/cpp/CMakeLists.txt`): builds a `SHARED` library named
  **`llama_jni`** from `llama_jni.cpp`; uses `FetchContent` to pull `llama.cpp`
  from `https://github.com/ggml-org/llama.cpp.git`; disables tests/examples/
  tools/server/curl/OpenMP and static-links ggml/llama; links `llama ggml android
  ${log-lib}`. **[SOURCE-VERIFIED]**
- **JNI contract:** the four `external` declarations in `LlamaBridge.kt`
  (`nativeInitModel`, `nativeGenerateTokens`, `nativeCancelGeneration`,
  `nativeFreeModel`) **exactly match** the four
  `Java_com_ace_app_brain_native_LlamaBridge_*` C++ entry points by name, arity,
  and JNI type. The loaded library name `System.loadLibrary("llama_jni")` matches
  the CMake target. **[SOURCE-VERIFIED]**

Whether this configuration compiles and links against llama.cpp at the pinned
commit `64a155d` is **[NOT VERIFIED]** by a real Android build (no NDK/Gradle here).
A bounded host check was done: `g++ -std=c++17 -fsyntax-only` of `llama_jni.cpp`
against the actual fetched `include/llama.h` + `ggml/include` passed (exit 0),
confirming the file's llama.cpp API usage is well-formed against the pin. See §18.

---

## 8. Model Discovery, Storage & Loading

- **Discovery is SAF-first** (`ModelRepository`): a persisted `content://` URI is
  checked before any filesystem candidate, so the app works under Android scoped
  storage without broad storage permissions. `getRegisteredModelUri` /
  `getRegisteredModelPath` / `registerModel` / `validateModel` all present.
  **[SOURCE-VERIFIED]**
- **Boot gate** (`MainActivity.kt`): `startDestination` is `"home"` only when a
  model is registered (URI or path); otherwise it routes to `"model_setup"`, so
  the SAF selection screen is reachable. **[SOURCE-VERIFIED]**
- **Real validation:** `validateModel` reads the GGUF magic bytes; registration
  happens only after validation. **[SOURCE-VERIFIED]**
- **No 4 GB copy:** the model is loaded by a readable path when available, else by
  a `dup`'d file descriptor mapped through `/proc/self/fd/<fd>` with mmap in native
  code — the GGUF is never read into a Kotlin `ByteArray`. Loading runs off the
  main thread (`Dispatchers.IO`). **[SOURCE-VERIFIED]**

Actual registration + load + `READY` transition on a device is **[NOT VERIFIED]**.

---

## 9. Structured Planning — JSON → Schema → AgentPlan

`GemmaLocalBrain.parseModelOutputToPlan` extracts the first `{...}` object from the
model's raw output and parses it with `org.json`. It reads `intent`, `channel`
(→ `CommunicationChannel.PHONE|WHATSAPP`), `targetEntity`, `requiresClarification`,
`clarificationQuestion`, and a `steps[]` array. If clarification is requested (or
no valid executable step survives), it returns a clarification plan instead of
inventing actions. Unparseable output yields a truthful "couldn't turn that into an
actionable plan" clarification rather than a fabricated plan. Free-form text is
never executed directly. **[SOURCE-VERIFIED]**

---

## 10. Capability Allowlist & Registry

`CapabilityRegistry` (an `object`) registers a fixed set of capabilities and
exposes `isRegistered(id)`, `getAllCapabilities()`, and
`CONSEQUENTIAL_CAPABILITY_IDS = setOf("phone_dialer","whatsapp_call","app_share")`.
During parsing, each proposed step's capability id is normalized (`phone_call →
phone_dialer`, `contact_search → contact_lookup`) and then checked against the
registry; **any capability the model invents is dropped before execution**. The
prompt's allowlist is generated dynamically from the registry, so the model is only
ever told about capabilities that actually exist. **[SOURCE-VERIFIED]**

---

## 11. Capability Implementations & Truthful Failure

All eight capabilities are real Android integrations, and each reports truthful
failure rather than fabricating success. **[SOURCE-VERIFIED (code)]**

| Capability | Real mechanism | Truthful-failure behavior |
|---|---|---|
| `contact_lookup` | `ContactsContract` query, `READ_CONTACTS` checked | fails on no permission / no match / ambiguous |
| `phone_dialer` | `ACTION_CALL`, `CALL_PHONE` checked | fails on missing permission / number; no silent SIM fallback |
| `whatsapp_call` | WhatsApp package intent | fails if WhatsApp not installed; no silent fallback |
| `file_discovery` | app `filesDir` + `MediaStore` query | fails if file not found |
| `document_analysis` | reads the content URI via `ContentResolver` (name / MIME / size) | fails if URI/file unreadable; reports only observed facts |
| `app_share` | `ACTION_SEND` + `FileProvider` / content URI | reports only "opened the share sheet," never "sent" |
| `web_search` | OkHttp GET | fails when offline / non-2xx |
| `text_reasoning` | local synthesis of prior step outputs | — |

Whether each intent's real-world effect fires on a device is **[NOT VERIFIED]**.

---

## 12. Consequential-Action Approval Gate

Steps whose capability is in `CONSEQUENTIAL_CAPABILITY_IDS` are flagged
`requiresApproval = true` at plan-build time, and the plan's top-level
`requiresApproval` is set if any step is consequential. Execution of those steps is
gated behind explicit user approval. The confirmation parser
(`VoiceManager.isApprovalIntent` / `isCancellationIntent`) is **token-based**: input
is lowercased and split on non-letters, then matched against approval/cancel token
sets and a few multi-word phrases — so "no" cannot match inside "now"/"know," and
"ok" cannot match inside "look." This matters because a false approve/cancel here
authorizes a real-world action. **[SOURCE-VERIFIED]**

---

## 13. Attachment Handling (content URI threading)

The dropped-attachment gap is closed end-to-end: the SAF picker in `HomeScreen`
calls `takePersistableUriPermission` and passes `uri.toString()` into
`TaskViewModel.setAttachment(name, uri)`; `TaskUiState` and `AgentTask` both carry
`attachmentUri`; `AgentExecutor.executeCapabilityStep(step, task)` injects
`attachmentUri`, `attachmentName`, and `goal` into every step's params;
`DocumentAnalysisCapability` and `AppShareCapability` read the `content://` URI
through `ContentResolver` (display name, MIME via `getType`, byte size, readability
via `openInputStream`). Document analysis therefore reports only genuinely observed
facts, and sharing attaches the real content URI (with a read-permission grant) or
a `FileProvider` file. **[SOURCE-VERIFIED]**

`FileProvider` is declared in the manifest with authority `${applicationId}.
fileprovider` (matching `AppShareCapability`) and backed by `res/xml/file_paths.xml`.
**[SOURCE-VERIFIED]**

---

## 14. Voice I/O & Provider Truthfulness

- **Input:** `VoiceManager` drives `SpeechRecognizer` for free-form speech.
  **[SOURCE-VERIFIED]**
- **Output:** `RimeVoiceOutput` prefers a Rime TTS proxy and falls back to
  on-device `TextToSpeech`. **[SOURCE-VERIFIED]**
- **Truthful provider state:** `currentProvider` starts at `OFFLINE_LOCAL` and is
  set to `ONLINE_RIME` **only** inside `playAudioFile` (i.e. only while real audio
  bytes returned by the proxy are actually playing); it reverts to `OFFLINE_LOCAL`
  on any network/server/exception path. The UI badge therefore never claims "Rime"
  unless Rime audio is genuinely playing. **[SOURCE-VERIFIED]**
- The `HomeScreen` brain-status color mapping reflects the true state (ready →
  green; "inference engine unavailable" → amber; otherwise muted). **[SOURCE-VERIFIED]**

Actual audio capture/playback and a live Rime round-trip are **[NOT VERIFIED]**.

---

## 15. Interruption, Barge-in & Generation Fencing

- **Barge-in:** tapping the orb / submitting a new goal calls `stopSpeaking()` /
  `startListening()`, which immediately stops the `MediaPlayer`, cancels the active
  OkHttp call, and stops device TTS. **[SOURCE-VERIFIED]**
- **Generation fencing:** a monotonically increasing generation id is checked in
  async callbacks before touching UI/speech/state, and `GemmaLocalBrain.generate`
  discards its result (returns `Cancelled`) if the generation id was superseded.
  Native generation also honors a cooperative cancel flag. **[SOURCE-VERIFIED]**

---

## 16. Security & Secret Hygiene

- **Rime credential stays server-side.** The API key lives only in `server/.env`
  (git-ignored; `.env.example` holds placeholders) and is never compiled into or
  shipped in the APK. The app only knows the proxy URL. **[SOURCE-VERIFIED]**
- **Cleartext is narrowly scoped.** `res/xml/network_security_config.xml` permits
  cleartext **only** for `10.0.2.2`, `localhost`, `127.0.0.1` — no global cleartext.
  **[SOURCE-VERIFIED]**
- **The multi-GB model is not committed.** `.gitignore` covers `*.gguf`, plus
  `.env`/`.env.*` (allowing `.env.example`), `build/`, `.gradle/`,
  `local.properties`, and native artifacts (`.cxx/`, `**/.cxx/`). **[SOURCE-VERIFIED]**

---

## 17. Dead Code Removal & Documentation Truthfulness

- **Removed dead code:** `TaskPlanner.kt`, `AgentTools.kt`, and
  `google-services.json.old`. A repo-wide search for `TaskPlanner|AgentTools`
  returns **zero** references. **[SOURCE-VERIFIED]**
- **Docs de-fabricated:** `README.md` and `RIME_EVIDENCE.md` were rewritten to
  remove the false "does not use an LLM"/"deterministic task planning" statements
  and the fabricated build/install/clean-logcat/executed-test claims. Every claim
  in `RIME_EVIDENCE.md` is now tagged `[SOURCE-VERIFIED]` or `[NOT VERIFIED]`.
  **[SOURCE-VERIFIED]**

---

## 18. Known Limitations & Risks

- **Not runtime-verified.** The native path's real Android compile/link, model
  load, and generation, plus all on-device behaviors, are **[NOT VERIFIED]** here —
  no toolchain/device. Status: `REAL_INFERENCE_NOT_VERIFIED`. (A bounded host
  `g++ -fsyntax-only` check against the pinned header passed — the API usage is
  well-formed — but that is not the NDK cross-compile/link/device run.)
- **`LLAMA_CPP_TAG` is now pinned** to `64a155d242cb427766055ea9caea6f34df1ca94b`
  (was the moving `master` branch). This is the exact commit FetchContent had
  downloaded and that `llama_jni.cpp` was API-verified against; it includes
  `gemma3n` arch support. Two compile errors from the earlier `master` drift were
  fixed to match this revision: `use_mmap` → `load_mode = LLAMA_LOAD_MODE_MMAP`,
  and `llama_kv_cache_clear(ctx)` → `llama_memory_clear(llama_get_memory(ctx), true)`.
  If ever moving the pin, re-verify every `llama_*` symbol against the new revision's
  `include/llama.h`. **[SOURCE-VERIFIED (config + fixes); build behavior NOT VERIFIED]**
- **llama.cpp API drift.** If a fetched revision renamed C-API symbols, adjust per
  the alias note in `llama_jni.cpp`. **[SOURCE-VERIFIED (note present)]**
- **Minor:** `MainActivity.kt` retains one unused import
  (`ModelValidationResult`) — a warning, not a compile error. A rarely-hit path in
  `ModelDownloadViewModel.checkRegisteredModel` constructs a `GemmaLocalBrain` it
  does not `close()`. Neither blocks the build or affects the mainline flow.
  **[SOURCE-VERIFIED]**
- **Rime requires setup.** With no `RIME_API_KEY` / no running proxy, the app
  truthfully falls back to device TTS.

---

## 19. Build & Runtime Verification Checklist + Final Status

Run on a machine with the Android SDK, NDK, and CMake (and a device/emulator):

```
./gradlew clean assembleDebug      # compile Kotlin + fetch/build llama.cpp + link llama_jni
./gradlew installDebug             # install on a device/emulator (arm64-v8a recommended)
```

Then confirm on-device (none of these are asserted as passing here — all **[NOT VERIFIED]**):

1. The app launches and, with no model registered, opens the SAF model-setup screen.
2. Selecting `google_gemma-3n-E4B-it-Q4_K_M.gguf` validates + registers it, and the
   brain reaches `READY` (logcat tag `ACE_MODEL_LOAD`).
3. A spoken goal yields real model output (`ACE_INFERENCE`) that parses into a valid
   JSON plan; invented capabilities are dropped.
4. Consequential steps (call / WhatsApp / share) prompt for explicit approval and
   only then act; token-based yes/no is honored.
5. Barge-in (tap orb / new goal) instantly stops audio and supersedes stale work.
6. With the Rime proxy running and keyed, the badge shows "Rime (Online)" only while
   Rime audio plays; otherwise it truthfully shows the device-TTS fallback.
7. Document analysis of a picked file reports real name/MIME/size; share opens the
   system share sheet.

**Static consistency:** CLEAN (no compile-breaking symbol/signature problems found
by source audit). **[SOURCE-VERIFIED]**

**Final brain status:** `REAL_INFERENCE_NOT_VERIFIED` — the on-device Gemma
inference path is genuinely implemented (native llama.cpp shim, JNI/CMake/Gradle
wiring, honest readiness), the reported compile blocker is fixed, and llama.cpp is
pinned to `64a155d` (host `-fsyntax-only` check passed against that header). But it
has **not** been compiled with the NDK, linked, installed, or run on a device in
this environment, and no real inference has been generated here — so per the
verification bar it is not "verified." It must be built and exercised on a real
device to confirm generation. **[SOURCE-VERIFIED for implementation + fixes; runtime NOT VERIFIED]**
