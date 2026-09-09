# ACE — llama.cpp Compile-Failure Fix: Verification Report

**Scope of this task (and nothing else):** fix the real llama.cpp integration
that failed to compile, pin the llama.cpp version, and verify as far as this
environment allows. No fallback keyword planning, no hardcoded JSON, no template
`AgentPlan`s, no Kotlin "fallback intelligence" were added. The architecture is
unchanged: **Voice → SpeechRecognizer → GemmaLocalBrain → REAL llama.cpp GGUF
inference → generated structured JSON → schema validation → capability allowlist
→ AgentExecutor → Android capabilities → truthful result → voice output.**

**Honesty contract upheld:** if real inference cannot initialize, the brain
reports the model unavailable — it never fabricates a GGUF result. Nothing below
is reported as "working" unless it was actually observed in this environment.

---

## The two reported compile errors

```
1. llama_jni.cpp: error: no member named 'use_mmap' in 'llama_model_params'
2. llama_jni.cpp: error: use of undeclared identifier 'llama_kv_cache_clear'
   (:app:buildCMakeDebug[arm64-v8a] and [x86_64] both failed)
```

**Root cause — confirmed by reading the source that FetchContent actually
downloaded, not from memory.** The build fetched llama.cpp from the moving
`master` branch, which had landed a public C-API refactor. Both symbols the JNI
shim used were renamed/removed in the fetched revision. The APIs were not
guessed; every symbol was checked against the on-disk header of the exact
fetched commit.

---

## 1. Exact llama.cpp version/commit pinned

**Pinned commit:** `64a155d242cb427766055ea9caea6f34df1ca94b` (ggml-org/llama.cpp)

This is the exact revision FetchContent had already downloaded — verified from
the fetched checkout's git head:

```
app/.cxx/Debug/66g4x1a4/arm64-v8a/_deps/llama_cpp-src/.git/HEAD
  → 64a155d242cb427766055ea9caea6f34df1ca94b
```

`CMakeLists.txt` was changed from the moving branch to that commit:

```cmake
# BEFORE
set(LLAMA_CPP_TAG "master" CACHE STRING "llama.cpp git tag/branch to build against")

# AFTER
set(LLAMA_CPP_TAG "64a155d242cb427766055ea9caea6f34df1ca94b"
    CACHE STRING "llama.cpp git commit (pinned for reproducibility)")
```

`FetchContent_Declare` uses a **full clone** (no `GIT_SHALLOW`), which is
required to check out an arbitrary commit SHA, so the pin is valid. Pinning
removes the reproducibility hazard of `master` silently changing the API again.

**gemma3n architecture is present at this pin (SOURCE VERIFIED).** The target
model `google_gemma-3n-E4B-it-Q4_K_M.gguf` has GGUF architecture id `gemma3n`.
The pinned revision supports it:

```
src/llama-arch.cpp:58    { LLM_ARCH_GEMMA3N, "gemma3n" }
src/llama-arch.cpp:1124  case LLM_ARCH_GEMMA3N:
src/llama-arch.h:63      LLM_ARCH_GEMMA3N,
```

plus dedicated gemma3n tensor enums. So the pin is both reproducible and
capable of loading the target architecture (necessary condition; the actual
load is runtime and is NOT verified here — see §D).

---

## 2. Every API mismatch fixed

Both fixes were made in `app/src/main/cpp/llama_jni.cpp` and verified against
`include/llama.h` at the pinned commit (line numbers from that header).

### Fix 1 — `use_mmap` → `load_mode`

The `bool use_mmap` field was removed from `llama_model_params`; memory-mapping
is now selected through `enum llama_load_mode load_mode`.

```cpp
// BEFORE (does not compile at this commit)
mparams.use_mmap = true;

// AFTER
mparams.load_mode = LLAMA_LOAD_MODE_MMAP;
```

Header evidence: `struct llama_model_params` contains `enum llama_load_mode
load_mode;` and **no** `use_mmap`. The enum is
`{ AUTO=-1, NONE=0, MMAP=1, MLOCK=2, MMAP_MLOCK=3, DIRECT_IO=4 }`, so
`LLAMA_LOAD_MODE_MMAP == 1`. This preserves the intended behaviour: the ~3.94 GB
GGUF is mmap'd (including via the SAF `/proc/self/fd/<fd>` path) and never copied
into RAM.

### Fix 2 — `llama_kv_cache_clear(ctx)` → memory API

`llama_kv_cache_clear(ctx)` was removed in favour of the memory API.

```cpp
// BEFORE (undeclared identifier at this commit)
llama_kv_cache_clear(w->ctx);

// AFTER
llama_memory_clear(llama_get_memory(w->ctx), /*data=*/true);
```

Header evidence: `llama_get_memory(const llama_context*) -> llama_memory_t`
(llama.h:582) and `llama_memory_clear(llama_memory_t mem, bool data)`
(llama.h:739). Passing `data=true` clears cached cell data — the equivalent of
the old KV-cache clear for a fresh generation.

### Audit of the remaining llama_* calls (no other changes needed)

Every other llama.cpp symbol used by the shim was checked against the pinned
header and confirmed present with a matching signature, so no further edits
were required. Notable confirmations:

- `llama_model_load_from_file`, `llama_init_from_model`, `llama_model_free` —
  the non-deprecated entry points (the `*_model` / `new_context_with_model`
  spellings are deprecated at this commit; the shim uses the current ones).
- `llama_tokenize(vocab,text,len,tokens,max,add_special,parse_special)` — 7-arg.
- `llama_token_to_piece(vocab,token,buf,len,lstrip,special)` — 6-arg.
- `llama_batch_get_one(tokens,n)` — 2-arg; `llama_decode`.
- Sampler chain: `llama_sampler_chain_init` / `_add` / `init_top_k(int32)` /
  `init_top_p(float,size_t)` / `init_temp(float)` / `init_dist(uint32 seed)`;
  `llama_sampler_sample(smpl,ctx,idx)`.
- `llama_vocab_is_eog`, `llama_get_memory`, `llama_memory_clear`.

`CMakeLists.txt` and the `VERIFICATION NOTE` block in `llama_jni.cpp` were also
updated to name the pinned commit and document both migrations, so a future
maintainer re-verifies against the header rather than guessing if the pin moves.

---

## 3. Full Gradle build result — **NOT VERIFIED HERE (hard blocker)**

`./gradlew clean assembleDebug` was **not** run in this environment, so I cannot
and do not claim `BUILD SUCCESSFUL`.

**Concrete unavoidable blocker:** this authoring environment is a Linux VM with
**no Android SDK, no NDK, no CMake, no Gradle, no `adb`, and no connected
device.** The mounted disk (`E:\ACE IIT`) is readable/writable — which is how
the fetched source was inspected and the fixes verified — but the Android
cross-compile toolchain simply is not installed here and cannot be. The build
must be run on your Windows toolchain:

```
.\gradlew clean assembleDebug
```

**What was verified here instead (a genuine, bounded check — not a substitute
for the NDK build):** the edited `llama_jni.cpp` was compiled **against the real
pinned `include/llama.h` and the fetched `ggml/include` headers** with:

```
g++ -std=c++17 -fsyntax-only llama_jni.cpp \
    -I <pinned llama.cpp>/include -I <pinned>/ggml/include \
    -I <jni/android stubs>
  → exit 0   (">>> SYNTAX CHECK PASSED")
```

This proves the entire translation unit's llama.cpp API usage — both fixes and
all other calls — is well-formed against the pinned header set. It does **not**
prove the NDK/Gradle build succeeds (see residual risks below).

**Residual build risks to confirm on the real toolchain (stated honestly):**
- Host `g++` (libstdc++) parsed the headers; the NDK uses `clang`/`libc++`.
  API usage is identical, but clang-specific diagnostics are possible (low risk).
- `-fsyntax-only` parses and type-checks but does not link. The two symbols are
  public `LLAMA_API` functions, so linking should resolve, but only the real
  build confirms it.
- A clean build re-runs FetchContent; with a full clone + full-SHA `GIT_TAG` the
  checkout is valid, but network access to GitHub is required at configure time.
- NDK version / 16 KB page size / `libc++` specifics are environmental and can
  only be validated by the actual build.

---

## 4. Native ABI build result (arm64-v8a, x86_64) — **NOT VERIFIED HERE**

Cannot be produced without the NDK. `app/build.gradle.kts` is correctly
configured to build both ABIs (`abiFilters += setOf("arm64-v8a", "x86_64")`)
and `CMakeLists.txt` builds `llama_jni` as a `SHARED` library linked against
`llama` + `ggml` + `android` + `log`. On your toolchain, after a successful
build, confirm both ABIs produced a native library:

```
app/build/intermediates/cmake/debug/obj/arm64-v8a/libllama_jni.so
app/build/intermediates/cmake/debug/obj/x86_64/libllama_jni.so
```

(or under `app/build/intermediates/merged_native_libs/debug/.../lib/<abi>/`).

---

## 5. APK install result — **NOT VERIFIED HERE**

No `adb`, no device/emulator. Run on your machine:

```
.\gradlew installDebug
```

---

## 6. Runtime Logcat evidence — **NOT VERIFIED HERE**

No device to capture Logcat from. The native code already emits explicit,
greppable evidence tags so you can capture proof on your device:

- `ACE_MODEL_LOAD:` — "Loading model via file descriptor path …", "llama backend
  initialized", "Model + context ready (n_ctx=…, threads=…)", or the failure
  line "llama_model_load_from_file FAILED (unsupported arch or unreadable file)".
- `ACE_INFERENCE:` — "Prompt tokens=…, maxTokens=…", "Generation produced N
  tokens (M chars)", plus EOG/stop/cancel lines.

Capture with:

```
.\adb logcat -s ACE_MODEL_LOAD ACE_INFERENCE ACE_PLAN
```

---

## 7. Actual model inference evidence — **NOT VERIFIED HERE**

The differential tests you specified cannot be executed without a device + the
GGUF loaded. On your toolchain, run them and keep the Logcat:

- **TEST A:** "What is 17 multiplied by 23?" (expect 391).
- **TEST B:** "Explain why the sky appears blue in one sentence."
- **TEST C (differential):** make the GGUF unavailable and confirm ACE does
  **not** produce those answers — it must report the brain unavailable. This is
  the test that proves output genuinely depends on the GGUF.

The code is written to satisfy TEST C's honesty requirement: if
`nativeInitModel` returns 0 (load failed), no plan is fabricated — the brain
surfaces unavailability rather than inventing a result.

---

## 8. Final honest status

### **REAL_INFERENCE_NOT_VERIFIED**

Per your rule #17, "real inference works" may only be claimed when *all* of:
Gradle build succeeds, the native library loads, the GGUF loads, at least one
real inference is generated **on a device**, and removing the model prevents
that inference. **None of those five can be executed in this environment** (no
Android toolchain, no device). Therefore the honest status is
`REAL_INFERENCE_NOT_VERIFIED`.

What is **not** in doubt: the specific compile blocker you reported is fixed at
the source level and the fix is pinned and header-verified.

---

## Verification taxonomy

### A. SOURCE VERIFIED (checked against the actually-fetched source)
- Pinned commit is the exact fetched revision `64a155d…` (from the checkout's
  `.git/HEAD`).
- `use_mmap` is absent and `enum llama_load_mode load_mode` is present in
  `llama_model_params`; `LLAMA_LOAD_MODE_MMAP == 1`.
- `llama_kv_cache_clear` is absent; `llama_get_memory` (l.582) and
  `llama_memory_clear` (l.739) exist and are used correctly.
- All other llama_* calls in the shim exist with matching signatures at the pin.
- `gemma3n` architecture (`LLM_ARCH_GEMMA3N` / `"gemma3n"`) is supported at the pin.
- `CMakeLists.txt` uses a full clone, so the full-SHA pin is checkout-valid.

### B. BUILD VERIFIED (this environment)
- Bounded host check only: `g++ -std=c++17 -fsyntax-only` of the edited
  `llama_jni.cpp` against the **real pinned** `llama.h` + `ggml/include`
  → exit 0. The file's llama.cpp API usage is well-formed against the pin.
- **NOT** verified: the NDK/Gradle cross-compile, per-ABI `.so` generation,
  linking. (No SDK/NDK/CMake/Gradle here.)

### C. DEVICE VERIFIED
- Nothing. No device/emulator, no `adb`, no APK install, no Logcat, no model
  load, no inference, no differential (model-absent) test.

### D. NOT VERIFIED (requires your Windows toolchain + a device)
1. `.\gradlew clean assembleDebug` → `BUILD SUCCESSFUL`.
2. Both `libllama_jni.so` (arm64-v8a, x86_64) present in build output.
3. `.\gradlew installDebug` succeeds.
4. `ACE_MODEL_LOAD` shows the GGUF opening and the model+context becoming ready.
5. `ACE_INFERENCE` shows prompt tokens and generated token counts.
6. TEST A / TEST B produce real generated output; TEST C shows the answers
   disappear when the GGUF is removed (output depends on the model).

---

## Files changed in this task
- `app/src/main/cpp/llama_jni.cpp` — Fix 1 (`load_mode`), Fix 2
  (`llama_memory_clear(llama_get_memory(ctx), true)`), and an updated
  `VERIFICATION NOTE` naming the pinned commit and the two migrations.
- `app/src/main/cpp/CMakeLists.txt` — `LLAMA_CPP_TAG` pinned to `64a155d…`
  (was `master`) with updated guidance to re-verify symbols if the pin moves.

No other files were modified. No fallback intelligence was introduced.
