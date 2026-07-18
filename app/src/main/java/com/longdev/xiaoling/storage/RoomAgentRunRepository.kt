package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.agent.APPROVAL_REQUEST_NO_EXPIRY_AT
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.AgentRunLedger
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentRunResumeKind
import com.longdev.xiaoling.agent.AgentRunResumePolicy
import com.longdev.xiaoling.agent.AgentStepRecord
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventMetadataCodec
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.isWaitingForInteractiveApprovalDecision
import com.longdev.xiaoling.agent.isTerminal
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
    override suspend fun createRun(
        conversationId: String,
        userMessageId: String,
        goal: String,
        retryOfRunId: String?,
    ): AgentRunRecord {
        val now = System.currentTimeMillis()
        val run = AgentRunRecord(
            id = "run-${UUID.randomUUID()}",
            retryOfRunId = retryOfRunId,
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
        val completedAt = if (status.isTerminal) now else current.completedAt
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

    override suspend fun appendEvent(runId: String, type: String, message: String, metadata: RunEventMetadata?) {
        database.agentRunDao().insertEvent(
            RunEventEntity(
                id = "event-${UUID.randomUUID()}",
                runId = runId,
                type = type,
                message = message,
                metadataJson = metadata?.let(RunEventMetadataCodec::encode),
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
            expiresAt = APPROVAL_REQUEST_NO_EXPIRY_AT,
            decidedAt = null,
        )
        database.agentRunDao().upsertApprovalRequest(request.toEntity())
        appendEvent(
            runId = runId,
            type = "approval.requested",
            message = "等待审批：${request.toolName}",
            metadata = request.toEventMetadata(),
        )
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
        appendEvent(
            runId = decided.runId,
            type = "approval.request_decided",
            message = "审批状态已更新：${decided.toolName} -> ${decided.status.name}",
            metadata = decided.toEventMetadata(),
        )
        return decided
    }

    suspend fun pendingApprovalRequests(conversationId: String): List<ApprovalRequestRecord> {
        return activePendingApprovalRequests(
            database.agentRunDao()
                .getPendingApprovalRequests(conversationId)
                .map { it.toRecord() },
        )
    }

    suspend fun recoverPendingApprovalRuns(): List<AgentRunDetailRecord> {
        val dao = database.agentRunDao()
        val activeStatuses = listOf(
            AgentRunStatus.QUEUED,
            AgentRunStatus.THINKING,
            AgentRunStatus.WAITING_APPROVAL,
            AgentRunStatus.EXECUTING,
            AgentRunStatus.VERIFYING,
        )
        val resumable = dao.getRunsByStatuses(activeStatuses.map { it.name })
            .mapNotNull { run ->
                val detail = loadDetail(run)
                if (AgentRunResumePolicy.assess(detail).kind != AgentRunResumeKind.APPROVAL_WAIT) {
                    return@mapNotNull null
                }
                // long: 进程重建后只保留尚未执行工具的审批边界，并记录一次恢复审计；用户批准后从持久化 ToolCall 继续原 Run。
                appendEvent(
                    runId = run.id,
                    type = "run.recovered",
                    message = "已恢复待审批 Run，等待用户决定",
                    metadata = RunEventMetadata.Recovery(
                        fromStatus = AgentRunStatus.WAITING_APPROVAL,
                        toStatus = AgentRunStatus.WAITING_APPROVAL,
                        reason = "仅保留待审批且尚未执行工具的 Run",
                    ),
                )
                loadDetail(run)
            }
        return resumable
    }

    suspend fun recoverCommittedToolRuns(
        definitionLookup: (String) -> ToolDefinition?,
    ): List<AgentRunDetailRecord> {
        val dao = database.agentRunDao()
        val candidates = dao.getRunsByStatuses(
            listOf(AgentRunStatus.EXECUTING.name, AgentRunStatus.VERIFYING.name),
        )
        return candidates.mapNotNull { run ->
            val detail = loadDetail(run)
            val assessment = AgentRunResumePolicy.assess(detail, definitionLookup)
            if (assessment.kind != AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION) {
                return@mapNotNull null
            }
            val fromStatus = AgentRunStatus.valueOf(run.status)
            if (fromStatus != AgentRunStatus.VERIFYING) {
                updateRunStatus(run.id, AgentRunStatus.VERIFYING)
            }
            appendEvent(
                runId = run.id,
                type = "run.recovered",
                message = "已恢复已提交工具结果，准备只读验证",
                metadata = RunEventMetadata.Recovery(
                    fromStatus = fromStatus,
                    toStatus = AgentRunStatus.VERIFYING,
                    reason = assessment.reason,
                ),
            )
            loadDetail(dao.getRun(run.id) ?: return@mapNotNull null)
        }
    }

    suspend fun closeInterruptedRuns(
        definitionLookup: (String) -> ToolDefinition? = { null },
    ): Int {
        val dao = database.agentRunDao()
        val activeStatuses = listOf(
            AgentRunStatus.QUEUED,
            AgentRunStatus.THINKING,
            AgentRunStatus.WAITING_APPROVAL,
            AgentRunStatus.EXECUTING,
            AgentRunStatus.VERIFYING,
        )
        val interruptedRuns = dao.getRunsByStatuses(activeStatuses.map { it.name })
        if (interruptedRuns.isEmpty()) return 0
        val reason = "应用重启后终止上次未完成 Agent 任务"
        var closedCount = 0
        interruptedRuns.forEach { run ->
            val detail = loadDetail(run)
            if (AgentRunResumePolicy.assess(detail, definitionLookup).canResumeInPlace) return@forEach
            // long: 进程被系统杀掉后，内存里的协程和网络请求已经不存在；启动时把中间态 Run 收敛成 CANCELLED，避免任务中心长期显示不可继续的执行中状态。
            detail.snapshot.steps
                .filter { it.status == AgentStepStatus.PENDING || it.status == AgentStepStatus.RUNNING }
                .forEach { step ->
                    // long: Run 与活动步骤必须在同一次启动收敛中进入一致终态；保留 RUNNING 会误导用户并削弱重试的副作用判断。
                    updateStep(step.id, AgentStepStatus.CANCELLED, reason)
                }
            dao.getApprovalRequests(run.id)
                .map { it.toRecord() }
                .filter { it.status == ApprovalRequestStatus.PENDING }
                .forEach { request ->
                    decideApprovalRequest(
                        requestId = request.id,
                        status = ApprovalRequestStatus.CANCELLED,
                        reason = reason,
                    )
                }
            appendEvent(
                runId = run.id,
                type = "run.recovered",
                message = reason,
                metadata = RunEventMetadata.Recovery(
                    fromStatus = AgentRunStatus.valueOf(run.status),
                    toStatus = AgentRunStatus.CANCELLED,
                    reason = reason,
                ),
            )
            updateRunStatus(run.id, AgentRunStatus.CANCELLED, errorMessage = reason)
            closedCount += 1
        }
        return closedCount
    }

    suspend fun recentRunDetails(limit: Int): List<AgentRunDetailRecord> {
        val dao = database.agentRunDao()
        // long: 任务中心只读 Room 里的审计数据，不从当前页面状态反推；这样历史 Run、审批和事件在应用重启后仍可追溯。
        val runs = dao.getRecentRuns(limit)
        if (runs.isEmpty()) return emptyList()
        val runIds = runs.map { it.id }
        // long: 运行记录页会一次展示最近多条 Run；步骤、审批和事件必须批量读取再内存分组，避免每条 Run 触发多次 Room 查询。
        val stepsByRunId = dao.getStepsForRuns(runIds).groupBy { it.runId }
        val eventsByRunId = dao.getEventsForRuns(runIds).groupBy { it.runId }
        val approvalsByRunId = dao.getApprovalRequestsForRuns(runIds).groupBy { it.runId }
        return runs.map { run ->
            val snapshot = AgentRunSnapshot(
                run = run.toRecord(),
                steps = stepsByRunId[run.id].orEmpty().map { it.toRecord() },
                events = eventsByRunId[run.id].orEmpty().map { it.toRecord() },
            )
            AgentRunDetailRecord(
                snapshot = snapshot,
                approvals = approvalsByRunId[run.id].orEmpty().map { it.toRecord() },
            )
        }
    }

    suspend fun runDetail(runId: String): AgentRunDetailRecord? {
        val dao = database.agentRunDao()
        val run = dao.getRun(runId) ?: return null
        return loadDetail(run)
    }

    private suspend fun loadDetail(run: AgentRunEntity): AgentRunDetailRecord {
        val dao = database.agentRunDao()
        return AgentRunDetailRecord(
            snapshot = AgentRunSnapshot(
                run = run.toRecord(),
                steps = dao.getSteps(run.id).map { it.toRecord() },
                events = dao.getEvents(run.id).map { it.toRecord() },
            ),
            approvals = dao.getApprovalRequests(run.id).map { it.toRecord() },
        )
    }

    private suspend fun findStep(stepId: String): AgentStepEntity? {
        return database.agentRunDao().getStep(stepId)
    }

    private fun AgentRunRecord.toEntity() = AgentRunEntity(
        id = id,
        retryOfRunId = retryOfRunId,
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
        retryOfRunId = retryOfRunId,
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
        metadata = RunEventMetadataCodec.decode(type, metadataJson),
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

    private fun ApprovalRequestRecord.toEventMetadata(): RunEventMetadata {
        return RunEventMetadata.ApprovalRequest(
            id = id,
            toolName = toolName,
            risk = risk,
            arguments = arguments.toSortedMap(),
            status = status,
            expiresAt = expiresAt,
            reason = decisionReason,
        )
    }
}

internal fun activePendingApprovalRequests(requests: List<ApprovalRequestRecord>): List<ApprovalRequestRecord> {
    return requests.filter { it.isWaitingForInteractiveApprovalDecision() }
}
