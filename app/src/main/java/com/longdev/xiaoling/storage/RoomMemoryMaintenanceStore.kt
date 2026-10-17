package com.longdev.xiaoling.storage

import androidx.room.withTransaction
import com.longdev.xiaoling.agent.MemoryMaintenancePolicy
import com.longdev.xiaoling.data.AgentMemoryCandidateEntity
import com.longdev.xiaoling.data.MemoryMaintenanceCheckpointEntity
import com.longdev.xiaoling.data.MemoryMaintenanceOutboxEntity
import com.longdev.xiaoling.data.MemoryMaintenanceSourceRow
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID

class RoomMemoryMaintenanceStore(private val database: XiaoLingDatabase) {
    private val dao get() = database.memoryMaintenanceDao()

    suspend fun current(): MemoryMaintenanceCheckpointEntity? = dao.checkpoint()

    suspend fun begin(now: Long = System.currentTimeMillis()): MemoryMaintenanceCheckpointEntity = database.withTransaction {
        val old = dao.checkpoint()
        if (old?.status == "RUNNING") return@withTransaction old
        val lower = (now - MemoryMaintenancePolicy.WINDOW_MILLIS).coerceAtLeast(0)
        val retainCursor = old != null && old.cursorAt >= lower
        MemoryMaintenanceCheckpointEntity(requestId = UUID.randomUUID().toString(), status = "RUNNING",
            cursorAt = if (retainCursor) old.cursorAt else lower,
            cursorRunId = if (retainCursor) old.cursorRunId else "", windowStart = lower, windowEnd = now,
            remaining = MemoryMaintenancePolicy.MAX_RUNS, scanned = 0, suggested = 0, delivered = 0)
            .also { dao.saveCheckpoint(it) }
    }

    suspend fun cancel(requestId: String) = database.withTransaction {
        active(requestId)?.let { dao.saveCheckpoint(it.copy(status = "CANCELLED")) }
    }

    suspend fun fail(requestId: String) = database.withTransaction {
        active(requestId)?.let { dao.saveCheckpoint(it.copy(status = "FAILED")) }
    }

    suspend fun scanOne(requestId: String): Boolean = database.withTransaction {
        val state = active(requestId) ?: return@withTransaction false
        if (dao.pending() != null) return@withTransaction false
        if (state.remaining == 0) return@withTransaction false
        val source = dao.nextSource(state.cursorAt, state.cursorRunId, state.windowEnd, MemoryMaintenancePolicy.MAX_SOURCE_BYTES)
            ?: return@withTransaction false
        val draft = source.text?.let { MemoryMaintenancePolicy.suggest(it, source.conversationId, source.runId) }
        var added = 0
        if (draft != null && dao.outbox(source.runId) == null) {
            check(dao.outboxCount() < MemoryMaintenancePolicy.MAX_OUTBOX_ROWS) { "整理建议容量已满" }
            val row = MemoryMaintenanceOutboxEntity(source.runId, requestId, source.userMessageId, source.conversationId,
                source.completedAt, sourceHash(source), draft.content, draft.normalizedContent, draft.type,
                draft.topicKey, draft.source.summary, draft.confidence, draft.status.name, draft.sensitiveCategory?.name,
                "", System.currentTimeMillis())
            dao.insertOutbox(row.copy(payloadHash = payloadHash(row)))
            added = 1
        }
        // long: 建议先落 outbox 再推进游标，二者同事务；容量、插入或取消失败都不能跳过该轮。
        currentCoroutineContext().ensureActive()
        dao.saveCheckpoint(state.copy(cursorAt = source.completedAt, cursorRunId = source.runId,
            remaining = state.remaining - 1, scanned = state.scanned + 1, suggested = state.suggested + added))
        true
    }

    suspend fun deliverOne(requestId: String): Boolean = database.withTransaction {
        var state = active(requestId) ?: return@withTransaction false
        val row = dao.pending() ?: return@withTransaction false
        check(row.payloadHash == payloadHash(row)) { "整理建议完整性校验失败" }
        if (row.requestId != requestId) {
            if (state.remaining == 0) return@withTransaction false
            // long: 旧 outbox 重放也占当前窗口的 Run 预算，不能在 20 个新轮次之外额外生成建议。
            state = state.copy(remaining = state.remaining - 1, scanned = state.scanned + 1)
        }
        if (row.completedAt < state.windowStart || row.completedAt > state.windowEnd) {
            check(dao.settle(row.runId, "OUTSIDE_WINDOW", null) == 1)
            dao.saveCheckpoint(state)
            return@withTransaction true
        }
        val source = dao.source(row.runId, MemoryMaintenancePolicy.MAX_SOURCE_BYTES)
        if (source?.text == null || sourceHash(source) != row.sourceHash) {
            // long: 来源被删除或修改时只封存固定处置，不借用旧 outbox 正文重新提出候选。
            check(dao.settle(row.runId, "SOURCE_CHANGED", null) == 1)
            dao.saveCheckpoint(state)
            return@withTransaction true
        }
        val expected = requireNotNull(MemoryMaintenancePolicy.suggest(source.text, source.conversationId, source.runId))
        check(row.content == expected.content && row.normalizedContent == expected.normalizedContent &&
            row.type == expected.type && row.topicKey == expected.topicKey && row.sourceSummary == expected.source.summary &&
            row.confidence == expected.confidence && row.candidateStatus == expected.status.name &&
            row.sensitiveCategory == expected.sensitiveCategory?.name) { "整理建议与原话不一致" }
        val existing = dao.matchingCandidate(row.normalizedContent, row.sensitiveCategory, row.sourceConversationId)
        val candidateId = existing?.id ?: "memory-maintenance-${MemoryMaintenancePolicy.hash(row.runId)}"
        if (existing == null) {
            dao.insertCandidate(AgentMemoryCandidateEntity(candidateId, row.content, row.normalizedContent,
                row.type, row.topicKey, row.sourceConversationId, row.runId, row.sourceSummary, row.confidence,
                row.candidateStatus, row.sensitiveCategory, null, row.createdAt, row.createdAt))
        }
        // long: 候选与投递回执同事务；已接受/拒绝的候选原样复用，半写入或重放不能让旧决定变回 PENDING。
        currentCoroutineContext().ensureActive()
        check(dao.settle(row.runId, "DELIVERED", candidateId) == 1)
        dao.saveCheckpoint(state.copy(delivered = state.delivered + if (existing == null) 1 else 0))
        true
    }

    suspend fun complete(requestId: String) = database.withTransaction {
        val state = active(requestId) ?: return@withTransaction
        check(dao.pending() == null) { "整理建议尚未投递" }
        dao.saveCheckpoint(state.copy(status = "SUCCEEDED"))
    }

    private suspend fun active(requestId: String): MemoryMaintenanceCheckpointEntity? {
        val state = dao.checkpoint() ?: return null
        check(state.remaining in 0..MemoryMaintenancePolicy.MAX_RUNS && state.scanned in 0..MemoryMaintenancePolicy.MAX_RUNS)
        // long: 两个字段各自合法仍可能因损坏重复预算；校验固定总数后才允许继续扫描或投递。
        check(state.remaining + state.scanned == MemoryMaintenancePolicy.MAX_RUNS)
        return state.takeIf { it.requestId == requestId && it.status == "RUNNING" }
    }

    private fun sourceHash(source: MemoryMaintenanceSourceRow): String = MemoryMaintenancePolicy.hash(
        source.runId, source.conversationId, source.userMessageId, source.completedAt.toString(), requireNotNull(source.text))

    private fun payloadHash(row: MemoryMaintenanceOutboxEntity): String = MemoryMaintenancePolicy.hash(
        row.runId, row.requestId, row.sourceMessageId, row.sourceConversationId, row.completedAt.toString(), row.sourceHash,
        row.content, row.normalizedContent, row.type, row.topicKey, row.sourceSummary, row.confidence.toString(),
        row.candidateStatus, row.sensitiveCategory.orEmpty(), row.createdAt.toString())
}
