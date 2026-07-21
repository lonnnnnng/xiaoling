package com.longdev.xiaoling.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.longdev.xiaoling.network.ApiFailure
import com.longdev.xiaoling.network.FailureKind
import org.json.JSONObject

internal fun interface MonotonicClock {
    fun nowMs(): Long
}

private val systemMonotonicClock = MonotonicClock {
    System.nanoTime() / 1_000_000L
}

class MinimalAgentRuntime internal constructor(
    private val ledger: AgentRunLedger,
    private val toolRegistry: ToolRegistry = FakeToolRegistry(),
    private val llm: AgentLlm,
    private val approvalGate: ApprovalGate = AutoApprovalGate(),
    private val permissionChecker: ToolPermissionChecker = FailClosedToolPermissionChecker,
    private val options: AgentRuntimeOptions = AgentRuntimeOptions(),
    private val faultInjector: AgentRuntimeFaultInjector = NoOpAgentRuntimeFaultInjector,
    private val monotonicClock: MonotonicClock = systemMonotonicClock,
) {
    suspend fun run(
        conversationId: String,
        userMessageId: String,
        goal: String,
        retryOfRunId: String? = null,
        executionOrigin: AgentExecutionOrigin = AgentExecutionOrigin.FOREGROUND,
        invocationSource: AgentInvocationSource = AgentInvocationSource.DIRECT,
        memoryRecallEnabled: Boolean = true,
        selectedSkills: List<AgentSkillDefinition> = emptyList(),
        agentProfile: AgentProfileSnapshot? = null,
    ): AgentRunSummary {
        val run = ledger.createRun(conversationId, userMessageId, goal, retryOfRunId)
        (toolRegistry as? AgentRunContextAwareToolRegistry)?.bindRunContext(
            AgentToolExecutionContext(
                conversationId = conversationId,
                userMessageId = userMessageId,
                runId = run.id,
                goal = goal,
                memoryRecallEnabled = memoryRecallEnabled,
                executionOrigin = executionOrigin,
                invocationSource = invocationSource,
            ),
        )
        val state = AgentRuntimeExecutionState(options.runTimeoutMs, monotonicClock = monotonicClock)
        return try {
            persistExecutionBudget(run.id, "初始化执行预算", state.executionBudget)
            if (agentProfile != null) {
                // long: Run 必须冻结启动时的 Agent 身份、模型和能力白名单；后续编辑或删除 Profile 不能改变历史审计、审批恢复和工具边界。
                ledger.appendEvent(
                    runId = run.id,
                    type = AgentEventTypes.PROFILE_SELECTED,
                    message = "已选择 Agent：${agentProfile.name} · ${agentProfile.model}",
                    metadata = RunEventMetadata.AgentProfileSelection(agentProfile),
                )
            }
            if (selectedSkills.isNotEmpty()) {
                // long: Skill 选择必须进入 Run 审计，方便用户确认本轮为何只暴露部分工具；指令文本不改变工具定义中的风险和审批策略。
                ledger.appendEvent(
                    runId = run.id,
                    type = "skill.selected",
                    message = "已按目标选择 Skill：${selectedSkills.joinToString { it.name }}",
                    metadata = RunEventMetadata.Reason(AgentSkillSelectionCodec.encode(selectedSkills)),
                )
            }
            if (!memoryRecallEnabled) {
                // long: 单次 Run 关闭记忆召回只阻止读取长期记忆，仍保留审计事件，方便用户确认本轮没有使用记忆上下文。
                ledger.appendEvent(
                    runId = run.id,
                    type = MEMORY_RECALL_DISABLED_EVENT_TYPE,
                    message = "本次 Run 已关闭长期记忆召回",
                    metadata = RunEventMetadata.Reason("用户关闭本次 Run 的长期记忆召回"),
                )
            }
            continuePlanning(run, goal, executionOrigin, state)
            completeRun(run, goal, state)
        } catch (error: AgentProcessTerminationSimulation) {
            // long: 测试注入模拟操作系统直接杀死进程；真实进程不会执行 catch 收敛，因此保留中间态供启动恢复策略判定。
            throw error
        } catch (error: AgentBudgetExceededException) {
            withContext(NonCancellable) {
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, error.message ?: "Agent 预算耗尽") }
                ledger.appendEvent(
                    run.id,
                    "run.budget_exhausted",
                    error.message.orEmpty(),
                    RunEventMetadata.Reason(error.message.orEmpty()),
                )
                ledger.updateRunStatus(run.id, AgentRunStatus.BUDGET_EXHAUSTED, errorMessage = error.message ?: "Agent 预算耗尽")
            }
            throw error
        } catch (error: AgentTimeoutException) {
            withContext(NonCancellable) {
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, error.message ?: "Agent 步骤超时") }
                ledger.appendEvent(
                    run.id,
                    "run.timeout",
                    error.message.orEmpty(),
                    RunEventMetadata.Reason(error.message.orEmpty()),
                )
                ledger.updateRunStatus(run.id, AgentRunStatus.BUDGET_EXHAUSTED, errorMessage = error.message ?: "Agent 步骤超时")
            }
            throw error
        } catch (error: AgentBackgroundApprovalRequiredException) {
            settleBlockedRun(run.id, state, error)
            throw error
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                // long: 取消本身会让当前协程进入 cancelled 状态，终态落库必须脱离取消上下文，否则 Room 写入也会被一起取消，Run 会卡在 THINKING/RUNNING。
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.CANCELLED, "用户取消 Agent 任务") }
                ledger.appendEvent(
                    run.id,
                    "run.cancelled",
                    "用户取消 Agent 任务",
                    RunEventMetadata.Reason("用户取消 Agent 任务"),
                )
                ledger.updateRunStatus(run.id, AgentRunStatus.CANCELLED, errorMessage = "用户取消 Agent 任务")
            }
            throw error
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                // long: 失败终态是后续审计和恢复任务的依据，即使上游异常叠加协程取消，也要尽量把当前 step 和 run 写成可追踪的 FAILED。
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, error.message ?: "Agent 任务失败") }
                val reason = error.message ?: "Agent 任务失败"
                ledger.appendEvent(run.id, "run.failed", reason, RunEventMetadata.Reason(reason))
                ledger.updateRunStatus(run.id, AgentRunStatus.FAILED, errorMessage = error.message ?: "Agent 任务失败")
            }
            throw error
        }
    }

    suspend fun resumeApprovedRun(
        detail: AgentRunDetailRecord,
        approval: ApprovalRequestRecord,
        approvalDecision: ApprovalDecision,
        executionOrigin: AgentExecutionOrigin = AgentExecutionOrigin.FOREGROUND,
    ): AgentRunSummary {
        val assessment = AgentRunResumePolicy.assess(detail)
        require(assessment.kind == AgentRunResumeKind.APPROVAL_WAIT) { assessment.reason }
        val recovery = requireNotNull(assessment.approvalWait) { "恢复策略缺少待审批工具证据" }
        require(approvalDecision.approved) { "未批准的审批请求不能进入恢复执行" }
        require(approval.status == ApprovalRequestStatus.PENDING) { "审批请求已经处理，不能重复恢复 Agent Run" }
        require(approval.runId == detail.snapshot.run.id) { "审批请求不属于当前 Agent Run" }
        require(approval.id == recovery.approvalRequestId) { "审批请求不是恢复策略确认的链尾请求" }
        val run = detail.snapshot.run
        val toolCall = recovery.toolCall
        val definition = toolRegistry.definition(toolCall.name)
            ?: error("恢复时找不到已登记工具：${toolCall.name}")
        require(definition.risk == toolCall.risk) { "恢复时工具风险等级与原 Run 快照不一致" }
        // long: 单次记忆开关属于原 Run 的安全边界；进程重建后必须从持久化事件还原，不能使用当前 UI 默认值重新开放 memory.search。
        val memoryRecallEnabled = detail.snapshot.events.none { event ->
            event.type == MEMORY_RECALL_DISABLED_EVENT_TYPE
        }
        (toolRegistry as? AgentRunContextAwareToolRegistry)?.bindRunContext(
            AgentToolExecutionContext(
                conversationId = run.conversationId,
                userMessageId = run.userMessageId,
                runId = run.id,
                goal = run.goal,
                memoryRecallEnabled = memoryRecallEnabled,
            ),
        )
        val restoredBudget = restoredExecutionBudget(detail)
        val state = AgentRuntimeExecutionState(
            runTimeoutMs = restoredBudget.snapshot.totalTimeoutMs,
            activeStepId = recovery.approvalStepId,
            monotonicClock = monotonicClock,
            initialConsumedMs = restoredBudget.snapshot.consumedMs,
        )
        // long: 前序工具的结果、调用额度和循环指纹都属于原 Run；进程重建不能把这些约束清零，也不能重新执行已经验证的工具。
        state.completedTools += recovery.verifiedPrefix
        state.executedToolCalls = recovery.verifiedPrefix.size
        state.toolCallFingerprints += recovery.verifiedPrefix.map { toolCallFingerprint(it.toolCall) }
        return try {
            persistLegacyExecutionBudgetStart(run.id, restoredBudget, state.executionBudget)
            // long: 审批请求已经持久化，恢复入口只补写批准审计并从原审批步骤继续，不重新规划工具，避免重复模型决策。
            ledger.appendEvent(
                runId = run.id,
                type = "approval.granted",
                message = "工具审批通过：${toolCall.name}",
                metadata = AgentEventMetadata.approval(toolCall, approvalDecision),
            )
            state.activeStepId?.let {
                ledger.updateStep(it, AgentStepStatus.COMPLETED, "已批准：${toolCall.name} · ${approvalDecision.reason}")
                state.activeStepId = null
            }
            executeToolCall(
                runId = run.id,
                definition = definition,
                toolCall = toolCall,
                executionOrigin = executionOrigin,
                state = state,
                recordValidationStep = false,
                approvalAlreadyGranted = true,
            )
            continuePlanning(run, run.goal, executionOrigin, state)
            completeRun(run, run.goal, state)
        } catch (error: AgentProcessTerminationSimulation) {
            throw error
        } catch (error: AgentBudgetExceededException) {
            withContext(NonCancellable) {
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, error.message ?: "Agent 预算耗尽") }
                ledger.appendEvent(run.id, "run.budget_exhausted", error.message.orEmpty(), RunEventMetadata.Reason(error.message.orEmpty()))
                ledger.updateRunStatus(run.id, AgentRunStatus.BUDGET_EXHAUSTED, errorMessage = error.message ?: "Agent 预算耗尽")
            }
            throw error
        } catch (error: AgentTimeoutException) {
            withContext(NonCancellable) {
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, error.message ?: "Agent 步骤超时") }
                ledger.appendEvent(run.id, "run.timeout", error.message.orEmpty(), RunEventMetadata.Reason(error.message.orEmpty()))
                ledger.updateRunStatus(run.id, AgentRunStatus.BUDGET_EXHAUSTED, errorMessage = error.message ?: "Agent 步骤超时")
            }
            throw error
        } catch (error: AgentBackgroundApprovalRequiredException) {
            settleBlockedRun(run.id, state, error)
            throw error
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.CANCELLED, "用户取消 Agent 任务") }
                ledger.appendEvent(run.id, "run.cancelled", "用户取消 Agent 任务", RunEventMetadata.Reason("用户取消 Agent 任务"))
                ledger.updateRunStatus(run.id, AgentRunStatus.CANCELLED, errorMessage = "用户取消 Agent 任务")
            }
            throw error
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, error.message ?: "Agent 任务失败") }
                val reason = error.message ?: "Agent 任务失败"
                ledger.appendEvent(run.id, "run.failed", reason, RunEventMetadata.Reason(reason))
                ledger.updateRunStatus(run.id, AgentRunStatus.FAILED, errorMessage = reason)
            }
            throw error
        }
    }

    suspend fun resumeCommittedToolRun(
        detail: AgentRunDetailRecord,
    ): AgentRunSummary {
        val assessment = AgentRunResumePolicy.assess(
            detail,
            toolRegistry::definition,
            toolRegistry::supportsCommittedEffectVerification,
        )
        require(assessment.kind == AgentRunResumeKind.COMMITTED_TOOL_VERIFICATION) { assessment.reason }
        val recovery = requireNotNull(assessment.committedTool) { "恢复策略缺少已提交工具证据" }
        val run = detail.snapshot.run
        val definition = toolRegistry.definition(recovery.toolCall.name)
            ?: error("恢复时找不到已登记工具：${recovery.toolCall.name}")
        val receipt = requireNotNull(recovery.persistedResult.executionReceipt) {
            "恢复时缺少已提交执行回执"
        }
        val memoryRecallEnabled = detail.snapshot.events.none { event ->
            event.type == MEMORY_RECALL_DISABLED_EVENT_TYPE
        }
        (toolRegistry as? AgentRunContextAwareToolRegistry)?.bindRunContext(
            AgentToolExecutionContext(
                conversationId = run.conversationId,
                userMessageId = run.userMessageId,
                runId = run.id,
                goal = run.goal,
                memoryRecallEnabled = memoryRecallEnabled,
            ),
        )
        val restoredBudget = restoredExecutionBudget(detail)
        val state = AgentRuntimeExecutionState(
            runTimeoutMs = restoredBudget.snapshot.totalTimeoutMs,
            activeStepId = recovery.verificationStepId ?: recovery.executionStepId,
            monotonicClock = monotonicClock,
            initialConsumedMs = restoredBudget.snapshot.consumedMs,
        )
        // long: 多工具 Run 的前序步骤已由历史 tool.verify 证明完成；恢复总结必须保留这些事实，不能只向用户展示最后一条笔记结果。
        state.completedTools += recovery.verifiedPrefix
        return try {
            persistLegacyExecutionBudgetStart(run.id, restoredBudget, state.executionBudget)
            val executionStep = detail.snapshot.steps.single { it.id == recovery.executionStepId }
            if (executionStep.status == AgentStepStatus.RUNNING) {
                // long: ToolResult 与 COMMITTED 回执已落库时，可以把中断的执行 Step 收敛为完成；这里不再调用 Executor。
                ledger.updateStep(executionStep.id, AgentStepStatus.COMPLETED, recovery.persistedResult.content)
            }
            ledger.updateRunStatus(run.id, AgentRunStatus.VERIFYING)
            val verifyStep = recovery.verificationStepId?.let { stepId ->
                detail.snapshot.steps.single { it.id == stepId }
            } ?: ledger.appendStep(
                runId = run.id,
                type = AgentStepTypes.TOOL_VERIFY,
                title = "恢复执行后验证",
                detail = "只读回读已提交的 ${recovery.toolCall.name} 业务记录。",
                status = AgentStepStatus.RUNNING,
            )
            state.activeStepId = verifyStep.id
            validateRequiredAndroidPermissions(definition, checkpoint = "恢复验证前")
            val recoveredResult = toolRegistry.verifyCommittedEffect(recovery.toolCall, receipt)
                ?: error("工具不支持已提交结果的只读恢复验证：${recovery.toolCall.name}")
            validateExecutionReceipt(recovery.toolCall, recoveredResult)
            require(recoveredResult.executionReceipt == receipt) { "恢复回读的执行回执与历史证据不一致" }
            if (!recoveredResult.success) {
                recoveredResult.recoveryFailure?.let { failure ->
                    throw ToolRecoveryFailureException(recovery.toolCall.name, failure, recoveredResult.content)
                }
                error("已提交工具结果恢复验证失败：${recoveredResult.content}")
            }
            when (definition.verificationPolicy) {
                ToolVerificationPolicy.RESULT_READABLE -> require(recoveredResult.content.isNotBlank()) {
                    "恢复验证的工具结果为空"
                }
                ToolVerificationPolicy.EXECUTOR_VERIFIED -> require(recoveredResult.verified == true) {
                    "已提交工具结果未通过 Executor 回读验证"
                }
            }
            ledger.appendEvent(
                runId = run.id,
                type = "tool.verify",
                message = "进程重建后工具验证通过：${recovery.toolCall.name}",
                metadata = RunEventMetadata.ToolVerification(
                    toolName = recovery.toolCall.name,
                    status = ToolVerificationStatus.PASSED,
                    toolCallId = recovery.toolCall.id,
                ),
            )
            ledger.updateStep(verifyStep.id, AgentStepStatus.COMPLETED, "已只读回读 operation ${receipt.operationId} 并验证通过")
            state.activeStepId = null
            state.completedTools += AgentToolExecution(recovery.toolCall, recoveredResult)
            completeRecoveredRun(run, state)
        } catch (error: CancellationException) {
            // long: 用户取消恢复时必须同时关闭活动验证 Step 和原 Run，避免任务中心留下仍在验证的假状态。
            withContext(NonCancellable) {
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.CANCELLED, "用户取消恢复验证") }
                ledger.appendEvent(
                    run.id,
                    "run.cancelled",
                    "用户取消恢复验证",
                    RunEventMetadata.Reason("用户取消恢复验证"),
                )
                ledger.updateRunStatus(run.id, AgentRunStatus.CANCELLED, errorMessage = "用户取消恢复验证")
            }
            throw error
        } catch (error: Throwable) {
            // long: operation 回读或证据核对失败时保留已提交事实，但把验证 Step 与 Run 明确标为失败，后续只能走带确认的新 Run 重试。
            withContext(NonCancellable) {
                val reason = error.message ?: "已提交工具结果恢复验证失败"
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, reason) }
                val recoveryFailure = error as? ToolRecoveryFailureException
                if (recoveryFailure == null) {
                    ledger.appendEvent(run.id, "run.failed", reason, RunEventMetadata.Reason(reason))
                } else {
                    // long: 恢复失败码和建议动作以 typed event 独立落库，任务中心不需要解析异常文案，也不会因后续措辞调整丢失可操作信息。
                    ledger.appendEvent(
                        run.id,
                        AgentEventTypes.RECOVERY_FAILED,
                        reason,
                        RunEventMetadata.RecoveryFailure(
                            toolName = recoveryFailure.toolName,
                            code = recoveryFailure.failure.code,
                            reason = recoveryFailure.failure.reason,
                            suggestedAction = recoveryFailure.failure.suggestedAction,
                        ),
                    )
                }
                ledger.updateRunStatus(run.id, AgentRunStatus.FAILED, errorMessage = reason)
            }
            throw error
        }
    }

    suspend fun resumeVerifiedToolRun(
        detail: AgentRunDetailRecord,
    ): AgentRunSummary {
        val assessment = AgentRunResumePolicy.assess(detail)
        require(assessment.kind == AgentRunResumeKind.VERIFIED_TOOL_COMPLETION) { assessment.reason }
        val recovery = requireNotNull(assessment.verifiedTool) { "恢复策略缺少已验证工具证据" }
        val run = detail.snapshot.run
        val restoredBudget = restoredExecutionBudget(detail)
        val state = AgentRuntimeExecutionState(
            runTimeoutMs = restoredBudget.snapshot.totalTimeoutMs,
            activeStepId = recovery.lastVerificationStepId,
            monotonicClock = monotonicClock,
            initialConsumedMs = restoredBudget.snapshot.consumedMs,
        )
        state.completedTools += recovery.verifiedTools
        state.executedToolCalls = recovery.verifiedTools.size
        state.toolCallFingerprints += recovery.verifiedTools.map { toolCallFingerprint(it.toolCall) }
        return try {
            persistLegacyExecutionBudgetStart(run.id, restoredBudget, state.executionBudget)
            val verificationStep = detail.snapshot.steps.single { it.id == recovery.lastVerificationStepId }
            if (verificationStep.status == AgentStepStatus.RUNNING) {
                // long: tool.verify 已经持久化为 PASSED，进程重建只补齐同一验证 Step 的控制面终态，不能再次执行工具或追加第二条验证事实。
                ledger.updateStep(
                    verificationStep.id,
                    AgentStepStatus.COMPLETED,
                    "验证结果已在进程终止前持久化",
                )
            }
            state.activeStepId = null
            completeRecoveredRun(run, state)
        } catch (error: CancellationException) {
            withContext(NonCancellable) {
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.CANCELLED, "用户取消已验证结果恢复") }
                ledger.appendEvent(
                    run.id,
                    "run.cancelled",
                    "用户取消已验证结果恢复",
                    RunEventMetadata.Reason("用户取消已验证结果恢复"),
                )
                ledger.updateRunStatus(run.id, AgentRunStatus.CANCELLED, errorMessage = "用户取消已验证结果恢复")
            }
            throw error
        } catch (error: Throwable) {
            withContext(NonCancellable) {
                val reason = error.message ?: "已验证工具结果恢复失败"
                state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.FAILED, reason) }
                ledger.appendEvent(run.id, "run.failed", reason, RunEventMetadata.Reason(reason))
                ledger.updateRunStatus(run.id, AgentRunStatus.FAILED, errorMessage = reason)
            }
            throw error
        }
    }

    private suspend fun continuePlanning(
        run: AgentRunRecord,
        goal: String,
        executionOrigin: AgentExecutionOrigin,
        state: AgentRuntimeExecutionState,
    ) {
        while (true) {
            ledger.updateRunStatus(run.id, AgentRunStatus.THINKING)
            val thinking = ledger.appendStep(
                runId = run.id,
                type = AgentStepTypes.LLM_PLAN,
                title = "模型规划",
                detail = "模型正在根据目标和已验证结果决定下一步。",
                status = AgentStepStatus.RUNNING,
            )
            state.activeStepId = thinking.id
            currentCoroutineContext().ensureActive()
            val planCall = try {
                runTimedStep("模型规划", options.modelStepTimeoutMs, state.executionBudget) {
                    llm.proposeNextActionWithTelemetry(goal, toolRegistry.availableTools(), state.completedTools.toList())
                }
            } catch (error: AgentLlmResponseException) {
                appendLlmRequestEvent(run.id, AgentLlmPhase.PLAN, error.telemetry)
                // long: 模型已消耗的单调预算必须和失败遥测一起落库；否则进程重建或任务重试会看见过期预算快照，误以为这段网络等待从未发生。
                persistExecutionBudget(run.id, "模型规划失败后的执行预算", state.executionBudget)
                appendLlmFailureEvent(run.id, AgentLlmPhase.PLAN, error)
                throw error
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // long: 网络、解析和网关异常没有统一 telemetry 时仍要冻结已消耗预算，再由外层写入失败终态，不能留下只写 Step 的半份审计。
                persistExecutionBudget(run.id, "模型规划异常后的执行预算", state.executionBudget)
                appendLlmFailureEvent(run.id, AgentLlmPhase.PLAN, error)
                throw error
            }
            appendLlmRequestEvent(run.id, AgentLlmPhase.PLAN, planCall.telemetry)
            persistExecutionBudget(run.id, "模型规划执行预算", state.executionBudget)
            val planDecision = planCall.value
            if (planDecision == AgentPlanDecision.Complete) {
                ledger.updateStep(thinking.id, AgentStepStatus.COMPLETED, "模型确认任务工具步骤已完成")
                state.activeStepId = null
                return
            }

            val proposedCall = (planDecision as AgentPlanDecision.CallTool).toolCall
            val definition = toolRegistry.definition(proposedCall.name)
                ?: error("模型选择了未注册工具：${proposedCall.name}")
            val toolCall = proposedCall.copy(risk = definition.risk)
            if (definition.validateBeforeAudit) {
                // long: 设备输入等参数可能包含不应落盘的敏感值；这类工具必须先完成确定性校验，再写 proposed/ledger 审计。
                validateToolArguments(definition, toolCall)
            }
            // long: 每轮模型只负责提出下一次工具调用；风险、校验、审批和验证仍逐步由应用代码决定，已执行结果不能放宽后续工具边界。
            ledger.appendEvent(
                runId = run.id,
                type = "tool.call.proposed",
                message = "模型提出工具调用：${toolCall.name}",
                metadata = AgentEventMetadata.toolCall(toolCall),
            )
            ledger.updateStep(thinking.id, AgentStepStatus.COMPLETED, "模型选择工具：${toolCall.name}")
            state.activeStepId = null
            executeToolCall(
                runId = run.id,
                definition = definition,
                toolCall = toolCall,
                executionOrigin = executionOrigin,
                state = state,
                recordValidationStep = true,
                approvalAlreadyGranted = false,
            )
        }
    }

    private suspend fun executeToolCall(
        runId: String,
        definition: ToolDefinition,
        toolCall: ToolCall,
        executionOrigin: AgentExecutionOrigin,
        state: AgentRuntimeExecutionState,
        recordValidationStep: Boolean,
        approvalAlreadyGranted: Boolean,
    ) {
        val validation = if (recordValidationStep) {
            ledger.appendStep(
                runId = runId,
                type = "tool.validate",
                title = "工具参数校验",
                detail = "校验 ${toolCall.name} 的 JSON Schema、业务规则、Android 权限和循环风险。",
                status = AgentStepStatus.RUNNING,
            ).also { state.activeStepId = it.id }
        } else {
            null
        }
        validateToolArguments(definition, toolCall)
        if (executionOrigin == AgentExecutionOrigin.BACKGROUND && definition.approvalPolicy != ToolApprovalPolicy.NONE) {
            // long: 后台没有可见审批卡，任何需要确认的工具都在 Executor 之前收敛为 BLOCKED；前台 once/session 授权不会传入这条链路。
            throw AgentBackgroundApprovalRequiredException(toolCall.name)
        }
        validateToolExecutionPolicy(definition, executionOrigin)
        checkToolBudget(state.executedToolCalls)
        checkLoopRisk(toolCall, state.toolCallFingerprints)
        if (validation != null) {
            ledger.appendEvent(
                runId = runId,
                type = "tool.call.validated",
                message = "工具调用已校验：${toolCall.name}",
                metadata = AgentEventMetadata.toolCall(toolCall),
            )
            ledger.updateStep(validation.id, AgentStepStatus.COMPLETED, "参数校验通过")
            state.activeStepId = null
        }

        if (!approvalAlreadyGranted) {
            if (definition.approvalPolicy == ToolApprovalPolicy.NONE) {
                ledger.appendEvent(
                    runId = runId,
                    type = "approval.skipped",
                    message = "SAFE 工具无需审批：${toolCall.name}",
                    metadata = RunEventMetadata.ApprovalSkipped(
                        toolName = toolCall.name,
                        reason = "SAFE 工具无需审批",
                    ),
                )
            } else {
                ledger.updateRunStatus(runId, AgentRunStatus.WAITING_APPROVAL)
                val approval = ledger.appendStep(
                    runId = runId,
                    type = "approval",
                    title = "应用侧审批",
                    detail = "等待应用侧审批 ${toolCall.name}",
                    status = AgentStepStatus.RUNNING,
                )
                state.activeStepId = approval.id
                // long: 每一步写操作都独立等待用户决定，前一步获批不代表后续副作用自动继承授权。
                val decision = approvalGate.requestApproval(runId, toolCall, definition)
                ledger.appendEvent(
                    runId = runId,
                    type = if (decision.approved) "approval.granted" else "approval.denied",
                    message = if (decision.approved) "工具审批通过：${toolCall.name}" else "工具审批拒绝：${toolCall.name}",
                    metadata = AgentEventMetadata.approval(toolCall, decision),
                )
                if (!decision.approved) error("工具未获批准：${decision.reason}")
                ledger.updateStep(approval.id, AgentStepStatus.COMPLETED, "已批准：${toolCall.name} · ${decision.reason}")
                state.activeStepId = null
            }
        }

        // long: 用户查看审批时可能切到系统设置撤销权限；批准只表达副作用意愿，不能替代执行瞬间的 Android 授权状态。
        validateRequiredAndroidPermissions(definition, checkpoint = "执行前")
        ledger.updateRunStatus(runId, AgentRunStatus.EXECUTING)
        val execution = ledger.appendStep(
            runId = runId,
            type = AgentStepTypes.TOOL_EXECUTE,
            title = "执行工具",
            detail = "正在执行 ${toolCall.name}",
            status = AgentStepStatus.RUNNING,
        )
        state.activeStepId = execution.id
        currentCoroutineContext().ensureActive()
        val toolStartedAtMs = monotonicClock.nowMs()
        val toolResult = runTimedStep(
            "工具执行 ${toolCall.name}",
            definition.timeoutMs ?: options.toolStepTimeoutMs,
            state.executionBudget,
        ) {
            toolRegistry.execute(toolCall)
        }
        validateExecutionReceipt(toolCall, toolResult)
        state.executedToolCalls += 1
        // long: 工具耗时与 Run 执行预算必须使用同一单调时钟；系统时间校准不能制造负耗时或虚增剩余预算。
        val toolDurationMs = (monotonicClock.nowMs() - toolStartedAtMs).coerceAtLeast(0)
        ledger.appendEvent(
            runId = runId,
            type = "tool.result",
            message = if (toolResult.success) "工具执行成功：${toolCall.name}" else "工具执行失败：${toolCall.name}",
            metadata = AgentEventMetadata.toolResult(definition, toolCall, toolResult, toolDurationMs),
        )
        faultInjector.afterToolResultEventPersisted(runId, toolCall, toolResult)
        persistExecutionBudget(runId, "工具执行预算：${toolCall.name}", state.executionBudget)
        faultInjector.afterToolResultPersisted(runId, toolCall, toolResult)
        if (!toolResult.success) error("工具执行失败：${toolResult.content}")
        ledger.updateStep(execution.id, AgentStepStatus.COMPLETED, toolResult.content)
        state.activeStepId = null

        ledger.updateRunStatus(runId, AgentRunStatus.VERIFYING)
        val verify = ledger.appendStep(
            runId = runId,
            type = AgentStepTypes.TOOL_VERIFY,
            title = "执行后验证",
            detail = when (definition.verificationPolicy) {
                ToolVerificationPolicy.RESULT_READABLE -> "检查工具是否返回可读结果。"
                ToolVerificationPolicy.EXECUTOR_VERIFIED -> "检查 Executor 是否完成回读验证。"
            },
            status = AgentStepStatus.RUNNING,
        )
        state.activeStepId = verify.id
        currentCoroutineContext().ensureActive()
        // long: Executor 运行期间系统权限仍可能被撤销；验证前再次检查，保留已发生的工具结果，同时拒绝把失去授权后的状态标记为已验证完成。
        validateRequiredAndroidPermissions(definition, checkpoint = "验证前")
        when (definition.verificationPolicy) {
            ToolVerificationPolicy.RESULT_READABLE -> require(toolResult.content.isNotBlank()) { "工具结果为空，无法验证" }
            ToolVerificationPolicy.EXECUTOR_VERIFIED -> require(toolResult.verified == true) { "工具未通过 Executor 回读验证" }
        }
        ledger.appendEvent(
            runId = runId,
            type = "tool.verify",
            message = "工具验证通过：${toolCall.name}",
            metadata = RunEventMetadata.ToolVerification(
                toolName = toolCall.name,
                status = ToolVerificationStatus.PASSED,
                toolCallId = toolCall.id,
            ),
        )
        faultInjector.afterToolVerificationPersisted(runId, toolCall, toolResult)
        ledger.updateStep(verify.id, AgentStepStatus.COMPLETED, "验证通过")
        state.activeStepId = null
        state.completedTools += AgentToolExecution(toolCall, toolResult)
    }

    private suspend fun completeRun(
        run: AgentRunRecord,
        goal: String,
        state: AgentRuntimeExecutionState,
    ): AgentRunSummary {
        require(state.completedTools.isNotEmpty()) { "模型未执行任何工具就结束了 Agent Run" }
        val summaryStep = ledger.appendStep(
            runId = run.id,
            type = AgentStepTypes.LLM_SUMMARIZE,
            title = "回复样式选择",
            detail = "模型根据用户偏好选择回复详略和语气。",
            status = AgentStepStatus.RUNNING,
        )
        state.activeStepId = summaryStep.id
        var summaryFallbackReason: String? = null
        val summaryCall = try {
            runTimedStep("模型总结", options.modelStepTimeoutMs, state.executionBudget) {
                llm.summarizeWithTelemetry(goal, state.completedTools)
            }
        } catch (error: AgentTimeoutException) {
            // long: 工具已经执行并验证成功时，最终总结只是展示层增强；上游总结超时不应把已经完成的本地写入任务改判失败。
            summaryFallbackReason = error.message ?: "模型总结超时"
            persistExecutionBudget(run.id, "模型总结超时后的执行预算", state.executionBudget)
            null
        } catch (error: AgentLlmResponseException) {
            appendLlmRequestEvent(run.id, AgentLlmPhase.SUMMARIZE, error.telemetry)
            persistExecutionBudget(run.id, "模型总结失败后的执行预算", state.executionBudget)
            appendLlmFailureEvent(run.id, AgentLlmPhase.SUMMARIZE, error)
            summaryFallbackReason = error.message ?: "模型总结失败"
            null
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            persistExecutionBudget(run.id, "模型总结异常后的执行预算", state.executionBudget)
            appendLlmFailureEvent(run.id, AgentLlmPhase.SUMMARIZE, error)
            summaryFallbackReason = error.message ?: "模型总结异常"
            null
        }
        summaryCall?.let {
            appendLlmRequestEvent(run.id, AgentLlmPhase.SUMMARIZE, it.telemetry)
            persistExecutionBudget(run.id, "模型总结执行预算", state.executionBudget)
        }
        val summaryCandidate = summaryCall?.value?.takeIf { it.isNotBlank() } ?: run {
            if (summaryFallbackReason == null) summaryFallbackReason = "模型总结为空"
            null
        }
        val presentation = summaryCandidate?.let(AgentSummaryPresentationParser::parse)
        if (summaryCandidate != null && presentation == null) summaryFallbackReason = "模型没有返回合法的总结样式配置"
        val response = presentation?.let { buildVerifiedResponse(run.id, goal, state.completedTools, it) }
            ?: buildFallbackResponse(run.id, goal, state.completedTools)
        val summaryDetail = if (summaryFallbackReason != null) {
            ledger.appendEvent(
                runId = run.id,
                type = "llm.summarize.fallback",
                message = summaryFallbackReason.orEmpty(),
                metadata = RunEventMetadata.Reason(summaryFallbackReason.orEmpty()),
            )
            "${summaryFallbackReason}，已使用本地兜底回复"
        } else {
            "已按模型选择的安全样式生成最终回复"
        }
        ledger.updateStep(summaryStep.id, AgentStepStatus.COMPLETED, summaryDetail)
        state.activeStepId = null
        ledger.updateRunStatus(run.id, AgentRunStatus.COMPLETED, result = response)
        return AgentRunSummary(
            runId = run.id,
            status = AgentRunStatus.COMPLETED,
            responseText = response,
            verifiedContext = buildVerifiedContext(run.id, state.completedTools),
        )
    }

    private suspend fun completeRecoveredRun(
        run: AgentRunRecord,
        state: AgentRuntimeExecutionState,
    ): AgentRunSummary {
        require(state.completedTools.isNotEmpty()) { "验证阶段恢复缺少已验证工具结果" }
        val summaryStep = ledger.appendStep(
            runId = run.id,
            type = AgentStepTypes.RECOVERY_SUMMARIZE,
            title = "生成恢复验证总结",
            detail = "使用已持久化 ToolCall 和只读回读结果生成本地可信总结。",
            status = AgentStepStatus.RUNNING,
        )
        state.activeStepId = summaryStep.id
        // long: 旧模型请求与规划协程已丢失，恢复只使用已验证工具事实生成本地总结，避免为了文案再开放旧执行栈。
        val response = buildFallbackResponse(run.id, run.goal, state.completedTools)
        ledger.appendEvent(
            runId = run.id,
            type = AgentEventTypes.RECOVERY_SUMMARY,
            message = "已使用验证后工具事实生成本地总结",
            metadata = RunEventMetadata.Reason("验证阶段恢复不恢复旧模型协程"),
        )
        ledger.updateStep(summaryStep.id, AgentStepStatus.COMPLETED, "已生成本地可信总结")
        state.activeStepId = null
        ledger.updateRunStatus(run.id, AgentRunStatus.COMPLETED, result = response)
        return AgentRunSummary(
            runId = run.id,
            status = AgentRunStatus.COMPLETED,
            responseText = response,
            verifiedContext = buildVerifiedContext(run.id, state.completedTools),
        )
    }

    private suspend fun appendLlmRequestEvent(
        runId: String,
        phase: AgentLlmPhase,
        telemetry: AgentLlmRequestTelemetry?,
    ) {
        if (telemetry == null) return
        // long: 事件只保存请求规模、计时和上游 usage，不保存 Prompt 正文；这样可以分析成本与性能，同时不复制用户内容和工具结果。
        ledger.appendEvent(
            runId = runId,
            type = AgentEventTypes.LLM_REQUEST_COMPLETED,
            message = "模型请求完成：${phase.name.lowercase()}",
            metadata = RunEventMetadata.LlmRequest(
                phase = phase,
                model = telemetry.model,
                latencyMs = telemetry.latencyMs,
                firstByteLatencyMs = telemetry.firstByteLatencyMs,
                promptBytes = telemetry.promptBytes,
                inputTokens = telemetry.inputTokens,
                outputTokens = telemetry.outputTokens,
                totalTokens = telemetry.totalTokens,
            ),
        )
    }

    private suspend fun appendLlmFailureEvent(
        runId: String,
        phase: AgentLlmPhase,
        error: Throwable,
    ) {
        // long: 失败分类只保存稳定枚举和可读原因，不把网络异常对象或请求正文写入 Room；流式断流、HTTP 错误和解析失败因此共享同一审计入口。
        ledger.appendEvent(
            runId = runId,
            type = AgentEventTypes.LLM_REQUEST_FAILED,
            message = "模型请求失败：${phase.name.lowercase()}",
            metadata = RunEventMetadata.LlmFailure(
                phase = phase,
                kind = error.toAgentLlmFailureKind(),
                reason = error.message ?: "未知模型请求错误",
            ),
        )
    }

    private fun Throwable.toAgentLlmFailureKind(): AgentLlmFailureKind {
        val apiFailure = this as? ApiFailure ?: cause as? ApiFailure
        return when (apiFailure?.kind) {
            FailureKind.AUTHENTICATION -> AgentLlmFailureKind.AUTHENTICATION
            FailureKind.REQUEST_URL -> AgentLlmFailureKind.REQUEST_URL
            FailureKind.RATE_LIMIT -> AgentLlmFailureKind.RATE_LIMIT
            FailureKind.MODEL -> AgentLlmFailureKind.MODEL
            FailureKind.TIMEOUT -> AgentLlmFailureKind.TIMEOUT
            FailureKind.DNS -> AgentLlmFailureKind.DNS
            FailureKind.TLS -> AgentLlmFailureKind.TLS
            FailureKind.CONNECTION -> AgentLlmFailureKind.CONNECTION
            FailureKind.RESPONSE -> AgentLlmFailureKind.RESPONSE
            FailureKind.UNKNOWN -> AgentLlmFailureKind.UNKNOWN
            null -> if (this is AgentLlmResponseException) AgentLlmFailureKind.RESPONSE else AgentLlmFailureKind.UNKNOWN
        }
    }

    private fun buildFallbackResponse(
        runId: String,
        goal: String,
        completedTools: List<AgentToolExecution>,
    ): String {
        return buildVerifiedResponse(
            runId = runId,
            goal = goal,
            completedTools = completedTools,
            presentation = AgentSummaryPresentation(
                style = AgentSummaryStyle.DETAILED,
                tone = AgentSummaryTone.NEUTRAL,
            ),
        )
    }

    private fun buildVerifiedResponse(
        runId: String,
        goal: String,
        completedTools: List<AgentToolExecution>,
        presentation: AgentSummaryPresentation,
    ): String {
        val finalExecution = completedTools.lastOrNull() ?: error("没有已完成工具，无法生成 Agent 回复")
        if (completedTools.size == 1) {
            val definition = toolRegistry.definition(finalExecution.toolCall.name)
                ?: error("找不到已完成工具定义：${finalExecution.toolCall.name}")
            val heading = when (presentation.tone) {
                AgentSummaryTone.NEUTRAL -> "Agent 任务已完成"
                AgentSummaryTone.FRIENDLY -> "任务已完成，结果如下"
                AgentSummaryTone.FORMAL -> "Agent 执行报告"
            }
            if (presentation.style == AgentSummaryStyle.COMPACT) {
                return "$heading\n\n- 工具：${finalExecution.toolCall.name}\n- 结果：${finalExecution.toolResult.content}"
            }
            return """
                $heading

                - Run ID：$runId
                - 目标：${goal.ifBlank { "未填写目标" }}
                - 工具：${finalExecution.toolCall.name}
                - 审批：${if (definition.approvalPolicy == ToolApprovalPolicy.NONE) "SAFE 工具无需审批" else "已通过应用侧审批"}
                - 执行结果：${finalExecution.toolResult.content}
                - 验证：${if (definition.verificationPolicy == ToolVerificationPolicy.EXECUTOR_VERIFIED) "Executor 回读验证通过" else "工具结果可读"}，任务进入 COMPLETED 终态
            """.trimIndent()
        }
        val heading = when (presentation.tone) {
            AgentSummaryTone.NEUTRAL -> "Agent 任务已完成"
            AgentSummaryTone.FRIENDLY -> "任务已完成，结果如下"
            AgentSummaryTone.FORMAL -> "Agent 执行报告"
        }
        val resultLines = completedTools.mapIndexed { index, execution ->
            "- 步骤 ${index + 1} · ${execution.toolCall.name}：${execution.toolResult.content}"
        }.joinToString("\n")
        if (presentation.style == AgentSummaryStyle.COMPACT) {
            return "$heading\n\n$resultLines"
        }
        return """
            $heading

            - Run ID：$runId
            - 目标：${goal.ifBlank { "未填写目标" }}
            - 工具步骤：${completedTools.joinToString(" -> ") { it.toolCall.name }}
            $resultLines
            - 验证：全部 ${completedTools.size} 个工具步骤均已通过应用侧验证，任务进入 COMPLETED 终态
        """.trimIndent()
    }

    private fun buildVerifiedContext(
        runId: String,
        completedTools: List<AgentToolExecution>,
    ): VerifiedAgentContext {
        val executions = completedTools.map { execution ->
            execution.toVerifiedToolExecution()
        }
        val finalExecution = executions.lastOrNull() ?: error("没有已完成工具，无法生成可信上下文")
        // long: 顶层字段继续映射最后一步以兼容旧消息；完整列表才是多步 Run 的事实来源，顺序与 RunEvent 中的真实执行顺序一致。
        return VerifiedAgentContext(
            runId = runId,
            toolName = finalExecution.toolName,
            arguments = finalExecution.arguments,
            success = executions.all { it.success },
            verificationStatus = finalExecution.verificationStatus,
            rawResult = finalExecution.rawResult,
            memoryIdsUsed = executions.flatMap { it.memoryIdsUsed }.distinct(),
            knowledgeReferences = executions.flatMap { it.knowledgeReferences }.distinct(),
            toolExecutions = executions,
        )
    }

    private fun AgentToolExecution.toVerifiedToolExecution(): VerifiedToolExecution {
        val verificationStatus = when (toolResult.verified) {
            true -> AgentVerificationStatus.VERIFIED
            false -> AgentVerificationStatus.FAILED
            null -> AgentVerificationStatus.READABLE_ONLY
        }
        return VerifiedToolExecution(
            toolName = toolCall.name,
            arguments = toolCall.arguments.toSortedMap(),
            success = toolResult.success,
            verificationStatus = verificationStatus,
            rawResult = toolResult.content,
            memoryIdsUsed = toolResult.memoryIdsUsed,
            knowledgeReferences = toolResult.knowledgeReferences,
        )
    }

    private suspend fun <T> runTimedStep(
        label: String,
        timeoutMs: Long,
        executionBudget: AgentExecutionBudget,
        block: suspend () -> T,
    ): T {
        return executionBudget.run(label, timeoutMs, block)
    }

    private suspend fun persistExecutionBudget(
        runId: String,
        reason: String,
        budget: AgentExecutionBudget,
    ) {
        val snapshot = budget.snapshot()
        ledger.appendEvent(
            runId = runId,
            type = AgentEventTypes.EXECUTION_BUDGET_UPDATED,
            message = "$reason：${snapshot.consumedMs}/${snapshot.totalTimeoutMs}ms",
            metadata = RunEventMetadata.ExecutionBudget(
                totalTimeoutMs = snapshot.totalTimeoutMs,
                consumedMs = snapshot.consumedMs,
            ),
        )
    }

    private fun restoredExecutionBudget(detail: AgentRunDetailRecord): RestoredExecutionBudget {
        return when (val assessment = AgentExecutionBudgetEvidencePolicy.read(detail)) {
            AgentExecutionBudgetEvidenceAssessment.Legacy -> RestoredExecutionBudget(
                snapshot = AgentExecutionBudgetSnapshot(options.runTimeoutMs, consumedMs = 0),
                legacy = true,
            )
            is AgentExecutionBudgetEvidenceAssessment.Available -> RestoredExecutionBudget(
                snapshot = assessment.snapshot,
                legacy = false,
            )
            is AgentExecutionBudgetEvidenceAssessment.Invalid -> error(assessment.reason)
        }
    }

    private suspend fun persistLegacyExecutionBudgetStart(
        runId: String,
        restoredBudget: RestoredExecutionBudget,
        budget: AgentExecutionBudget,
    ) {
        if (!restoredBudget.legacy) return
        // long: 升级前 Run 没有预算快照；三个恢复入口都先冻结兼容起点，恢复过程再次中断时也不能重新获得一份完整预算。
        persistExecutionBudget(runId, "建立旧 Run 执行预算起点", budget)
    }

    private suspend fun settleBlockedRun(
        runId: String,
        state: AgentRuntimeExecutionState,
        error: AgentBackgroundApprovalRequiredException,
    ) {
        withContext(NonCancellable) {
            val reason = error.message ?: "后台任务需要用户确认"
            // long: 新 Run 与审批恢复入口共用同一 BLOCKED 收敛，避免步骤、事件和 Run 三层状态在后续扩展时发生分叉。
            state.activeStepId?.let { ledger.updateStep(it, AgentStepStatus.BLOCKED, reason) }
            ledger.appendEvent(runId, "run.blocked", reason, RunEventMetadata.Reason(reason))
            ledger.updateRunStatus(runId, AgentRunStatus.BLOCKED, errorMessage = reason)
        }
    }

    private fun validateToolArguments(
        definition: ToolDefinition,
        toolCall: ToolCall,
    ) {
        val validation = definition.validateArguments(toolCall.arguments)
        require(validation.isValid) {
            "工具 ${definition.name} 参数校验失败：${validation.errors.joinToString("；")}"
        }
    }

    private fun validateToolExecutionPolicy(
        definition: ToolDefinition,
        executionOrigin: AgentExecutionOrigin,
    ) {
        // long: 后台任务不能继承前台工具能力；只有定义明确声明支持后台时才继续审批和执行，避免未来调度入口默认放大工具权限面。
        check(executionOrigin != AgentExecutionOrigin.BACKGROUND || definition.permissionPolicy.supportsBackground) {
            "工具 ${definition.name} 不允许后台执行"
        }
        validateRequiredAndroidPermissions(definition, checkpoint = "参数校验时")
    }

    private fun validateRequiredAndroidPermissions(
        definition: ToolDefinition,
        checkpoint: String,
    ) {
        val missingPermissions = permissionChecker
            .missingPermissions(definition.permissionPolicy.requiredAndroidPermissions)
            .sorted()
        // long: 每个权限检查点都只接受系统当前明确授予的权限；检查器缺失时默认拒绝，防止新工具因漏注入或运行中撤销而 fail-open。
        check(missingPermissions.isEmpty()) {
            "工具 ${definition.name} 在$checkpoint 缺少 Android 权限：${missingPermissions.joinToString(", ")}"
        }
    }

    private fun validateExecutionReceipt(
        toolCall: ToolCall,
        result: ToolExecutionResult,
    ) {
        val receipt = result.executionReceipt ?: return
        // long: Executor 可以不提供回执，但提供后必须绑定本次 ToolCall；错配回执不能作为当前副作用证据写入审计或驱动后续恢复判断。
        check(receipt.toolCallId == toolCall.id) {
            "执行回执不属于当前工具调用：expected=${toolCall.id}, actual=${receipt.toolCallId}"
        }
    }

    private fun checkToolBudget(executedToolCalls: Int) {
        if (executedToolCalls >= options.maxToolCalls) {
            throw AgentBudgetExceededException("工具调用次数超过上限：${options.maxToolCalls}")
        }
    }

    private fun checkLoopRisk(toolCall: ToolCall, fingerprints: MutableSet<String>) {
        val fingerprint = toolCallFingerprint(toolCall)
        if (!fingerprints.add(fingerprint)) {
            throw AgentBudgetExceededException("检测到重复工具调用：${toolCall.name}")
        }
    }

    private fun toolCallFingerprint(toolCall: ToolCall): String =
        "${toolCall.name}:${toolCall.arguments.toSortedMap()}"

    private data class RestoredExecutionBudget(
        val snapshot: AgentExecutionBudgetSnapshot,
        val legacy: Boolean,
    )
}

private class ToolRecoveryFailureException(
    val toolName: String,
    val failure: ToolRecoveryFailure,
    detail: String,
) : IllegalStateException("已提交工具结果恢复验证失败：$detail")

private const val MEMORY_RECALL_DISABLED_EVENT_TYPE = "memory.recall.disabled"

private class AgentRuntimeExecutionState(
    runTimeoutMs: Long,
    activeStepId: String? = null,
    monotonicClock: MonotonicClock,
    initialConsumedMs: Long = 0,
) {
    var activeStepId: String? = activeStepId
    var executedToolCalls: Int = 0
    val toolCallFingerprints = mutableSetOf<String>()
    val completedTools = mutableListOf<AgentToolExecution>()
    val executionBudget = AgentExecutionBudget(runTimeoutMs, monotonicClock, initialConsumedMs)
}

internal class AgentExecutionBudget(
    private val totalTimeoutMs: Long,
    private val monotonicClock: MonotonicClock = systemMonotonicClock,
    initialConsumedMs: Long = 0,
) {
    private var consumedMs: Long = initialConsumedMs

    init {
        require(totalTimeoutMs > 0) { "Agent Run 超时时间必须大于 0" }
        require(initialConsumedMs in 0..totalTimeoutMs) { "Agent Run 已消耗预算超出合法范围" }
    }

    suspend fun <T> run(label: String, stepTimeoutMs: Long, block: suspend () -> T): T {
        require(stepTimeoutMs > 0) { "$label 超时时间必须大于 0" }
        val remainingMs = remainingMs()
        if (remainingMs <= 0) {
            throw AgentTimeoutException("Agent Run 超时：${totalTimeoutMs}ms")
        }
        val effectiveTimeoutMs = minOf(stepTimeoutMs, remainingMs)
        val timeoutSource = if (remainingMs <= stepTimeoutMs) TimeoutSource.RUN else TimeoutSource.STEP
        val startedAtMs = monotonicClock.nowMs()
        return try {
            withTimeout(effectiveTimeoutMs) { block() }
        } catch (error: TimeoutCancellationException) {
            // long: 单步超时和整次执行预算超时都会抛出 TimeoutCancellationException；先确认不是用户取消，再按触发的预算来源写入审计原因。
            currentCoroutineContext().ensureActive()
            if (timeoutSource == TimeoutSource.RUN) {
                throw AgentTimeoutException("Agent Run 超时：${totalTimeoutMs}ms")
            }
            throw AgentTimeoutException("$label 超时：${stepTimeoutMs}ms")
        } finally {
            // long: 只累计模型/工具执行段的单调耗时；应用侧审批没有进入这里，系统时间回拨和用户阅读审批都不能返还或消耗执行预算。
            val elapsedMs = (monotonicClock.nowMs() - startedAtMs).coerceAtLeast(0)
            consumedMs += minOf(elapsedMs, totalTimeoutMs - consumedMs)
        }
    }

    fun snapshot(): AgentExecutionBudgetSnapshot = AgentExecutionBudgetSnapshot(
        totalTimeoutMs = totalTimeoutMs,
        consumedMs = consumedMs,
    )

    private fun remainingMs(): Long = totalTimeoutMs - consumedMs

    private enum class TimeoutSource {
        STEP,
        RUN,
    }
}

private enum class AgentSummaryStyle {
    COMPACT,
    DETAILED,
}

private enum class AgentSummaryTone {
    NEUTRAL,
    FRIENDLY,
    FORMAL,
}

private data class AgentSummaryPresentation(
    val style: AgentSummaryStyle,
    val tone: AgentSummaryTone,
)

private object AgentSummaryPresentationParser {
    fun parse(raw: String): AgentSummaryPresentation? {
        return runCatching {
            val json = JSONObject(raw.trim())
            val keys = buildSet { json.keys().forEach(::add) }
            require(keys == setOf("style", "tone"))
            AgentSummaryPresentation(
                style = AgentSummaryStyle.valueOf(json.getString("style").uppercase()),
                tone = AgentSummaryTone.valueOf(json.getString("tone").uppercase()),
            )
        }.getOrNull()
    }
}

private object AgentEventMetadata {
    fun toolCall(call: ToolCall): RunEventMetadata {
        return RunEventMetadata.ToolCall(
            id = call.id,
            toolName = call.name,
            risk = call.risk,
            arguments = call.arguments.toSortedMap(),
        )
    }

    fun approval(call: ToolCall, decision: ApprovalDecision): RunEventMetadata {
        return RunEventMetadata.ApprovalDecision(
            toolName = call.name,
            approved = decision.approved,
            reason = decision.reason,
        )
    }

    fun toolResult(
        definition: ToolDefinition,
        call: ToolCall,
        result: ToolExecutionResult,
        durationMs: Long,
    ): RunEventMetadata {
        return RunEventMetadata.ToolResult(
            toolName = call.name,
            success = result.success,
            content = result.content,
            verified = result.verified,
            durationMs = durationMs,
            memoryIdsUsed = result.memoryIdsUsed,
            knowledgeReferences = result.knowledgeReferences,
            toolCallId = call.id,
            replaySafety = definition.replaySafety,
            executionReceipt = result.executionReceipt,
        )
    }
}
