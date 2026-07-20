package com.longdev.xiaoling.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers")
    suspend fun getAll(): List<ProviderEntity>

    @Query("DELETE FROM providers")
    suspend fun deleteAll()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(providers: List<ProviderEntity>)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations WHERE id = :conversationId")
    suspend fun getConversation(conversationId: String): ConversationEntity?

    @Query("SELECT * FROM conversations")
    suspend fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM messages ORDER BY createdAt ASC")
    suspend fun getAllMessages(): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAt ASC")
    suspend fun getMessagesByConversationId(conversationId: String): List<MessageEntity>

    @Query(
        """
        SELECT id, messageId, sequence, type, text, toolName, argumentsJson, result,
               success, verificationStatus, memoryIdsJson, knowledgeReferencesJson, reasoningSource, providerItemId,
               summaryIndex, mimeType, fileName, NULL AS binaryData, imageDetail,
               documentExtractedText, documentPageCount, documentDetail
        FROM message_parts
        ORDER BY messageId ASC, sequence ASC
        """,
    )
    suspend fun getAllMessagePartsWithoutBinaryData(): List<MessagePartEntity>

    @Query(
        """
        SELECT message_parts.* FROM message_parts
        INNER JOIN messages ON messages.id = message_parts.messageId
        WHERE messages.conversationId IN (:conversationIds) AND message_parts.type IN ('IMAGE', 'DOCUMENT')
        ORDER BY message_parts.messageId ASC, message_parts.sequence ASC
        """,
    )
    suspend fun getBinaryMessagePartsByConversationIds(conversationIds: List<String>): List<MessagePartEntity>

    @Query(
        """
        SELECT message_parts.* FROM message_parts
        INNER JOIN messages ON messages.id = message_parts.messageId
        WHERE messages.conversationId = :conversationId
        ORDER BY message_parts.messageId ASC, message_parts.sequence ASC
        """,
    )
    suspend fun getMessagePartsByConversationId(conversationId: String): List<MessagePartEntity>

    @Query("SELECT DISTINCT messageId FROM message_parts WHERE messageId IN (:messageIds) AND type IN ('IMAGE', 'DOCUMENT')")
    suspend fun getMessageIdsWithBinaryParts(messageIds: List<String>): List<String>

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM message_parts")
    suspend fun deleteAllMessageParts()

    @Query("DELETE FROM message_parts WHERE messageId IN (:messageIds)")
    suspend fun deleteMessageParts(messageIds: List<String>)

    @Query("DELETE FROM message_parts WHERE messageId IN (:messageIds) AND type NOT IN ('IMAGE', 'DOCUMENT')")
    suspend fun deleteNonBinaryMessageParts(messageIds: List<String>)

    @Query("SELECT id FROM messages WHERE conversationId IN (:conversationIds)")
    suspend fun getMessageIdsByConversationIds(conversationIds: List<String>): List<String>

    @Query("DELETE FROM messages WHERE conversationId IN (:conversationIds)")
    suspend fun deleteMessagesByConversationIds(conversationIds: List<String>)

    @Query("DELETE FROM conversations WHERE id IN (:conversationIds)")
    suspend fun deleteConversations(conversationIds: List<String>)

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessageParts(parts: List<MessagePartEntity>)
}

@Dao
interface AgentRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: AgentRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStep(step: AgentStepEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertApprovalRequest(request: ApprovalRequestEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: RunEventEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertToolCall(call: AgentToolCallEntity)

    @Insert
    suspend fun insertToolResult(result: AgentToolResultEntity)

    @Query("SELECT * FROM agent_runs WHERE id = :runId")
    suspend fun getRun(runId: String): AgentRunEntity?

    @Query("SELECT * FROM agent_runs ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecentRuns(limit: Int): List<AgentRunEntity>

    @Query("SELECT * FROM agent_runs WHERE status IN (:statuses) ORDER BY createdAt ASC")
    suspend fun getRunsByStatuses(statuses: List<String>): List<AgentRunEntity>

    @Query("SELECT * FROM agent_steps WHERE runId = :runId ORDER BY sequence ASC")
    suspend fun getSteps(runId: String): List<AgentStepEntity>

    @Query("SELECT * FROM agent_steps WHERE runId IN (:runIds) ORDER BY runId ASC, sequence ASC")
    suspend fun getStepsForRuns(runIds: List<String>): List<AgentStepEntity>

    @Query("SELECT * FROM agent_steps WHERE id = :stepId")
    suspend fun getStep(stepId: String): AgentStepEntity?

    @Query("SELECT * FROM approval_requests WHERE id = :requestId")
    suspend fun getApprovalRequest(requestId: String): ApprovalRequestEntity?

    @Query("SELECT * FROM approval_requests WHERE conversationId = :conversationId AND status = 'PENDING' ORDER BY createdAt DESC")
    suspend fun getPendingApprovalRequests(conversationId: String): List<ApprovalRequestEntity>

    @Query("SELECT * FROM approval_requests WHERE runId = :runId ORDER BY createdAt ASC")
    suspend fun getApprovalRequests(runId: String): List<ApprovalRequestEntity>

    @Query("SELECT * FROM approval_requests WHERE runId IN (:runIds) ORDER BY runId ASC, createdAt ASC")
    suspend fun getApprovalRequestsForRuns(runIds: List<String>): List<ApprovalRequestEntity>

    @Query("SELECT * FROM run_events WHERE runId = :runId ORDER BY createdAt ASC, rowid ASC")
    suspend fun getEvents(runId: String): List<RunEventEntity>

    @Query("SELECT * FROM run_events WHERE runId IN (:runIds) ORDER BY runId ASC, createdAt ASC, rowid ASC")
    suspend fun getEventsForRuns(runIds: List<String>): List<RunEventEntity>

    @Query("SELECT * FROM agent_tool_calls WHERE id = :toolCallId")
    suspend fun getToolCall(toolCallId: String): AgentToolCallEntity?

    @Query("SELECT * FROM agent_tool_calls WHERE runId = :runId ORDER BY createdAt ASC, id ASC")
    suspend fun getToolCalls(runId: String): List<AgentToolCallEntity>

    @Query("SELECT * FROM agent_tool_calls WHERE runId IN (:runIds) ORDER BY runId ASC, createdAt ASC, id ASC")
    suspend fun getToolCallsForRuns(runIds: List<String>): List<AgentToolCallEntity>

    @Query("SELECT * FROM agent_tool_results WHERE runId = :runId ORDER BY createdAt ASC, toolCallId ASC")
    suspend fun getToolResults(runId: String): List<AgentToolResultEntity>

    @Query("SELECT * FROM agent_tool_results WHERE runId IN (:runIds) ORDER BY runId ASC, createdAt ASC, toolCallId ASC")
    suspend fun getToolResultsForRuns(runIds: List<String>): List<AgentToolResultEntity>

    @Query("SELECT * FROM agent_tool_results WHERE toolCallId = :toolCallId")
    suspend fun getToolResult(toolCallId: String): AgentToolResultEntity?

    @Query(
        """
        UPDATE agent_tool_results
        SET verificationStatus = :status, verifiedEventId = :eventId, verifiedAt = :verifiedAt
        WHERE toolCallId = :toolCallId AND runId = :runId AND toolName = :toolName
        """,
    )
    suspend fun markToolResultVerified(
        toolCallId: String,
        runId: String,
        toolName: String,
        status: String,
        eventId: String,
        verifiedAt: Long,
    ): Int
}

@Dao
interface AgentMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: AgentMemoryEntity)

    @Insert
    suspend fun insertMemoryIndex(memory: AgentMemoryFtsEntity)

    @Query("SELECT * FROM agent_memories WHERE id = :memoryId")
    suspend fun getMemory(memoryId: String): AgentMemoryEntity?

    @Insert
    suspend fun insertMemoryOperation(operation: AgentMemoryOperationEntity)

    @Query("SELECT * FROM agent_memory_operations WHERE idempotencyKey = :idempotencyKey")
    suspend fun getMemoryOperation(idempotencyKey: String): AgentMemoryOperationEntity?

    @Query(
        """
        SELECT * FROM agent_memories
        WHERE (:enabledFilter IS NULL OR enabled = :enabledFilter)
        AND (:activeOnly = 0 OR expiresAt IS NULL OR expiresAt > :now)
        ORDER BY pinned DESC, updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun list(limit: Int, enabledFilter: Boolean?, activeOnly: Boolean = false, now: Long = Long.MAX_VALUE): List<AgentMemoryEntity>

    @Query("SELECT * FROM agent_memories ORDER BY pinned DESC, updatedAt DESC")
    suspend fun listAllMemories(): List<AgentMemoryEntity>

    @Query(
        """
        SELECT * FROM agent_memories
        WHERE (:enabledOnly = 0 OR enabled = 1)
        AND (:activeOnly = 0 OR expiresAt IS NULL OR expiresAt > :now)
        AND (:pattern = '' OR content LIKE :pattern OR tags LIKE :pattern OR type LIKE :pattern OR sourceSummary LIKE :pattern)
        ORDER BY pinned DESC, updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, limit: Int, enabledOnly: Boolean, activeOnly: Boolean = false, now: Long = Long.MAX_VALUE): List<AgentMemoryEntity>

    @RawQuery
    suspend fun searchForManagement(query: SupportSQLiteQuery): List<AgentMemoryEntity>

    @Query(
        """
        SELECT agent_memories.* FROM agent_memories
        JOIN agent_memories_fts ON agent_memories_fts.memoryId = agent_memories.id
        WHERE agent_memories_fts MATCH :ftsQuery
        AND (:enabledFilter IS NULL OR agent_memories.enabled = :enabledFilter)
        AND (:activeOnly = 0 OR agent_memories.expiresAt IS NULL OR agent_memories.expiresAt > :now)
        ORDER BY agent_memories.pinned DESC, agent_memories.updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun searchFts(ftsQuery: String, limit: Int, enabledFilter: Boolean?, activeOnly: Boolean, now: Long): List<AgentMemoryEntity>

    @Query("DELETE FROM agent_memories_fts WHERE memoryId = :memoryId")
    suspend fun deleteMemoryIndex(memoryId: String)

    @Query("DELETE FROM agent_memories WHERE id = :memoryId")
    suspend fun deleteMemory(memoryId: String): Int

    @Query("UPDATE agent_memories SET lastReferencedAt = :referencedAt WHERE id IN (:memoryIds)")
    suspend fun touchReferenced(memoryIds: List<String>, referencedAt: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCandidate(candidate: AgentMemoryCandidateEntity)

    @Query("SELECT * FROM agent_memory_candidates WHERE id = :candidateId")
    suspend fun getCandidate(candidateId: String): AgentMemoryCandidateEntity?

    @Query("SELECT * FROM agent_memory_candidates ORDER BY createdAt DESC LIMIT :limit")
    suspend fun listCandidates(limit: Int): List<AgentMemoryCandidateEntity>

    @Query("SELECT * FROM agent_memory_candidates ORDER BY createdAt DESC")
    suspend fun listAllCandidates(): List<AgentMemoryCandidateEntity>
}

@Dao
interface KnowledgeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertDocument(document: KnowledgeDocumentEntity)

    @Update
    suspend fun updateDocument(document: KnowledgeDocumentEntity): Int

    @Query("SELECT * FROM knowledge_documents WHERE id = :documentId")
    suspend fun getDocument(documentId: String): KnowledgeDocumentEntity?

    @Query(
        """
        SELECT knowledge_documents.id, knowledge_documents.displayName, knowledge_documents.mimeType,
               knowledge_documents.contentHash, knowledge_documents.revision, knowledge_documents.parserVersion,
               knowledge_documents.byteSize, knowledge_documents.characterCount, knowledge_documents.enabled,
               knowledge_documents.createdAt, knowledge_documents.updatedAt,
               (SELECT COUNT(*) FROM knowledge_chunks WHERE knowledge_chunks.documentId = knowledge_documents.id) AS chunkCount
        FROM knowledge_documents
        ORDER BY knowledge_documents.updatedAt DESC, knowledge_documents.id DESC
        """,
    )
    suspend fun listDocumentSummaries(): List<KnowledgeDocumentSummaryEntity>

    @Query(
        """
        SELECT knowledge_documents.id, knowledge_documents.displayName, knowledge_documents.mimeType,
               knowledge_documents.contentHash, knowledge_documents.revision, knowledge_documents.parserVersion,
               knowledge_documents.byteSize, knowledge_documents.characterCount, knowledge_documents.enabled,
               knowledge_documents.createdAt, knowledge_documents.updatedAt,
               (SELECT COUNT(*) FROM knowledge_chunks WHERE knowledge_chunks.documentId = knowledge_documents.id) AS chunkCount
        FROM knowledge_documents
        WHERE knowledge_documents.id IN (:documentIds)
        """,
    )
    suspend fun getDocumentSummaries(documentIds: List<String>): List<KnowledgeDocumentSummaryEntity>

    @Query(
        """
        SELECT id, displayName, mimeType, contentHash, revision, parserVersion, byteSize, characterCount,
               substr(normalizedText, 1, :previewCharacterLimit) AS previewText,
               CASE WHEN characterCount > :previewCharacterLimit THEN 1 ELSE 0 END AS previewTruncated,
               enabled, createdAt, updatedAt
        FROM knowledge_documents
        WHERE id = :documentId
        """,
    )
    suspend fun getDocumentDetail(
        documentId: String,
        previewCharacterLimit: Int,
    ): KnowledgeDocumentDetailEntity?

    @Query("SELECT * FROM knowledge_documents WHERE id IN (:documentIds)")
    suspend fun getDocuments(documentIds: List<String>): List<KnowledgeDocumentEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChunks(chunks: List<KnowledgeChunkEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertChunkIndexes(chunks: List<KnowledgeChunkFtsEntity>)

    @Query("SELECT * FROM knowledge_chunks WHERE id = :chunkId")
    suspend fun getChunk(chunkId: String): KnowledgeChunkEntity?

    @Query("SELECT * FROM knowledge_chunks WHERE id IN (:chunkIds)")
    suspend fun getChunksByIds(chunkIds: List<String>): List<KnowledgeChunkEntity>

    @Query("SELECT * FROM knowledge_chunks WHERE documentId = :documentId ORDER BY sequence ASC")
    suspend fun getChunks(documentId: String): List<KnowledgeChunkEntity>

    @Query(
        """
        SELECT knowledge_chunks.* FROM knowledge_chunks
        JOIN knowledge_chunks_fts ON knowledge_chunks_fts.chunkId = knowledge_chunks.id
        JOIN knowledge_documents ON knowledge_documents.id = knowledge_chunks.documentId
        WHERE knowledge_chunks_fts MATCH :ftsQuery
        AND knowledge_documents.enabled = 1
        ORDER BY knowledge_documents.updatedAt DESC, knowledge_chunks.sequence ASC
        LIMIT :limit
        """,
    )
    suspend fun searchFts(ftsQuery: String, limit: Int): List<KnowledgeChunkEntity>

    @RawQuery
    suspend fun searchLike(query: SupportSQLiteQuery): List<KnowledgeChunkEntity>

    @Query("UPDATE knowledge_documents SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :documentId")
    suspend fun setEnabled(documentId: String, enabled: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM knowledge_chunks_fts WHERE documentId = :documentId")
    suspend fun deleteChunkIndexes(documentId: String)

    @Query("DELETE FROM knowledge_chunks WHERE documentId = :documentId")
    suspend fun deleteChunks(documentId: String)

    @Query("DELETE FROM knowledge_documents WHERE id = :documentId")
    suspend fun deleteDocument(documentId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertRetrieval(retrieval: KnowledgeRetrievalEntity)

    @Query("SELECT * FROM knowledge_retrievals ORDER BY createdAt DESC, id DESC LIMIT :limit")
    suspend fun recentRetrievals(limit: Int): List<KnowledgeRetrievalEntity>
}

@Dao
interface AgentNoteDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertNote(note: AgentNoteEntity)

    @Query("SELECT * FROM agent_notes WHERE id = :id")
    suspend fun getNote(id: String): AgentNoteEntity?

    @Query("SELECT * FROM agent_notes WHERE idempotencyKey = :idempotencyKey")
    suspend fun getNoteByIdempotencyKey(idempotencyKey: String): AgentNoteEntity?

    @Query("SELECT * FROM agent_notes ORDER BY updatedAt DESC LIMIT :limit")
    suspend fun list(limit: Int): List<AgentNoteEntity>

    @Query(
        """
        SELECT * FROM agent_notes
        WHERE title LIKE :pattern OR content LIKE :pattern
        ORDER BY updatedAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, limit: Int): List<AgentNoteEntity>
}

@Dao
interface AgentSkillDao {
    @Query("SELECT * FROM agent_skills ORDER BY source ASC, name COLLATE NOCASE ASC, id ASC")
    suspend fun list(): List<AgentSkillEntity>

    @Query("SELECT * FROM agent_skills WHERE id = :skillId")
    suspend fun get(skillId: String): AgentSkillEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: AgentSkillEntity)

    @Query("UPDATE agent_skills SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :skillId")
    suspend fun setEnabled(skillId: String, enabled: Boolean, updatedAt: Long): Int

    @Query("DELETE FROM agent_skills WHERE id = :skillId AND source = 'LOCAL'")
    suspend fun deleteLocal(skillId: String): Int
}

@Dao
interface AgentProfileDao {
    @Query("SELECT * FROM agent_profiles ORDER BY updatedAt DESC, name COLLATE NOCASE ASC, id ASC")
    suspend fun list(): List<AgentProfileEntity>

    @Query("SELECT * FROM agent_profiles WHERE id = :profileId")
    suspend fun get(profileId: String): AgentProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(profile: AgentProfileEntity)

    @Query("DELETE FROM agent_profiles WHERE id = :profileId")
    suspend fun delete(profileId: String): Int
}

@Dao
interface WorkflowDao {
    @Query("SELECT * FROM workflows ORDER BY updatedAt DESC, name COLLATE NOCASE ASC")
    suspend fun listWorkflows(): List<WorkflowEntity>

    @Query("SELECT * FROM workflows WHERE id = :workflowId")
    suspend fun getWorkflow(workflowId: String): WorkflowEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkflow(workflow: WorkflowEntity)

    @Query("SELECT * FROM workflow_step_definitions WHERE workflowId = :workflowId ORDER BY sequence ASC")
    suspend fun getWorkflowStepDefinitions(workflowId: String): List<WorkflowStepDefinitionEntity>

    @Query("SELECT * FROM workflow_step_definitions ORDER BY workflowId ASC, sequence ASC")
    suspend fun listWorkflowStepDefinitions(): List<WorkflowStepDefinitionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkflowStepDefinitions(steps: List<WorkflowStepDefinitionEntity>)

    @Query("DELETE FROM workflow_step_definitions WHERE workflowId = :workflowId")
    suspend fun deleteWorkflowStepDefinitions(workflowId: String)

    @Query("UPDATE workflows SET enabled = :enabled, updatedAt = :updatedAt WHERE id = :workflowId")
    suspend fun setWorkflowEnabled(workflowId: String, enabled: Boolean, updatedAt: Long): Int

    @Query("SELECT * FROM workflow_runs WHERE id = :workflowRunId")
    suspend fun getRun(workflowRunId: String): WorkflowRunEntity?

    @Query(
        """
        SELECT workflow_runs.* FROM workflow_runs
        JOIN workflow_steps ON workflow_steps.workflowRunId = workflow_runs.id
        WHERE workflow_steps.agentRunId = :agentRunId
        LIMIT 1
        """,
    )
    suspend fun getRunByAgentRunId(agentRunId: String): WorkflowRunEntity?

    @Query("SELECT * FROM workflow_runs WHERE workflowId = :workflowId AND status IN ('QUEUED', 'RUNNING') ORDER BY createdAt DESC LIMIT 1")
    suspend fun getActiveRun(workflowId: String): WorkflowRunEntity?

    @Query("SELECT * FROM workflow_runs ORDER BY createdAt DESC LIMIT :limit")
    suspend fun recentRuns(limit: Int): List<WorkflowRunEntity>

    @Query("SELECT * FROM workflow_runs ORDER BY createdAt DESC")
    suspend fun listRuns(): List<WorkflowRunEntity>

    @Query("SELECT * FROM workflow_runs WHERE status IN (:statuses) ORDER BY createdAt ASC")
    suspend fun runsByStatuses(statuses: List<String>): List<WorkflowRunEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRun(run: WorkflowRunEntity)

    @Query("SELECT * FROM workflow_steps WHERE workflowRunId = :workflowRunId ORDER BY sequence ASC")
    suspend fun getSteps(workflowRunId: String): List<WorkflowStepEntity>

    @Query("SELECT * FROM workflow_steps WHERE id = :stepId")
    suspend fun getStep(stepId: String): WorkflowStepEntity?

    @Query("SELECT * FROM workflow_steps WHERE agentRunId = :agentRunId LIMIT 1")
    suspend fun getStepByAgentRunId(agentRunId: String): WorkflowStepEntity?

    @Query("SELECT * FROM workflow_steps WHERE workflowRunId IN (:workflowRunIds) ORDER BY workflowRunId ASC, sequence ASC")
    suspend fun getStepsForRuns(workflowRunIds: List<String>): List<WorkflowStepEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertStep(step: WorkflowStepEntity)

    @Query("SELECT * FROM workflow_schedules ORDER BY updatedAt DESC")
    suspend fun listWorkflowSchedules(): List<WorkflowScheduleEntity>

    @Query("SELECT * FROM workflow_schedules WHERE id = :scheduleId")
    suspend fun getWorkflowSchedule(scheduleId: String): WorkflowScheduleEntity?

    @Query("SELECT * FROM workflow_schedules WHERE workflowId = :workflowId LIMIT 1")
    suspend fun getWorkflowScheduleByWorkflowId(workflowId: String): WorkflowScheduleEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWorkflowSchedule(schedule: WorkflowScheduleEntity)

    @Query("UPDATE workflow_schedules SET enabled = 0, nextTaskId = NULL, nextPlannedAt = NULL, updatedAt = :updatedAt WHERE workflowId = :workflowId")
    suspend fun disableWorkflowScheduleForWorkflow(workflowId: String, updatedAt: Long): Int

    @Query("SELECT * FROM scheduled_tasks ORDER BY createdAt DESC")
    suspend fun listScheduledTasks(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :taskId")
    suspend fun getScheduledTask(taskId: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE status = 'RUNNING' ORDER BY actualStartedAt ASC")
    suspend fun getRunningScheduledTasks(): List<ScheduledTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScheduledTask(task: ScheduledTaskEntity)
}
