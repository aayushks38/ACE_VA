package com.ace.app.instant

data class InstantResult(
    val isHandled: Boolean,
    val capabilityId: String,
    val message: String,
    val source: String,
    val outputData: Map<String, String> = emptyMap(),
    val error: String? = null
)
