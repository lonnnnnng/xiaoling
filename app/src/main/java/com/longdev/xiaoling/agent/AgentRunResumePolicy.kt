package com.longdev.xiaoling.agent

enum class AgentRunResumeKind {
    APPROVAL_WAIT,
    RESTART_REQUIRED,
}

data class AgentRunResumeAssessment(
    val kind: AgentRunResumeKind,
    val reason: String,
) {
    val canResumeInPlace: Boolean get() = kind == AgentRunResumeKind.APPROVAL_WAIT
}

object AgentRunResumePolicy {
    fun assess(detail: AgentRunDetailRecord): AgentRunResumeAssessment {
        val snapshot = detail.snapshot
        if (snapshot.run.status != AgentRunStatus.WAITING_APPROVAL) {
            return AgentRunResumeAssessment(
                kind = AgentRunResumeKind.RESTART_REQUIRED,
                reason = "只有等待用户审批且尚未执行工具的 Run 可以原地恢复",
            )
        }
        val hasPendingApproval = detail.approvals.any { it.status == ApprovalRequestStatus.PENDING }
        if (!hasPendingApproval) {
            return AgentRunResumeAssessment(
                kind = AgentRunResumeKind.RESTART_REQUIRED,
                reason = "Run 没有待处理审批，不能恢复原审批边界",
            )
        }
        val hasToolExecution = snapshot.steps.any {
            it.type == AgentStepTypes.TOOL_EXECUTE || it.type == AgentStepTypes.TOOL_VERIFY
        } || snapshot.events.any {
            it.type == "tool.result" || it.type == "tool.verify"
        }
        if (hasToolExecution) {
            return AgentRunResumeAssessment(
                kind = AgentRunResumeKind.RESTART_REQUIRED,
                reason = "工具执行或验证已经开始，必须安全重新运行，不能重复原地执行",
            )
        }
        return AgentRunResumeAssessment(
            kind = AgentRunResumeKind.APPROVAL_WAIT,
            reason = "工具尚未执行，保留原 Run 和审批请求等待用户决定",
        )
    }
}
