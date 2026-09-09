# ACE

ACE is a voice-first, general-purpose Android AI agent. The user speaks a goal; an
on-device model turns it into a validated, bounded execution plan; ACE runs the
plan with real Android capabilities, asks for explicit approval before
consequential actions, and speaks a truthful result. There is no chat transcript
and no text prompt field — the interface is a voice orb.

## Pipeline

```
Voice → SpeechRecognizer → on-device Gemma (llama.cpp) → JSON plan
     → schema validation → capability allowlist → AgentExecutor (DAG)
     → real Android capabilities → verify → text-to-speech
```

The model plans; it does not hard-code tasks. Steps whose capability is not in the
registry are dropped before execution, and no capability reports success unless the
underlying Android operation actually succeeded.

## On-device brain

Reasoning runs locally through `llama.cpp` compiled via the NDK (`app/src/main/cpp`,
`GemmaLocalBrain`, `LlamaBridge`). The expected model is
`google_gemma-3n-E4B-it-Q4_K_M.gguf` (~3.9 GB), selected once through the Storage
Access Framework on the setup screen and loaded by memory-mapped file descriptor
(never copied into memory).

**Status:** the native inference path is fully implemented but **not runtime-verified**
in the environment where it was authored, because that environment had no Android
SDK/NDK/CMake/device. It must be built and exercised on a real device to confirm
generation. Readiness is honest in code: the brain only reports `READY` after a real
model load, and refuses to generate (rather than faking a plan) otherwise. Gemma 3n
requires a recent `llama.cpp`; pin `LLAMA_CPP_TAG` to a known-good tag when building.

## Capabilities

Contact lookup (`ContactsContract`), direct calling (`ACTION_CALL`), WhatsApp
hand-off, file discovery (app storage + `MediaStore`), document analysis (reads the
selected content URI via `ContentResolver`), content sharing (`FileProvider` /
content URI), and web search (OkHttp). Placing a call, opening WhatsApp, and sharing
are treated as consequential and require explicit spoken or tapped approval.

## Voice

Output prefers a Rime TTS proxy and falls back to on-device `TextToSpeech`. The Rime
API key stays server-side (`server/`, git-ignored `.env`) and is never shipped in the
APK; the app only reaches the proxy over a narrowly-scoped local cleartext rule. The
UI badge reflects the true provider — it shows "Rime (Online)" only while Rime audio
is actually playing.

## Build

```
./gradlew clean assembleDebug     # requires Android SDK + NDK + CMake
./gradlew installDebug            # install on a device/emulator (arm64-v8a recommended)
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`. The multi-GB
GGUF model is **not** committed to the repository (see `.gitignore`); provide it on
the device and select it via the setup screen.

## Layout

- `ui/home` — voice-only task workspace (orb, plan, approval card).
- `ui/model` — SAF model selection / download + validation.
- `agent` — plan model, capability registry, and the executor DAG.
- `brain` — `GemmaLocalBrain`, `LlamaBridge`, native `cpp/` runtime, model repository.
- `voice` — SpeechRecognizer input and Rime/device TTS output with truthful provider state.
- `server` — Express proxy that holds the Rime credential.

See `RIME_EVIDENCE.md` for a per-claim verification/honesty breakdown.
