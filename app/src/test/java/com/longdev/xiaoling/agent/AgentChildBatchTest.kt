package com.longdev.xiaoling.agent

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentChildBatchTest {
    private fun outcome(second: AgentRunStatus = AgentRunStatus.COMPLETED) = AgentChildBatchOutcome("parent", listOf(
        AgentChildConsumedResult("child-1", "run-one", "时间一", AgentRunStatus.COMPLETED, 10, "已验证时间", null, 1),
        AgentChildConsumedResult("child-2", "run-two", "时间二", second, 11, null, "超时", 0)))

    @Test fun outcomeRoundTripsAndPartialFailureCannotClaimParentComplete() {
        listOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.CANCELLED, AgentRunStatus.BUDGET_EXHAUSTED).forEach { status ->
            val value = outcome(status)
            assertEquals(value, AgentChildBatchPolicy.decode(AgentChildBatchPolicy.encode(value)))
            assertEquals(if (status == AgentRunStatus.COMPLETED) AgentRunStatus.COMPLETED else AgentRunStatus.FAILED, value.status)
            assertTrue(value.summary().contains("已验证时间"))
            assertTrue(value.summary().contains(status.name))
        }
    }
    @Test fun forgedAggregateActiveChildAndDuplicateIdentityAreRejected() {
        val raw = JSONObject(AgentChildBatchPolicy.encode(outcome(AgentRunStatus.FAILED))).put("status", "COMPLETED").toString()
        assertTrue(runCatching { AgentChildBatchPolicy.decode(raw) }.isFailure)
        assertTrue(runCatching { AgentChildBatchPolicy.encode(outcome(AgentRunStatus.THINKING)) }.isFailure)
        val one = outcome().children.first()
        assertTrue(runCatching { AgentChildBatchPolicy.encode(outcome().copy(children = listOf(one, one.copy(slotId = "child-2")))) }.isFailure)
        assertTrue(runCatching { AgentChildBatchPolicy.decode(" ".repeat(AgentChildBatchPolicy.MAX_OUTCOME_CHARS + 1)) }.isFailure)
    }
    @Test fun goalsAreBoundedAndOnlyExplicitNonemptySlotsAreAccepted() {
        assertEquals(listOf("时间"), AgentChildBatchPolicy.goals(listOf(" 时间 ")))
        listOf(emptyList(), listOf(""), listOf("a", " "), listOf("a", "b", "c"), listOf("a".repeat(2001))).forEach {
            assertTrue(runCatching { AgentChildBatchPolicy.goals(it) }.isFailure)
        }
    }
    @Test fun selectionIsSingleUseAndBoundToActualForegroundState() {
        val state = AgentPluginForegroundState(); val owner = Any()
        assertTrue(runCatching { AgentChildBatchSelection.capture(state) }.isFailure)
        state.resumed(owner)
        val selection = AgentChildBatchSelection.capture(state)
        assertTrue(runCatching { selection.claim(AgentPluginForegroundState().apply { resumed(Any()) }) }.isFailure)
        selection.claim(state)
        assertTrue(runCatching { selection.claim(state) }.isFailure)
        val old = AgentChildBatchSelection.capture(state)
        state.paused(owner); state.resumed(owner)
        assertTrue(runCatching { old.claim(state) }.isFailure)
    }
    @Test fun pauseAndResumeDuringModelWaitCancelsOldSelection() = runTest {
        val state = AgentPluginForegroundState(); val owner = Any(); state.resumed(owner)
        val selection = AgentChildBatchSelection.capture(state); selection.claim(state)
        val entered = CompletableDeferred<Unit>()
        val result = async { runCatching { selection.guard { entered.complete(Unit); awaitCancellation() } } }
        entered.await(); state.paused(owner); state.resumed(owner)
        assertTrue(result.await().exceptionOrNull() is CancellationException)
    }
}
