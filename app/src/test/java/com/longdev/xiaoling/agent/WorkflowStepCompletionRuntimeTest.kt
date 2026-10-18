package com.longdev.xiaoling.agent

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/** long: 明确的步骤工具契约约束完成判定，纠错不能绕过审批、重放动作或放宽调用预算。 */
class WorkflowStepCompletionRuntimeTest {
    private val required = listOf("device.snapshot", "device.open_app", "device.snapshot")

    @Test fun snapshotOnlyCompleteFailsAfterOneCorrectionWithoutExecutingAction() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = CompletionToolRegistry()
        var planningCalls = 0
        val llm = planner { _, history ->
            planningCalls++
            if (history.isEmpty()) tool("device.snapshot") else AgentPlanDecision.Complete
        }
        val failure = runCatching {
            MinimalAgentRuntime(ledger, registry, llm).run("c", "m", "打开设置", invocationSource = AgentInvocationSource.WORKFLOW,
                workflowDeviceActionContext = context())
        }.exceptionOrNull()
        val snapshot = ledger.snapshot(requireNotNull(ledger.lastRunId))
        assertTrue(failure?.message.orEmpty().contains("纠错后仍未满足"))
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertEquals(listOf("device.snapshot"), registry.executed)
        assertEquals(3, planningCalls)
        assertEquals(1, snapshot.events.count { it.type == AgentEventTypes.LLM_PREMATURE_COMPLETE_RETRIED })
        assertFalse(snapshot.steps.any { it.type == AgentStepTypes.LLM_SUMMARIZE })
    }

    @Test fun correctionUsesRemainingToolsAndKeepsIndependentApproval() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = CompletionToolRegistry()
        var premature = false
        val planningGoals = mutableListOf<String>()
        val approved = mutableListOf<String>()
        val llm = planner { goal, history ->
            planningGoals += goal
            when (history.size) {
                0 -> tool("device.snapshot")
                1 -> if (!premature) { premature = true; AgentPlanDecision.Complete } else tool("device.open_app")
                2 -> tool("device.snapshot")
                else -> AgentPlanDecision.Complete
            }
        }
        val gate = object : ApprovalGate {
            override suspend fun requestApproval(runId: String, toolCall: ToolCall, definition: ToolDefinition): ApprovalDecision {
                approved += toolCall.name
                return ApprovalDecision(true, "仅批准本次打开设置")
            }
        }
        val summary = MinimalAgentRuntime(ledger, registry, llm, approvalGate = gate).run("c", "m", "打开设置",
            invocationSource = AgentInvocationSource.WORKFLOW, workflowDeviceActionContext = context())
        assertEquals(AgentRunStatus.COMPLETED, summary.status)
        assertEquals(required, registry.executed)
        assertEquals(listOf("device.open_app"), approved)
        assertTrue(planningGoals[2].contains("应用侧校验反馈"))
        assertTrue(planningGoals[2].contains("device.open_app -> device.snapshot"))
        assertEquals(1, ledger.snapshot(summary.runId).events.count { it.type == AgentEventTypes.LLM_PREMATURE_COMPLETE_RETRIED })
    }

    @Test fun actionWithoutFinalObservationCannotCompleteOrReplayAction() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = CompletionToolRegistry()
        val llm = planner { _, history -> when (history.size) {
            0 -> tool("device.snapshot")
            1 -> tool("device.open_app")
            else -> AgentPlanDecision.Complete
        } }
        val failure = runCatching { MinimalAgentRuntime(ledger, registry, llm).run("c", "m", "打开设置",
            invocationSource = AgentInvocationSource.WORKFLOW, workflowDeviceActionContext = context()) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("device.snapshot"))
        assertEquals(required.take(2), registry.executed)
        assertEquals(AgentRunStatus.FAILED, ledger.snapshot(requireNotNull(ledger.lastRunId)).run.status)
    }

    @Test fun repeatedVerifiedObservationDoesNotUseReadOnlyCompletionShortcut() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = CompletionToolRegistry()
        val llm = planner { _, _ -> tool("device.snapshot") }
        val failure = runCatching { MinimalAgentRuntime(ledger, registry, llm).run("c", "m", "打开设置",
            invocationSource = AgentInvocationSource.WORKFLOW, workflowDeviceActionContext = context()) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("纠错后仍未满足"))
        assertEquals(listOf("device.snapshot"), registry.executed)
        val snapshot = ledger.snapshot(requireNotNull(ledger.lastRunId))
        assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
        assertEquals(1, snapshot.events.count { it.type == AgentEventTypes.LLM_PREMATURE_COMPLETE_RETRIED })
        assertFalse(snapshot.events.any { it.type == AgentEventTypes.LLM_REPEAT_COMPLETED })
    }

    @Test fun repeatedReadAndCompleteShareOneCorrectionBudget() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = CompletionToolRegistry()
        var plans = 0
        val llm = planner { _, _ -> if (++plans <= 2) tool("device.snapshot") else AgentPlanDecision.Complete }
        val failure = runCatching { MinimalAgentRuntime(ledger, registry, llm).run("c", "m", "打开设置",
            invocationSource = AgentInvocationSource.WORKFLOW, workflowDeviceActionContext = context()) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("纠错后仍未满足"))
        assertEquals(3, plans)
        assertEquals(listOf("device.snapshot"), registry.executed)
        assertEquals(1, ledger.snapshot(requireNotNull(ledger.lastRunId)).events.count { it.type == AgentEventTypes.LLM_PREMATURE_COMPLETE_RETRIED })
    }

    @Test fun legacyObservationStepWithoutExplicitRequirementsKeepsItsBehavior() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = CompletionToolRegistry()
        val summary = MinimalAgentRuntime(ledger, registry, planner { _, history ->
            if (history.isEmpty()) tool("device.snapshot") else AgentPlanDecision.Complete
        }).run("c", "m", "只观察设置首页", invocationSource = AgentInvocationSource.WORKFLOW,
            workflowDeviceActionContext = context(emptyList()))
        assertEquals(AgentRunStatus.COMPLETED, summary.status)
        assertEquals(listOf("device.snapshot"), registry.executed)
    }

    @Test fun contractCannotIncreaseOriginalToolBudget() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = CompletionToolRegistry()
        val failure = runCatching { MinimalAgentRuntime(ledger, registry, planner { _, _ -> error("不能进入模型") },
            options = AgentRuntimeOptions(maxToolCalls = 2)).run("c", "m", "打开设置",
            invocationSource = AgentInvocationSource.WORKFLOW, workflowDeviceActionContext = context()) }.exceptionOrNull()
        assertTrue(failure?.message.orEmpty().contains("原预算内"))
        assertTrue(registry.executed.isEmpty())
        assertEquals(AgentRunStatus.FAILED, ledger.snapshot(requireNotNull(ledger.lastRunId)).run.status)
    }

    @Test fun recoveredVerifiedPrefixCannotReplaceActionOrFinalObservation() = runTest {
        for (verifiedCount in listOf(1, 2)) {
            val ledger = InMemoryAgentRunLedger()
            val registry = CompletionToolRegistry()
            var count = 0
            val failure = runCatching { MinimalAgentRuntime(ledger, registry, sequencePlanner(),
                faultInjector = object : AgentRuntimeFaultInjector {
                    override fun afterToolVerificationPersisted(runId: String, call: ToolCall, result: ToolExecutionResult) {
                        if (++count == verifiedCount) throw AgentProcessTerminationSimulation()
                    }
                }).run("c", "m", "打开设置", invocationSource = AgentInvocationSource.WORKFLOW,
                    workflowDeviceActionContext = context()) }.exceptionOrNull()
            assertTrue(failure is AgentProcessTerminationSimulation)
            val id = requireNotNull(ledger.lastRunId)
            val interrupted = AgentRunDetailRecord(ledger.snapshot(id), emptyList())
            val restored = runCatching { MinimalAgentRuntime(ledger, registry, planner { _, _ -> error("恢复不得重规划") })
                .resumeVerifiedToolRun(interrupted, AgentInvocationSource.WORKFLOW, context()) }.exceptionOrNull()
            assertTrue(restored?.message.orEmpty().contains("恢复未满足步骤完成标准"))
            val final = ledger.snapshot(id)
            assertEquals(AgentRunStatus.FAILED, final.run.status)
            assertEquals(required.take(verifiedCount), registry.executed)
            assertFalse(final.steps.any { it.type == AgentStepTypes.RECOVERY_SUMMARIZE })
        }
    }

    @Test fun recoveredCompleteChainKeepsRepeatedObservationAndDoesNotReplay() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = CompletionToolRegistry()
        var count = 0
        runCatching { MinimalAgentRuntime(ledger, registry, sequencePlanner(), faultInjector = object : AgentRuntimeFaultInjector {
            override fun afterToolVerificationPersisted(runId: String, call: ToolCall, result: ToolExecutionResult) {
                if (++count == 3) throw AgentProcessTerminationSimulation()
            }
        }).run("c", "m", "打开设置", invocationSource = AgentInvocationSource.WORKFLOW, workflowDeviceActionContext = context()) }
        val id = requireNotNull(ledger.lastRunId)
        val summary = MinimalAgentRuntime(ledger, registry, planner { _, _ -> error("恢复不得调用模型") })
            .resumeVerifiedToolRun(AgentRunDetailRecord(ledger.snapshot(id), emptyList()), AgentInvocationSource.WORKFLOW, context())
        assertEquals(AgentRunStatus.COMPLETED, summary.status)
        assertEquals(required, registry.executed)
        assertEquals(required, summary.verifiedContext.toolExecutions.map { it.toolName })
        assertEquals(3, ledger.snapshot(id).events.count { it.type == "tool.verify" })
    }

    @Test fun defaultRecoveryArgumentsCannotDowngradePersistedWorkflowSource() = runTest {
        val ledger = InMemoryAgentRunLedger()
        val registry = CompletionToolRegistry()
        val invokedLedger = object : AgentRunLedger by ledger {
            override suspend fun createRun(conversationId: String, userMessageId: String, goal: String, retryOfRunId: String?, parentRunId: String?) =
                createRun(conversationId, userMessageId, goal, retryOfRunId)
            override suspend fun createRun(conversationId: String, userMessageId: String, goal: String, retryOfRunId: String?): AgentRunRecord {
                val run = ledger.createRun(conversationId, userMessageId, goal, retryOfRunId)
                ledger.appendEvent(run.id, AgentChildLifecyclePolicy.INVOCATION_EVENT, "冻结来源", RunEventMetadata.Reason(
                    AgentChildLifecyclePolicy.invocation(AgentExecutionOrigin.FOREGROUND, AgentInvocationSource.WORKFLOW, false)))
                return run
            }
        }
        runCatching { MinimalAgentRuntime(invokedLedger, registry, sequencePlanner(), faultInjector = object : AgentRuntimeFaultInjector {
            override fun afterToolVerificationPersisted(runId: String, call: ToolCall, result: ToolExecutionResult) { throw AgentProcessTerminationSimulation() }
        }).run("c", "m", "打开设置", invocationSource = AgentInvocationSource.WORKFLOW, workflowDeviceActionContext = context()) }
        val id = requireNotNull(ledger.lastRunId)
        val failure = runCatching { MinimalAgentRuntime(ledger, registry, planner { _, _ -> error("不可规划") })
            .resumeVerifiedToolRun(AgentRunDetailRecord(ledger.snapshot(id), emptyList())) }.exceptionOrNull()
        assertTrue("failure=$failure", failure?.message.orEmpty().contains("原 Run 审计不一致"))
        assertEquals(AgentRunStatus.FAILED, ledger.snapshot(id).run.status)
        assertEquals(listOf("device.snapshot"), registry.executed)
    }

    @Test fun approvalRecoveryKeepsFrozenRequirementsAndAlreadyUsedCorrection() = runTest {
        for (correctedBeforeInterruption in listOf(false, true)) {
            val ledger = InMemoryAgentRunLedger()
            val registry = CompletionToolRegistry()
            var pending: ApprovalRequestRecord? = null
            val gate = object : ApprovalGate {
                override suspend fun requestApproval(runId: String, toolCall: ToolCall, definition: ToolDefinition): ApprovalDecision {
                    pending = ApprovalRequestRecord("a", runId, "c", toolCall.id, toolCall.name, definition.description,
                        definition.risk, toolCall.arguments, ApprovalRequestStatus.PENDING, null, 1, Long.MAX_VALUE, null)
                    throw AgentProcessTerminationSimulation()
                }
            }
            runCatching { MinimalAgentRuntime(ledger, registry, sequencePlanner(), gate).run("c", "m", "打开设置",
                invocationSource = AgentInvocationSource.WORKFLOW, workflowDeviceActionContext = context()) }
            val approval = requireNotNull(pending)
            if (correctedBeforeInterruption) ledger.appendEvent(approval.runId, AgentEventTypes.LLM_PREMATURE_COMPLETE_RETRIED,
                "已使用一次完成纠错", RunEventMetadata.Reason("已使用一次完成纠错"))
            val interrupted = AgentRunDetailRecord(ledger.snapshot(approval.runId), listOf(approval))
            val error = runCatching { MinimalAgentRuntime(ledger, registry, planner { _, _ -> AgentPlanDecision.Complete })
                .resumeApprovedRun(interrupted, approval, ApprovalDecision(true, "仅批准原动作"),
                    invocationSource = AgentInvocationSource.WORKFLOW, workflowDeviceActionContext = context()) }.exceptionOrNull()
            assertTrue(error?.message.orEmpty().contains("纠错后仍未满足"))
            val snapshot = ledger.snapshot(approval.runId)
            assertEquals(AgentRunStatus.FAILED, snapshot.run.status)
            assertEquals(required.take(2), registry.executed)
            assertEquals(1, snapshot.events.count { it.type == AgentEventTypes.LLM_PREMATURE_COMPLETE_RETRIED })
        }
    }

    private fun sequencePlanner() = planner { _, history -> if (history.size < required.size) tool(required[history.size]) else AgentPlanDecision.Complete }

    @Test fun committedReadBackCannotCompleteAWorkflowWithMissingLaterTools() = runTest {
        for (completeContract in listOf(false, true)) {
            val ledger = InMemoryAgentRunLedger()
            val name = "notes.create"
            val names = if (completeContract) listOf(name) else listOf(name, "device.snapshot")
            val frozen = context(names)
            var executions = 0
            var readBacks = 0
            val definition = ToolDefinition(name, "受控笔记写入", ToolRisk.SAFE,
                verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED, replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY)
            val registry = object : ToolRegistry {
                override fun availableTools() = listOf(definition)
                override fun definition(name: String) = definition.takeIf { it.name == name }
                private fun result(call: ToolCall) = ToolExecutionResult(true, "公开已提交笔记", verified = true,
                    executionReceipt = ToolExecutionReceipt(call.id, "note", call.id, ToolExecutionReceiptStatus.COMMITTED))
                override suspend fun execute(call: ToolCall): ToolExecutionResult { executions++; return result(call) }
                override suspend fun verifyCommittedEffect(call: ToolCall, receipt: ToolExecutionReceipt): ToolExecutionResult { readBacks++; return result(call) }
                override fun supportsCommittedEffectVerification(toolName: String) = toolName == name
            }
            val initial = planner { _, _ -> tool(name) }
            val failure = runCatching { MinimalAgentRuntime(ledger, registry, initial, faultInjector = object : AgentRuntimeFaultInjector {
                override fun afterToolResultPersisted(runId: String, call: ToolCall, result: ToolExecutionResult) { throw AgentProcessTerminationSimulation() }
            }).run("c", "m", "受控完成契约", invocationSource = AgentInvocationSource.WORKFLOW, workflowDeviceActionContext = frozen) }.exceptionOrNull()
            assertTrue(failure is AgentProcessTerminationSimulation)
            val id = requireNotNull(ledger.lastRunId)
            val restored = runCatching { MinimalAgentRuntime(ledger, registry, planner { _, _ -> error("本地恢复不得规划") })
                .resumeCommittedToolRun(AgentRunDetailRecord(ledger.snapshot(id), emptyList()), AgentInvocationSource.WORKFLOW, frozen) }
            assertEquals(completeContract, restored.isSuccess)
            assertEquals(if (completeContract) AgentRunStatus.COMPLETED else AgentRunStatus.FAILED, ledger.snapshot(id).run.status)
            assertEquals(1, executions)
            assertEquals(1, readBacks)
        }
    }

    private fun context(names: List<String> = required) = WorkflowDeviceActionRunContext("w", "s", "打开设置", "com.android.settings", names)
    private fun tool(name: String): AgentPlanDecision = AgentPlanDecision.CallTool(ToolCall(name = name,
        arguments = if (name == "device.open_app") mapOf("package_name" to "com.android.settings") else emptyMap(), risk = ToolRisk.SAFE))
    private fun planner(decide: (String, List<AgentToolExecution>) -> AgentPlanDecision) = object : AgentLlm {
        override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall = error("使用顺序规划入口")
        override suspend fun proposeNextAction(goal: String, tools: List<ToolDefinition>, completedTools: List<AgentToolExecution>) = decide(goal, completedTools)
        override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult): String = """{"style":"compact","tone":"neutral"}"""
    }
}

private class CompletionToolRegistry : ToolRegistry {
    val executed = mutableListOf<String>()
    private val definitions = listOf(ToolDefinition("device.snapshot", "观察当前窗口", ToolRisk.SAFE),
        ToolDefinition("device.open_app", "打开设置", ToolRisk.REQUIRES_APPROVAL,
            inputSchema = listOf(ToolInputField("package_name", "已确认应用", required = true)),
            verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED))
    override fun availableTools() = definitions
    override fun definition(name: String) = definitions.singleOrNull { it.name == name }
    override suspend fun execute(call: ToolCall): ToolExecutionResult {
        executed += call.name
        return ToolExecutionResult(success = true, content = "当前工具已执行并验证", verified = true)
    }
}
