package com.example.looma.data.assistant.firebase

import com.example.looma.data.assistant.InferenceRoute

internal object HybridRoutingPolicy {
    const val CLOUD_FALLBACK_MODEL = "gemini-4-hydrogen"
    const val ON_DEVICE_CHAR_BUDGET = 10_000
    const val CLOUD_CHAR_BUDGET = 24_000

    fun resolveEffectiveRoute(
        requested: InferenceRoute?,
        multiStep: Boolean,
        promptChars: Int,
    ): InferenceRoute = when (requested) {
        InferenceRoute.OnDeviceOnly -> InferenceRoute.OnDeviceOnly
        InferenceRoute.CloudOnly -> InferenceRoute.CloudOnly
        InferenceRoute.PreferCloud -> InferenceRoute.PreferCloud
        InferenceRoute.PreferOnDevice, null ->
            if (multiStep || promptChars > ON_DEVICE_CHAR_BUDGET) {
                InferenceRoute.PreferCloud
            } else {
                InferenceRoute.PreferOnDevice
            }
    }

    fun contextBudgetFor(route: InferenceRoute?, multiStep: Boolean): Int = when (route) {
        InferenceRoute.OnDeviceOnly -> ON_DEVICE_CHAR_BUDGET
        InferenceRoute.CloudOnly, InferenceRoute.PreferCloud -> CLOUD_CHAR_BUDGET
        InferenceRoute.PreferOnDevice, null ->
            if (multiStep) CLOUD_CHAR_BUDGET else ON_DEVICE_CHAR_BUDGET
    }
}
