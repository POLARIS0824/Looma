package com.example.looma.data.assistant.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
internal interface AskHistoryDao {

    @Query("SELECT * FROM ask_conversations WHERE vaultId = :vaultId ORDER BY updatedAt DESC")
    fun conversationsForVault(vaultId: String): Flow<List<AskConversationEntity>>

    @Query("SELECT * FROM ask_messages WHERE conversationId = :conversationId ORDER BY orderIndex ASC")
    suspend fun messagesForConversation(conversationId: String): List<AskMessageEntity>

    @Upsert
    suspend fun upsertConversation(entity: AskConversationEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<AskMessageEntity>)

    @Query("DELETE FROM ask_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: String)

    @Transaction
    suspend fun saveConversation(conversation: AskConversationEntity, messages: List<AskMessageEntity>) {
        upsertConversation(conversation)
        deleteMessagesForConversation(conversation.id)
        if (messages.isNotEmpty()) {
            insertMessages(messages)
        }
    }

    @Query("DELETE FROM ask_conversations WHERE id = :conversationId")
    suspend fun deleteConversation(conversationId: String)
}
