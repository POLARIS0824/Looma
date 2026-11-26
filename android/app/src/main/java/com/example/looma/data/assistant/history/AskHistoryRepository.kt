package com.example.looma.data.assistant.history

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

internal data class AskConversationSummary(
    val id: String,
    val vaultId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String?,
)

internal data class StoredChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val thinking: String,
    val origin: String?,
    val errorCode: String?,
    val savedNotePath: String?,
    val createdAt: Long,
)

internal interface AskHistoryRepository {
    fun conversations(vaultId: String): Flow<List<AskConversationSummary>>
    suspend fun loadMessages(conversationId: String): List<StoredChatMessage>
    suspend fun saveConversation(summary: AskConversationSummary, messages: List<StoredChatMessage>)
    suspend fun deleteConversation(conversationId: String)
}

internal class RoomAskHistoryRepository(
    private val dao: AskHistoryDao,
) : AskHistoryRepository {

    override fun conversations(vaultId: String): Flow<List<AskConversationSummary>> =
        dao.conversationsForVault(vaultId).map { list -> list.map(AskConversationEntity::toSummary) }

    override suspend fun loadMessages(conversationId: String): List<StoredChatMessage> =
        dao.messagesForConversation(conversationId).map(AskMessageEntity::toStored)

    override suspend fun saveConversation(summary: AskConversationSummary, messages: List<StoredChatMessage>) {
        dao.saveConversation(
            conversation = summary.toEntity(),
            messages = messages.mapIndexed { index, msg -> msg.toEntity(summary.id, index) },
        )
    }

    override suspend fun deleteConversation(conversationId: String) {
        dao.deleteConversation(conversationId)
    }
}

private fun AskConversationEntity.toSummary(): AskConversationSummary = AskConversationSummary(
    id = id,
    vaultId = vaultId,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId,
)

private fun AskConversationSummary.toEntity(): AskConversationEntity = AskConversationEntity(
    id = id,
    vaultId = vaultId,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    modelId = modelId,
)

private fun AskMessageEntity.toStored(): StoredChatMessage = StoredChatMessage(
    id = id,
    role = role,
    text = text,
    thinking = thinking,
    origin = origin,
    errorCode = errorCode,
    savedNotePath = savedNotePath,
    createdAt = createdAt,
)

private fun StoredChatMessage.toEntity(conversationId: String, orderIndex: Int): AskMessageEntity = AskMessageEntity(
    id = id,
    conversationId = conversationId,
    orderIndex = orderIndex,
    role = role,
    text = text,
    thinking = thinking,
    origin = origin,
    errorCode = errorCode,
    savedNotePath = savedNotePath,
    createdAt = createdAt,
)
