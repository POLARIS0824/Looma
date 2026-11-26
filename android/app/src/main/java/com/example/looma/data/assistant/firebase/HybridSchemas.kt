package com.example.looma.data.assistant.firebase

import com.google.firebase.ai.annotations.Generable
import com.google.firebase.ai.annotations.Guide
import kotlinx.serialization.Serializable

@Serializable
@Generable(description = "A factual statement supported by exact passage IDs.")
data class HybridCitedClaim(
    @Guide(description = "One supported statement without inline citation markers.")
    val text: String,
    @Guide(description = "1-based passage IDs that directly support this statement.")
    val sources: List<Int>,
) {
    companion object
}

@Serializable
@Generable(description = "Structured answer composed of cited claims.")
data class HybridAnswerClaims(
    @Guide(description = "1 to 8 supported claims, or empty when evidence is insufficient.")
    val claims: List<HybridCitedClaim>,
) {
    companion object
}
