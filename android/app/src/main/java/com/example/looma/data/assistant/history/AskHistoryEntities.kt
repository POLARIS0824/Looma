package com.example.looma.data.assistant.history

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "ask_conversations",
    indices = [Index("vaultId", "updatedAt")],
)
internal data class AskConversationEntity(
    @PrimaryKey val id: String,
    val vaultId: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val modelId: String?,
)

@Entity(
    tableName = "ask_messages",
    foreignKeys = [
        ForeignKey(
            entity = AskConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversationId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("conversationId")],
)
internal data class AskMessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val orderIndex: Int,
    val role: String,
    val text: String,
    val thinking: String,
    val origin: String?,
    val errorCode: String?,
    val savedNotePath: String?,
    val createdAt: Long,
)
