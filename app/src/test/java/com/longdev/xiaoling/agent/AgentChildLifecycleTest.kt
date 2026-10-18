package com.longdev.xiaoling.agent

import com.longdev.xiaoling.model.ApiMode
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentChildLifecycleTest {
    private val profile = AgentProfileSnapshot("p", "只读", "读", "provider", "fixture", ApiMode.CHAT_COMPLETIONS,
        "只读取事实", AgentContextPolicy.CURRENT_CONVERSATION, listOf("app.current_time"), emptyList(), false)
    private fun detail(origin: AgentExecutionOrigin = AgentExecutionOrigin.FOREGROUND,
        source: AgentInvocationSource = AgentInvocationSource.DIRECT, external: Boolean = false): AgentRunDetailRecord {
        val run = AgentRunRecord("parent", "c", "m", "目标", AgentRunStatus.THINKING, null, null, 1, 1, null)
        val metadata = listOf(AgentChildLifecyclePolicy.INVOCATION_EVENT to RunEventMetadata.Reason(
            AgentChildLifecyclePolicy.invocation(origin, source, external)),
            AgentEventTypes.PROFILE_SELECTED to RunEventMetadata.AgentProfileSelection(profile),
            AgentEventTypes.EXECUTION_BUDGET_UPDATED to RunEventMetadata.ExecutionBudget(20_000, 0),
            AgentEventTypes.EXECUTION_BUDGET_UPDATED to RunEventMetadata.ExecutionBudget(20_000, 11_000))
        return AgentRunDetailRecord(AgentRunSnapshot(run, emptyList(), metadata.mapIndexed { i, (type, value) ->
            RunEventRecord("event-$i", run.id, type, "", i.toLong(), value)
        }), emptyList())
    }
    private fun checkpoint() = AgentChildCheckpoint("parent", profile, AgentRuntimeOptions(3, 9000, 9000, 9000),
        listOf(AgentChildSlot("child-1", "run-one"), AgentChildSlot("child-2", "run-two")))

    @Test fun checkpointRoundTripsAndBindsAllSlotsProfileAndBudget() {
        assertEquals(checkpoint(), AgentChildLifecyclePolicy.decode(AgentChildLifecyclePolicy.encode(checkpoint())))
    }
    @Test fun previousProcessOwnerCannotAuthorizeEvenUnclaimedSlot() {
        AgentChildLifecyclePolicy.requireCurrentOwner(checkpoint())
        val old = checkpoint().copy(ownerSessionId = java.util.UUID.randomUUID().toString())
        assertEquals(old, AgentChildLifecyclePolicy.decode(AgentChildLifecyclePolicy.encode(old)))
        assertTrue(runCatching { AgentChildLifecyclePolicy.requireCurrentOwner(old) }.isFailure)
        val parent = detail()
        val invocation = parent.snapshot.events.first()
        val raw = (invocation.metadata as RunEventMetadata.Reason).reason
        val oldInvocation = invocation.copy(metadata = RunEventMetadata.Reason(JSONObject(raw).put("ownerSessionId", old.ownerSessionId).toString()))
        assertTrue(runCatching { AgentChildLifecyclePolicy.validateParent(parent.copy(snapshot = parent.snapshot.copy(
            events = listOf(oldInvocation) + parent.snapshot.events.drop(1))), profile) }.isFailure)
    }
    @Test fun childBudgetCannotExceedLatestParentRemainingTime() {
        assertEquals(9000L, AgentChildLifecyclePolicy.options(detail()).runTimeoutMs)
        assertEquals(9000L, AgentChildLifecyclePolicy.options(detail()).modelStepTimeoutMs)
        assertEquals(3, AgentChildLifecyclePolicy.options(detail()).maxToolCalls)
    }
    @Test fun missingOrExhaustedParentBudgetIsRejected() {
        val d = detail()
        assertTrue(runCatching { AgentChildLifecyclePolicy.options(d.copy(snapshot = d.snapshot.copy(events = emptyList()))) }.isFailure)
        val spent = d.snapshot.events.dropLast(1) + d.snapshot.events.last().copy(metadata = RunEventMetadata.ExecutionBudget(20_000, 20_000))
        assertTrue(runCatching { AgentChildLifecyclePolicy.options(d.copy(snapshot = d.snapshot.copy(events = spent))) }.isFailure)
    }
    @Test fun parentSourceCannotBeHardcodedToForegroundDirect() {
        AgentChildLifecyclePolicy.validateParent(detail(), profile)
        listOf(detail(AgentExecutionOrigin.BACKGROUND), detail(source = AgentInvocationSource.WORKFLOW), detail(external = true))
            .forEach { assertTrue(runCatching { AgentChildLifecyclePolicy.validateParent(it, profile) }.isFailure) }
    }
    @Test fun cancelledRecursiveAndChangedProfileParentsAreRejected() {
        val d = detail()
        listOf(d.snapshot.run.copy(cancelRequestedAt = 2), d.snapshot.run.copy(status = AgentRunStatus.COMPLETED),
            d.snapshot.run.copy(parentRunId = "other")).forEach { run ->
            assertTrue(runCatching { AgentChildLifecyclePolicy.validateParent(d.copy(snapshot = d.snapshot.copy(run = run)), profile) }.isFailure)
        }
        assertTrue(runCatching { AgentChildLifecyclePolicy.validateParent(d, profile.copy(model = "changed")) }.isFailure)
    }
    @Test fun missingDuplicateAndMalformedInvocationCannotAuthorizeChildren() {
        val d = detail()
        val invocation = d.snapshot.events.first()
        listOf(d.snapshot.events.drop(1), d.snapshot.events + invocation,
            listOf(invocation.copy(metadata = RunEventMetadata.Reason("broken"))) + d.snapshot.events.drop(1)).forEach { events ->
            assertTrue(runCatching { AgentChildLifecyclePolicy.validateParent(d.copy(snapshot = d.snapshot.copy(events = events)), profile) }.isFailure)
        }
    }
    @Test fun checkpointRejectsUnknownVersionDuplicateSlotAndExpandedTools() {
        val raw = AgentChildLifecyclePolicy.encode(checkpoint())
        assertTrue(runCatching { AgentChildLifecyclePolicy.decode(JSONObject(raw).put("schemaVersion", 2).toString()) }.isFailure)
        assertTrue(runCatching { AgentChildLifecyclePolicy.encode(checkpoint().copy(slots = listOf(checkpoint().slots.first(), checkpoint().slots.first()))) }.isFailure)
        assertTrue(runCatching { AgentChildLifecyclePolicy.encode(checkpoint().copy(profile = profile.copy(allowedToolNames = listOf("notes.create")))) }.isFailure)
        assertTrue(runCatching { AgentChildLifecyclePolicy.encode(checkpoint().copy(options = AgentRuntimeOptions(4))) }.isFailure)
    }
    @Test fun corruptChildAndParentMarkersStillBlockEveryNativeRecoveryAndRetry() {
        val d = detail()
        listOf(AgentChildLifecyclePolicy.CHILD_EVENT, AgentChildLifecyclePolicy.BATCH_EVENT).forEach { type ->
            val marked = d.copy(snapshot = d.snapshot.copy(events = d.snapshot.events + RunEventRecord("bad", "parent", type, "", 5, null)))
            assertFalse(AgentRunResumePolicy.assess(marked).canResumeInPlace)
            assertEquals(AgentTaskRetryEligibility.NotRetryable, AgentTaskRetryPolicy.evaluate(marked))
            assertTrue(runCatching { AgentChildLifecyclePolicy.requireFreshInvocation(marked) }.isFailure)
            assertTrue(AgentNotCommittedReplayQualificationPolicy.assess(marked, profile) { null } is AgentNotCommittedReplayQualificationAssessment.Ineligible)
        }
    }
    @Test fun checkpointAndInvocationMetadataSurviveRoomCodec() {
        listOf(AgentChildLifecyclePolicy.INVOCATION_EVENT, AgentChildLifecyclePolicy.BATCH_EVENT,
            AgentChildLifecyclePolicy.CHILD_EVENT, AgentChildLifecyclePolicy.CLAIM_EVENT).forEach { type ->
            val reason = RunEventMetadata.Reason(AgentChildLifecyclePolicy.encode(checkpoint()))
            assertEquals(reason, RunEventMetadataCodec.decode(type, RunEventMetadataCodec.encode(reason)))
        }
    }
}
