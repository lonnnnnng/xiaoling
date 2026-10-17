package com.longdev.xiaoling.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "memory_maintenance_checkpoint")
data class MemoryMaintenanceCheckpointEntity(
    @PrimaryKey val id: Int = 1,
    val requestId: String,
    val status: String,
    val cursorAt: Long,
    val cursorRunId: String,
    val windowStart: Long,
    val windowEnd: Long,
    val remaining: Int,
    val scanned: Int,
    val suggested: Int,
    val delivered: Int,
)

// long: outbox 不依附 Run 外键；用户删除来源后仍保留固定处置证据，不能用旧正文重新生成候选。
@Entity(tableName = "memory_maintenance_outbox", indices = [Index(value = ["deliveryStatus", "runId"])])
data class MemoryMaintenanceOutboxEntity(
    @PrimaryKey val runId: String,
    val requestId: String,
    val sourceMessageId: String,
    val sourceConversationId: String,
    val completedAt: Long,
    val sourceHash: String,
    val content: String,
    val normalizedContent: String,
    val type: String,
    val topicKey: String,
    val sourceSummary: String,
    val confidence: Double,
    val candidateStatus: String,
    val sensitiveCategory: String?,
    val payloadHash: String,
    val createdAt: Long,
    val deliveryStatus: String = "PENDING",
    val candidateId: String? = null,
)

data class MemoryMaintenanceSourceRow(
    val runId: String,
    val conversationId: String,
    val userMessageId: String,
    val completedAt: Long,
    val text: String?,
)

@Dao
interface MemoryMaintenanceDao {
    @Query("SELECT * FROM memory_maintenance_checkpoint WHERE id = 1")
    suspend fun checkpoint(): MemoryMaintenanceCheckpointEntity?

    @Upsert suspend fun saveCheckpoint(checkpoint: MemoryMaintenanceCheckpointEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertOutbox(row: MemoryMaintenanceOutboxEntity)
    @Query("SELECT COUNT(*) FROM memory_maintenance_outbox") suspend fun outboxCount(): Int
    @Query("SELECT * FROM memory_maintenance_outbox WHERE runId = :runId")
    suspend fun outbox(runId: String): MemoryMaintenanceOutboxEntity?
    @Query("SELECT * FROM memory_maintenance_outbox WHERE deliveryStatus = 'PENDING' ORDER BY runId LIMIT 1")
    suspend fun pending(): MemoryMaintenanceOutboxEntity?
    @Query("UPDATE memory_maintenance_outbox SET deliveryStatus = :status, candidateId = :candidateId WHERE runId = :runId AND deliveryStatus = 'PENDING'")
    suspend fun settle(runId: String, status: String, candidateId: String?): Int

    // long: 主键作为完成时间相同的稳定裁决；在 SQL 中限制原文大小，附件、旧 origin 和系统生成输入不进入整理。
    @Query("""SELECT r.id AS runId, r.conversationId, r.userMessageId, r.completedAt,
        CASE WHEN m.role = 'user' AND m.origin = 'USER' AND m.conversationId = r.conversationId
          AND length(CAST(m.text AS BLOB)) <= :maxBytes
          AND NOT EXISTS (SELECT 1 FROM message_parts p WHERE p.messageId = m.id AND p.type IN ('IMAGE','DOCUMENT'))
        THEN m.text ELSE NULL END AS text
        FROM agent_runs r LEFT JOIN messages m ON m.id = r.userMessageId
        WHERE r.status = 'COMPLETED' AND r.completedAt IS NOT NULL AND r.parentRunId IS NULL
          AND r.cancelRequestedAt IS NULL AND r.completedAt <= :windowEnd
          AND r.completedAt >= :cursorAt
          AND (r.completedAt > :cursorAt OR (r.completedAt = :cursorAt AND r.id > :cursorRunId))
        ORDER BY r.completedAt, r.id LIMIT 1""")
    suspend fun nextSource(cursorAt: Long, cursorRunId: String, windowEnd: Long, maxBytes: Int): MemoryMaintenanceSourceRow?

    @Query("""SELECT r.id AS runId, r.conversationId, r.userMessageId, r.completedAt,
        CASE WHEN m.role = 'user' AND m.origin = 'USER' AND m.conversationId = r.conversationId
          AND length(CAST(m.text AS BLOB)) <= :maxBytes
          AND NOT EXISTS (SELECT 1 FROM message_parts p WHERE p.messageId = m.id AND p.type IN ('IMAGE','DOCUMENT'))
        THEN m.text ELSE NULL END AS text
        FROM agent_runs r LEFT JOIN messages m ON m.id = r.userMessageId
        WHERE r.id = :runId AND r.status = 'COMPLETED' AND r.completedAt IS NOT NULL
          AND r.parentRunId IS NULL AND r.cancelRequestedAt IS NULL""")
    suspend fun source(runId: String, maxBytes: Int): MemoryMaintenanceSourceRow?

    @Query("""SELECT * FROM agent_memory_candidates WHERE
        (:normalized <> '' AND normalizedContent = :normalized) OR
        (:normalized = '' AND status = 'BLOCKED_SENSITIVE' AND sensitiveCategory = :category AND sourceConversationId = :conversationId)
        ORDER BY createdAt, id LIMIT 1""")
    suspend fun matchingCandidate(normalized: String, category: String?, conversationId: String): AgentMemoryCandidateEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertCandidate(candidate: AgentMemoryCandidateEntity)
}
