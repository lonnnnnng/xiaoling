package com.longdev.xiaoling.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class MinimalAgentRuntime(
    private val ledger: AgentRunLedger,
    private val toolRegistry: ToolRegistry = FakeToolRegistry(),
    private val llm: AgentLlm,
    private val approvalGate: ApprovalGate = AutoApprovalGate(),
) {
    suspend fun runDemo(conversationId: String, userMessageId: String, goal: String): AgentRunSummary {
        val run = ledger.createRun(conversationId, userMessageId, goal)
        var activeStepId: String? = null
        return try {
            ledger.updateRunStatus(run.id, AgentRunStatus.THINKING)
            val thinking = ledger.appendStep(
                runId = run.id,
                type = "llm.plan",
                title = "模型规划",
                detail = "模型正在根据可用工具提出工具调用。",
                status = AgentStepStatus.RUNNING,
            )
            activeStepId = thinking.id
            currentCoroutineContext().ensureActive()
            val proposedCall = llm.proposeToolCall(goal, toolRegistry.availableTools())
            val definition = toolRegistry.definition(proposedCall.name)
                ?: error("模型选择了未注册工具：${proposedCall.name}")
            val toolCall = proposedCall.copy(risk = definition.risk)
            ledger.updateStep(thinking.id, AgentStepStatus.COMPLETED, "模型选择工具：${toolCall.name}")
            activeStepId = null

            ledger.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
            val approval = ledger.appendStep(
                runId = run.id,
                type = "approval",
                title = "应用侧审批",
                detail = "等待应用侧审批 ${toolCall.name}",
                status = AgentStepStatus.RUNNING,
            )
            activeStepId = approval.id
            val decision = approvalGate.requestApproval(run.id, toolCall, definition)
            ledger.appendEvent(
                run.id,
                if (decision.approved) "approval.granted" else "approval.denied",
                decision.reason,
            )
            if (!decision.approved) error("工具未获批准：${decision.reason}")
            ledger.updateStep(approval.id, AgentStepStatus.COMPLETED, "已批准：${toolCall.name} · ${decision.reason}")
            activeStepId = null

            ledger.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
            val execution = ledger.appendStep(
                runId = run.id,
                type = "tool.execute",
                title = "执行工具",
                detail = "正在执行 ${toolCall.name}",
                status = AgentStepStatus.RUNNING,
            )
            activeStepId = execution.id
            currentCoroutineContext().ensureActive()
            val toolResult = toolRegistry.execute(toolCall)
            if (!toolResult.success) error("工具执行失败：${toolResult.content}")
            ledger.updateStep(execution.id, AgentStepStatus.COMPLETED, toolResult.content)
            activeStepId = null

            ledger.updateRunStatus(run.id, AgentRunStatus.VERIFYING)
            val verify = ledger.appendStep(
                runId = run.id,
                type = "tool.verify",
                title = "执行后验证",
                detail = "检查 fake tool 是否返回可读结果。",
                status = AgentStepStatus.RUNNING,
            )
            activeStepId = verify.id
            currentCoroutineContext().ensureActive()
            require(toolResult.content.isNotBlank()) { "工具结果为空，无法验证" }
            ledger.updateStep(verify.id, AgentStepStatus.COMPLETED, "验证通过")
            activeStepId = null

            ledger.updateRunStatus(run.id, AgentRunStatus.THINKING)
            val summaryStep = ledger.appendStep(
                runId = run.id,
                type = "llm.summarize",
                title = "模型总结",
                detail = "模型根据工具结果生成最终回复。",
                status = AgentStepStatus.RUNNING,
            )
            activeStepId = summaryStep.id
            val response = llm.summarize(goal, toolCall, toolResult).ifBlank {
                buildFallbackResponse(run.id, goal, toolCall, toolResult)
            }
            ledger.updateStep(summaryStep.id, AgentStepStatus.COMPLETED, "已生成最终回复")
            activeStepId = null
            ledger.updateRunStatus(run.id, AgentRunStatus.COMPLETED, result = response)
            AgentRunSummary(runId = run.id, status = AgentRunStatus.COMPLETED, responseText = response)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                // long: 取消本身会让当前协程进入 cancelled 状态，终态落库必须脱离取消上下文，否则 Room 写入也会被一起取消，Run 会卡在 THINKING/RUNNING。
                activeStepId?.let { ledger.updateStep(it, AgentStepStatus.CANCELLED, "用户取消 Agent 任务") }
                ledger.updateRunStatus(run.id, AgentRunStatus.CANCELLED, errorMessage = "用户取消 Agent 任务")
            }
            throw error
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                // long: 失败终态是后续审计和恢复任务的依据，即使上游异常叠加协程取消，也要尽量把当前 step 和 run 写成可追踪的 FAILED。
                activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, error.message ?: "Agent 任务失败") }
                ledger.updateRunStatus(run.id, AgentRunStatus.FAILED, errorMessage = error.message ?: "Agent 任务失败")
            }
            throw error
        }
    }

    private fun buildFallbackResponse(
        runId: String,
        goal: String,
        toolCall: ToolCall,
        toolResult: ToolExecutionResult,
    ): String {
        return """
            Agent 演示任务已完成

            - Run ID：$runId
            - 目标：${goal.ifBlank { "未填写目标" }}
            - 工具：${toolCall.name}
            - 审批：已通过应用侧审批
            - 执行结果：${toolResult.content}
            - 验证：工具结果可读，任务进入 COMPLETED 终态
        """.trimIndent()
    }
}
