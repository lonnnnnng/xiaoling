package com.longdev.xiaoling.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
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
    @ColumnInfo(defaultValue = "LEGACY") val origin: String,
    val verifiedAgentContext: String?,
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
    tableName = "message_parts",
    indices = [
        Index(value = ["messageId", "sequence"], unique = true),
        Index(value = ["type"]),
    ],
)
data class MessagePartEntity(
    @PrimaryKey val id: String,
    val messageId: String,
    val sequence: Int,
    val type: String,
    val text: String?,
    val toolName: String?,
    val argumentsJson: String?,
    val result: String?,
    val success: Boolean?,
    val verificationStatus: String?,
    val memoryIdsJson: String?,
    @ColumnInfo(defaultValue = "'[]'") val knowledgeReferencesJson: String = "[]",
    val reasoningSource: String?,
    val providerItemId: String?,
    val summaryIndex: Int?,
    val mimeType: String?,
    val fileName: String?,
    val binaryData: ByteArray?,
    val imageDetail: String?,
    val documentExtractedText: String? = null,
    val documentPageCount: Int? = null,
    val documentDetail: String? = null,
)

@Entity(
    tableName = "agent_runs",
    indices = [Index(value = ["conversationId", "createdAt"])],
)
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val retryOfRunId: String?,
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
    val metadataJson: String?,
    val createdAt: Long,
)

@Entity(
    tableName = "agent_tool_calls",
    indices = [
        Index(value = ["runId", "createdAt"]),
        Index(value = ["proposedEventId"], unique = true),
        Index(value = ["validatedEventId"], unique = true),
    ],
)
data class AgentToolCallEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val toolName: String,
    val risk: String,
    val argumentsJson: String,
    val proposedEventId: String?,
    val validatedEventId: String?,
    val createdAt: Long,
    val validatedAt: Long?,
)

@Entity(
    tableName = "agent_tool_results",
    indices = [
        Index(value = ["runId", "createdAt"]),
        Index(value = ["eventId"], unique = true),
    ],
)
data class AgentToolResultEntity(
    @PrimaryKey val toolCallId: String,
    val runId: String,
    val eventId: String,
    val toolName: String,
    val content: String,
    val success: Boolean,
    val errorMessage: String?,
    val durationMs: Long,
    val executorVerified: Boolean?,
    val verificationStatus: String?,
    val verifiedEventId: String?,
    val memoryIdsJson: String,
    @ColumnInfo(defaultValue = "'[]'") val knowledgeReferencesJson: String = "[]",
    val replaySafety: String,
    val receiptToolCallId: String?,
    val receiptOperationId: String?,
    val receiptIdempotencyKey: String?,
    val receiptStatus: String?,
    val createdAt: Long,
    val verifiedAt: Long?,
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
    @ColumnInfo(defaultValue = "0") val pinned: Boolean,
    val expiresAt: Long?,
    val lastReferencedAt: Long?,
)

@Entity(
    tableName = "agent_memory_operations",
    indices = [Index(value = ["memoryId"])],
)
data class AgentMemoryOperationEntity(
    @PrimaryKey val idempotencyKey: String,
    val memoryId: String,
    val payloadHash: String,
    val resultHash: String?,
    val createdAt: Long,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "agent_memories_fts")
data class AgentMemoryFtsEntity(
    val memoryId: String,
    val content: String,
    val tags: String,
    val type: String,
    val sourceSummary: String,
)

@Entity(
    tableName = "agent_memory_candidates",
    indices = [
        Index(value = ["status", "createdAt"]),
        Index(value = ["normalizedContent"]),
    ],
)
data class AgentMemoryCandidateEntity(
    @PrimaryKey val id: String,
    val content: String,
    val normalizedContent: String,
    val type: String,
    val topicKey: String,
    val sourceConversationId: String?,
    val sourceRunId: String?,
    val sourceSummary: String,
    val confidence: Double,
    val status: String,
    val sensitiveCategory: String?,
    val relatedMemoryId: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "knowledge_documents",
    indices = [
        Index(value = ["enabled", "updatedAt"]),
        Index(value = ["contentHash"]),
    ],
)
data class KnowledgeDocumentEntity(
    @PrimaryKey val id: String,
    val displayName: String,
    val mimeType: String,
    val contentHash: String,
    val revision: Int,
    val parserVersion: Int,
    val byteSize: Long,
    val characterCount: Int,
    val normalizedText: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

data class KnowledgeDocumentSummaryEntity(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val contentHash: String,
    val revision: Int,
    val parserVersion: Int,
    val byteSize: Long,
    val characterCount: Int,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val chunkCount: Int,
)

data class KnowledgeDocumentDetailEntity(
    val id: String,
    val displayName: String,
    val mimeType: String,
    val contentHash: String,
    val revision: Int,
    val parserVersion: Int,
    val byteSize: Long,
    val characterCount: Int,
    val previewText: String,
    val previewTruncated: Boolean,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "knowledge_chunks",
    indices = [
        Index(value = ["documentId"]),
        Index(value = ["documentId", "documentRevision", "sequence"], unique = true),
    ],
)
data class KnowledgeChunkEntity(
    @PrimaryKey val id: String,
    val documentId: String,
    val documentRevision: Int,
    val sequence: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "knowledge_chunks_fts")
data class KnowledgeChunkFtsEntity(
    val chunkId: String,
    val documentId: String,
    val text: String,
)

@Entity(
    tableName = "knowledge_chunk_embeddings",
    primaryKeys = ["chunkId", "providerId", "model"],
    indices = [
        Index(value = ["documentId", "documentRevision"]),
        Index(value = ["providerId", "model"]),
    ],
)
data class KnowledgeChunkEmbeddingEntity(
    val chunkId: String,
    val documentId: String,
    val documentRevision: Int,
    val providerId: String,
    val model: String,
    val dimensions: Int,
    val vectorBlob: ByteArray,
    val createdAt: Long,
)

data class KnowledgeEmbeddingIndexSummaryEntity(
    val providerId: String,
    val model: String,
    val documentRevision: Int,
    val dimensions: Int,
    val chunkCount: Int,
    val updatedAt: Long,
)

@Entity(
    tableName = "knowledge_retrievals",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["sourceConversationId"]),
        Index(value = ["sourceRunId"]),
    ],
)
data class KnowledgeRetrievalEntity(
    @PrimaryKey val id: String,
    val query: String,
    val chunkIdsJson: String,
    val documentIdsJson: String,
    val sourceConversationId: String?,
    val sourceRunId: String?,
    val embeddingProviderId: String?,
    val embeddingModel: String?,
    @ColumnInfo(defaultValue = "'LEXICAL_ONLY'") val embeddingStatus: String,
    val createdAt: Long,
)

@Entity(
    tableName = "agent_notes",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["updatedAt"]),
        Index(value = ["idempotencyKey"], unique = true),
    ],
)
data class AgentNoteEntity(
    @PrimaryKey val id: String,
    val title: String,
    val content: String,
    val createdAt: Long,
    val updatedAt: Long,
    val idempotencyKey: String?,
)

@Entity(
    tableName = "agent_skills",
    indices = [Index(value = ["source", "enabled", "updatedAt"])],
)
data class AgentSkillEntity(
    @PrimaryKey val id: String,
    val version: Int,
    val name: String,
    val description: String,
    val instructions: String,
    val toolNamesJson: String,
    val keywordsJson: String,
    val triggerExamplesJson: String,
    val requiredAndroidPermissionsJson: String,
    val declaredRisk: String,
    val failureRecovery: String,
    val completionCriteria: String,
    val source: String,
    val enabled: Boolean,
    val importedAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "agent_profiles",
    indices = [
        Index(value = ["providerId"]),
        Index(value = ["updatedAt"]),
    ],
)
data class AgentProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatar: String,
    val providerId: String,
    val model: String,
    val apiMode: String,
    val systemPrompt: String,
    val contextPolicy: String,
    val allowedToolNamesJson: String,
    val allowedSkillIdsJson: String,
    val memoryEnabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "workflows",
    indices = [Index(value = ["enabled", "updatedAt"])],
)
data class WorkflowEntity(
    @PrimaryKey val id: String,
    val name: String,
    val goal: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "workflow_step_definitions",
    indices = [
        Index(value = ["workflowId", "sequence"], unique = true),
        Index(value = ["workflowId", "idempotencyKey"], unique = true),
    ],
)
data class WorkflowStepDefinitionEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val sequence: Int,
    val goal: String,
    val idempotencyKey: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "workflow_runs",
    indices = [
        Index(value = ["workflowId", "createdAt"]),
        Index(value = ["status", "createdAt"]),
        Index(value = ["agentRunId"], unique = true),
        Index(value = ["scheduledTaskId"], unique = true),
        Index(value = ["retryOfWorkflowRunId"]),
    ],
)
data class WorkflowRunEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val trigger: String,
    val scheduledTaskId: String?,
    val plannedAt: Long?,
    val conversationId: String,
    val agentRunId: String?,
    val status: String,
    val result: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val retryOfWorkflowRunId: String?,
    val workerStopReasonCode: Int? = null,
    val workerStopReasonName: String? = null,
)

@Entity(
    tableName = "scheduled_tasks",
    indices = [
        Index(value = ["workflowId", "plannedAt"]),
        Index(value = ["scheduleId", "plannedAt"]),
        Index(value = ["status", "plannedAt"]),
        Index(value = ["workRequestId"], unique = true),
        Index(value = ["workflowRunId"], unique = true),
    ],
)
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val type: String,
    val scheduleId: String?,
    val status: String,
    val plannedAt: Long,
    val workRequestId: String?,
    val workflowRunId: String?,
    val actualStartedAt: Long?,
    val completedAt: Long?,
    val errorMessage: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val workerStopReasonCode: Int? = null,
    val workerStopReasonName: String? = null,
)

@Entity(
    tableName = "workflow_schedules",
    indices = [
        Index(value = ["workflowId"], unique = true),
        Index(value = ["enabled", "nextPlannedAt"]),
        Index(value = ["nextTaskId"], unique = true),
    ],
)
data class WorkflowScheduleEntity(
    @PrimaryKey val id: String,
    val workflowId: String,
    val type: String,
    val timeOfDayMinutes: Int,
    val dayOfWeek: Int?,
    val zoneId: String,
    val enabled: Boolean,
    val nextTaskId: String?,
    val nextPlannedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "workflow_steps",
    indices = [
        Index(value = ["workflowRunId", "sequence"], unique = true),
        Index(value = ["workflowRunId", "idempotencyKey"], unique = true),
        Index(value = ["agentRunId"], unique = true),
        Index(value = ["reusedFromStepId"]),
    ],
)
data class WorkflowStepEntity(
    @PrimaryKey val id: String,
    val workflowRunId: String,
    val sequence: Int,
    val type: String,
    val status: String,
    val title: String,
    val detail: String,
    val agentRunId: String?,
    val result: String?,
    val errorMessage: String?,
    val createdAt: Long,
    val startedAt: Long?,
    val completedAt: Long?,
    val definitionStepId: String?,
    val idempotencyKey: String,
    val inputSnapshot: String,
    val outputSnapshot: String?,
    val reusedFromStepId: String?,
)

@Entity(
    tableName = "process_exit_observations",
    indices = [
        Index(value = ["timestamp"]),
        Index(value = ["evidenceKind", "timestamp"]),
    ],
)
data class ProcessExitObservationEntity(
    @PrimaryKey val id: String,
    val timestamp: Long,
    val processName: String,
    val pid: Int,
    val reasonCode: Int,
    val reasonName: String,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val lowMemoryReportSupported: Boolean,
    val evidenceKind: String,
    val observedAt: Long,
)
