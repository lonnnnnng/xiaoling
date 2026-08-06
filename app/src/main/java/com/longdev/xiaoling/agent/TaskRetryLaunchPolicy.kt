package com.longdev.xiaoling.agent

import com.longdev.xiaoling.automation.WorkflowRunDetail
import com.longdev.xiaoling.automation.WorkflowRunStatus
import com.longdev.xiaoling.automation.WorkflowStepStatus
import com.longdev.xiaoling.automation.WorkflowTrigger

data class TaskRetryLaunchRequest(
    val workflowRunId: String,
    val taskName: String,
)

object TaskRetryLaunchPolicy {
    fun resolve(detail: AgentRunDetailRecord): TaskRetryLaunchRequest? {
        if (detail.snapshot.run.status != AgentRunStatus.COMPLETED) return null
        val assessment = AgentToolLedgerConsistencyPolicy.inspect(detail)
            as? AgentToolLedgerConsistencyAssessment.Available
            ?: return null
        val retries = assessment.executions.filter { execution -> execution.call.toolName == TASK_RETRY_TOOL_NAME }
        if (retries.size != 1) return null
        val execution = retries.single()
        val call = execution.call
        val result = execution.result ?: return null
        val name = call.arguments["name"].orEmpty().trim()
        val receipt = result.executionReceipt ?: return null
        // long: 前台接管只信任 Room 中调用、结果、typed 验证和提交回执的完整一致链；模型总结和可见结果正文都不参与启动判断。
        val trusted = call.risk == ToolRisk.REQUIRES_APPROVAL &&
            call.arguments.keys == setOf("name") &&
            name.isNotEmpty() &&
            name.length <= 100 &&
            result.success &&
            result.executorVerified == true &&
            result.verificationStatus == ToolVerificationStatus.PASSED &&
            result.replaySafety == ToolReplaySafety.IDEMPOTENT_BY_KEY &&
            receipt.toolCallId == call.id &&
            receipt.idempotencyKey == call.id &&
            receipt.status == ToolExecutionReceiptStatus.COMMITTED
        if (!trusted) return null
        return TaskRetryLaunchRequest(
            workflowRunId = receipt.operationId,
            taskName = name,
        )
    }

    fun canStart(
        request: TaskRetryLaunchRequest,
        detail: WorkflowRunDetail,
        conversationId: String,
    ): Boolean {
        val run = detail.run
        return request.workflowRunId == run.id &&
            conversationId.isNotBlank() &&
            run.conversationId == conversationId &&
            run.status == WorkflowRunStatus.QUEUED &&
            run.trigger == WorkflowTrigger.MANUAL &&
            run.scheduledTaskId == null &&
            run.agentRunId == null &&
            run.startedAt == null &&
            run.completedAt == null &&
            run.retryOfWorkflowRunId != null &&
            detail.steps.isNotEmpty() &&
            detail.steps.all { step ->
                step.workflowRunId == run.id &&
                    step.status in setOf(WorkflowStepStatus.SKIPPED, WorkflowStepStatus.PENDING)
            }
    }
}

private const val TASK_RETRY_TOOL_NAME = "tasks.retry"
