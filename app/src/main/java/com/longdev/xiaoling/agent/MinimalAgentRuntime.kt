package com.longdev.xiaoling.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class MinimalAgentRuntime(
    private val ledger: AgentRunLedger,
    private val toolRegistry: ToolRegistry = FakeToolRegistry(),
    private val llm: AgentLlm,
    private val approvalGate: ApprovalGate = AutoApprovalGate(),
    private val options: AgentRuntimeOptions = AgentRuntimeOptions(),
) {
    suspend fun run(conversationId: String, userMessageId: String, goal: String): AgentRunSummary {
        val run = ledger.createRun(conversationId, userMessageId, goal)
        (toolRegistry as? AgentRunContextAwareToolRegistry)?.bindRunContext(
            AgentToolExecutionContext(
                conversationId = conversationId,
                userMessageId = userMessageId,
                runId = run.id,
                goal = goal,
            ),
        )
        var activeStepId: String? = null
        var executedToolCalls = 0
        val toolCallFingerprints = mutableSetOf<String>()
        val executionBudget = AgentExecutionBudget(options.runTimeoutMs)
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
            val proposedCall = runTimedStep("模型规划", options.modelStepTimeoutMs, executionBudget) {
                llm.proposeToolCall(goal, toolRegistry.availableTools())
            }
            val definition = toolRegistry.definition(proposedCall.name)
                ?: error("模型选择了未注册工具：${proposedCall.name}")
            val toolCall = proposedCall.copy(risk = definition.risk)
            // long: 模型只负责“提出”工具调用，事件里记录原始参数；后续风险、校验和审批都以应用注册表为准。
            ledger.appendEvent(run.id, "tool.call.proposed", AgentEventPayload.toolCall(toolCall))
            ledger.updateStep(thinking.id, AgentStepStatus.COMPLETED, "模型选择工具：${toolCall.name}")
            activeStepId = null

            val validation = ledger.appendStep(
                runId = run.id,
                type = "tool.validate",
                title = "工具参数校验",
                detail = "校验 ${toolCall.name} 的必填参数和循环风险。",
                status = AgentStepStatus.RUNNING,
            )
            activeStepId = validation.id
            validateToolCall(definition, toolCall)
            checkToolBudget(executedToolCalls)
            checkLoopRisk(toolCall, toolCallFingerprints)
            ledger.appendEvent(run.id, "tool.call.validated", AgentEventPayload.toolCall(toolCall))
            ledger.updateStep(validation.id, AgentStepStatus.COMPLETED, "参数校验通过")
            activeStepId = null

            if (definition.risk == ToolRisk.SAFE) {
                // long: SAFE 工具只能读取低敏环境或本机记忆，不产生外部副作用；它仍写审计事件，但不打断用户当前对话去做确认。
                ledger.appendEvent(
                    run.id,
                    "approval.skipped",
                    AgentEventPayload.simple("tool" to toolCall.name, "reason" to "SAFE 工具无需审批"),
                )
            } else {
                ledger.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
                val approval = ledger.appendStep(
                    runId = run.id,
                    type = "approval",
                    title = "应用侧审批",
                    detail = "等待应用侧审批 ${toolCall.name}",
                    status = AgentStepStatus.RUNNING,
                )
                activeStepId = approval.id
                // long: 审批等待取决于用户阅读风险和做决定的时间，不属于模型推理或工具执行预算；否则用户稍慢点击批准就会把可恢复任务误记成预算耗尽。
                val decision = approvalGate.requestApproval(run.id, toolCall, definition)
                ledger.appendEvent(
                    run.id,
                    if (decision.approved) "approval.granted" else "approval.denied",
                    AgentEventPayload.approval(toolCall, decision),
                )
                if (!decision.approved) error("工具未获批准：${decision.reason}")
                ledger.updateStep(approval.id, AgentStepStatus.COMPLETED, "已批准：${toolCall.name} · ${decision.reason}")
                activeStepId = null
            }

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
            val toolStartedAt = System.currentTimeMillis()
            val toolResult = runTimedStep("工具执行 ${toolCall.name}", definition.timeoutMs ?: options.toolStepTimeoutMs, executionBudget) {
                toolRegistry.execute(toolCall)
            }
            executedToolCalls += 1
            val toolDurationMs = System.currentTimeMillis() - toolStartedAt
            ledger.appendEvent(run.id, "tool.result", AgentEventPayload.toolResult(toolCall, toolResult, toolDurationMs))
            if (!toolResult.success) error("工具执行失败：${toolResult.content}")
            ledger.updateStep(execution.id, AgentStepStatus.COMPLETED, toolResult.content)
            activeStepId = null

            ledger.updateRunStatus(run.id, AgentRunStatus.VERIFYING)
            val verify = ledger.appendStep(
                runId = run.id,
                type = "tool.verify",
                title = "执行后验证",
                detail = "检查工具是否返回可读结果。",
                status = AgentStepStatus.RUNNING,
            )
            activeStepId = verify.id
            currentCoroutineContext().ensureActive()
            require(toolResult.content.isNotBlank()) { "工具结果为空，无法验证" }
            ledger.appendEvent(run.id, "tool.verify", AgentEventPayload.simple("status" to "passed", "tool" to toolCall.name))
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
            var summaryFallbackReason: String? = null
            val response = try {
                runTimedStep("模型总结", options.modelStepTimeoutMs, executionBudget) {
                    llm.summarize(goal, toolCall, toolResult)
                }
            } catch (error: AgentTimeoutException) {
                // long: 工具已经执行并验证成功时，最终总结只是展示层增强；上游总结超时不应把已经完成的本地写入任务改判失败，改用本地兜底回复保留可审计结果。
                summaryFallbackReason = error.message ?: "模型总结超时"
                buildFallbackResponse(run.id, goal, toolCall, toolResult)
            }.ifBlank {
                summaryFallbackReason = "模型总结为空"
                buildFallbackResponse(run.id, goal, toolCall, toolResult)
            }
            val summaryDetail = if (summaryFallbackReason != null) {
                ledger.appendEvent(
                    run.id,
                    "llm.summarize.fallback",
                    AgentEventPayload.simple("reason" to summaryFallbackReason.orEmpty()),
                )
                "${summaryFallbackReason}，已使用本地兜底回复"
            } else {
                "已生成最终回复"
            }
            ledger.updateStep(summaryStep.id, AgentStepStatus.COMPLETED, summaryDetail)
            activeStepId = null
            ledger.updateRunStatus(run.id, AgentRunStatus.COMPLETED, result = response)
            AgentRunSummary(runId = run.id, status = AgentRunStatus.COMPLETED, responseText = response)
        } catch (error: AgentBudgetExceededException) {
            withContext(NonCancellable) {
                activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, error.message ?: "Agent 预算耗尽") }
                ledger.appendEvent(run.id, "run.budget_exhausted", AgentEventPayload.simple("reason" to error.message.orEmpty()))
                ledger.updateRunStatus(run.id, AgentRunStatus.BUDGET_EXHAUSTED, errorMessage = error.message ?: "Agent 预算耗尽")
            }
            throw error
        } catch (error: TimeoutCancellationException) {
            val timeout = AgentTimeoutException("Agent Run 超时：${options.runTimeoutMs}ms")
            withContext(NonCancellable) {
                activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, timeout.message ?: "Agent Run 超时") }
                ledger.appendEvent(run.id, "run.timeout", AgentEventPayload.simple("reason" to timeout.message.orEmpty()))
                ledger.updateRunStatus(run.id, AgentRunStatus.BUDGET_EXHAUSTED, errorMessage = timeout.message)
            }
            throw timeout
        } catch (error: AgentTimeoutException) {
            withContext(NonCancellable) {
                activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, error.message ?: "Agent 步骤超时") }
                ledger.appendEvent(run.id, "run.timeout", AgentEventPayload.simple("reason" to error.message.orEmpty()))
                ledger.updateRunStatus(run.id, AgentRunStatus.BUDGET_EXHAUSTED, errorMessage = error.message ?: "Agent 步骤超时")
            }
            throw error
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                // long: 取消本身会让当前协程进入 cancelled 状态，终态落库必须脱离取消上下文，否则 Room 写入也会被一起取消，Run 会卡在 THINKING/RUNNING。
                activeStepId?.let { ledger.updateStep(it, AgentStepStatus.CANCELLED, "用户取消 Agent 任务") }
                ledger.appendEvent(run.id, "run.cancelled", AgentEventPayload.simple("reason" to "用户取消 Agent 任务"))
                ledger.updateRunStatus(run.id, AgentRunStatus.CANCELLED, errorMessage = "用户取消 Agent 任务")
            }
            throw error
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                // long: 失败终态是后续审计和恢复任务的依据，即使上游异常叠加协程取消，也要尽量把当前 step 和 run 写成可追踪的 FAILED。
                activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, error.message ?: "Agent 任务失败") }
                ledger.appendEvent(run.id, "run.failed", AgentEventPayload.simple("reason" to (error.message ?: "Agent 任务失败")))
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
            - 审批：${if (toolCall.risk == ToolRisk.SAFE) "SAFE 工具无需审批" else "已通过应用侧审批"}
            - 执行结果：${toolResult.content}
            - 验证：工具结果可读，任务进入 COMPLETED 终态
        """.trimIndent()
    }

    private suspend fun <T> runTimedStep(
        label: String,
        timeoutMs: Long,
        executionBudget: AgentExecutionBudget,
        block: suspend () -> T,
    ): T {
        return executionBudget.run(label, timeoutMs, block)
    }

    private fun validateToolCall(definition: ToolDefinition, toolCall: ToolCall) {
        val missing = definition.inputSchema
            .filter { it.required }
            .map { it.name }
            .filter { name -> toolCall.arguments[name].isNullOrBlank() }
        require(missing.isEmpty()) {
            "工具 ${definition.name} 缺少必填参数：${missing.joinToString(", ")}"
        }
    }

    private fun checkToolBudget(executedToolCalls: Int) {
        if (executedToolCalls >= options.maxToolCalls) {
            throw AgentBudgetExceededException("工具调用次数超过上限：${options.maxToolCalls}")
        }
    }

    private fun checkLoopRisk(toolCall: ToolCall, fingerprints: MutableSet<String>) {
        val fingerprint = "${toolCall.name}:${toolCall.arguments.toSortedMap()}"
        if (!fingerprints.add(fingerprint)) {
            throw AgentBudgetExceededException("检测到重复工具调用：${toolCall.name}")
        }
    }
}

private class AgentExecutionBudget(
    private val totalTimeoutMs: Long,
) {
    private var consumedMs: Long = 0

    suspend fun <T> run(label: String, stepTimeoutMs: Long, block: suspend () -> T): T {
        val remainingMs = remainingMs()
        if (remainingMs <= 0) {
            throw AgentTimeoutException("Agent Run 超时：${totalTimeoutMs}ms")
        }
        val effectiveTimeoutMs = minOf(stepTimeoutMs, remainingMs)
        val startedAt = System.currentTimeMillis()
        return try {
            withTimeout(effectiveTimeoutMs) { block() }
        } catch (error: TimeoutCancellationException) {
            // long: 单步超时和整次执行预算超时都会抛出 TimeoutCancellationException；先确认不是用户取消，再按触发的预算来源写入审计原因。
            currentCoroutineContext().ensureActive()
            if (effectiveTimeoutMs == remainingMs && remainingMs <= stepTimeoutMs) {
                throw AgentTimeoutException("Agent Run 超时：${totalTimeoutMs}ms")
            }
            throw AgentTimeoutException("$label 超时：${stepTimeoutMs}ms")
        } finally {
            // long: 只在模型/工具执行段累计预算；应用侧审批没有进入这里，因此用户阅读和确认风险的等待时间不会消耗执行预算。
            consumedMs += (System.currentTimeMillis() - startedAt).coerceAtLeast(0)
        }
    }

    private fun remainingMs(): Long = totalTimeoutMs - consumedMs
}

private object AgentEventPayload {
    fun toolCall(call: ToolCall): String {
        return json(
            "id" to call.id,
            "name" to call.name,
            "risk" to call.risk.name,
            "arguments" to call.arguments,
        )
    }

    fun approval(call: ToolCall, decision: ApprovalDecision): String {
        return json(
            "tool" to call.name,
            "approved" to decision.approved,
            "reason" to decision.reason,
        )
    }

    fun toolResult(call: ToolCall, result: ToolExecutionResult, durationMs: Long): String {
        return json(
            "tool" to call.name,
            "success" to result.success,
            "content" to result.content,
            "verified" to result.verified,
            "durationMs" to durationMs,
        )
    }

    fun simple(vararg pairs: Pair<String, String>): String = json(*pairs)

    private fun json(vararg pairs: Pair<String, Any?>): String {
        val payload = JSONObject()
        pairs.forEach { (key, value) ->
            payload.put(key, value.toJsonValue())
        }
        return payload.toString()
    }

    private fun Any?.toJsonValue(): Any {
        return when (this) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().also { json ->
                // long: 工具参数是审计事件的关键证据，按 key 排序写入可以让测试、日志和后续 diff 更稳定。
                entries.sortedBy { it.key.toString() }.forEach { (key, value) ->
                    json.put(key.toString(), value ?: JSONObject.NULL)
                }
            }
            else -> this
        }
    }
}
