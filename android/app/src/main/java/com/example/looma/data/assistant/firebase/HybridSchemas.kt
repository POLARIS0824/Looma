package com.example.looma.data.assistant.firebase

import com.example.looma.data.assistant.CitedClaim
import com.example.looma.data.assistant.PlannerAction
import com.google.firebase.ai.annotations.Generable
import com.google.firebase.ai.annotations.Guide
import kotlinx.serialization.Serializable

@Serializable
@Generable(description = "Next investigation action for answering a question about notes.")
data class HybridPlannerAction(
    @Guide(description = "One of: finish, search, read.")
    val action: String,
    @Guide(description = "Short keyword search query when action is search; empty otherwise.")
    val query: String = "",
    @Guide(description = "1-based passage ID when action is read; 0 otherwise.")
    val passageId: Int = 0,
) {
    companion object
}

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

internal fun HybridPlannerAction.toDomain(): PlannerAction =
    when (action.trim().lowercase()) {
        "search" -> {
            val trimmed = query.trim()
            if (trimmed.length in 1..200) PlannerAction.Search(trimmed) else PlannerAction.Finish
        }
        "read" -> {
            if (passageId > 0) PlannerAction.Read(passageId) else PlannerAction.Finish
        }
        else -> PlannerAction.Finish
    }

internal fun HybridAnswerClaims.toDomain(passageCount: Int): List<CitedClaim> {
    if (passageCount <= 0 || claims.size !in 1..8) return emptyList()
    return claims.mapNotNull { claim ->
        val trimmed = claim.text.trim()
        val sources = claim.sources.filter { it in 1..passageCount }.distinct()
        if (trimmed.length in 1..1600 && sources.isNotEmpty()) {
            CitedClaim(trimmed, sources)
        } else {
            null
        }
    }
}
