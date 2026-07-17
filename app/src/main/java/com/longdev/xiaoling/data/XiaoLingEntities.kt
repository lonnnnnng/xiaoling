package com.longdev.xiaoling.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "providers")
data class ProviderEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String,
    val apiKeyIv: String,
    val apiKeyCiphertext: String,
    val model: String,
    val availableModelsJson: String,
    val enabledModelsJson: String,
    val lastSyncedAt: String,
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val summary: String,
    val summaryUntilMessageId: String?,
    val summaryUpdatedAt: Long?,
    val summaryModel: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "messages",
    indices = [Index(value = ["conversationId", "createdAt"])],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val role: String,
    val text: String,
    val createdAt: Long,
    val providerId: String?,
    val providerName: String?,
    val model: String?,
    val apiMode: String?,
    val streaming: Boolean?,
    val requestUrl: String?,
    val firstTokenLatencyMs: Long?,
    val latencyMs: Long?,
    val promptTokens: Int?,
    val completionTokens: Int?,
    val totalTokens: Int?,
    val finishReason: String?,
    val errorKind: String?,
    val errorMessage: String?,
)

@Entity(
    tableName = "agent_runs",
    indices = [Index(value = ["conversationId", "createdAt"])],
)
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val userMessageId: String,
    val goal: String,
    val status: String,
    val result: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val completedAt: Long?,
)

@Entity(
    tableName = "agent_steps",
    indices = [Index(value = ["runId", "sequence"])],
)
data class AgentStepEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val sequence: Int,
    val type: String,
    val status: String,
    val title: String,
    val detail: String,
    val createdAt: Long,
    val completedAt: Long?,
)

@Entity(
    tableName = "approval_requests",
    indices = [
        Index(value = ["conversationId", "status", "createdAt"]),
        Index(value = ["runId", "createdAt"]),
    ],
)
data class ApprovalRequestEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val conversationId: String,
    val toolCallId: String,
    val toolName: String,
    val toolDescription: String,
    val risk: String,
    val argumentsJson: String,
    val status: String,
    val decisionReason: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val decidedAt: Long?,
)

@Entity(
    tableName = "run_events",
    indices = [Index(value = ["runId", "createdAt"])],
)
data class RunEventEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val type: String,
    val message: String,
    val createdAt: Long,
)

@Entity(
    tableName = "agent_memories",
    indices = [Index(value = ["createdAt"])],
)
data class AgentMemoryEntity(
    @PrimaryKey val id: String,
    val content: String,
    val tags: String,
    val type: String,
    val sourceConversationId: String?,
    val sourceRunId: String?,
    val sourceSummary: String,
    val confidence: Double,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "agent_notes",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["updatedAt"]),
    ],
)
data class AgentNoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
)
