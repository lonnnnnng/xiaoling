package com.longdev.xiaoling.agent

import android.content.Context
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.storage.RoomAgentConversationStore
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomAgentSkillStore

class AgentRunUseCase(
    context: Context,
    private val client: OpenAiCompatibleClient,
) {
    private val baseLedger = RoomAgentRunRepository(context)
    private val permissionChecker = AndroidToolPermissionChecker(context)
    private val toolRegistry = XiaoLingToolRegistry(
        clock = SystemAgentClock(),
        conversationStore = RoomAgentConversationStore(context.applicationContext),
        noteStore = RoomAgentNoteStore(context.applicationContext),
        memoryStore = RoomAgentMemoryStore(context.applicationContext),
    )
    private val skillCatalog = AgentSkillCatalog(
        store = RoomAgentSkillStore(context.applicationContext),
        registeredTools = toolRegistry::registeredTools,
    )

    suspend fun run(
        conversationId: String,
        userMessageId: String,
        goal: String,
        config: ProviderRequestConfig,
        summarySystemPrompt: String,
        retryOfRunId: String? = null,
        memoryRecallEnabled: Boolean = true,
        executionOrigin: AgentExecutionOrigin = AgentExecutionOrigin.FOREGROUND,
        approvalGate: ApprovalGate = AutoApprovalGate(),
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {},
    ): AgentRunSummary {
        val selectedSkills = skillCatalog.select(goal)
        val scopedToolRegistry = SkillScopedToolRegistry(toolRegistry, selectedSkills)
        val ledger = ReportingAgentRunLedger(
            delegate = baseLedger,
            onSnapshot = onSnapshot,
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = scopedToolRegistry,
            llm = OpenAiAgentLlm(client, config, summarySystemPrompt, selectedSkills),
            approvalGate = approvalGate,
            permissionChecker = permissionChecker,
        )
        return runtime.run(
            conversationId = conversationId,
            userMessageId = userMessageId,
            goal = goal,
            retryOfRunId = retryOfRunId,
            executionOrigin = executionOrigin,
            memoryRecallEnabled = memoryRecallEnabled,
            selectedSkills = selectedSkills,
        )
    }

    suspend fun resumeApprovedRun(
        detail: AgentRunDetailRecord,
        approval: ApprovalRequestRecord,
        config: ProviderRequestConfig,
        summarySystemPrompt: String,
        approvalReason: String,
        approvalGate: ApprovalGate = AutoApprovalGate(),
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {},
    ): AgentRunSummary {
        val selectionEvent = detail.snapshot.events.lastOrNull { it.type == "skill.selected" }
        val selectedSkills = if (selectionEvent == null) {
            emptyList()
        } else {
            val selection = (selectionEvent.metadata as? RunEventMetadata.Reason)?.reason
                ?: error("原 Run 的 Skill 选择审计无法读取，请创建新 Run 重试")
            // long: 审批等待期间用户可以停用、升级或删除 Skill；恢复必须固定原 Run 的 ID 与版本，不能重新分类后意外扩大工具白名单。
            skillCatalog.resolveSelection(AgentSkillSelectionCodec.decode(selection))
        }
        val scopedToolRegistry = SkillScopedToolRegistry(toolRegistry, selectedSkills)
        val ledger = ReportingAgentRunLedger(
            delegate = baseLedger,
            onSnapshot = onSnapshot,
        )
        // long: 批准决定先写入 Room，再进入同一 Run 的执行入口；应用崩溃时至少能区分“已批准但尚未执行”和“执行中断”。
        baseLedger.decideApprovalRequest(
            requestId = approval.id,
            status = ApprovalRequestStatus.APPROVED,
            reason = approvalReason,
        ) ?: error("审批请求不存在：${approval.id}")
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = scopedToolRegistry,
            llm = OpenAiAgentLlm(client, config, summarySystemPrompt, selectedSkills),
            approvalGate = approvalGate,
            permissionChecker = permissionChecker,
        )
        return runtime.resumeApprovedRun(
            detail = detail,
            approval = approval,
            approvalDecision = ApprovalDecision(approved = true, reason = approvalReason),
            executionOrigin = AgentExecutionOrigin.FOREGROUND,
        )
    }

    suspend fun recoverCommittedToolRuns(): List<AgentRunDetailRecord> {
        return baseLedger.recoverCommittedToolRuns(
            toolRegistry::definition,
            toolRegistry::supportsCommittedEffectVerification,
        )
    }

    suspend fun closeInterruptedRuns(): Int {
        return baseLedger.closeInterruptedRuns(
            toolRegistry::definition,
            toolRegistry::supportsCommittedEffectVerification,
        )
    }

    suspend fun resumeCommittedToolRun(
        detail: AgentRunDetailRecord,
        onSnapshot: suspend (AgentRunSnapshot) -> Unit = {},
    ): AgentRunSummary {
        val ledger = ReportingAgentRunLedger(
            delegate = baseLedger,
            onSnapshot = onSnapshot,
        )
        val runtime = MinimalAgentRuntime(
            ledger = ledger,
            toolRegistry = toolRegistry,
            llm = RecoveryOnlyAgentLlm,
            permissionChecker = permissionChecker,
        )
        return runtime.resumeCommittedToolRun(detail)
    }

    suspend fun listSkills(): List<AgentSkillRecord> = skillCatalog.list()

    suspend fun importSkill(raw: String): AgentSkillRecord = skillCatalog.importDocument(raw)

    suspend fun setSkillEnabled(skillId: String, enabled: Boolean): AgentSkillRecord? {
        return skillCatalog.setEnabled(skillId, enabled)
    }

    suspend fun deleteLocalSkill(skillId: String): Boolean = skillCatalog.deleteLocal(skillId)
}

private object RecoveryOnlyAgentLlm : AgentLlm {
    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
        error("验证阶段恢复不允许重建旧规划请求")
    }

    override suspend fun summarize(
        goal: String,
        toolCall: ToolCall,
        toolResult: ToolExecutionResult,
    ): String = error("验证阶段恢复固定使用本地可信总结")
}

private class ReportingAgentRunLedger(
    private val delegate: AgentRunLedger,
    private val onSnapshot: suspend (AgentRunSnapshot) -> Unit,
) : AgentRunLedger {
    private val stepRunIds = mutableMapOf<String, String>()

    override suspend fun createRun(
        conversationId: String,
        userMessageId: String,
        goal: String,
        retryOfRunId: String?,
    ): AgentRunRecord {
        val run = delegate.createRun(conversationId, userMessageId, goal, retryOfRunId)
        emit(run.id)
        return run
    }

    override suspend fun updateRunStatus(runId: String, status: AgentRunStatus, result: String?, errorMessage: String?) {
        delegate.updateRunStatus(runId, status, result, errorMessage)
        emit(runId)
    }

    override suspend fun appendStep(
        runId: String,
        type: String,
        title: String,
        detail: String,
        status: AgentStepStatus,
    ): AgentStepRecord {
        val step = delegate.appendStep(runId, type, title, detail, status)
        stepRunIds[step.id] = runId
        emit(runId)
        return step
    }

    override suspend fun updateStep(stepId: String, status: AgentStepStatus, detail: String?) {
        delegate.updateStep(stepId, status, detail)
        stepRunIds[stepId]?.let { emit(it) }
    }

    override suspend fun appendEvent(runId: String, type: String, message: String, metadata: RunEventMetadata?) {
        delegate.appendEvent(runId, type, message, metadata)
        emit(runId)
    }

    override suspend fun snapshot(runId: String): AgentRunSnapshot = delegate.snapshot(runId)

    private suspend fun emit(runId: String) {
        // long: 运行时间线依赖 Room 里的真实审计记录，而不是 UI 自己猜状态；每次落库后回读快照，保证界面展示和可追溯数据一致。
        onSnapshot(delegate.snapshot(runId))
    }
}
