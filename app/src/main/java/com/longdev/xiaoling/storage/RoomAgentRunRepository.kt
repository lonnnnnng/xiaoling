package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.AgentRunLedger
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentStepRecord
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.data.AgentRunEntity
import com.longdev.xiaoling.data.AgentStepEntity
import com.longdev.xiaoling.data.ApprovalRequestEntity
import com.longdev.xiaoling.data.RunEventEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import org.json.JSONObject
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

    suspend fun createApprovalRequest(
        conversationId: String,
        runId: String,
        toolCall: ToolCall,
        definition: ToolDefinition,
        expiresAt: Long,
    ): ApprovalRequestRecord {
        val now = System.currentTimeMillis()
        val request = ApprovalRequestRecord(
            id = "approval-${UUID.randomUUID()}",
            runId = runId,
            conversationId = conversationId,
            toolCallId = toolCall.id,
            toolName = toolCall.name,
            toolDescription = definition.description,
            risk = definition.risk,
            arguments = toolCall.arguments,
            status = ApprovalRequestStatus.PENDING,
            decisionReason = null,
            createdAt = now,
            expiresAt = expiresAt,
            decidedAt = null,
        )
        database.agentRunDao().upsertApprovalRequest(request.toEntity())
        appendEvent(runId, "approval.requested", request.toEventMessage())
        return request
    }

    suspend fun decideApprovalRequest(
        requestId: String,
        status: ApprovalRequestStatus,
        reason: String,
    ): ApprovalRequestRecord? {
        val current = database.agentRunDao().getApprovalRequest(requestId)?.toRecord() ?: return null
        val decided = current.copy(
            status = status,
            decisionReason = reason,
            decidedAt = System.currentTimeMillis(),
        )
        database.agentRunDao().upsertApprovalRequest(decided.toEntity())
        appendEvent(decided.runId, "approval.request_decided", decided.toEventMessage())
        return decided
    }

    suspend fun pendingApprovalRequests(conversationId: String): List<ApprovalRequestRecord> {
        val now = System.currentTimeMillis()
        return database.agentRunDao()
            .getPendingApprovalRequests(conversationId)
            .map { it.toRecord() }
            .map { request ->
                if (request.expiresAt <= now) {
                    decideApprovalRequest(
                        requestId = request.id,
                        status = ApprovalRequestStatus.EXPIRED,
                        reason = "审批请求已过期",
                    ) ?: request.copy(status = ApprovalRequestStatus.EXPIRED, decisionReason = "审批请求已过期", decidedAt = now)
                } else {
                    request
                }
            }
            .filter { it.status == ApprovalRequestStatus.PENDING }
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

    private fun ApprovalRequestRecord.toEntity() = ApprovalRequestEntity(
        id = id,
        runId = runId,
        conversationId = conversationId,
        toolCallId = toolCallId,
        toolName = toolName,
        toolDescription = toolDescription,
        risk = risk.name,
        argumentsJson = arguments.toJsonObject().toString(),
        status = status.name,
        decisionReason = decisionReason,
        createdAt = createdAt,
        expiresAt = expiresAt,
        decidedAt = decidedAt,
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

    private fun ApprovalRequestEntity.toRecord() = ApprovalRequestRecord(
        id = id,
        runId = runId,
        conversationId = conversationId,
        toolCallId = toolCallId,
        toolName = toolName,
        toolDescription = toolDescription,
        risk = runCatching { ToolRisk.valueOf(risk) }.getOrDefault(ToolRisk.REQUIRES_APPROVAL),
        arguments = argumentsJson.toStringMap(),
        status = runCatching { ApprovalRequestStatus.valueOf(status) }.getOrDefault(ApprovalRequestStatus.EXPIRED),
        decisionReason = decisionReason,
        createdAt = createdAt,
        expiresAt = expiresAt,
        decidedAt = decidedAt,
    )

    private fun RunEventEntity.toRecord() = RunEventRecord(
        id = id,
        runId = runId,
        type = type,
        message = message,
        createdAt = createdAt,
    )

    private fun Map<String, String>.toJsonObject(): JSONObject {
        val json = JSONObject()
        toSortedMap().forEach { (key, value) -> json.put(key, value) }
        return json
    }

    private fun String.toStringMap(): Map<String, String> {
        return runCatching {
            val json = JSONObject(this)
            buildMap {
                json.keys().forEach { key -> put(key, json.optString(key)) }
            }
        }.getOrDefault(emptyMap())
    }

    private fun ApprovalRequestRecord.toEventMessage(): String {
        return JSONObject()
            .put("id", id)
            .put("tool", toolName)
            .put("risk", risk.name)
            .put("status", status.name)
            .put("expiresAt", expiresAt)
            .put("decisionReason", decisionReason)
            .put("arguments", arguments.toJsonObject())
            .toString()
    }
}
