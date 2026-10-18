package com.longdev.xiaoling.agent

import android.os.Bundle
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.storage.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** long: 三个独立进程只共享 UUID 测试数据库，保留成功工具证据，证明旧槽位不会再次执行。 */
class AgentChildLifecycleProcessInstrumentedTest {
    private fun fixture(): ChildLifecycleFixture {
        assertEquals("begonia", android.os.Build.DEVICE)
        val uuid = UUID.fromString(requireNotNull(InstrumentationRegistry.getArguments().getString("childLifecycleUuid")))
        return ChildLifecycleFixture(ApplicationProvider.getApplicationContext(), "stage289-child-process-$uuid")
    }
    private fun report(cleaned: Boolean = false) = InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply {
        putString("childLifecyclePid", Process.myPid().toString())
        if (cleaned) putString("childLifecycleCleaned", "true")
    })
    private suspend fun execute(f: ChildLifecycleFixture, id: String, interrupted: Boolean = false): AgentRunSummary {
        val run = f.repo.snapshot(id).run
        val checkpoint = f.repo.readOnlyChildCheckpoint(id)
        val ledger = object : AgentRunLedger by f.repo {
            override suspend fun createRun(conversationId: String, userMessageId: String, goal: String, retryOfRunId: String?, parentRunId: String?) =
                f.repo.claimReadOnlyChildRun(id)
        }
        val native = XiaoLingToolRegistry(clock = SystemAgentClock(), conversationStore = RoomAgentConversationStore(f.context, f.db),
            noteStore = RoomAgentNoteStore(f.context, f.db), memoryStore = RoomAgentMemoryStore(f.context, f.db),
            knowledgeStore = RoomKnowledgeDocumentStore(f.context, f.db))
        val llm = object : AgentLlm {
            override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>) = ToolCall(
                name = "app.current_time", arguments = emptyMap(), risk = ToolRisk.SAFE)
            override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult) =
                """{"style":"compact","tone":"neutral"}"""
        }
        val fault = object : AgentRuntimeFaultInjector {
            override fun afterToolVerificationPersisted(runId: String, call: ToolCall, result: ToolExecutionResult) {
                if (interrupted) throw AgentProcessTerminationSimulation()
            }
        }
        return MinimalAgentRuntime(ledger, ProfileScopedToolRegistry(native, checkpoint.profile.allowedToolNames), llm,
            options = checkpoint.options, faultInjector = fault).run(run.conversationId, run.userMessageId, run.goal,
            parentRunId = checkpoint.parentRunId, agentProfile = checkpoint.profile, memoryRecallEnabled = false)
    }
    @Test fun seedCompletedAndVerifiedInterruptedChildren() = runBlocking {
        fixture().use { f ->
            assertTrue(f.db.agentRunDao().getRecentRuns(10).isEmpty())
            val checkpoint = f.prepare(f.parent())
            assertEquals(AgentRunStatus.COMPLETED, execute(f, checkpoint.slots.first().runId).status)
            assertTrue(runCatching { execute(f, checkpoint.slots.last().runId, true) }.exceptionOrNull() is AgentProcessTerminationSimulation)
            assertEquals(AgentRunStatus.VERIFYING, f.repo.snapshot(checkpoint.slots.last().runId).run.status)
            assertEquals(ToolVerificationStatus.PASSED, f.repo.toolLedger(checkpoint.slots.last().runId).results.single().verificationStatus)
            val waitingParent = f.parent()
            f.repo.prepareReadOnlyChildRuns(waitingParent.id, "fixture-c", "fixture-m", f.specs.take(1), f.profile, f.childProfile)
            report()
        }
    }
    @Test fun restartClosesBatchWithoutRecoveringOrChangingChildToolLedger() = runBlocking {
        fixture().use { f ->
            val parents = f.db.agentRunDao().getRecentRuns(10).filter { it.parentRunId == null }
            assertEquals(2, parents.size)
            val ids = parents.flatMap { f.db.agentRunDao().getRunsByParentRunId(it.id) }.map { it.id }
            val queued = ids.single { f.repo.snapshot(it).run.status == AgentRunStatus.QUEUED }
            assertTrue(f.repo.snapshot(queued).events.none { it.type == AgentChildLifecyclePolicy.CLAIM_EVENT })
            assertEquals("child 批次所属进程已失效", runCatching { f.repo.claimReadOnlyChildRun(queued) }.exceptionOrNull()?.message)
            val before = ids.associateWith { f.repo.toolLedger(it) }
            val completed = ids.map { f.repo.snapshot(it) }.single { it.run.status == AgentRunStatus.COMPLETED }
            assertTrue(f.repo.recoverVerifiedToolRuns().isEmpty())
            assertTrue(f.repo.closeInterruptedRuns() >= 1)
            parents.forEach { assertEquals(AgentRunStatus.CANCELLED, f.repo.snapshot(it.id).run.status) }
            ids.forEach { id ->
                assertEquals(before[id], f.repo.toolLedger(id))
                assertTrue(f.repo.snapshot(id).run.status.isTerminal)
                assertTrue(runCatching { f.repo.claimReadOnlyChildRun(id) }.isFailure)
            }
            assertEquals(completed, f.repo.snapshot(completed.run.id))
            report()
        }
    }
    @Test fun freshParentGetsNewSlotsAndOldTerminalFactsRemainFrozen() = runBlocking {
        val f = fixture(); var completed = false
        try {
            val old = f.db.agentRunDao().getRecentRuns(10).map { f.repo.snapshot(it.id) }
            assertTrue(old.all { it.run.status.isTerminal })
            val ledgers = old.associate { it.run.id to f.repo.toolLedger(it.run.id) }
            val checkpoint = f.prepare(f.parent())
            checkpoint.slots.forEach { assertEquals(AgentRunStatus.COMPLETED, execute(f, it.runId).status) }
            f.repo.updateRunStatus(checkpoint.parentRunId, AgentRunStatus.COMPLETED, "新批次完成")
            old.forEach { snapshot ->
                f.repo.updateRunStatus(snapshot.run.id, AgentRunStatus.COMPLETED, "迟到回调")
                assertEquals(snapshot, f.repo.snapshot(snapshot.run.id))
                assertEquals(ledgers[snapshot.run.id], f.repo.toolLedger(snapshot.run.id))
                assertEquals(AgentTaskRetryEligibility.NotRetryable, AgentTaskRetryPolicy.evaluate(requireNotNull(f.repo.runDetail(snapshot.run.id))))
            }
            completed = true
        } finally { if (completed) f.delete() else f.close() }
        report(cleaned = true)
    }
}
