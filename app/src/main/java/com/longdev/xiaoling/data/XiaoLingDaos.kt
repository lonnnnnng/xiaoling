package com.longdev.xiaoling.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

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
    @Query("SELECT * FROM conversations")
    suspend fun getAllConversations(): List<ConversationEntity>

    @Query("SELECT * FROM messages ORDER BY createdAt ASC")
    suspend fun getAllMessages(): List<MessageEntity>

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()

    @Query("DELETE FROM conversations")
    suspend fun deleteAllConversations()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversations(conversations: List<ConversationEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessages(messages: List<MessageEntity>)
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

    @Query("SELECT * FROM run_events WHERE runId = :runId ORDER BY createdAt ASC")
    suspend fun getEvents(runId: String): List<RunEventEntity>

    @Query("SELECT * FROM run_events WHERE runId IN (:runIds) ORDER BY runId ASC, createdAt ASC")
    suspend fun getEventsForRuns(runIds: List<String>): List<RunEventEntity>
}

@Dao
interface AgentMemoryDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMemory(memory: AgentMemoryEntity)

    @Query(
        """
        SELECT * FROM agent_memories
        WHERE (:enabledOnly = 0 OR enabled = 1)
        AND (:pattern = '' OR content LIKE :pattern OR tags LIKE :pattern OR type LIKE :pattern OR sourceSummary LIKE :pattern)
        ORDER BY createdAt DESC
        LIMIT :limit
        """,
    )
    suspend fun search(pattern: String, limit: Int, enabledOnly: Boolean): List<AgentMemoryEntity>
}

@Dao
interface AgentNoteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: AgentNoteEntity)

    @Query("SELECT * FROM agent_notes WHERE id = :id")
    suspend fun getNote(id: String): AgentNoteEntity?

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
