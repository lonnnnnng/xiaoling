package com.longdev.xiaoling.agent

import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentTaskRescheduleTest {
    private val now = Instant.parse("2026-09-08T01:00:00Z").toEpochMilli()
    private val arguments = mapOf(
        "name" to "喝水提醒",
        "expected_planned_at" to "2026-09-08T09:30:00.123+08:00",
        "planned_at" to "2026-09-08T10:00:00+08:00",
        "time_zone" to "Asia/Shanghai",
        "expected_schedule_token" to "one-time-v1-${"a".repeat(64)}",
    )
    private val request = AgentTaskRescheduleRequest.parse(arguments)!!
    private val context = AgentToolExecutionContext("conversation", "message", "run", "修改喝水提醒")
    private val clock = object : AgentClock {
        override fun nowMillis() = now
        override fun formattedNow() = "2026-09-08 09:00:00"
        override fun zoneId() = "Asia/Shanghai"
    }

    @Test fun requestRequiresExactParametersAndMatchingOffset() {
        assertEquals(123, (request.expectedPlannedAtMillis % 1000).toInt())
        assertTrue(request.isFutureTimeAllowed(now))
        assertFalse(request.isFutureTimeAllowed(request.plannedAtMillis - 59_999))
        assertTrue(request.isFutureTimeAllowed(request.plannedAtMillis - 60_000))
        assertFalse(request.isFutureTimeAllowed(request.plannedAtMillis - 604_800_001))
        listOf(
            arguments + ("extra" to "ignored"),
            arguments - "expected_schedule_token",
            arguments + ("name" to "喝水\n提醒"),
            arguments + ("planned_at" to "2026-09-08T10:00:00"),
            arguments + ("planned_at" to "2026-09-08T10:00:00+00:00"),
            arguments + ("planned_at" to arguments.getValue("expected_planned_at")),
            arguments + ("planned_at" to "2026-09-08T10:00:00.123456+08:00"),
            arguments + ("time_zone" to "+08:00"),
        ).forEach { assertNull(AgentTaskRescheduleRequest.parse(it)) }
    }

    @Test fun definitionKeepsNarrowApprovalAndRestartContract() {
        val definition = AgentTaskRescheduleTools(clock, Store()).definition
        assertEquals(ToolRisk.REQUIRES_APPROVAL, definition.risk)
        assertFalse(definition.permissionPolicy.supportsBackground)
        assertEquals(ToolReplaySafety.RESTART_REQUIRED, definition.replaySafety)
        assertEquals(ToolVerificationPolicy.EXECUTOR_VERIFIED, definition.verificationPolicy)
        assertEquals(arguments.keys, definition.inputSchema.map { it.name }.toSet())
    }

    @Test fun candidateRequiresUniqueListThenMatchingInspection() {
        val tools = AgentTaskRescheduleTools(clock, Store())
        val schedule = AgentTaskOneTimeScheduleRecord(request.expectedPlannedAtMillis, request.expectedScheduleToken)
        tools.inspected(request.name, schedule)
        assertFalse(tools.definition.validateArguments(arguments).isValid)
        val task = AgentTaskRecord(request.name, "喝水", true, 1, now, null, "ONE_TIME", schedule.plannedAt)
        tools.listed(listOf(task, task))
        tools.inspected(request.name, schedule)
        assertFalse(tools.definition.validateArguments(arguments).isValid)
        tools.listed(listOf(task))
        tools.inspected(request.name, schedule)
        assertTrue(tools.definition.validateArguments(arguments).isValid)
        assertFalse(tools.definition.validateArguments(arguments + ("expected_schedule_token" to "one-time-v1-${"b".repeat(64)}")).isValid)
        tools.clear()
        assertFalse(tools.definition.validateArguments(arguments).isValid)
    }

    @Test fun executionRequiresForegroundDirectAndAnUnconsumedApproval() = runBlocking {
        val store = Store()
        val tools = AgentTaskRescheduleTools(clock, store)
        val call = call()
        assertFalse(tools.execute(call, context).success)
        listOf(null, context.copy(executionOrigin = AgentExecutionOrigin.BACKGROUND), context.copy(invocationSource = AgentInvocationSource.WORKFLOW)).forEach {
            tools.approved(call)
            assertFalse(tools.execute(call, it).success)
        }
        assertEquals(0, store.calls)
        tools.approved(call)
        val result = tools.execute(call, context)
        assertTrue(result.success)
        assertEquals(true, result.verified)
        assertEquals(request.resultText(), result.content)
        assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
        assertNull(result.executionReceipt?.idempotencyKey)
        assertFalse(tools.execute(call, context).success)
        assertEquals(1, store.calls)
    }

    @Test fun approvalCannotBeReusedForChangedParameters() = runBlocking {
        val store = Store()
        val tools = AgentTaskRescheduleTools(clock, store)
        tools.approved(call())
        assertFalse(tools.execute(call().copy(arguments = arguments + ("name" to "别的提醒")), context).success)
        assertEquals(0, store.calls)
    }

    @Test fun expiredNewTimeDoesNotReachStore() = runBlocking {
        val store = Store()
        val tools = AgentTaskRescheduleTools(clock, store)
        val call = call().copy(arguments = arguments + ("planned_at" to "2026-09-08T09:00:30+08:00"))
        tools.approved(call)
        assertFalse(tools.execute(call, context).success)
        assertEquals(0, store.calls)
    }

    @Test fun unverifiedCommitKeepsReceiptButCannotReportSuccess() = runBlocking {
        val store = Store().apply { result = AgentTaskRescheduleResult.Committed("task-new", false, false) }
        val tools = AgentTaskRescheduleTools(clock, store)
        tools.approved(call())
        val result = tools.execute(call(), context)
        assertFalse(result.success)
        assertEquals(false, result.verified)
        assertEquals(ToolExecutionReceiptStatus.COMMITTED, result.executionReceipt?.status)
    }

    @Test fun storeRejectionDoesNotForgeCommit() = runBlocking {
        val store = Store().apply { result = AgentTaskRescheduleResult.Rejected("计划已变化") }
        val tools = AgentTaskRescheduleTools(clock, store)
        tools.approved(call())
        val result = tools.execute(call(), context)
        assertFalse(result.success)
        assertNull(result.executionReceipt)
    }

    private fun call() = ToolCall("call", TASK_RESCHEDULE_TOOL_NAME, arguments, ToolRisk.REQUIRES_APPROVAL)

    private class Store : AgentTaskStore {
        var calls = 0
        var result: AgentTaskRescheduleResult = AgentTaskRescheduleResult.Committed("task-new", true, false)
        override suspend fun list(limit: Int) = emptyList<AgentTaskRecord>()
        override suspend fun inspect(name: String) = AgentTaskInspectionResult.NotFound
        override suspend fun reschedule(request: AgentTaskRescheduleRequest, operationId: String): AgentTaskRescheduleResult {
            calls++
            return result
        }
    }
}
