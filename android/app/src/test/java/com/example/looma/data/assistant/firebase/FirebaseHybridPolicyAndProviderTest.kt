package com.example.looma.data.assistant.firebase

import com.example.looma.data.assistant.AnswerModel
import com.example.looma.data.assistant.InferenceOrigin
import com.example.looma.data.assistant.InferenceRoute
import com.example.looma.data.assistant.ModelSource
import com.example.looma.data.assistant.ThinkingEffort
import com.google.firebase.ai.InferenceSource
import com.google.firebase.ai.type.PublicPreviewAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(PublicPreviewAPI::class)
class FirebaseHybridPolicyAndProviderTest {

    @Test
    fun cloudFallbackModelIsGemini25Flash() {
        assertEquals("gemini-2.5-flash", HybridRoutingPolicy.CLOUD_FALLBACK_MODEL)
    }

    @Test
    fun evaluateFallbackReportsReasonsAndNonFallbackCases() {
        assertEquals(
            true to "context_limit",
            HybridRoutingPolicy.evaluateFallback(
                requested = InferenceRoute.PreferOnDevice,
                effective = InferenceRoute.PreferCloud,
                actualOrigin = InferenceOrigin.Cloud,
                multiStep = false,
                promptChars = HybridRoutingPolicy.ON_DEVICE_CHAR_BUDGET + 1,
            ),
        )
        assertEquals(
            true to "multi_step",
            HybridRoutingPolicy.evaluateFallback(
                requested = InferenceRoute.PreferOnDevice,
                effective = InferenceRoute.PreferCloud,
                actualOrigin = InferenceOrigin.Cloud,
                multiStep = true,
                promptChars = 100,
            ),
        )
        assertEquals(
            true to "on_device_fallback",
            HybridRoutingPolicy.evaluateFallback(
                requested = InferenceRoute.PreferOnDevice,
                effective = InferenceRoute.PreferOnDevice,
                actualOrigin = InferenceOrigin.Cloud,
                multiStep = false,
                promptChars = 100,
            ),
        )
        assertEquals(
            true to "cloud_fallback",
            HybridRoutingPolicy.evaluateFallback(
                requested = InferenceRoute.PreferCloud,
                effective = InferenceRoute.PreferCloud,
                actualOrigin = InferenceOrigin.OnDevice,
                multiStep = false,
                promptChars = 100,
            ),
        )
        assertEquals(
            false to null,
            HybridRoutingPolicy.evaluateFallback(
                requested = InferenceRoute.PreferOnDevice,
                effective = InferenceRoute.PreferOnDevice,
                actualOrigin = InferenceOrigin.OnDevice,
                multiStep = false,
                promptChars = 100,
            ),
        )
        assertEquals(
            false to null,
            HybridRoutingPolicy.evaluateFallback(
                requested = InferenceRoute.CloudOnly,
                effective = InferenceRoute.CloudOnly,
                actualOrigin = InferenceOrigin.Cloud,
                multiStep = false,
                promptChars = 100,
            ),
        )
    }

    @Test
    fun nullInferenceSourceUsesDefaultOriginInsteadOfAlwaysCloud() {
        val nullSource: InferenceSource? = null
        assertEquals(InferenceOrigin.OnDevice, nullSource.toOrigin(InferenceOrigin.OnDevice))
        assertEquals(InferenceOrigin.Cloud, nullSource.toOrigin(InferenceOrigin.Cloud))
        assertEquals(InferenceOrigin.OnDevice, InferenceSource.ON_DEVICE.toOrigin(InferenceOrigin.Cloud))
        assertEquals(InferenceOrigin.Cloud, InferenceSource.IN_CLOUD.toOrigin(InferenceOrigin.OnDevice))
    }

    @Test
    fun unconfiguredProviderExposesNoModelsAndNullBackend() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val provider = FirebaseHybridModelProvider(firebaseConfigured = false, scope = scope)
            assertTrue(provider.models.value.isEmpty())

            provider.refresh()
            assertTrue(provider.models.value.isEmpty())

            val dummyModel = AnswerModel(
                id = FirebaseHybridModelProvider.MODEL_ID,
                source = ModelSource.Hybrid,
                efforts = ThinkingEffort.entries,
                defaultEffort = ThinkingEffort.Off,
            )
            assertNull(provider.backend(dummyModel))
        } finally {
            scope.cancel()
        }
    }
}
