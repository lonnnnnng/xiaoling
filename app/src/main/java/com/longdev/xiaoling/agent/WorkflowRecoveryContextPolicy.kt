package com.longdev.xiaoling.agent

import com.longdev.xiaoling.automation.WorkflowRunRecord
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepRecord
import com.longdev.xiaoling.automation.WorkflowStepSnapshotCodec
import com.longdev.xiaoling.automation.WorkflowStepStatus
import org.json.JSONObject

internal object WorkflowRecoveryContextPolicy {
    fun resolve(detail: AgentRunDetailRecord, parent: WorkflowRunRecord?, steps: List<WorkflowStepRecord>): WorkflowDeviceActionRunContext? {
        val run = detail.snapshot.run
        val invocations = detail.snapshot.events.filter { it.type == AgentChildLifecyclePolicy.INVOCATION_EVENT }
        require(invocations.size <= 1) { "恢复来源审计重复" }
        val source = invocations.singleOrNull()?.let { event ->
            val json = JSONObject((event.metadata as? RunEventMetadata.Reason)?.reason ?: error("恢复来源审计损坏"))
            require(json.getInt("schemaVersion") == 1) { "恢复来源审计版本未知" }
            AgentInvocationSource.valueOf(json.getString("source"))
        }
        val matches = steps.filter { it.agentRunId == run.id }
        if (parent == null) {
            require(matches.isEmpty() && source != AgentInvocationSource.WORKFLOW) { "Workflow 冻结关联丢失，拒绝恢复" }
            return null
        }
        require(source == null || source == AgentInvocationSource.WORKFLOW) { "恢复来源与 Workflow 关联冲突" }
        require(parent.conversationId == run.conversationId && matches.size == 1) { "Workflow 恢复关联不唯一或会话不一致" }
        val step = matches.single()
        require(parent.status == WorkflowRunStatus.RUNNING && step.status == WorkflowStepStatus.RUNNING) {
            "Workflow 或步骤已停止，拒绝恢复旧执行"
        }
        require(steps.all { it.workflowRunId == parent.id } &&
            steps.map { it.sequence }.sorted() == (1..steps.size).toList()) { "Workflow 冻结步骤顺序无效" }
        val input = WorkflowStepSnapshotCodec.decodeInput(step.inputSnapshot)
        require(input.goal == step.detail) { "Workflow 冻结目标与步骤不一致" }
        // long: 每步完成要求只能从原输入快照恢复；缺组、坏序号或坏 codec 直接拒绝，不能读取新 Profile 或从目标文本猜动作。
        val groups = input.goalVerificationContract?.spec?.requiredToolNamesByStep.orEmpty()
        require(groups.isEmpty() || groups.size == steps.size) { "每步工具完成标准与工作流步骤数量不一致" }
        return WorkflowDeviceActionRunContext(parent.id, step.id, input.goal, input.targetAppPackage,
            if (groups.isEmpty()) emptyList() else groups[step.sequence - 1].toList())
    }
}
