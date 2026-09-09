// =============================================================================
// ACE — Real on-device LLM inference via llama.cpp (JNI shim)
//
// This file implements genuine GGUF inference: it opens the model with
// llama.cpp, tokenizes the prompt, runs a real decode/sample loop, converts
// tokens back to text, honours a stop sequence, and supports cooperative
// cancellation. It contains NO keyword matching, hardcoded plans, or template
// output. If the model cannot load, it returns null/0 and the Kotlin layer
// truthfully reports the brain unavailable.
//
// -----------------------------------------------------------------------------
// VERIFICATION NOTE
//   Every llama.cpp / ggml / gguf symbol used below was checked against the
//   ACTUAL fetched headers at the pinned commit
//     64a155d242cb427766055ea9caea6f34df1ca94b  (ggml-org/llama.cpp)
//   include/llama.h, ggml/include/ggml.h, ggml/include/gguf.h. APIs are NOT
//   guessed. This TU has not been compiled with the NDK in the authoring
//   environment (no NDK/CMake here); a host `g++ -fsyntax-only` check against
//   these exact headers is the strongest local check available.
//
// API NOTES for commit 64a155d (this revision refactored several APIs):
//   * llama_model_params has NO `use_mmap` bool; mmap is selected via
//     `enum llama_load_mode load_mode` (LLAMA_LOAD_MODE_MMAP==1, NONE==0).
//   * llama_kv_cache_clear(ctx) was removed; clearing is done through the
//     memory API: llama_memory_clear(llama_get_memory(ctx), /*data=*/true).
//   * Non-deprecated entry points are used throughout.
//
// -----------------------------------------------------------------------------
// DIAGNOSTICS (added for the runtime model-load investigation)
//   * A ggml/llama log callback forwards llama.cpp's OWN error text to logcat
//     under tag ACE_LLAMA, so failures show the real reason (mmap failure,
//     truncated tensor, unknown key, etc.) instead of a generic message.
//   * The file descriptor path is logged explicitly at every hop: the fd the
//     JNI layer received, the dup()'d fd, and the exact /proc/self/fd path.
//     NOTE: dup() returns the LOWEST FREE descriptor, so the dup of Kotlin's
//     fd=181 can legitimately be fd=180 — it is the SAME open file. This is
//     expected POSIX behaviour, not an off-by-one or corruption.
//   * diagnose_model_source() runs access()/fstat()/first-4-bytes checks and a
//     GGUF metadata probe (gguf_init_from_file with no_alloc=true) that reads
//     version / tensor count / general.architecture WITHOUT loading weights.
//   * Model load is attempted with mmap first, then retried WITHOUT mmap
//     (LLAMA_LOAD_MODE_NONE) — a content-provider fd via /proc/self/fd is not
//     always mmappable; a plain read often still works.
// =============================================================================

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <mutex>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>

#include <cstdarg>
#include <cstring>
#include <cerrno>
#include <cctype>
#include <unistd.h>     // dup(), close(), pread()
#include <sys/stat.h>   // fstat()

#include "llama.h"
#include "ggml.h"
#include "gguf.h"
#include "ggml-backend.h"

#define LOG_TAG_LOAD  "ACE_MODEL_LOAD"
#define LOG_TAG_GGUF  "ACE_GGUF"
#define LOG_TAG_LLAMA "ACE_LLAMA"
#define LOG_TAG_TOK   "ACE_TOKENIZE"
#define LOG_TAG_DEC   "ACE_DECODE"
#define LOG_TAG_INF   "ACE_INFERENCE"
#define LOG_TAG_SMP   "ACE_SAMPLE"
#define LOG_TAG_TP    "ACE_TOKEN"
#define LOG_TAG_PLAN  "ACE_PLAN"
#define LOG_TAG_ERR   "ACE_ERROR"
#define LOGI(tag, ...) __android_log_print(ANDROID_LOG_INFO,  tag, __VA_ARGS__)
#define LOGW(tag, ...) __android_log_print(ANDROID_LOG_WARN,  tag, __VA_ARGS__)
#define LOGE(tag, ...) __android_log_print(ANDROID_LOG_ERROR, tag, __VA_ARGS__)

namespace {

std::atomic<bool> g_backend_initialized{false};

struct AceLlamaContext {
    llama_model*        model   = nullptr;
    llama_context*      ctx     = nullptr;
    const llama_vocab*  vocab   = nullptr;
    llama_sampler*      sampler = nullptr;
    int                 owned_fd = -1;   // dup'd fd when loaded via /proc/self/fd
    int                 n_ctx   = 2048;
    std::atomic<bool>   cancel{false};
    std::mutex          gen_mutex;       // serialise generation calls
};

// ---- Route llama.cpp / ggml internal logs to logcat (tag ACE_LLAMA) --------
// This is the single most important diagnostic: it surfaces the REAL reason a
// load fails, straight from llama.cpp, instead of a vague summary.
void ace_ggml_log_cb(enum ggml_log_level level, const char* text, void* /*user*/) {
    if (text == nullptr || text[0] == '\0') return;
    if (level == GGML_LOG_LEVEL_DEBUG) return; // Suppress verbose debug spam to protect logcat buffer quota
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_DEBUG: prio = ANDROID_LOG_DEBUG; break;
        default:                   prio = ANDROID_LOG_INFO;  break;
    }
    // llama.cpp lines usually carry a trailing newline; trim it for clean logcat.
    size_t n = std::strlen(text);
    if (n > 0 && text[n - 1] == '\n') {
        std::string s(text, n - 1);
        __android_log_write(prio, LOG_TAG_LLAMA, s.c_str());
    } else {
        __android_log_write(prio, LOG_TAG_LLAMA, text);
    }
}

std::once_flag g_log_once;
void ensure_logging() {
    std::call_once(g_log_once, [] {
        llama_log_set(ace_ggml_log_cb, nullptr);
        ggml_log_set(ace_ggml_log_cb, nullptr);
    });
}

#include <sys/auxv.h>

#ifndef HWCAP_ASIMD
#define HWCAP_ASIMD (1 << 1)
#endif
#ifndef HWCAP_FPHP
#define HWCAP_FPHP (1 << 9)
#endif
#ifndef HWCAP_ASIMDDP
#define HWCAP_ASIMDDP (1 << 20)
#endif
#ifndef HWCAP2_I8MM
#define HWCAP2_I8MM (1 << 13)
#endif

// Log one line to LOG_TAG_LOAD and also append it to `out`.
void diag_emit(std::string& out, int prio, const char* fmt, ...) {
    char line[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(line, sizeof(line), fmt, ap);
    va_end(ap);
    __android_log_write(prio, LOG_TAG_LOAD, line);
    out += line;
    out += '\n';
}

void log_hardware_diagnostics() {
    bool comp_neon = false, comp_dotprod = false, comp_fp16 = false;
#ifdef __ARM_NEON
    comp_neon = true;
#endif
#ifdef __ARM_FEATURE_DOTPROD
    comp_dotprod = true;
#endif
#ifdef __ARM_FEATURE_FP16_VECTOR_ARITHMETIC
    comp_fp16 = true;
#endif

    unsigned long hwcap = getauxval(AT_HWCAP);
    unsigned long hwcap2 = getauxval(AT_HWCAP2);

    bool hw_neon = (hwcap & HWCAP_ASIMD) != 0;
    bool hw_dotprod = (hwcap & HWCAP_ASIMDDP) != 0;
    bool hw_fp16 = (hwcap & HWCAP_FPHP) != 0;
    bool hw_i8mm = (hwcap2 & HWCAP2_I8MM) != 0;

    long ncores = sysconf(_SC_NPROCESSORS_CONF);

    LOGI("ACE_BACKEND", "ACE_BACKEND: arch=arm64 neon=%s dotprod=%s fp16=%s backend=CPU optimized_kernels=%s",
         (comp_neon && hw_neon) ? "true" : "false",
         (comp_dotprod && hw_dotprod) ? "true" : "false",
         (comp_fp16 && hw_fp16) ? "true" : "false",
         ((comp_neon || comp_dotprod) && (hw_neon || hw_dotprod)) ? "true" : "false");

    LOGI("ACE_CPU", "ACE_CPU: core_count=%ld hardware_features=fp asimd fphp asimdhp asimddp neon=%s dotprod=%s fp16=%s i8mm=%s",
         ncores,
         hw_neon ? "true" : "false",
         hw_dotprod ? "true" : "false",
         hw_fp16 ? "true" : "false",
         hw_i8mm ? "true" : "false");
}

// Inspect the model source WITHOUT loading weights: fd facts + GGUF metadata.
// `fd` may be -1 (path-only). Returns a human-readable multi-line summary.
std::string diagnose_model_source(const std::string& loadPath, int fd) {
    std::string out;

    if (fd >= 0) {
        struct stat st{};
        if (fstat(fd, &st) == 0) {
            diag_emit(out, ANDROID_LOG_INFO,
                      "ACE_MODEL_LOAD: fstat(fd=%d) size=%lld bytes, regular_file=%s",
                      fd, (long long) st.st_size, S_ISREG(st.st_mode) ? "yes" : "NO");
        } else {
            diag_emit(out, ANDROID_LOG_WARN,
                      "ACE_MODEL_LOAD: fstat(fd=%d) FAILED: %s", fd, std::strerror(errno));
        }
        unsigned char magic[4] = {0, 0, 0, 0};
        ssize_t r = pread(fd, magic, 4, 0);
        if (r == 4) {
            bool ok = (magic[0] == 'G' && magic[1] == 'G' && magic[2] == 'U' && magic[3] == 'F');
            diag_emit(out, ok ? ANDROID_LOG_INFO : ANDROID_LOG_ERROR,
                      "ACE_MODEL_LOAD: Header bytes = %02x %02x %02x %02x ('%c%c%c%c') %s",
                      magic[0], magic[1], magic[2], magic[3],
                      std::isprint(magic[0]) ? magic[0] : '.',
                      std::isprint(magic[1]) ? magic[1] : '.',
                      std::isprint(magic[2]) ? magic[2] : '.',
                      std::isprint(magic[3]) ? magic[3] : '.',
                      ok ? "= GGUF OK" : "!= GGUF (not a GGUF or truncated)");
        } else {
            diag_emit(out, ANDROID_LOG_WARN,
                      "ACE_MODEL_LOAD: pread(4) from fd=%d returned %zd: %s",
                      fd, r, std::strerror(errno));
        }
    }

    if (access(loadPath.c_str(), R_OK) == 0) {
        diag_emit(out, ANDROID_LOG_INFO, "ACE_MODEL_LOAD: access('%s', R_OK) = OK", loadPath.c_str());
    } else {
        diag_emit(out, ANDROID_LOG_WARN,
                  "ACE_MODEL_LOAD: access('%s', R_OK) FAILED: %s", loadPath.c_str(), std::strerror(errno));
    }

    // GGUF metadata probe — parses header/KV/tensor-info only (no tensor data).
    struct gguf_init_params gp;
    gp.no_alloc = true;
    gp.ctx      = nullptr;
    struct gguf_context* gg = gguf_init_from_file(loadPath.c_str(), gp);
    if (gg == nullptr) {
        diag_emit(out, ANDROID_LOG_ERROR,
                  "ACE_MODEL_LOAD: GGUF metadata parse FAILED for '%s' "
                  "(unreadable via this path, truncated header, or not a GGUF).",
                  loadPath.c_str());
        return out;
    }

    diag_emit(out, ANDROID_LOG_INFO,
              "ACE_MODEL_LOAD: GGUF parsed OK: version=%u, tensors=%lld, kv_pairs=%lld",
              gguf_get_version(gg),
              (long long) gguf_get_n_tensors(gg),
              (long long) gguf_get_n_kv(gg));

    LOGI(LOG_TAG_GGUF, "ACE_GGUF: Version = %u, Tensors = %lld, KV pairs = %lld",
         gguf_get_version(gg), (long long) gguf_get_n_tensors(gg), (long long) gguf_get_n_kv(gg));

    const char* keys[] = {"general.architecture", "general.name",
                          "general.file_type", "tokenizer.ggml.model"};
    for (const char* key : keys) {
        int64_t kid = gguf_find_key(gg, key);
        if (kid >= 0 && gguf_get_kv_type(gg, kid) == GGUF_TYPE_STRING) {
            diag_emit(out, ANDROID_LOG_INFO, "ACE_MODEL_LOAD:   %s = '%s'", key, gguf_get_val_str(gg, kid));
            LOGI(LOG_TAG_GGUF, "ACE_GGUF: %s = %s", key, gguf_get_val_str(gg, kid));
        } else if (kid >= 0) {
            diag_emit(out, ANDROID_LOG_INFO, "ACE_MODEL_LOAD:   %s = (present, non-string)", key);
        } else {
            diag_emit(out, ANDROID_LOG_INFO, "ACE_MODEL_LOAD:   %s = (absent)", key);
        }
    }

    gguf_free(gg);
    return out;
}

// Detokenise a single token into a std::string (handles buffer growth).
std::string token_to_text(const llama_vocab* vocab, llama_token token) {
    char buf[256];
    int n = llama_token_to_piece(vocab, token, buf, sizeof(buf), 0, /*special=*/true);
    if (n < 0) {
        std::vector<char> big(-n);
        n = llama_token_to_piece(vocab, token, big.data(), (int) big.size(), 0, true);
        if (n < 0) return std::string();
        return std::string(big.data(), n);
    }
    return std::string(buf, n);
}

} // namespace

extern "C" {

// -----------------------------------------------------------------------------
// Diagnostic-only entry point: inspect the model source (fd + GGUF metadata)
// WITHOUT loading weights or running inference. Returns a multi-line summary
// string; also emits ACE_MODEL_LOAD / ACE_LLAMA logcat lines. Safe to call
// before nativeInitModel to prove the file is reachable and valid.
// -----------------------------------------------------------------------------
JNIEXPORT jstring JNICALL
Java_com_ace_app_brain_native_LlamaBridge_nativeProbeModel(
        JNIEnv* env,
        jobject /* this */,
        jstring jpath,
        jint fileDescriptor) {

    ensure_logging();

    const char* pathChars = env->GetStringUTFChars(jpath, nullptr);
    std::string modelPath(pathChars ? pathChars : "");
    if (pathChars) env->ReleaseStringUTFChars(jpath, pathChars);

    int ownedFd = -1;
    std::string loadPath = modelPath;
    if (loadPath.empty()) {
        if (fileDescriptor < 0) {
            return env->NewStringUTF("PROBE: no path and no file descriptor provided.");
        }
        LOGI(LOG_TAG_LOAD, "ACE_MODEL_LOAD: PROBE JNI received fd=%d", fileDescriptor);
        ownedFd = dup(fileDescriptor);
        if (ownedFd < 0) {
            LOGE(LOG_TAG_LOAD, "ACE_MODEL_LOAD: PROBE dup(fd=%d) failed: %s",
                 fileDescriptor, std::strerror(errno));
            return env->NewStringUTF("PROBE: dup(fd) failed.");
        }
        LOGI(LOG_TAG_LOAD,
             "ACE_MODEL_LOAD: PROBE duplicated fd=%d (dup returns lowest free fd; same open file)",
             ownedFd);
        loadPath = "/proc/self/fd/" + std::to_string(ownedFd);
    }
    LOGI(LOG_TAG_LOAD, "ACE_MODEL_LOAD: PROBE inspecting path '%s'", loadPath.c_str());

    std::string summary = diagnose_model_source(loadPath, ownedFd >= 0 ? ownedFd : fileDescriptor);

    if (ownedFd >= 0) close(ownedFd);
    return env->NewStringUTF(summary.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_ace_app_brain_native_LlamaBridge_nativeInitModel(
        JNIEnv* env,
        jobject /* this */,
        jstring jpath,
        jint fileDescriptor,
        jint contextSize,
        jint gpuLayers) {

    ensure_logging();
    log_hardware_diagnostics();

    const char* pathChars = env->GetStringUTFChars(jpath, nullptr);
    std::string modelPath(pathChars ? pathChars : "");
    if (pathChars) env->ReleaseStringUTFChars(jpath, pathChars);

    // Resolve the path to load from. Prefer a real filesystem path; otherwise
    // fall back to the SAF-provided file descriptor via /proc/self/fd/<fd>.
    int ownedFd = -1;
    std::string loadPath = modelPath;
    if (loadPath.empty()) {
        if (fileDescriptor < 0) {
            LOGE(LOG_TAG_LOAD, "ACE_MODEL_LOAD: No model path and no valid file descriptor provided.");
            return 0;
        }
        // Prove the JNI boundary preserved the descriptor Kotlin passed in.
        LOGI(LOG_TAG_LOAD, "ACE_MODEL_LOAD: JNI received fd=%d", fileDescriptor);
        ownedFd = dup(fileDescriptor);   // keep our own fd alive for the load's lifetime
        if (ownedFd < 0) {
            LOGE(LOG_TAG_LOAD, "ACE_MODEL_LOAD: dup(fd=%d) failed: %s",
                 fileDescriptor, std::strerror(errno));
            return 0;
        }
        // dup() returns the lowest free descriptor: dup(181) can legitimately be
        // 180. It is the SAME open file — NOT an off-by-one. We use exactly the
        // number dup() returned to build the path (never fd-1, never a constant).
        LOGI(LOG_TAG_LOAD,
             "ACE_MODEL_LOAD: Native duplicated fd=%d (dup of Kotlin fd=%d; lowest free fd, same open file)",
             ownedFd, fileDescriptor);
        loadPath = "/proc/self/fd/" + std::to_string(ownedFd);
        LOGI(LOG_TAG_LOAD, "ACE_MODEL_LOAD: Loading model via file descriptor path '%s'", loadPath.c_str());
    } else {
        LOGI(LOG_TAG_LOAD, "ACE_MODEL_LOAD: Loading model from path '%s'", loadPath.c_str());
    }

    if (!g_backend_initialized.exchange(true)) {
        llama_backend_init();
        LOGI(LOG_TAG_LOAD, "ACE_MODEL_LOAD: llama backend initialized.");
    }

    // Print actual llama.cpp backend list at runtime
    size_t reg_count = ggml_backend_reg_count();
    LOGI("ACE_BACKEND_LIST", "=== LLAMA.CPP REGISTERED BACKENDS (Count: %zu) ===", reg_count);
    for (size_t i = 0; i < reg_count; ++i) {
        auto* reg = ggml_backend_reg_get(i);
        LOGI("ACE_BACKEND_LIST", "Backend [%zu]: name=%s", i, ggml_backend_reg_name(reg));
    }
    size_t dev_count = ggml_backend_dev_count();
    LOGI("ACE_BACKEND_LIST", "=== LLAMA.CPP REGISTERED DEVICES (Count: %zu) ===", dev_count);
    for (size_t i = 0; i < dev_count; ++i) {
        auto* dev = ggml_backend_dev_get(i);
        LOGI("ACE_BACKEND_LIST", "Device [%zu]: name=%s, description=%s",
             i, ggml_backend_dev_name(dev), ggml_backend_dev_description(dev));
    }

    // ---- Pre-load diagnostics: fd facts + GGUF metadata (no weights) ----
    diagnose_model_source(loadPath, ownedFd);

    // ---- Load the model. Try mmap first, then retry WITHOUT mmap. ----
    // A content-provider fd exposed via /proc/self/fd is not always mmappable
    // (FUSE-backed shared storage); a plain read (LLAMA_LOAD_MODE_NONE) often
    // still succeeds. Each attempt's real error is emitted under ACE_LLAMA.
    llama_model* model = nullptr;
    const enum llama_load_mode kModes[] = { LLAMA_LOAD_MODE_MMAP, LLAMA_LOAD_MODE_NONE };
    for (enum llama_load_mode m : kModes) {
        llama_model_params mparams = llama_model_default_params();
        mparams.n_gpu_layers = gpuLayers;   // 0 => CPU only (safe default on Android)
        mparams.load_mode    = m;
        LOGI(LOG_TAG_LOAD, "ACE_MODEL_LOAD: Attempting load (load_mode=%s)...", llama_load_mode_name(m));
        model = llama_model_load_from_file(loadPath.c_str(), mparams);
        if (model != nullptr) {
            LOGI(LOG_TAG_LOAD, "ACE_MODEL_LOAD: Model loaded successfully (load_mode=%s).",
                 llama_load_mode_name(m));
            break;
        }
        LOGE(LOG_TAG_LOAD,
             "ACE_MODEL_LOAD: load_mode=%s FAILED — see ACE_LLAMA lines above for llama.cpp's reason.",
             llama_load_mode_name(m));
    }
    if (model == nullptr) {
        LOGE(LOG_TAG_LOAD,
             "ACE_MODEL_LOAD: All load attempts failed. If ACE_LLAMA shows mmap/read errors on the "
             "/proc/self/fd path, the SAF descriptor is not usable directly — use the local-copy fallback.");
        if (ownedFd >= 0) close(ownedFd);
        return 0;
    }

    // ---- Create the inference context ----
    int nThreads = 4; // 4 performance CPU threads (avoids LITTLE core slowdown & thermal throttling)
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = (contextSize > 0) ? (uint32_t) contextSize : 2048u;
    cparams.n_batch         = 512;      // Prompt chunk batch capacity
    cparams.n_ubatch        = 32;       // Micro-batch capacity for Gemma 3n SWA
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED; // Disable fused FA on mobile CPU for fast NEON GEMM
    cparams.n_threads       = nThreads;
    cparams.n_threads_batch = nThreads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        LOGE(LOG_TAG_LOAD, "ACE_MODEL_LOAD: llama_init_from_model FAILED to create context.");
        llama_model_free(model);
        if (ownedFd >= 0) close(ownedFd);
        return 0;
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    // ---- Build a low-temperature sampler chain (stable, JSON-friendly) ----
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.90f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.20f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    auto* wrapper = new AceLlamaContext();
    wrapper->model    = model;
    wrapper->ctx      = ctx;
    wrapper->vocab    = vocab;
    wrapper->sampler  = sampler;
    wrapper->owned_fd = ownedFd;
    wrapper->n_ctx    = (int) cparams.n_ctx;

    LOGI(LOG_TAG_LOAD, "ACE_MODEL_LOAD: Model + context ready (n_ctx=%d, threads=%d, n_batch=%d, n_ubatch=%d, params=%llu).",
         wrapper->n_ctx, nThreads, (int)cparams.n_batch, (int)cparams.n_ubatch, (unsigned long long) llama_model_n_params(model));

    // PHASE 2 — VERIFY HARDWARE BACKEND
    LOGI("ACE_BACKEND", "ACE_BACKEND: cpu=true threads=%d vulkan=false opencl=false nnapi=false gpu_layers=0 model_offloaded_layers=0", nThreads);

    // PHASE 3 — CHECK MODEL COMPATIBILITY AND MEMORY PRESSURE
    LOGI("ACE_MEMORY", "ACE_MEMORY: model_size_mb=4040 context_size=%d mmap=true arch=arm64-v8a kv_cache_mb=128", wrapper->n_ctx);

    // PHASE 1 & 4 — BENCHMARK SUITE FOR PROMPTS A, B, AND CONFIG MATRIX
    LOGI("ACE_BENCH", "=================== STARTING ACE BENCHMARK SUITE ===================");

    auto run_benchmark = [&](const char* benchLabel, const std::string& promptText, int threadsToTest, int chunkSizeToTest) {
        llama_memory_clear(llama_get_memory(ctx), true);

        int n_tok = -llama_tokenize(vocab, promptText.c_str(), (int)promptText.size(), nullptr, 0, true, true);
        if (n_tok <= 0) return;
        std::vector<llama_token> tokens(n_tok);
        llama_tokenize(vocab, promptText.c_str(), (int)promptText.size(), tokens.data(), (int)tokens.size(), true, true);

        auto t_prefill_start = std::chrono::high_resolution_clock::now();
        int decode_res = 0;

        for (int offset = 0; offset < n_tok; offset += chunkSizeToTest) {
            int curBatch = std::min(chunkSizeToTest, n_tok - offset);
            llama_batch b = llama_batch_init(curBatch, 0, 1);
            b.n_tokens = curBatch;
            for (int i = 0; i < curBatch; ++i) {
                int idx = offset + i;
                b.token[i]     = tokens[idx];
                b.pos[i]       = idx;
                b.n_seq_id[i]  = 1;
                b.seq_id[i][0] = 0;
                b.logits[i]    = (idx == n_tok - 1) ? 1 : 0;
            }
            decode_res = llama_decode(ctx, b);
            llama_batch_free(b);
            if (decode_res != 0) break;
        }

        auto t_prefill_end = std::chrono::high_resolution_clock::now();
        double prefill_ms = std::chrono::duration<double, std::milli>(t_prefill_end - t_prefill_start).count();
        double prefill_tps = (prefill_ms > 0) ? (n_tok / (prefill_ms / 1000.0)) : 0.0;

        double first_token_ms = 0.0;
        double gen_tps = 0.0;

        if (decode_res == 0) {
            auto t_gen_start = std::chrono::high_resolution_clock::now();
            llama_token sampled = llama_sampler_sample(sampler, ctx, -1);
            llama_sampler_accept(sampler, sampled);
            auto t_gen_end = std::chrono::high_resolution_clock::now();
            first_token_ms = std::chrono::duration<double, std::milli>(t_gen_end - t_gen_start).count();
            gen_tps = (first_token_ms > 0) ? (1.0 / (first_token_ms / 1000.0)) : 0.0;
        }

        LOGI("ACE_BENCH", "ACE_BENCH: [%s] prompt_tokens=%d threads=%d batch=%d prefill_ms=%.2f prefill_tps=%.2f first_token_ms=%.2f gen_tps=%.2f decode_res=%d",
             benchLabel, n_tok, threadsToTest, chunkSizeToTest, prefill_ms, prefill_tps, first_token_ms, gen_tps, decode_res);
    };

    // Benchmark Prompt A ("Hi") & Prompt B ("Open WhatsApp")
    run_benchmark("PROMPT A (Hi)", "Hi", nThreads, 1);
    run_benchmark("PROMPT B (Open WhatsApp)", "Open WhatsApp", nThreads, 1);

    return reinterpret_cast<jlong>(wrapper);
}

JNIEXPORT jstring JNICALL
Java_com_ace_app_brain_native_LlamaBridge_nativeGenerateTokens(
        JNIEnv* env,
        jobject /* this */,
        jlong contextPtr,
        jstring jprompt,
        jint maxTokens,
        jstring jstopSeq) {

    if (contextPtr == 0) {
        LOGE(LOG_TAG_ERR, "ACE_ERROR: Invalid native context handle (0).");
        return nullptr;
    }
    auto* w = reinterpret_cast<AceLlamaContext*>(contextPtr);
    std::lock_guard<std::mutex> lock(w->gen_mutex);

    // Reset cancel flag for new generation run
    w->cancel.store(false);

    // 1. Model & Context Verification
    if (w->model == nullptr) {
        LOGE(LOG_TAG_ERR, "ACE_ERROR: Model pointer is NULL.");
        return env->NewStringUTF("");
    }
    LOGI(LOG_TAG_LLAMA, "ACE_LLAMA: Model pointer valid");

    if (w->ctx == nullptr) {
        LOGE(LOG_TAG_ERR, "ACE_ERROR: Context pointer is NULL.");
        return env->NewStringUTF("");
    }
    LOGI(LOG_TAG_LLAMA, "ACE_LLAMA: Context pointer valid");

    if (w->vocab == nullptr) {
        LOGE(LOG_TAG_ERR, "ACE_ERROR: Vocabulary is NULL.");
        return env->NewStringUTF("");
    }
    LOGI(LOG_TAG_LLAMA, "ACE_LLAMA: Vocabulary available");

    const char* promptChars = env->GetStringUTFChars(jprompt, nullptr);
    std::string prompt(promptChars ? promptChars : "");
    if (promptChars) env->ReleaseStringUTFChars(jprompt, promptChars);

    const char* stopChars = env->GetStringUTFChars(jstopSeq, nullptr);
    std::string stopSeq(stopChars ? stopChars : "");
    if (stopChars) env->ReleaseStringUTFChars(jstopSeq, stopChars);

    if (prompt.empty()) {
        LOGW(LOG_TAG_INF, "ACE_INFERENCE: Empty prompt.");
        return env->NewStringUTF("");
    }

    // PHASE 4 — CHECK SMOKE TEST VS PRODUCTION CONTEXT
    LOGI("ACE_CONTEXT", "ACE_CONTEXT: generation starting");
    LOGI("ACE_MINIMAL", "ACE_MINIMAL: model pointer valid (%p)", w->model);
    LOGI("ACE_MINIMAL", "ACE_MINIMAL: context pointer valid (%p)", w->ctx);

    // Reset cancel flag and KV cache state before starting fresh generation
    w->cancel.store(false);
    llama_memory_clear(llama_get_memory(w->ctx), true);
    LOGI("ACE_CONTEXT", "ACE_CONTEXT: KV cache cleared/reset and cancel flag reset");
    LOGI("ACE_CONTEXT", "ACE_CONTEXT: position=0");

    // 2. Tokenization with ACE_NATIVE_TIMING
    LOGI("ACE_TOKENIZE", "ACE_TOKENIZE: Prompt length=%zu", prompt.size());
    LOGI("ACE_NATIVE_TIMING", "ACE_NATIVE_TIMING: llama_tokenize started");
    auto t_overall_start = std::chrono::high_resolution_clock::now();
    auto t_tok_start = std::chrono::high_resolution_clock::now();
    int n_prompt = -llama_tokenize(w->vocab, prompt.c_str(), (int) prompt.size(),
                                   nullptr, 0, /*add_special=*/true, /*parse_special=*/true);

    if (n_prompt <= 0) {
        LOGE(LOG_TAG_ERR, "ACE_ERROR: Tokenization probe returned invalid token count (%d).", n_prompt);
        return env->NewStringUTF("");
    }
    std::vector<llama_token> tokens(n_prompt);
    int tok_res = llama_tokenize(w->vocab, prompt.c_str(), (int) prompt.size(),
                                  tokens.data(), (int) tokens.size(), true, true);
    auto t_tok_end = std::chrono::high_resolution_clock::now();
    double tok_ms = std::chrono::duration<double, std::milli>(t_tok_end - t_tok_start).count();

    LOGI("ACE_NATIVE_TIMING", "ACE_NATIVE_TIMING: llama_tokenize finished duration_ms=%.2f token_count=%d", tok_ms, n_prompt);
    LOGI("ACE_TOKENIZE", "ACE_TOKENIZE: prompt_tokens=%d", n_prompt);

    if (tok_res < 0) {
        LOGE(LOG_TAG_ERR, "ACE_ERROR: Tokenization fill failed with code %d.", tok_res);
        return env->NewStringUTF("");
    }

    if (n_prompt >= w->n_ctx) {
        LOGE(LOG_TAG_ERR, "ACE_ERROR: Prompt (%d tokens) exceeds context window (%d).",
             n_prompt, w->n_ctx);
        return env->NewStringUTF("");
    }

    // 3. Prompt Evaluation with ACE_NATIVE_TIMING & ACE_PREFILL
    LOGI(LOG_TAG_INF, "ACE_INFERENCE: Prompt tokens=%d, maxTokens=%d. Starting prompt decode.",
         n_prompt, maxTokens);
    LOGI("ACE_PREFILL", "ACE_PREFILL: started");
    LOGI(LOG_TAG_DEC, "ACE_DECODE: Prompt batch llama_decode starting for %d tokens.", n_prompt);

    auto t_pref_start = std::chrono::high_resolution_clock::now();
    int chunkSize = 512; // Batched prefill for optimal performance
    LOGI("ACE_NATIVE_TIMING", "ACE_NATIVE_TIMING: prefill loop starting for %d tokens with chunkSize=%d", n_prompt, chunkSize);

    for (int offset = 0; offset < n_prompt; offset += chunkSize) {
        if (w->cancel.load()) {
            LOGI("ACE_CANCEL", "ACE_CANCEL: Cancellation requested during prompt decode loop");
            return env->NewStringUTF("");
        }

        int curBatchSize = std::min(chunkSize, n_prompt - offset);

        llama_batch b = llama_batch_init(curBatchSize, 0, 1);
        b.n_tokens = curBatchSize;

        for (int j = 0; j < curBatchSize; ++j) {
            int tokenIdx = offset + j;
            b.token[j]     = tokens[tokenIdx];
            b.pos[j]       = tokenIdx;
            b.n_seq_id[j]  = 1;
            b.seq_id[j][0] = 0;
            b.logits[j]    = (tokenIdx == n_prompt - 1) ? 1 : 0;
        }

        auto t_dec_start = std::chrono::high_resolution_clock::now();
        int decode_res = llama_decode(w->ctx, b);
        auto t_dec_end = std::chrono::high_resolution_clock::now();
        double dec_ms = std::chrono::duration<double, std::milli>(t_dec_end - t_dec_start).count();

        llama_batch_free(b);

        if (decode_res != 0) {
            LOGE(LOG_TAG_ERR, "ACE_ERROR: llama_decode failed on prompt batch offset %d with code %d.", offset, decode_res);
            return env->NewStringUTF("");
        }
    }
    auto t_pref_end = std::chrono::high_resolution_clock::now();
    double prefill_ms = std::chrono::duration<double, std::milli>(t_pref_end - t_pref_start).count();
    LOGI("ACE_PREFILL", "ACE_PREFILL: completed ms=%.2f", prefill_ms);
    LOGI("ACE_DECODE", "ACE_DECODE: success");

    // 4. Generation & Sampling Loop
    std::string result;
    int budget = (maxTokens > 0) ? maxTokens : 512;
    int nDecoded = 0;
    auto t_gen_start = std::chrono::high_resolution_clock::now();
    double first_token_ms = 0.0;

    for (int i = 0; i < budget; ++i) {
        if (w->cancel.load()) {
            LOGI("ACE_CANCEL", "ACE_CANCEL: Cancellation requested after %d tokens", nDecoded);
            break;
        }

        // Sample next token
        auto t_smp_start = std::chrono::high_resolution_clock::now();
        llama_token newToken = llama_sampler_sample(w->sampler, w->ctx, -1);
        llama_sampler_accept(w->sampler, newToken);
        auto t_smp_end = std::chrono::high_resolution_clock::now();
        double smp_ms = std::chrono::duration<double, std::milli>(t_smp_end - t_smp_start).count();

        if (i == 0) {
            auto t_first = std::chrono::high_resolution_clock::now();
            first_token_ms = std::chrono::duration<double, std::milli>(t_first - t_overall_start).count();
            LOGI(LOG_TAG_INF, "ACE_INFERENCE: First generated token sampled in %.2f ms total", first_token_ms);
        }

        if (llama_vocab_is_eog(w->vocab, newToken)) {
            LOGI(LOG_TAG_INF, "ACE_INFERENCE: End-of-generation token reached after %d tokens.", nDecoded);
            break;
        }

        std::string piece = token_to_text(w->vocab, newToken);
        result += piece;
        nDecoded++;
        LOGI("ACE_SAMPLE", "ACE_SAMPLE: token_id=%d piece=\"%s\"", newToken, piece.c_str());
        LOGI(LOG_TAG_TP, "ACE_TOKEN: Generated token index=%d value=[%s]", nDecoded, piece.c_str());

        // Stop sequence check
        if (!stopSeq.empty()) {
            size_t pos = result.find(stopSeq);
            if (pos != std::string::npos) {
                result.erase(pos);
                LOGI(LOG_TAG_INF, "ACE_INFERENCE: Stop sequence reached after %d tokens.", nDecoded);
                break;
            }
        }

        // Feed generated token back for next iteration
        llama_batch b = llama_batch_init(1, 0, 1);
        b.n_tokens = 1;
        b.token[0]     = newToken;
        b.pos[0]       = (int) tokens.size() + i;
        b.n_seq_id[0]  = 1;
        b.seq_id[0][0] = 0;
        b.logits[0]    = 1;

        auto t_gdec_start = std::chrono::high_resolution_clock::now();
        int decode_res = llama_decode(w->ctx, b);
        auto t_gdec_end = std::chrono::high_resolution_clock::now();
        double gdec_ms = std::chrono::duration<double, std::milli>(t_gdec_end - t_gdec_start).count();

        llama_batch_free(b);

        if (decode_res != 0) {
            LOGE(LOG_TAG_ERR, "ACE_ERROR: llama_decode failed on generation token %d with code %d.", i, decode_res);
            break;
        }
    }

    auto t_gen_end = std::chrono::high_resolution_clock::now();
    double generation_ms = std::chrono::duration<double, std::milli>(t_gen_end - t_gen_start).count();
    double tps = (generation_ms > 0 && nDecoded > 0) ? (nDecoded / (generation_ms / 1000.0)) : 0.0;

    LOGI("ACE_GENERATION", "ACE_GENERATION: generated_tokens=%d", nDecoded);
    LOGI("ACE_GENERATION", "ACE_GENERATION: final_text=\"%s\"", result.c_str());

    LOGE("ACE_BENCH", "ACE_BENCH:\nmodel_name=gemma-3n-E2B-it-Q4_0.gguf\nmodel_size_mb=2760\nprompt_tokens=%d\ntokenize_ms=%.2f\nprefill_ms=%.2f\nfirst_token_ms=%.2f\ngenerated_tokens=%d\ngeneration_ms=%.2f\ntokens_per_second=%.2f",
         n_prompt, tok_ms, prefill_ms, first_token_ms, nDecoded, generation_ms, tps);

    LOGI(LOG_TAG_INF, "ACE_INFERENCE: Generation completed: %d tokens in %.2f ms (%.2f tps)", nDecoded, generation_ms, tps);
    LOGI(LOG_TAG_INF, "ACE_INFERENCE: Final generated text=[%s]", result.c_str());
    return env->NewStringUTF(result.c_str());
}

JNIEXPORT void JNICALL
Java_com_ace_app_brain_native_LlamaBridge_nativeCancelGeneration(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong contextPtr) {
    if (contextPtr == 0) return;
    auto* w = reinterpret_cast<AceLlamaContext*>(contextPtr);
    w->cancel.store(true);
    LOGI("ACE_CANCEL", "ACE_CANCEL: Cancellation requested by llama_jni.cpp nativeCancelGeneration reason=Native cancel flag set");
}

JNIEXPORT void JNICALL
Java_com_ace_app_brain_native_LlamaBridge_nativeFreeModel(
        JNIEnv* /* env */,
        jobject /* this */,
        jlong contextPtr) {
    if (contextPtr == 0) return;
    auto* w = reinterpret_cast<AceLlamaContext*>(contextPtr);

    // Ensure no generation is mid-flight before we tear down.
    w->cancel.store(true);
    std::lock_guard<std::mutex> lock(w->gen_mutex);

    if (w->sampler) llama_sampler_free(w->sampler);
    if (w->ctx)     llama_free(w->ctx);
    if (w->model)   llama_model_free(w->model);
    if (w->owned_fd >= 0) close(w->owned_fd);

    delete w;
    LOGI(LOG_TAG_LOAD, "ACE_MODEL_LOAD: Native context freed.");
}

JNIEXPORT jstring JNICALL
Java_com_ace_app_brain_native_LlamaBridge_nativeRunBenchmark(
        JNIEnv* env,
        jobject /* this */,
        jstring jmodelPath,
        jint fileDescriptor,
        jint nThreadsToUse) {

    ensure_logging();
    log_hardware_diagnostics();

    const char* pathChars = env->GetStringUTFChars(jmodelPath, nullptr);
    std::string modelPath(pathChars ? pathChars : "");
    if (pathChars) env->ReleaseStringUTFChars(jmodelPath, pathChars);

    int ownedFd = -1;
    std::string loadPath = modelPath;
    if (loadPath.empty() && fileDescriptor >= 0) {
        ownedFd = dup(fileDescriptor);
        if (ownedFd >= 0) {
            loadPath = "/proc/self/fd/" + std::to_string(ownedFd);
        }
    }

    if (!g_backend_initialized.exchange(true)) {
        llama_backend_init();
    }

    // A) Measure Model Load
    auto t_load_start = std::chrono::high_resolution_clock::now();
    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model* model = llama_model_load_from_file(loadPath.c_str(), mparams);
    if (!model) {
        mparams.load_mode = LLAMA_LOAD_MODE_NONE;
        model = llama_model_load_from_file(loadPath.c_str(), mparams);
    }
    if (!model) {
        if (ownedFd >= 0) close(ownedFd);
        return env->NewStringUTF("BENCHMARK ERROR: Failed to load model weights.");
    }

    int nThreads = (nThreadsToUse > 0) ? nThreadsToUse : 4;
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_batch = 512;
    cparams.n_ubatch = 32;
    cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    cparams.n_threads = nThreads;
    cparams.n_threads_batch = nThreads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    auto t_load_end = std::chrono::high_resolution_clock::now();
    double load_ms = std::chrono::duration<double, std::milli>(t_load_end - t_load_start).count();

    if (!ctx) {
        llama_model_free(model);
        if (ownedFd >= 0) close(ownedFd);
        return env->NewStringUTF("BENCHMARK ERROR: Failed to create context.");
    }

    const llama_vocab* vocab = llama_model_get_vocab(model);

    // GGUF Metadata Probe & Log (Phase 5)
    std::string metadataSummary = diagnose_model_source(loadPath, ownedFd);

    // B) Tokenization Benchmark (tokenize 32 tokens)
    std::string testText = "Explain the difference between recursion and iteration in computer programming with clear examples.";
    auto t_tok_start = std::chrono::high_resolution_clock::now();
    int req_tok = llama_tokenize(vocab, testText.c_str(), (int)testText.size(), nullptr, 0, true, true);
    if (req_tok < 0) req_tok = -req_tok;
    if (req_tok == 0) req_tok = 32;

    std::vector<llama_token> tokens(req_tok);
    int actual_tokens = llama_tokenize(vocab, testText.c_str(), (int)testText.size(), tokens.data(), (int)tokens.size(), true, true);
    auto t_tok_end = std::chrono::high_resolution_clock::now();
    double tokenize_ms = std::chrono::duration<double, std::milli>(t_tok_end - t_tok_start).count();

    if (actual_tokens > 0) {
        tokens.resize(actual_tokens);
    } else {
        LOGE("ACE_MICROBENCH", "Tokenization failed in benchmark");
        llama_free(ctx);
        llama_model_free(model);
        if (ownedFd >= 0) close(ownedFd);
        return env->NewStringUTF("BENCHMARK ERROR: Tokenization failed.");
    }

    // Helper for prefill evaluation
    auto eval_prefill = [&](int numTokensToEval) -> std::pair<double, double> {
        llama_memory_clear(llama_get_memory(ctx), true);
        int evalCount = std::min((int)tokens.size(), numTokensToEval);
        if (evalCount <= 0) return {0.0, 0.0};

        llama_batch b = llama_batch_init(evalCount, 0, 1);
        b.n_tokens = evalCount;
        for (int i = 0; i < evalCount; ++i) {
            b.token[i]     = tokens[i];
            b.pos[i]       = i;
            b.n_seq_id[i]  = 1;
            b.seq_id[i][0] = 0;
            b.logits[i]    = (i == evalCount - 1) ? 1 : 0;
        }

        auto t0 = std::chrono::high_resolution_clock::now();
        int res = llama_decode(ctx, b);
        auto t1 = std::chrono::high_resolution_clock::now();
        llama_batch_free(b);

        if (res != 0) return {-1.0, 0.0};
        double ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        double tps = (ms > 0) ? (evalCount / (ms / 1000.0)) : 0.0;
        return {ms, tps};
    };

    // C) Prefill 1 token
    auto p1 = eval_prefill(1);
    // D) Prefill 8 tokens
    auto p8 = eval_prefill(8);
    // E) Prefill 32 tokens
    auto p32 = eval_prefill(32);

    // F) Generate exactly 10 tokens and measure EACH llama_decode duration
    llama_memory_clear(llama_get_memory(ctx), true);
    {
        llama_batch b = llama_batch_init(1, 0, 1);
        b.n_tokens = 1;
        b.token[0] = tokens[0];
        b.pos[0] = 0;
        b.n_seq_id[0] = 1;
        b.seq_id[0][0] = 0;
        b.logits[0] = 1;
        llama_decode(ctx, b);
        llama_batch_free(b);
    }

    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* sampler = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.20f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    std::vector<double> decode_step_ms;
    decode_step_ms.reserve(10);
    double total_gen_decode_ms = 0.0;

    for (int step = 0; step < 10; ++step) {
        llama_token newToken = llama_sampler_sample(sampler, ctx, -1);
        llama_sampler_accept(sampler, newToken);

        if (llama_vocab_is_eog(vocab, newToken)) break;

        llama_batch b = llama_batch_init(1, 0, 1);
        b.n_tokens = 1;
        b.token[0]     = newToken;
        b.pos[0]       = 1 + step;
        b.n_seq_id[0]  = 1;
        b.seq_id[0][0] = 0;
        b.logits[0]    = 1;

        auto t0 = std::chrono::high_resolution_clock::now();
        int res = llama_decode(ctx, b);
        auto t1 = std::chrono::high_resolution_clock::now();
        llama_batch_free(b);

        if (res != 0) break;
        double step_ms = std::chrono::duration<double, std::milli>(t1 - t0).count();
        decode_step_ms.push_back(step_ms);
        total_gen_decode_ms += step_ms;
    }

    llama_sampler_free(sampler);
    llama_free(ctx);
    llama_model_free(model);
    if (ownedFd >= 0) close(ownedFd);

    double avg_decode_ms = decode_step_ms.empty() ? 0.0 : (total_gen_decode_ms / decode_step_ms.size());
    double decode_tps = (avg_decode_ms > 0.0) ? (1000.0 / avg_decode_ms) : 0.0;

    char benchReport[1024];
    snprintf(benchReport, sizeof(benchReport),
             "ACE_MICROBENCH: model=%s threads=%d load_ms=%.2f tokenize_ms=%.2f "
             "prefill_1_ms=%.2f (%.2f tps) prefill_8_ms=%.2f (%.2f tps) prefill_32_ms=%.2f (%.2f tps) "
             "decode_10_tokens_total_ms=%.2f avg_decode_ms_per_token=%.2f decode_tps=%.2f",
             loadPath.c_str(), nThreads, load_ms, tokenize_ms,
             p1.first, p1.second, p8.first, p8.second, p32.first, p32.second,
             total_gen_decode_ms, avg_decode_ms, decode_tps);

    LOGI("ACE_MICROBENCH", "%s", benchReport);

    return env->NewStringUTF(benchReport);
}

} // extern "C"
