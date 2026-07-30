package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.withTransaction
import com.longdev.xiaoling.agent.APPROVAL_REQUEST_NO_EXPIRY_AT
import com.longdev.xiaoling.agent.ApprovalRequestRecord
import com.longdev.xiaoling.agent.ApprovalRequestStatus
import com.longdev.xiaoling.agent.AgentRunLedger
import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentEventTypes
import com.longdev.xiaoling.agent.AgentPersistedToolFailureRecovery
import com.longdev.xiaoling.agent.AgentPersistedToolVerificationFailureRecovery
import com.longdev.xiaoling.agent.AgentRunResumeKind
import com.longdev.xiaoling.agent.AgentRunResumePolicy
import com.longdev.xiaoling.agent.AgentStepRecord
import com.longdev.xiaoling.agent.AgentStepStatus
import com.longdev.xiaoling.agent.AgentStepTypes
import com.longdev.xiaoling.agent.AgentTaskRetryPolicy
import com.longdev.xiaoling.agent.AgentToolCallRecord
import com.longdev.xiaoling.agent.AgentToolLedgerRecord
import com.longdev.xiaoling.agent.AgentToolResultRecord
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventMetadataCodec
import com.longdev.xiaoling.agent.ToolCall
import com.longdev.xiaoling.agent.ToolDefinition
import com.longdev.xiaoling.agent.ToolDefinitionRecoveryContract
import com.longdev.xiaoling.agent.ToolExecutionReceipt
import com.longdev.xiaoling.agent.ToolExecutionReceiptStatus
import com.longdev.xiaoling.agent.ToolReplaySafety
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.ToolVerificationStatus
import com.longdev.xiaoling.agent.isWaitingForInteractiveApprovalDecision
import com.longdev.xiaoling.agent.isTerminal
import com.longdev.xiaoling.data.AgentRunEntity
import com.longdev.xiaoling.data.AgentStepEntity
import com.longdev.xiaoling.data.AgentToolCallEntity
import com.longdev.xiaoling.data.AgentToolResultEntity
import com.longdev.xiaoling.knowledge.KnowledgeReferenceCodec
import com.longdev.xiaoling.data.ApprovalRequestEntity
import com.longdev.xiaoling.data.RunEventEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

private data class RecoveryBoundary(
    val kind: AgentRunResumeKind,
    val evidenceId: String,
) {
    val key: String = "${kind.name}:$evidenceId"
}

private data class RecoveryMarkerSpec(
    val runId: String,
    val fromStatus: AgentRunStatus,
    val toStatus: AgentRunStatus,
    val boundary: RecoveryBoundary,
    val boundaryEventId: String?,
    val message: String,
    val reason: String,
)

private enum class RecoveryMarkerWriteResult {
    APPENDED,
    EXISTING,
    REJECTED,
}

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
        val now = System.currentTimeMillis()
        // long: 启动恢复与旧模型协程可能并发写入同一 Run；终态条件必须落在单条 SQL 中，避免“先读后写”让迟到结果覆盖已冻结的恢复处置。
        val updatedRows = database.agentRunDao().updateRunStatusIfActive(
            runId = runId,
            status = status.name,
            result = result,
            errorMessage = errorMessage,
            updatedAt = now,
            completedAt = now.takeIf { status.isTerminal },
            terminalStatuses = TERMINAL_RUN_STATUS_NAMES,
        )
        if (updatedRows == 0) return
        appendEventInternal(runId, "run.status", status.name, metadata = null, allowTerminalRun = true)
    }

    override suspend fun appendStep(
        runId: String,
        type: String,
        title: String,
        detail: String,
        status: AgentStepStatus,
    ): AgentStepRecord = database.withTransaction {
        val dao = database.agentRunDao()
        val run = dao.getRun(runId) ?: error("Agent Run 不存在：$runId")
        // long: Run 进入终态后整条子账本随之冻结；迟到模型回调不能再追加步骤，否则取消后的任务会重新出现执行痕迹。
        check(run.status !in TERMINAL_RUN_STATUS_NAMES) { "Agent Run 已结束，不能追加步骤：$runId" }
        val currentSteps = dao.getSteps(runId)
        if (type == AgentStepTypes.RECOVERY_SUMMARIZE) {
            val existingRecoverySteps = currentSteps.filter { it.type == AgentStepTypes.RECOVERY_SUMMARIZE }
            check(existingRecoverySteps.size <= 1) { "恢复总结步骤出现重复：$runId" }
            // long: 两个启动协调器可能同时进入同一 verified boundary；事务内 get-or-create 让它们复用同一个总结 Step，避免并发追加后永久失去恢复资格。
            existingRecoverySteps.singleOrNull()?.let { return@withTransaction it.toRecord() }
        }
        val sequence = currentSteps.size + 1
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
        dao.upsertStep(step.toEntity())
        appendEventInternal(
            runId,
            AgentEventTypes.STEP_CREATED,
            "$sequence. $title",
            metadata = RunEventMetadata.StepCreated(
                stepId = step.id,
                sequence = step.sequence,
                stepType = step.type,
                status = step.status,
            ),
        )
        step
    }

    override suspend fun updateStep(stepId: String, status: AgentStepStatus, detail: String?) {
        database.withTransaction {
            val dao = database.agentRunDao()
            val snapshot = dao.getStep(stepId) ?: return@withTransaction
            val run = dao.getRun(snapshot.runId) ?: return@withTransaction
            // long: 用户停止或恢复收敛完成后，旧协程只能退出，不能把已取消 Step 改回 RUNNING/COMPLETED。
            if (run.status in TERMINAL_RUN_STATUS_NAMES) return@withTransaction
            val fromStatus = AgentStepStatus.valueOf(snapshot.status)
            val nextDetail = detail ?: snapshot.detail
            if (fromStatus == status && nextDetail == snapshot.detail) return@withTransaction
            dao.upsertStep(
                snapshot.copy(
                    status = status.name,
                    detail = nextDetail,
                    completedAt = if (status == AgentStepStatus.RUNNING || status == AgentStepStatus.PENDING) {
                        snapshot.completedAt
                    } else {
                        System.currentTimeMillis()
                    },
                ),
            )
            appendEventInternal(
                snapshot.runId,
                AgentEventTypes.STEP_STATUS,
                "${snapshot.sequence}. ${snapshot.title} -> ${status.name}",
                metadata = RunEventMetadata.StepStatus(
                    stepId = snapshot.id,
                    sequence = snapshot.sequence,
                    fromStatus = fromStatus,
                    toStatus = status,
                ),
            )
        }
    }

    override suspend fun appendEvent(runId: String, type: String, message: String, metadata: RunEventMetadata?) {
        appendEventInternal(runId, type, message, metadata)
    }

    private suspend fun appendEventInternal(
        runId: String,
        type: String,
        message: String,
        metadata: RunEventMetadata?,
        allowTerminalRun: Boolean = false,
    ) {
        val event = RunEventEntity(
            id = "event-${UUID.randomUUID()}",
            runId = runId,
            type = type,
            message = message,
            metadataJson = metadata?.let(RunEventMetadataCodec::encode),
            createdAt = System.currentTimeMillis(),
        )
        database.withTransaction {
            val dao = database.agentRunDao()
            val run = dao.getRun(runId) ?: return@withTransaction
            // long: 终态后的迟到模型、工具与恢复事件一律丢弃；仅 Repository 自己写入终态 status 审计时显式放行。
            if (!allowTerminalRun && run.status in TERMINAL_RUN_STATUS_NAMES) return@withTransaction
            if (type == AgentEventTypes.RECOVERY_SUMMARY) {
                val existingSummaryEvents = dao.getEvents(runId).filter { it.type == AgentEventTypes.RECOVERY_SUMMARY }
                check(existingSummaryEvents.size <= 1) { "恢复总结事件出现重复：$runId" }
                existingSummaryEvents.singleOrNull()?.let { existing ->
                    val record = existing.toRecord()
                    check(record.message == message && record.metadata == metadata) {
                        "恢复总结事件与既有控制面事实冲突：$runId"
                    }
                    return@withTransaction
                }
            }
            dao.insertEvent(event)
            // long: RunEvent 在迁移期仍是时间线事实源；工具账本必须与对应事件同事务写入，避免进程中断后两套审计证据只成功一半。
            when {
                metadata is RunEventMetadata.ToolCall && type in TOOL_CALL_EVENT_TYPES ->
                    persistToolCall(event, metadata)
                metadata is RunEventMetadata.ToolResult && type == TOOL_RESULT_EVENT_TYPE ->
                    persistToolResult(event, metadata)
                metadata is RunEventMetadata.ToolVerification && type == TOOL_VERIFICATION_EVENT_TYPE ->
                    persistToolVerification(event, metadata)
            }
        }
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
    ): ApprovalRequestRecord = database.withTransaction {
        val dao = database.agentRunDao()
        val run = dao.getRun(runId) ?: error("Agent Run 不存在：$runId")
        check(run.status !in TERMINAL_RUN_STATUS_NAMES) { "Agent Run 已结束，不能创建审批：$runId" }
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
        dao.upsertApprovalRequest(request.toEntity())
        appendEventInternal(
            runId = runId,
            type = "approval.requested",
            message = "等待审批：${request.toolName}",
            metadata = request.toEventMetadata(
                definitionFingerprint = ToolDefinitionRecoveryContract.snapshot(definition).definitionFingerprint,
            ),
        )
        request
    }

    suspend fun decideApprovalRequest(
        requestId: String,
        status: ApprovalRequestStatus,
        reason: String,
    ): ApprovalRequestRecord? = database.withTransaction {
        val dao = database.agentRunDao()
        val current = dao.getApprovalRequest(requestId)?.toRecord() ?: return@withTransaction null
        val run = dao.getRun(current.runId) ?: return@withTransaction null
        // long: 审批决定是一次性状态迁移；Run 已结束或请求已处理时拒绝迟到决定，避免 CANCELLED 被覆盖为 APPROVED/DENIED。
        if (run.status in TERMINAL_RUN_STATUS_NAMES || current.status != ApprovalRequestStatus.PENDING) {
            return@withTransaction null
        }
        val decided = current.copy(
            status = status,
            decisionReason = reason,
            decidedAt = System.currentTimeMillis(),
        )
        // long: 审批决定必须沿用请求时冻结的工具定义指纹，恢复资格才能证明用户批准的是同一份工具语义，而不是事后变化的 Registry。
        val definitionFingerprint = dao.getEvents(current.runId)
            .asSequence()
            .filter { it.type == "approval.requested" }
            .mapNotNull { it.toRecord().metadata as? RunEventMetadata.ApprovalRequest }
            .singleOrNull { it.id == current.id }
            ?.definitionFingerprint
        dao.upsertApprovalRequest(decided.toEntity())
        appendEventInternal(
            runId = decided.runId,
            type = "approval.request_decided",
            message = "审批状态已更新：${decided.toolName} -> ${decided.status.name}",
            metadata = decided.toEventMetadata(definitionFingerprint),
        )
        decided
    }

    suspend fun rejectRecoveredApproval(
        requestId: String,
        runId: String,
        reason: String,
    ): AgentRunDetailRecord? {
        require(reason.isNotBlank()) { "拒绝原因不能为空" }
        return database.withTransaction {
            val dao = database.agentRunDao()
            val run = dao.getRun(runId) ?: return@withTransaction null
            if (run.status in TERMINAL_RUN_STATUS_NAMES) return@withTransaction null
            val detail = loadDetail(run)
            val assessment = AgentRunResumePolicy.assess(detail)
            val recovery = assessment.approvalWait
            if (
                assessment.kind != AgentRunResumeKind.APPROVAL_WAIT ||
                recovery?.approvalRequestId != requestId
            ) {
                return@withTransaction null
            }
            // long: 恢复审批拒绝必须把决定、活动审批步骤和 Run 放在同一事务；任何一步失败都回滚，不能留下 WAITING_APPROVAL 与 DENIED 并存的半状态。
            decideApprovalRequest(
                requestId = requestId,
                status = ApprovalRequestStatus.DENIED,
                reason = reason,
            ) ?: return@withTransaction null
            updateStep(
                stepId = recovery.approvalStepId,
                status = AgentStepStatus.FAILED,
                detail = reason,
            )
            updateRunStatus(
                runId = runId,
                status = AgentRunStatus.FAILED,
                errorMessage = reason,
            )
            val settled = dao.getRun(runId)?.let { loadDetail(it) }
                ?: error("拒绝恢复审批后 Agent Run 已丢失：$runId")
            check(settled.snapshot.run.status == AgentRunStatus.FAILED) {
                "拒绝恢复审批后 Agent Run 未进入失败终态：$runId"
            }
            settled
        }
    }

    suspend fun pendingApprovalRequests(conversationId: String): List<ApprovalRequestRecord> {
        return activePendingApprovalRequests(
            database.agentRunDao()
                .getPendingApprovalRequests(conversationId)
                .map { it.toRecord() },
        )
    }

    suspend fun cancelActiveRun(runId: String, reason: String): Boolean {
        require(reason.isNotBlank()) { "取消原因不能为空" }
        return database.withTransaction {
            val dao = database.agentRunDao()
            val run = dao.getRun(runId) ?: return@withTransaction false
            if (run.status in TERMINAL_RUN_STATUS_NAMES) return@withTransaction false
            val detail = loadDetail(run)
            // long: 用户停止必须同时关闭当前活动步骤和审批；只改 Run 会让任务中心继续显示可执行的中间态，后续重试也会误判副作用边界。
            detail.snapshot.steps
                .filter { it.status == AgentStepStatus.PENDING || it.status == AgentStepStatus.RUNNING }
                .forEach { step -> updateStep(step.id, AgentStepStatus.CANCELLED, reason) }
            detail.approvals
                .filter { it.status == ApprovalRequestStatus.PENDING }
                .forEach { request ->
                    decideApprovalRequest(request.id, ApprovalRequestStatus.CANCELLED, reason)
                }
            val now = System.currentTimeMillis()
            val updatedRows = dao.updateRunStatusIfActive(
                runId = runId,
                status = AgentRunStatus.CANCELLED.name,
                result = null,
                errorMessage = reason,
                updatedAt = now,
                completedAt = now,
                terminalStatuses = TERMINAL_RUN_STATUS_NAMES,
            )
            if (updatedRows == 0) return@withTransaction false
            appendEventInternal(runId, "run.cancelled", reason, RunEventMetadata.Reason(reason), allowTerminalRun = true)
            appendEventInternal(runId, "run.status", AgentRunStatus.CANCELLED.name, metadata = null, allowTerminalRun = true)
            true
        }
    }

    suspend fun activeRunIds(): Set<String> {
        return database.agentRunDao()
            .getRunsByStatuses(ACTIVE_RUN_STATUS_NAMES)
            .mapTo(linkedSetOf()) { it.id }
    }

    suspend fun recoverPendingApprovalRuns(runIds: Set<String>? = null): List<AgentRunDetailRecord> {
        val dao = database.agentRunDao()
        val resumable = dao.getRunsByStatuses(ACTIVE_RUN_STATUS_NAMES)
            .filter { runIds == null || it.id in runIds }
            .mapNotNull { run ->
                val detail = loadDetail(run)
                val assessment = AgentRunResumePolicy.assess(detail)
                if (assessment.kind != AgentRunResumeKind.APPROVAL_WAIT) {
                    return@mapNotNull null
                }
                val recovery = checkNotNull(assessment.approvalWait) { "恢复策略缺少待审批边界" }
                val boundaryEventId = detail.snapshot.events.lastOrNull { event ->
                    event.type == "approval.requested" &&
                        (event.metadata as? RunEventMetadata.ApprovalRequest)?.id == recovery.approvalRequestId
                }?.id
                // long: 进程重建后保留链尾尚未执行的审批边界；前序工具必须已有完整成功验证证据，批准后从原 Run 继续且不会重放已完成副作用。
                val writeResult = ensureRecoveryMarker(
                    RecoveryMarkerSpec(
                        runId = run.id,
                        fromStatus = AgentRunStatus.WAITING_APPROVAL,
                        toStatus = AgentRunStatus.WAITING_APPROVAL,
                        boundary = RecoveryBoundary(AgentRunResumeKind.APPROVAL_WAIT, recovery.approvalRequestId),
                        boundaryEventId = boundaryEventId,
                        message = "已恢复待审批 Run，等待用户决定",
                        reason = assessment.reason,
                    ),
                )
                if (writeResult == RecoveryMarkerWriteResult.REJECTED) return@mapNotNull null
                val freshRun = dao.getRun(run.id) ?: return@mapNotNull null
                val freshDetail = loadDetail(freshRun)
                val freshAssessment = AgentRunResumePolicy.assess(freshDetail)
                freshDetail.takeIf {
                    freshAssessment.kind == AgentRunResumeKind.APPROVAL_WAIT &&
                        freshAssessment.approvalWait?.approvalRequestId == recovery.approvalRequestId
                }
            }
        return resumable
    }

    suspend fun recoverCommittedToolRuns(
        definitionLookup: (String) -> ToolDefinition?,
        committedVerificationSupport: (String) -> Boolean,
        runIds: Set<String>? = null,
    ): List<AgentRunDetailRecord> {
        val dao = database.agentRunDao()
        val candidates = dao.getRunsByStatuses(
            listOf(AgentRunStatus.EXECUTING.name, AgentRunStatus.VERIFYING.name),
        )
        return candidates
            .filter { runIds == null || it.id in runIds }
            .mapNotNull { run ->
                val detail = loadDetail(run)
                val assessment = AgentRunResumePolicy.assess(detail, definitionLookup, committedVerificationSupport)
                if (assessment.kind != AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION) {
                    return@mapNotNull null
                }
                val recovery = checkNotNull(assessment.committedTool) { "恢复策略缺少已提交工具边界" }
                val fromStatus = AgentRunStatus.valueOf(run.status)
                val boundaryEventId = detail.snapshot.events.lastOrNull { event ->
                    event.type == TOOL_RESULT_EVENT_TYPE &&
                        (event.metadata as? RunEventMetadata.ToolResult)?.toolCallId == recovery.toolCall.id
                }?.id
                val writeResult = ensureRecoveryMarker(
                    RecoveryMarkerSpec(
                        runId = run.id,
                        fromStatus = fromStatus,
                        toStatus = AgentRunStatus.VERIFYING,
                        boundary = RecoveryBoundary(AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION, recovery.toolCall.id),
                        boundaryEventId = boundaryEventId,
                        message = "已恢复已提交工具结果，准备只读验证",
                        reason = assessment.reason,
                    ),
                )
                if (writeResult == RecoveryMarkerWriteResult.REJECTED) return@mapNotNull null
                val freshRun = dao.getRun(run.id) ?: return@mapNotNull null
                val freshDetail = loadDetail(freshRun)
                val freshAssessment = AgentRunResumePolicy.assess(
                    freshDetail,
                    definitionLookup,
                    committedVerificationSupport,
                )
                freshDetail.takeIf {
                    freshAssessment.kind == AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION &&
                        freshAssessment.committedTool?.toolCall?.id == recovery.toolCall.id
                }
            }
    }

    suspend fun recoverVerifiedToolRuns(runIds: Set<String>? = null): List<AgentRunDetailRecord> {
        val dao = database.agentRunDao()
        return dao.getRunsByStatuses(listOf(AgentRunStatus.VERIFYING.name))
            .filter { runIds == null || it.id in runIds }
            .mapNotNull { run ->
                val detail = loadDetail(run)
                val assessment = AgentRunResumePolicy.assess(detail)
                if (assessment.kind != AgentRunResumeKind.VERIFIED_TOOL_COMPLETION) {
                    return@mapNotNull null
                }
                val recovery = checkNotNull(assessment.verifiedTool) { "恢复策略缺少已验证工具边界" }
                val boundaryToolCallId = recovery.verifiedTools.last().toolCall.id
                val boundaryEventId = detail.snapshot.events.lastOrNull { event ->
                    event.type == TOOL_VERIFICATION_EVENT_TYPE &&
                        (event.metadata as? RunEventMetadata.ToolVerification)?.toolCallId == boundaryToolCallId
                }?.id
                // long: 所有 tool.verify 均已落库时不再触碰 Executor；启动阶段只登记控制面恢复，随后由 Runtime 补齐 Step 和本地总结。
                val writeResult = ensureRecoveryMarker(
                    RecoveryMarkerSpec(
                        runId = run.id,
                        fromStatus = AgentRunStatus.VERIFYING,
                        toStatus = AgentRunStatus.VERIFYING,
                        boundary = RecoveryBoundary(AgentRunResumeKind.VERIFIED_TOOL_COMPLETION, recovery.lastVerificationStepId),
                        boundaryEventId = boundaryEventId,
                        message = "已恢复全部验证通过的工具结果，准备完成原 Run",
                        reason = assessment.reason,
                    ),
                )
                if (writeResult == RecoveryMarkerWriteResult.REJECTED) return@mapNotNull null
                val freshRun = dao.getRun(run.id) ?: return@mapNotNull null
                val freshDetail = loadDetail(freshRun)
                val freshAssessment = AgentRunResumePolicy.assess(freshDetail)
                freshDetail.takeIf {
                    freshAssessment.kind == AgentRunResumeKind.VERIFIED_TOOL_COMPLETION &&
                        freshAssessment.verifiedTool?.lastVerificationStepId == recovery.lastVerificationStepId
                }
            }
    }

    private suspend fun ensureRecoveryMarker(
        spec: RecoveryMarkerSpec,
    ): RecoveryMarkerWriteResult = database.withTransaction {
        val dao = database.agentRunDao()
        val run = dao.getRun(spec.runId) ?: return@withTransaction RecoveryMarkerWriteResult.REJECTED
        if (run.status in TERMINAL_RUN_STATUS_NAMES) return@withTransaction RecoveryMarkerWriteResult.REJECTED
        if (run.status != spec.fromStatus.name && run.status != spec.toStatus.name) {
            return@withTransaction RecoveryMarkerWriteResult.REJECTED
        }
        val events = dao.getEvents(spec.runId)
        val boundaryIndex = spec.boundaryEventId
            ?.let { id -> events.indexOfFirst { event -> event.id == id } }
            ?.takeIf { it >= 0 }
            ?: return@withTransaction RecoveryMarkerWriteResult.REJECTED
        val recoveryEvents = events.withIndex().filter { (index, event) ->
            index >= boundaryIndex && event.type == RECOVERY_EVENT_TYPE
        }
        // long: 同一恢复边界只能有一条 marker；先完整计数再解析，避免第一条合法记录掩盖后续损坏或漂移证据。
        if (recoveryEvents.size > 1) return@withTransaction RecoveryMarkerWriteResult.REJECTED
        recoveryEvents.singleOrNull()?.let { (_, event) ->
            val metadata = RunEventMetadataCodec.decode(event.type, event.metadataJson) as? RunEventMetadata.Recovery
                ?: return@withTransaction RecoveryMarkerWriteResult.REJECTED
            val hasBoundaryKey = metadata.recoveryBoundaryKey != null
            val hasResumeKind = metadata.resumeKind != null
            if (hasBoundaryKey != hasResumeKind) {
                return@withTransaction RecoveryMarkerWriteResult.REJECTED
            }
            if (hasBoundaryKey) {
                if (metadata.recoveryBoundaryKey != spec.boundary.key) {
                    return@withTransaction RecoveryMarkerWriteResult.REJECTED
                }
                val statusMatches = metadata.toStatus == spec.toStatus &&
                    (
                        metadata.fromStatus == spec.fromStatus ||
                            (
                                spec.boundary.kind == AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION &&
                                    metadata.fromStatus == AgentRunStatus.EXECUTING &&
                                    spec.fromStatus == AgentRunStatus.VERIFYING
                                )
                        )
                val exactMatch = metadata.resumeKind == spec.boundary.kind &&
                    statusMatches &&
                    metadata.reason == spec.reason &&
                    event.message == spec.message
                return@withTransaction if (exactMatch && run.status == spec.toStatus.name) {
                    RecoveryMarkerWriteResult.EXISTING
                } else {
                    RecoveryMarkerWriteResult.REJECTED
                }
            }
            // long: 旧版 marker 只有状态和固定文案；兼容分支必须同时缺少边界键与恢复类型，并位于当前边界事件之后，不能让新格式冲突记录降级命中。
            val legacyStatusMatches = metadata.toStatus == spec.toStatus &&
                (
                    metadata.fromStatus == spec.fromStatus ||
                        (
                            spec.boundary.kind == AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION &&
                                metadata.fromStatus == AgentRunStatus.EXECUTING &&
                                spec.fromStatus == AgentRunStatus.VERIFYING
                            )
                    )
            val legacyMatch = metadata.retryEvidenceCode == null &&
                metadata.retryEvidenceFingerprint == null &&
                metadata.restartDisposition == null &&
                metadata.reason.isNotBlank() &&
                legacyStatusMatches &&
                event.message == spec.message
            return@withTransaction if (legacyMatch && run.status == spec.toStatus.name) {
                RecoveryMarkerWriteResult.EXISTING
            } else {
                RecoveryMarkerWriteResult.REJECTED
            }
        }
        val now = System.currentTimeMillis()
        if (spec.fromStatus != spec.toStatus) {
            if (run.status != spec.fromStatus.name) {
                return@withTransaction RecoveryMarkerWriteResult.REJECTED
            }
            val updatedRows = dao.updateRunStatusIfExpected(
                runId = spec.runId,
                expectedStatus = spec.fromStatus.name,
                status = spec.toStatus.name,
                result = null,
                errorMessage = null,
                updatedAt = now,
                completedAt = null,
            )
            if (updatedRows != 1) return@withTransaction RecoveryMarkerWriteResult.REJECTED
            // long: committed 恢复的状态迁移、run.status 与 marker 共用一个事务；任何进程中断只能看见完整边界或完全看不见。
            dao.insertEvent(
                RunEventEntity(
                    id = "event-${UUID.randomUUID()}",
                    runId = spec.runId,
                    type = "run.status",
                    message = spec.toStatus.name,
                    metadataJson = null,
                    createdAt = now,
                ),
            )
        } else if (run.status != spec.toStatus.name) {
            return@withTransaction RecoveryMarkerWriteResult.REJECTED
        }
        dao.insertEvent(
            RunEventEntity(
                id = "event-${UUID.randomUUID()}",
                runId = spec.runId,
                type = RECOVERY_EVENT_TYPE,
                message = spec.message,
                metadataJson = RunEventMetadataCodec.encode(
                    RunEventMetadata.Recovery(
                        fromStatus = spec.fromStatus,
                        toStatus = spec.toStatus,
                        reason = spec.reason,
                        resumeKind = spec.boundary.kind,
                        recoveryBoundaryKey = spec.boundary.key,
                    ),
                ),
                createdAt = now,
            ),
        )
        RecoveryMarkerWriteResult.APPENDED
    }

    suspend fun closeInterruptedRuns(
        definitionLookup: (String) -> ToolDefinition? = { null },
        committedVerificationSupport: (String) -> Boolean = { false },
        runIds: Set<String>? = null,
        preserveResumableCandidates: Boolean = true,
    ): Int {
        val dao = database.agentRunDao()
        val interruptedRuns = dao.getRunsByStatuses(ACTIVE_RUN_STATUS_NAMES)
            .filter { runIds == null || it.id in runIds }
        if (interruptedRuns.isEmpty()) return 0
        val reason = "应用重启后终止上次未完成 Agent 任务"
        var closedCount = 0
        interruptedRuns.forEach { run ->
            val closed = database.withTransaction {
                val freshRun = dao.getRun(run.id) ?: return@withTransaction false
                if (freshRun.status in TERMINAL_RUN_STATUS_NAMES) return@withTransaction false
                val detail = loadDetail(freshRun)
                val resumeAssessment = AgentRunResumePolicy.assess(
                    detail,
                    definitionLookup,
                    committedVerificationSupport,
                )
                if (resumeAssessment.kind == AgentRunResumeKind.PERSISTED_TOOL_FAILURE_SETTLEMENT) {
                    val recovery = checkNotNull(resumeAssessment.persistedToolFailure) {
                        "恢复策略缺少失败 ToolResult 收敛边界"
                    }
                    return@withTransaction settlePersistedToolFailure(detail, recovery, resumeAssessment.reason)
                }
                if (
                    resumeAssessment.kind ==
                    AgentRunResumeKind.PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT
                ) {
                    val recovery = checkNotNull(resumeAssessment.persistedToolVerificationFailure) {
                        "恢复策略缺少失败工具验证收敛边界"
                    }
                    return@withTransaction settlePersistedToolVerificationFailure(
                        detail,
                        recovery,
                        resumeAssessment.reason,
                    )
                }
                if (preserveResumableCandidates && resumeAssessment.canResumeInPlace) {
                    return@withTransaction false
                }
                val fromStatus = AgentRunStatus.valueOf(freshRun.status)
                val retryEvidence = AgentTaskRetryPolicy.assessEvidenceBeforeRecovery(detail, fromStatus)
                // long: 活动 Step、待审批、恢复结论和 CANCELLED 状态必须同事务收敛；进程中断不能留下“Run 已结束但缺 marker/status”的半条时间线。
                detail.snapshot.steps
                    .filter { it.status == AgentStepStatus.PENDING || it.status == AgentStepStatus.RUNNING }
                    .forEach { step -> updateStep(step.id, AgentStepStatus.CANCELLED, reason) }
                detail.approvals
                    .filter { it.status == ApprovalRequestStatus.PENDING }
                    .forEach { request ->
                        decideApprovalRequest(
                            requestId = request.id,
                            status = ApprovalRequestStatus.CANCELLED,
                            reason = reason,
                        )
                    }
                val now = System.currentTimeMillis()
                val updatedRows = dao.updateRunStatusIfExpected(
                    runId = freshRun.id,
                    expectedStatus = fromStatus.name,
                    status = AgentRunStatus.CANCELLED.name,
                    result = null,
                    errorMessage = reason,
                    updatedAt = now,
                    completedAt = now,
                )
                check(updatedRows == 1) { "Agent Run 启动收敛状态发生并发漂移：${freshRun.id}" }
                // long: 恢复处置先于终态 status 出现在时间线中，任务中心可以按固定顺序解释旧 Run 为什么被关闭。
                dao.insertEvent(
                    RunEventEntity(
                        id = "event-${UUID.randomUUID()}",
                        runId = freshRun.id,
                        type = RECOVERY_EVENT_TYPE,
                        message = reason,
                        metadataJson = RunEventMetadataCodec.encode(
                            RunEventMetadata.Recovery(
                                fromStatus = fromStatus,
                                toStatus = AgentRunStatus.CANCELLED,
                                reason = reason,
                                retryEvidenceCode = retryEvidence.code,
                                retryEvidenceFingerprint = retryEvidence.fingerprint,
                                resumeKind = resumeAssessment.kind,
                                restartDisposition = resumeAssessment.restartDisposition,
                            ),
                        ),
                        createdAt = now,
                    ),
                )
                dao.insertEvent(
                    RunEventEntity(
                        id = "event-${UUID.randomUUID()}",
                        runId = freshRun.id,
                        type = "run.status",
                        message = AgentRunStatus.CANCELLED.name,
                        metadataJson = null,
                        createdAt = now,
                    ),
                )
                true
            }
            if (closed) closedCount += 1
        }
        return closedCount
    }

    private suspend fun settlePersistedToolFailure(
        detail: AgentRunDetailRecord,
        recovery: AgentPersistedToolFailureRecovery,
        recoveryReason: String,
    ): Boolean {
        val dao = database.agentRunDao()
        val runId = detail.snapshot.run.id
        val run = dao.getRun(runId) ?: return false
        if (run.status != AgentRunStatus.EXECUTING.name) return false
        val executionStep = dao.getStep(recovery.executionStepId) ?: return false
        if (
            executionStep.runId != runId ||
            executionStep.type != AgentStepTypes.TOOL_EXECUTE ||
            executionStep.status != AgentStepStatus.RUNNING.name
        ) {
            return false
        }
        val now = System.currentTimeMillis()
        // long: 失败 ToolResult 已经证明 Executor 返回了失败；这里只复现正常 Runtime catch 的控制面终态，原始回执和重试风险继续留在 Tool Ledger，不追加验证或再次执行工具。
        dao.upsertStep(
            executionStep.copy(
                status = AgentStepStatus.FAILED.name,
                detail = recovery.failureReason,
                completedAt = now,
            ),
        )
        dao.insertEvent(
            RunEventEntity(
                id = "event-${UUID.randomUUID()}",
                runId = runId,
                type = AgentEventTypes.STEP_STATUS,
                message = "${executionStep.sequence}. ${executionStep.title} -> ${AgentStepStatus.FAILED.name}",
                metadataJson = RunEventMetadataCodec.encode(
                    RunEventMetadata.StepStatus(
                        stepId = executionStep.id,
                        sequence = executionStep.sequence,
                        fromStatus = AgentStepStatus.RUNNING,
                        toStatus = AgentStepStatus.FAILED,
                    ),
                ),
                createdAt = now,
            ),
        )
        val updatedRows = dao.updateRunStatusIfExpected(
            runId = runId,
            expectedStatus = AgentRunStatus.EXECUTING.name,
            status = AgentRunStatus.FAILED.name,
            result = null,
            errorMessage = recovery.failureReason,
            updatedAt = now,
            completedAt = now,
        )
        check(updatedRows == 1) { "失败 ToolResult 收敛时 Run 状态发生并发漂移：$runId" }
        dao.insertEvent(
            RunEventEntity(
                id = "event-${UUID.randomUUID()}",
                runId = runId,
                type = RECOVERY_EVENT_TYPE,
                message = "已恢复失败工具结果，完成原 Run 失败收敛",
                metadataJson = RunEventMetadataCodec.encode(
                    RunEventMetadata.Recovery(
                        fromStatus = AgentRunStatus.EXECUTING,
                        toStatus = AgentRunStatus.FAILED,
                        reason = recoveryReason,
                        resumeKind = AgentRunResumeKind.PERSISTED_TOOL_FAILURE_SETTLEMENT,
                        recoveryBoundaryKey = RecoveryBoundary(
                            AgentRunResumeKind.PERSISTED_TOOL_FAILURE_SETTLEMENT,
                            recovery.toolCall.id,
                        ).key,
                    ),
                ),
                createdAt = now,
            ),
        )
        dao.insertEvent(
            RunEventEntity(
                id = "event-${UUID.randomUUID()}",
                runId = runId,
                type = "run.failed",
                message = recovery.failureReason,
                metadataJson = RunEventMetadataCodec.encode(RunEventMetadata.Reason(recovery.failureReason)),
                createdAt = now,
            ),
        )
        dao.insertEvent(
            RunEventEntity(
                id = "event-${UUID.randomUUID()}",
                runId = runId,
                type = "run.status",
                message = AgentRunStatus.FAILED.name,
                metadataJson = null,
                createdAt = now,
            ),
        )
        return true
    }

    private suspend fun settlePersistedToolVerificationFailure(
        detail: AgentRunDetailRecord,
        recovery: AgentPersistedToolVerificationFailureRecovery,
        recoveryReason: String,
    ): Boolean {
        val dao = database.agentRunDao()
        val runId = detail.snapshot.run.id
        val run = dao.getRun(runId) ?: return false
        if (run.status != AgentRunStatus.VERIFYING.name) return false
        val verificationStep = dao.getStep(recovery.verificationStepId) ?: return false
        if (
            verificationStep.runId != runId ||
            verificationStep.type != AgentStepTypes.TOOL_VERIFY ||
            verificationStep.status != AgentStepStatus.RUNNING.name
        ) {
            return false
        }
        val now = System.currentTimeMillis()
        // long: 失败验证已经作为 typed event 与 Tool Ledger 原子落库；这里只补正常异常出口原本应写入的 Step/Run 失败终态，不再执行任何回读或工具副作用。
        dao.upsertStep(
            verificationStep.copy(
                status = AgentStepStatus.FAILED.name,
                detail = recovery.failureReason,
                completedAt = now,
            ),
        )
        dao.insertEvent(
            RunEventEntity(
                id = "event-${UUID.randomUUID()}",
                runId = runId,
                type = AgentEventTypes.STEP_STATUS,
                message = "${verificationStep.sequence}. ${verificationStep.title} -> ${AgentStepStatus.FAILED.name}",
                metadataJson = RunEventMetadataCodec.encode(
                    RunEventMetadata.StepStatus(
                        stepId = verificationStep.id,
                        sequence = verificationStep.sequence,
                        fromStatus = AgentStepStatus.RUNNING,
                        toStatus = AgentStepStatus.FAILED,
                    ),
                ),
                createdAt = now,
            ),
        )
        val updatedRows = dao.updateRunStatusIfExpected(
            runId = runId,
            expectedStatus = AgentRunStatus.VERIFYING.name,
            status = AgentRunStatus.FAILED.name,
            result = null,
            errorMessage = recovery.failureReason,
            updatedAt = now,
            completedAt = now,
        )
        check(updatedRows == 1) { "失败工具验证收敛时 Run 状态发生并发漂移：$runId" }
        dao.insertEvent(
            RunEventEntity(
                id = "event-${UUID.randomUUID()}",
                runId = runId,
                type = RECOVERY_EVENT_TYPE,
                message = "已恢复失败工具验证，完成原 Run 失败收敛",
                metadataJson = RunEventMetadataCodec.encode(
                    RunEventMetadata.Recovery(
                        fromStatus = AgentRunStatus.VERIFYING,
                        toStatus = AgentRunStatus.FAILED,
                        reason = recoveryReason,
                        resumeKind = AgentRunResumeKind.PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT,
                        recoveryBoundaryKey = RecoveryBoundary(
                            AgentRunResumeKind.PERSISTED_TOOL_VERIFICATION_FAILURE_SETTLEMENT,
                            recovery.toolCall.id,
                        ).key,
                    ),
                ),
                createdAt = now,
            ),
        )
        dao.insertEvent(
            RunEventEntity(
                id = "event-${UUID.randomUUID()}",
                runId = runId,
                type = "run.failed",
                message = recovery.failureReason,
                metadataJson = RunEventMetadataCodec.encode(RunEventMetadata.Reason(recovery.failureReason)),
                createdAt = now,
            ),
        )
        dao.insertEvent(
            RunEventEntity(
                id = "event-${UUID.randomUUID()}",
                runId = runId,
                type = "run.status",
                message = AgentRunStatus.FAILED.name,
                metadataJson = null,
                createdAt = now,
            ),
        )
        return true
    }

    suspend fun recentRunDetails(limit: Int): List<AgentRunDetailRecord> {
        val dao = database.agentRunDao()
        // long: 任务中心只读 Room 里的审计数据，不从当前页面状态反推；这样历史 Run、审批和事件在应用重启后仍可追溯。
        val runs = dao.getRecentRuns(limit)
        if (runs.isEmpty()) return emptyList()
        val runIds = runs.map { it.id }
        // long: 运行记录页会一次展示最近多条 Run；步骤、审批、事件和独立工具账本必须批量读取再内存分组，避免每条 Run 触发多次 Room 查询。
        val stepsByRunId = dao.getStepsForRuns(runIds).groupBy { it.runId }
        val eventsByRunId = dao.getEventsForRuns(runIds).groupBy { it.runId }
        val approvalsByRunId = dao.getApprovalRequestsForRuns(runIds).groupBy { it.runId }
        val toolCallsByRunId = dao.getToolCallsForRuns(runIds).groupBy { it.runId }
        val toolResultsByRunId = dao.getToolResultsForRuns(runIds).groupBy { it.runId }
        return runs.map { run ->
            val snapshot = AgentRunSnapshot(
                run = run.toRecord(),
                steps = stepsByRunId[run.id].orEmpty().map { it.toRecord() },
                events = eventsByRunId[run.id].orEmpty().map { it.toRecord() },
            )
            AgentRunDetailRecord(
                snapshot = snapshot,
                approvals = approvalsByRunId[run.id].orEmpty().map { it.toRecord() },
                toolLedger = AgentToolLedgerRecord(
                    calls = toolCallsByRunId[run.id].orEmpty().map { it.toRecord() },
                    results = toolResultsByRunId[run.id].orEmpty().map { it.toRecord() },
                ),
            )
        }
    }

    suspend fun runDetail(runId: String): AgentRunDetailRecord? {
        val dao = database.agentRunDao()
        val run = dao.getRun(runId) ?: return null
        return loadDetail(run)
    }

    suspend fun toolLedger(runId: String): AgentToolLedgerRecord {
        val dao = database.agentRunDao()
        return AgentToolLedgerRecord(
            calls = dao.getToolCalls(runId).map { it.toRecord() },
            results = dao.getToolResults(runId).map { it.toRecord() },
        )
    }

    suspend fun toolLedgers(runIds: Collection<String>): Map<String, AgentToolLedgerRecord> {
        val distinctRunIds = runIds.distinct()
        if (distinctRunIds.isEmpty()) return emptyMap()
        val dao = database.agentRunDao()
        // long: Workflow 历史可能累积超过 SQLite 单条语句的参数上限；保留批量读取避免 N+1，同时分块为 Room 的 IN 查询预留安全余量。
        val callsByRunId = distinctRunIds
            .chunked(ROOM_IN_QUERY_BATCH_SIZE)
            .flatMap { dao.getToolCallsForRuns(it) }
            .groupBy { it.runId }
        val resultsByRunId = distinctRunIds
            .chunked(ROOM_IN_QUERY_BATCH_SIZE)
            .flatMap { dao.getToolResultsForRuns(it) }
            .groupBy { it.runId }
        return distinctRunIds.associateWith { runId ->
            AgentToolLedgerRecord(
                calls = callsByRunId[runId].orEmpty().map { it.toRecord() },
                results = resultsByRunId[runId].orEmpty().map { it.toRecord() },
            )
        }
    }

    suspend fun approvalRequests(runIds: Collection<String>): Map<String, List<ApprovalRequestRecord>> {
        val distinctRunIds = runIds.distinct()
        if (distinctRunIds.isEmpty()) return emptyMap()
        val dao = database.agentRunDao()
        // long: Workflow 详情只需要关联步骤对应 Run 的审批证据；按 SQLite 参数上限分块读取，避免历史 Run 增长后退化为逐条查询。
        val approvalsByRunId = distinctRunIds
            .chunked(ROOM_IN_QUERY_BATCH_SIZE)
            .flatMap { dao.getApprovalRequestsForRuns(it) }
            .groupBy { it.runId }
        return distinctRunIds.associateWith { runId ->
            approvalsByRunId[runId].orEmpty().map { it.toRecord() }
        }
    }

    private suspend fun persistToolCall(event: RunEventEntity, metadata: RunEventMetadata.ToolCall) {
        val dao = database.agentRunDao()
        val current = dao.getToolCall(metadata.id)
        val argumentsJson = metadata.arguments.toJsonObject().toString()
        // long: ToolCall ID 是 proposed、validated、result 和 verify 的跨事件身份；同 ID 发生 Run、定义或参数漂移时必须回滚，不能让后续结果绑定到另一份调用。
        if (current != null) {
            require(current.runId == event.runId) { "ToolCall 不能跨 Run 复用：${metadata.id}" }
            require(current.toolName == metadata.toolName && current.risk == metadata.risk.name) {
                "ToolCall 定义与已持久化账本不一致：${metadata.id}"
            }
            require(current.argumentsJson == argumentsJson) { "ToolCall 参数与已持久化账本不一致：${metadata.id}" }
        }
        val isProposed = event.type == TOOL_CALL_PROPOSED_EVENT_TYPE
        // long: 每个阶段只能绑定一个 RunEvent 锚点；重复 proposed/validated 会让时间线与独立账本出现多对一歧义，因此拒绝覆盖第一次证据。
        if (isProposed) {
            require(current?.proposedEventId == null) { "ToolCall 已记录 proposed 事件：${metadata.id}" }
        } else {
            require(current?.validatedEventId == null) { "ToolCall 已记录 validated 事件：${metadata.id}" }
        }
        dao.upsertToolCall(
            AgentToolCallEntity(
                id = metadata.id,
                runId = event.runId,
                toolName = metadata.toolName,
                risk = metadata.risk.name,
                argumentsJson = argumentsJson,
                proposedEventId = if (isProposed) event.id else current?.proposedEventId,
                validatedEventId = if (isProposed) current?.validatedEventId else event.id,
                createdAt = current?.createdAt ?: event.createdAt,
                validatedAt = if (isProposed) current?.validatedAt else event.createdAt,
            ),
        )
    }

    private suspend fun persistToolResult(event: RunEventEntity, metadata: RunEventMetadata.ToolResult) {
        val toolCallId = requireNotNull(metadata.toolCallId) {
            "v20 新 ToolResult 必须携带 ToolCall ID"
        }
        val dao = database.agentRunDao()
        val persistedCall = dao.getToolCall(toolCallId)
        if (persistedCall == null) {
            // long: v19 待审批 Run 可能在升级后才执行，此时只有同一 Run 的历史 ToolCall 事件；除此之外，未知 ID 的结果必须回滚，不能静默制造 event-only 新数据。
            require(hasLegacyToolEvidence(event.runId, toolCallId, metadata.toolName, requireResult = false)) {
                "ToolResult 缺少对应 ToolCall：$toolCallId"
            }
            return
        }
        require(persistedCall.runId == event.runId && persistedCall.toolName == metadata.toolName) {
            "ToolResult 与已持久化 ToolCall 不一致：$toolCallId"
        }
        val receipt = metadata.executionReceipt
        require(receipt == null || receipt.toolCallId == toolCallId) {
            "ToolResult 执行回执与 ToolCall 不一致：$toolCallId"
        }
        dao.insertToolResult(
            AgentToolResultEntity(
                toolCallId = toolCallId,
                runId = event.runId,
                eventId = event.id,
                toolName = metadata.toolName,
                content = metadata.content,
                success = metadata.success,
                errorMessage = if (metadata.success) null else metadata.content,
                durationMs = metadata.durationMs,
                executorVerified = metadata.verified,
                verificationStatus = null,
                verifiedEventId = null,
                memoryIdsJson = metadata.memoryIdsUsed.toJsonArray().toString(),
                knowledgeReferencesJson = KnowledgeReferenceCodec.encodeToString(metadata.knowledgeReferences),
                replaySafety = metadata.replaySafety.name,
                receiptToolCallId = receipt?.toolCallId,
                receiptOperationId = receipt?.operationId,
                receiptIdempotencyKey = receipt?.idempotencyKey,
                receiptStatus = receipt?.status?.name,
                createdAt = event.createdAt,
                verifiedAt = null,
            ),
        )
    }

    private suspend fun persistToolVerification(
        event: RunEventEntity,
        metadata: RunEventMetadata.ToolVerification,
    ) {
        val toolCallId = requireNotNull(metadata.toolCallId) {
            "v20 新工具验证必须携带 ToolCall ID"
        }
        val dao = database.agentRunDao()
        val persistedCall = dao.getToolCall(toolCallId)
        if (persistedCall == null) {
            // long: v19 已提交结果恢复只保留历史事件；必须同时匹配同一 Run 的 Call 与 Result 才允许继续 event-only，避免任意 ToolCall ID 绕过 v20 身份校验。
            require(hasLegacyToolEvidence(event.runId, toolCallId, metadata.toolName, requireResult = true)) {
                "工具验证缺少历史 ToolCall/ToolResult 证据：$toolCallId"
            }
            return
        }
        require(persistedCall.runId == event.runId && persistedCall.toolName == metadata.toolName) {
            "工具验证与已持久化 ToolCall 不一致：$toolCallId"
        }
        val persistedResult = requireNotNull(dao.getToolResult(toolCallId)) {
            "工具验证缺少对应 ToolResult：$toolCallId"
        }
        require(persistedResult.runId == event.runId && persistedResult.toolName == metadata.toolName) {
            "工具验证与已持久化 ToolResult 不一致：$toolCallId"
        }
        val updated = dao.markToolResultVerified(
            toolCallId = toolCallId,
            runId = event.runId,
            toolName = metadata.toolName,
            status = metadata.status.name,
            eventId = event.id,
            verifiedAt = event.createdAt,
        )
        require(updated == 1) { "工具验证未更新唯一 ToolResult：$toolCallId" }
    }

    private suspend fun hasLegacyToolEvidence(
        runId: String,
        toolCallId: String,
        toolName: String,
        requireResult: Boolean,
    ): Boolean {
        val events = database.agentRunDao().getEvents(runId)
        val hasCall = events.any { event ->
            val call = RunEventMetadataCodec.decode(event.type, event.metadataJson) as? RunEventMetadata.ToolCall
            event.type in TOOL_CALL_EVENT_TYPES && call?.id == toolCallId && call.toolName == toolName
        }
        if (!hasCall || !requireResult) return hasCall
        return events.any { event ->
            val result = RunEventMetadataCodec.decode(event.type, event.metadataJson) as? RunEventMetadata.ToolResult
            event.type == TOOL_RESULT_EVENT_TYPE &&
                result?.toolCallId == toolCallId &&
                result.toolName == toolName
        }
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
            toolLedger = AgentToolLedgerRecord(
                calls = dao.getToolCalls(run.id).map { it.toRecord() },
                results = dao.getToolResults(run.id).map { it.toRecord() },
            ),
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

    private fun AgentToolCallEntity.toRecord() = AgentToolCallRecord(
        id = id,
        runId = runId,
        toolName = toolName,
        risk = runCatching { ToolRisk.valueOf(risk) }.getOrDefault(ToolRisk.REQUIRES_APPROVAL),
        arguments = argumentsJson.toStringMap(),
        proposedEventId = proposedEventId,
        validatedEventId = validatedEventId,
        createdAt = createdAt,
        validatedAt = validatedAt,
    )

    private fun AgentToolResultEntity.toRecord() = AgentToolResultRecord(
        toolCallId = toolCallId,
        runId = runId,
        eventId = eventId,
        toolName = toolName,
        content = content,
        success = success,
        errorMessage = errorMessage,
        durationMs = durationMs,
        executorVerified = executorVerified,
        verificationStatus = verificationStatus?.let { runCatching { ToolVerificationStatus.valueOf(it) }.getOrNull() },
        verifiedEventId = verifiedEventId,
        memoryIdsUsed = memoryIdsJson.toStringList(),
        knowledgeReferences = KnowledgeReferenceCodec.decode(knowledgeReferencesJson),
        replaySafety = runCatching { ToolReplaySafety.valueOf(replaySafety) }
            .getOrDefault(ToolReplaySafety.RESTART_REQUIRED),
        executionReceipt = toExecutionReceipt(),
        createdAt = createdAt,
        verifiedAt = verifiedAt,
    )

    private fun AgentToolResultEntity.toExecutionReceipt(): ToolExecutionReceipt? {
        val receiptToolCallId = receiptToolCallId ?: return null
        val operationId = receiptOperationId ?: return null
        val status = receiptStatus?.let { runCatching { ToolExecutionReceiptStatus.valueOf(it) }.getOrNull() }
            ?: return null
        return ToolExecutionReceipt(
            toolCallId = receiptToolCallId,
            operationId = operationId,
            idempotencyKey = receiptIdempotencyKey,
            status = status,
        )
    }

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

    private fun List<String>.toJsonArray(): JSONArray {
        return JSONArray().apply { this@toJsonArray.forEach(::put) }
    }

    private fun String.toStringList(): List<String> {
        return runCatching {
            val json = JSONArray(this)
            List(json.length()) { index -> json.getString(index) }
        }.getOrDefault(emptyList())
    }

    private fun ApprovalRequestRecord.toEventMetadata(
        definitionFingerprint: String? = null,
    ): RunEventMetadata {
        return RunEventMetadata.ApprovalRequest(
            id = id,
            toolName = toolName,
            risk = risk,
            arguments = arguments.toSortedMap(),
            status = status,
            expiresAt = expiresAt,
            reason = decisionReason,
            definitionFingerprint = definitionFingerprint,
        )
    }

    private companion object {
        const val ROOM_IN_QUERY_BATCH_SIZE = 900
        const val RECOVERY_EVENT_TYPE = "run.recovered"
        const val TOOL_CALL_PROPOSED_EVENT_TYPE = "tool.call.proposed"
        const val TOOL_RESULT_EVENT_TYPE = "tool.result"
        const val TOOL_VERIFICATION_EVENT_TYPE = "tool.verify"
        val TOOL_CALL_EVENT_TYPES = setOf(TOOL_CALL_PROPOSED_EVENT_TYPE, "tool.call.validated")
        val ACTIVE_RUN_STATUS_NAMES = listOf(
            AgentRunStatus.QUEUED,
            AgentRunStatus.THINKING,
            AgentRunStatus.WAITING_APPROVAL,
            AgentRunStatus.EXECUTING,
            AgentRunStatus.VERIFYING,
        ).map { it.name }
        val TERMINAL_RUN_STATUS_NAMES = AgentRunStatus.values().filter { it.isTerminal }.map { it.name }
    }
}

internal fun activePendingApprovalRequests(requests: List<ApprovalRequestRecord>): List<ApprovalRequestRecord> {
    return requests.filter { it.isWaitingForInteractiveApprovalDecision() }
}
