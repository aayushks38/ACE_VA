package com.ace.app.brain.native

import android.util.Log

class LlamaBridge {

    companion object {
        private const val TAG = "ACE_MODEL_LOAD"
        private var isLibraryLoaded = false

        init {
            try {
                System.loadLibrary("llama_jni")
                isLibraryLoaded = true
                Log.i(TAG, "ACE_MODEL_LOAD: Native llama_jni library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                isLibraryLoaded = false
                Log.w(TAG, "ACE_MODEL_LOAD: Native llama_jni library binary not found: ${e.message}")
            } catch (e: Throwable) {
                isLibraryLoaded = false
                Log.e(TAG, "ACE_MODEL_LOAD: Failed to load native llama_jni: ${e.message}")
            }
        }

        fun isAvailable(): Boolean = isLibraryLoaded
    }

    private var nativeContextPtr: Long = 0L

    external fun nativeInitModel(modelPath: String, fileDescriptor: Int, contextSize: Int, gpuLayers: Int): Long
    external fun nativeGenerateTokens(contextPtr: Long, prompt: String, maxTokens: Int, stopSequence: String): String?
    external fun nativeCancelGeneration(contextPtr: Long)
    external fun nativeFreeModel(contextPtr: Long)
    external fun nativeRunBenchmark(modelPath: String, fileDescriptor: Int, threads: Int): String?

    fun runBenchmark(path: String, fileDescriptor: Int = -1, threads: Int = 4): String? {
        if (!isAvailable()) return null
        return try {
            nativeRunBenchmark(path, fileDescriptor, threads)
        } catch (e: Throwable) {
            Log.e(TAG, "ACE_MICROBENCH: Benchmark error: ${e.message}", e)
            null
        }
    }

    fun initModel(path: String?, fileDescriptor: Int = -1, contextSize: Int = 2048, gpuLayers: Int = 0): Boolean {
        if (!isAvailable()) {
            Log.w(TAG, "ACE_MODEL_LOAD: Native llama_jni is not available in environment.")
            return false
        }
        return try {
            Log.i(TAG, "ACE_MODEL_LOAD: Initializing native model (path=${path ?: "FD"}, fd=$fileDescriptor, ctx=$contextSize)")
            nativeContextPtr = nativeInitModel(path.orEmpty(), fileDescriptor, contextSize, gpuLayers)
            val success = nativeContextPtr != 0L
            if (success) {
                Log.i(TAG, "ACE_MODEL_LOAD: GGUF runtime initialized successfully. Context handle: $nativeContextPtr")
            } else {
                Log.e(TAG, "ACE_MODEL_LOAD: Native model initialization returned null pointer.")
            }
            success
        } catch (e: Throwable) {
            Log.e(TAG, "ACE_MODEL_LOAD: Native model initialization failed: ${e.message}", e)
            false
        }
    }

    fun generate(prompt: String, maxTokens: Int = 768, stopSeq: String = "<end_of_turn>"): String? {
        if (!isAvailable() || nativeContextPtr == 0L) {
            return null
        }
        return try {
            Log.i("ACE_INFERENCE", "ACE_INFERENCE: Prompt submitted to GGUF runtime")
            val output = nativeGenerateTokens(nativeContextPtr, prompt, maxTokens, stopSeq)
            if (output != null) {
                Log.i("ACE_INFERENCE", "ACE_INFERENCE: Generated tokens = $output")
            } else {
                Log.w("ACE_INFERENCE", "ACE_INFERENCE: Native token generation returned null")
            }
            output
        } catch (e: Throwable) {
            Log.e("ACE_INFERENCE", "ACE_INFERENCE: Generation error: ${e.message}", e)
            null
        }
    }

    fun cancel() {
        if (isAvailable() && nativeContextPtr != 0L) {
            try {
                Log.i("ACE_CANCEL", "ACE_CANCEL: Cancellation requested by LlamaBridge.cancel reason=Native cancel bridge invoked")
                Log.i("ACE_INFERENCE", "ACE_INFERENCE: Requesting native token generation cancellation")
                nativeCancelGeneration(nativeContextPtr)
            } catch (e: Throwable) {
                Log.e("ACE_ERROR", "ACE_ERROR: Native cancel error: ${e.message}", e)
            }
        }
    }

    fun release() {
        if (isAvailable() && nativeContextPtr != 0L) {
            try {
                Log.i(TAG, "ACE_MODEL_LOAD: Releasing native GGUF context handle")
                nativeFreeModel(nativeContextPtr)
                nativeContextPtr = 0L
            } catch (e: Throwable) {
                Log.e(TAG, "ACE_MODEL_LOAD: Native release error: ${e.message}", e)
            }
        }
    }
}
