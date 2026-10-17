package com.longdev.xiaoling.agent

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** long: 用 Runtime 的真实校验与 typed verification 路径证明插件 wrapper 不绕过账本。 */
class AgentPluginRuntimeTest {
    @Test fun pluginAuditReasonRoundTripsWithoutLosingIdentityEvidence() {
        val metadata = RunEventMetadata.Reason(PluginExecutionFixture().gateway().evidenceJson())
        assertEquals(metadata, RunEventMetadataCodec.decode(AgentPluginExecutionPolicy.SELECTION_EVENT,
            RunEventMetadataCodec.encode(metadata)))
    }
    @Test fun realRunBindingFailureSettlesAndClosesRegistry() = runBlocking {
        val tools = PluginExecutionTools(); val ledger = InMemoryAgentRunLedger()
        val delegate = object : ToolRegistry by tools, AgentRunContextAwareToolRegistry, AgentRunLifecycleAwareToolRegistry {
            override fun bindRunContext(context: AgentToolExecutionContext) { error("fixture bind failed") }
            override fun onRunFinished(runId: String) { tools.onRunFinished(runId) }
        }
        val g = PluginExecutionFixture().gateway(delegate)
        assertTrue(runCatching { g.guardRun { MinimalAgentRuntime(ledger, g, model()).run("c", "m", "时间") } }.isFailure)
        assertEquals(AgentRunStatus.FAILED, ledger.snapshot(requireNotNull(ledger.lastRunId)).run.status)
        assertTrue(tools.finished); assertEquals(0, tools.calls)
    }
    private fun model(beforePlan: suspend () -> Unit = {}) = object : AgentLlm {
        override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall {
            beforePlan(); return pluginTimeCall()
        }
        override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult) =
            """{"style":"compact","tone":"neutral"}"""
    }
    @Test fun runtimeSuccessKeepsTypedVerificationAndClosesPlugin() = runBlocking {
        val f = PluginExecutionFixture(); val tools = PluginExecutionTools(); val g = f.gateway(tools)
        val ledger = InMemoryAgentRunLedger()
        val summary = g.guardRun { MinimalAgentRuntime(ledger, g, model()).run("c", "m", "读取时间") }
        assertEquals(AgentRunStatus.COMPLETED, summary.status)
        assertTrue(ledger.snapshot(summary.runId).events.any { it.type == "tool.verify" &&
            (it.metadata as? RunEventMetadata.ToolVerification)?.status == ToolVerificationStatus.PASSED })
        assertEquals(1, tools.calls); assertTrue(tools.finished)
    }
    @Test fun modelWaitRevocationPersistsCancellationWithoutToolResult() = runBlocking {
        val f = PluginExecutionFixture(); val tools = PluginExecutionTools(); val g = f.gateway(tools)
        val ledger = InMemoryAgentRunLedger(); val waiting = CompletableDeferred<Unit>()
        val job = async { runCatching { g.guardRun { MinimalAgentRuntime(ledger, g, model {
            waiting.complete(Unit); awaitCancellation()
        }).run("c", "m", "读取时间") } } }
        waiting.await(); f.plugins.setEnabled("signed.read", false)
        assertTrue(withTimeout(2_000) { job.await() }.exceptionOrNull() is CancellationException)
        val detail = ledger.snapshot(requireNotNull(ledger.lastRunId))
        assertEquals(AgentRunStatus.CANCELLED, detail.run.status)
        assertTrue(detail.events.none { it.type == "tool.result" || it.type == "tool.verify" })
        assertEquals(0, tools.calls)
    }
    @Test fun lateReadCannotProducePassedVerification() = runBlocking {
        val f = PluginExecutionFixture(); val tools = PluginExecutionTools()
        tools.executeBlock = { f.plugins.setEnabled("signed.read", false); ToolExecutionResult(true, "迟到成功", true) }
        val g = f.gateway(tools); val ledger = InMemoryAgentRunLedger()
        assertTrue(runCatching { g.guardRun { MinimalAgentRuntime(ledger, g, model()).run("c", "m", "读取时间") } }
            .exceptionOrNull() is CancellationException)
        val snapshot = ledger.snapshot(requireNotNull(ledger.lastRunId))
        assertEquals(AgentRunStatus.CANCELLED, snapshot.run.status)
        assertTrue(snapshot.events.none { it.type == "tool.result" || it.type == "tool.verify" })
    }
    @Test fun damagedSelectionMetadataStillBlocksEveryRecoveryPolicy() = runBlocking {
        val ledger = InMemoryAgentRunLedger(); val run = ledger.createRun("c", "m", "旧插件", null)
        ledger.appendEvent(run.id, AgentPluginExecutionPolicy.SELECTION_EVENT, "损坏 metadata", null)
        ledger.updateRunStatus(run.id, AgentRunStatus.EXECUTING)
        var detail = AgentRunDetailRecord(ledger.snapshot(run.id), emptyList())
        val assessment = AgentRunResumePolicy.assess(detail)
        assertEquals(AgentRunResumeKind.RESTART_REQUIRED, assessment.kind)
        assertEquals(AgentRunRestartDispositionCode.PLUGIN_FOREGROUND_SELECTION_EXPIRED, assessment.restartDisposition!!.code)
        assertTrue(runCatching { AgentPluginExecutionPolicy.requireFreshSelection(detail) }.isFailure)
        assertTrue(AgentNotCommittedReplayQualificationPolicy.assess(detail, null) { null } is AgentNotCommittedReplayQualificationAssessment.Ineligible)
        ledger.updateRunStatus(run.id, AgentRunStatus.CANCELLED)
        detail = detail.copy(snapshot = ledger.snapshot(run.id))
        assertEquals(AgentTaskRetryEligibility.NotRetryable, AgentTaskRetryPolicy.evaluate(detail))
        assertFalse(AgentTaskRetryPolicy.canConfirmRetry(AgentTaskRetryEvidenceCode.NO_SIDE_EFFECT, detail))
        assertTrue(AgentNotCommittedReplayQualificationPolicy.assessRecovered(detail, null) { null } is AgentNotCommittedReplayQualificationAssessment.Ineligible)
    }
}
