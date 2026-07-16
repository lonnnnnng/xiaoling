package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.AgentRunLedger
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentStepRecord
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.data.AgentRunEntity
import com.longdev.xiaoling.data.AgentStepEntity
import com.longdev.xiaoling.data.RunEventEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import java.util.UUID

class RoomAgentRunRepository(
    context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
) : AgentRunLedger {
    override suspend fun createRun(conversationId: String, userMessageId: String, goal: String): AgentRunRecord {
        val now = System.currentTimeMillis()
        val run = AgentRunRecord(
            id = "run-${UUID.randomUUID()}",
            conversationId = conversationId,
            userMessageId = userMessageId,
            goal = goal,
            status = AgentRunStatus.QUEUED,
            result = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
            completedAt = null,
        )
        database.agentRunDao().upsertRun(run.toEntity())
        appendEvent(run.id, "run.created", "Agent Run 已创建")
        return run
    }

    override suspend fun updateRunStatus(runId: String, status: AgentRunStatus, result: String?, errorMessage: String?) {
        val current = database.agentRunDao().getRun(runId) ?: return
        val now = System.currentTimeMillis()
        val completedAt = when (status) {
            AgentRunStatus.COMPLETED,
            AgentRunStatus.FAILED,
            AgentRunStatus.CANCELLED,
            AgentRunStatus.BUDGET_EXHAUSTED -> now
            else -> current.completedAt
        }
        database.agentRunDao().upsertRun(
            current.copy(
                status = status.name,
                result = result ?: current.result,
                errorMessage = errorMessage ?: current.errorMessage,
                updatedAt = now,
                completedAt = completedAt,
            ),
        )
        appendEvent(runId, "run.status", status.name)
    }

    override suspend fun appendStep(
        runId: String,
        type: String,
        title: String,
        detail: String,
        status: AgentStepStatus,
    ): AgentStepRecord {
        val sequence = database.agentRunDao().getSteps(runId).size + 1
        val now = System.currentTimeMillis()
        val step = AgentStepRecord(
            id = "step-${UUID.randomUUID()}",
            runId = runId,
            sequence = sequence,
            type = type,
            status = status,
            title = title,
            detail = detail,
            createdAt = now,
            completedAt = null,
        )
        database.agentRunDao().upsertStep(step.toEntity())
        appendEvent(runId, "step.created", "$sequence. $title")
        return step
    }

    override suspend fun updateStep(stepId: String, status: AgentStepStatus, detail: String?) {
        val snapshot = findStep(stepId) ?: return
        database.agentRunDao().upsertStep(
            snapshot.copy(
                status = status.name,
                detail = detail ?: snapshot.detail,
                completedAt = if (status == AgentStepStatus.RUNNING || status == AgentStepStatus.PENDING) {
                    snapshot.completedAt
                } else {
                    System.currentTimeMillis()
                },
            ),
        )
        appendEvent(snapshot.runId, "step.status", "${snapshot.sequence}. ${snapshot.title} -> ${status.name}")
    }

    override suspend fun appendEvent(runId: String, type: String, message: String) {
        database.agentRunDao().insertEvent(
            RunEventEntity(
                id = "event-${UUID.randomUUID()}",
                runId = runId,
                type = type,
                message = message,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    override suspend fun snapshot(runId: String): AgentRunSnapshot {
        val run = database.agentRunDao().getRun(runId)?.toRecord()
            ?: error("Agent Run 不存在：$runId")
        return AgentRunSnapshot(
            run = run,
            steps = database.agentRunDao().getSteps(runId).map { it.toRecord() },
            events = database.agentRunDao().getEvents(runId).map { it.toRecord() },
        )
    }

    private suspend fun findStep(stepId: String): AgentStepEntity? {
        return database.agentRunDao().getStep(stepId)
    }

    private fun AgentRunRecord.toEntity() = AgentRunEntity(
        id = id,
        conversationId = conversationId,
        userMessageId = userMessageId,
        goal = goal,
        status = status.name,
        result = result,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )

    private fun AgentStepRecord.toEntity() = AgentStepEntity(
        id = id,
        runId = runId,
        sequence = sequence,
        type = type,
        status = status.name,
        title = title,
        detail = detail,
        createdAt = createdAt,
        completedAt = completedAt,
    )

    private fun AgentRunEntity.toRecord() = AgentRunRecord(
        id = id,
        conversationId = conversationId,
        userMessageId = userMessageId,
        goal = goal,
        status = runCatching { AgentRunStatus.valueOf(status) }.getOrDefault(AgentRunStatus.FAILED),
        result = result,
        errorMessage = errorMessage,
        createdAt = createdAt,
        updatedAt = updatedAt,
        completedAt = completedAt,
    )

    private fun AgentStepEntity.toRecord() = AgentStepRecord(
        id = id,
        runId = runId,
        sequence = sequence,
        type = type,
        status = runCatching { AgentStepStatus.valueOf(status) }.getOrDefault(AgentStepStatus.FAILED),
        title = title,
        detail = detail,
        createdAt = createdAt,
        completedAt = completedAt,
    )

    private fun RunEventEntity.toRecord() = RunEventRecord(
        id = id,
        runId = runId,
        type = type,
        message = message,
        createdAt = createdAt,
    )
}
