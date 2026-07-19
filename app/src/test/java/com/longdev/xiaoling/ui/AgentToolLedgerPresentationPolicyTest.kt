package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentRunDetailRecord
import com.longdev.xiaoling.agent.AgentRunRecord
import com.longdev.xiaoling.agent.AgentRunSnapshot
import com.longdev.xiaoling.agent.AgentRunStatus
import com.longdev.xiaoling.agent.AgentToolCallRecord
import com.longdev.xiaoling.agent.AgentToolLedgerRecord
import com.longdev.xiaoling.agent.AgentToolResultRecord
import com.longdev.xiaoling.agent.RunEventMetadata
import com.longdev.xiaoling.agent.RunEventRecord
import com.longdev.xiaoling.agent.ToolReplaySafety
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.agent.ToolVerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolLedgerPresentationPolicyTest {
    @Test
    fun ledgerIsPreferredAndShowsEveryToolCallStage() {
        val callMetadata = RunEventMetadata.ToolCall(
            id = "tool-call-1",
            toolName = "app.current_time",
            risk = ToolRisk.SAFE,
            arguments = emptyMap(),
        )
        val resultMetadata = RunEventMetadata.ToolResult(
            toolName = callMetadata.toolName,
            content = "当前时间：2026-07-19 10:00:00",
            durationMs = 12L,
            success = true,
            verified = true,
            toolCallId = callMetadata.id,
        )
        val events = listOf(
            event("event-proposed", "tool.call.proposed", callMetadata, 10L),
            event("event-validated", "tool.call.validated", callMetadata, 11L),
            event("event-result", "tool.result", resultMetadata, 12L),
            event(
                "event-verified",
                "tool.verify",
                RunEventMetadata.ToolVerification(
                    toolName = callMetadata.toolName,
                    status = ToolVerificationStatus.PASSED,
                    toolCallId = callMetadata.id,
                ),
                13L,
            ),
        )
        val ledger = AgentToolLedgerRecord(
            calls = listOf(
                AgentToolCallRecord(
                    id = callMetadata.id,
                    runId = RUN_ID,
                    toolName = callMetadata.toolName,
                    risk = callMetadata.risk,
                    arguments = callMetadata.arguments,
                    proposedEventId = "event-proposed",
                    validatedEventId = "event-validated",
                    createdAt = 10L,
                    validatedAt = 11L,
                ),
            ),
            results = listOf(
                AgentToolResultRecord(
                    toolCallId = callMetadata.id,
                    runId = RUN_ID,
                    eventId = "event-result",
                    toolName = callMetadata.toolName,
                    content = resultMetadata.content,
                    success = true,
                    errorMessage = null,
                    durationMs = 12L,
                    executorVerified = true,
                    verificationStatus = ToolVerificationStatus.PASSED,
                    verifiedEventId = "event-verified",
                    memoryIdsUsed = emptyList(),
                    replaySafety = ToolReplaySafety.RESTART_REQUIRED,
                    executionReceipt = null,
                    createdAt = 12L,
                    verifiedAt = 13L,
                ),
            ),
        )

        val presentation = presentAgentToolLedger(detail(events, ledger))

        assertEquals(AgentToolDetailSource.LEDGER, presentation.source)
        assertEquals(1, presentation.calls.size)
        assertEquals(
            listOf(
                AgentToolStageState.COMPLETE,
                AgentToolStageState.COMPLETE,
                AgentToolStageState.COMPLETE,
                AgentToolStageState.COMPLETE,
            ),
            presentation.calls.single().let { call ->
                listOf(call.proposed, call.validated, call.result, call.verified)
            },
        )
        assertTrue(presentation.issues.isEmpty())
    }

    @Test
    fun typedEventsAreUsedWhenLegacyRunHasNoLedger() {
        val call = RunEventMetadata.ToolCall(
            id = "legacy-tool-call-1",
            toolName = "memory.search",
            risk = ToolRisk.SAFE,
            arguments = mapOf("query" to "项目偏好"),
        )
        val events = listOf(
            event("legacy-proposed", "tool.call.proposed", call, 10L),
            event("legacy-validated", "tool.call.validated", call, 11L),
            event(
                "legacy-result",
                "tool.result",
                RunEventMetadata.ToolResult(
                    toolName = call.toolName,
                    content = "找到 1 条记忆",
                    durationMs = 8L,
                    success = true,
                    verified = true,
                    toolCallId = call.id,
                ),
                12L,
            ),
            event(
                "legacy-verified",
                "tool.verify",
                RunEventMetadata.ToolVerification(
                    toolName = call.toolName,
                    status = ToolVerificationStatus.PASSED,
                    toolCallId = call.id,
                ),
                13L,
            ),
        )

        val presentation = presentAgentToolLedger(detail(events, AgentToolLedgerRecord()))

        assertEquals(AgentToolDetailSource.EVENT_FALLBACK, presentation.source)
        assertEquals(call.id, presentation.calls.single().id)
        assertEquals("找到 1 条记忆", presentation.calls.single().resultContent)
        assertEquals(
            listOf(
                AgentToolStageState.COMPLETE,
                AgentToolStageState.COMPLETE,
                AgentToolStageState.COMPLETE,
                AgentToolStageState.COMPLETE,
            ),
            presentation.calls.single().let { item ->
                listOf(item.proposed, item.validated, item.result, item.verified)
            },
        )
        assertTrue(presentation.issues.isEmpty())
    }

    @Test
    fun legacyEventsWithoutCallIdentityStayUnassociated() {
        val call = RunEventMetadata.ToolCall(
            id = "legacy-known-call",
            toolName = "memory.search",
            risk = ToolRisk.SAFE,
            arguments = mapOf("query" to "重复调用"),
        )
        val presentation = presentAgentToolLedger(
            detail(
                events = listOf(
                    event("legacy-known-proposed", "tool.call.proposed", call, 10L),
                    event(
                        "legacy-unknown-result",
                        "tool.result",
                        RunEventMetadata.ToolResult(
                            toolName = call.toolName,
                            content = "无法确定属于哪次调用",
                            durationMs = 6L,
                            success = true,
                            verified = true,
                        ),
                        11L,
                    ),
                    event(
                        "legacy-unknown-verify",
                        "tool.verify",
                        RunEventMetadata.ToolVerification(
                            toolName = call.toolName,
                            status = ToolVerificationStatus.PASSED,
                        ),
                        12L,
                    ),
                ),
                ledger = AgentToolLedgerRecord(),
            ),
        )

        assertEquals(AgentToolDetailSource.EVENT_FALLBACK, presentation.source)
        assertEquals(3, presentation.calls.size)
        assertEquals(listOf(call.id), presentation.calls.mapNotNull { it.id })
        assertEquals(2, presentation.calls.count { it.id == null })
        assertEquals(AgentToolStageState.PENDING, presentation.calls.first().result)
        assertEquals(AgentToolStageState.PENDING, presentation.calls.first().verified)
    }

    @Test
    fun ledgerRemainsPrimaryWhenDualSourcesDriftAndReportsStableIssues() {
        val storedCall = AgentToolCallRecord(
            id = "tool-call-drift",
            runId = RUN_ID,
            toolName = "notes.create",
            risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf("title" to "原始标题"),
            proposedEventId = "drift-proposed",
            validatedEventId = "drift-validated",
            createdAt = 10L,
            validatedAt = 11L,
        )
        val events = listOf(
            event(
                "drift-proposed",
                "tool.call.proposed",
                RunEventMetadata.ToolCall(
                    id = storedCall.id,
                    toolName = storedCall.toolName,
                    risk = storedCall.risk,
                    arguments = storedCall.arguments,
                ),
                10L,
            ),
            event(
                "drift-validated",
                "tool.call.validated",
                RunEventMetadata.ToolCall(
                    id = storedCall.id,
                    toolName = storedCall.toolName,
                    risk = storedCall.risk,
                    arguments = mapOf("title" to "漂移标题"),
                ),
                11L,
            ),
            event(
                "drift-result",
                "tool.result",
                RunEventMetadata.ToolResult(
                    toolName = storedCall.toolName,
                    content = "已创建笔记",
                    durationMs = 5L,
                    success = true,
                    verified = true,
                    toolCallId = storedCall.id,
                ),
                12L,
            ),
        )
        val presentation = presentAgentToolLedger(
            detail(
                events = events,
                ledger = AgentToolLedgerRecord(calls = listOf(storedCall)),
            ),
        )

        assertEquals(AgentToolDetailSource.LEDGER, presentation.source)
        assertEquals(
            setOf("TOOL_CALL_MISMATCH", "EVENT_RESULT_MISSING_IN_LEDGER"),
            presentation.issues.map { it.code }.toSet(),
        )
    }

    @Test
    fun orphanLedgerResultIsVisibleAndReported() {
        val orphanResult = AgentToolResultRecord(
            toolCallId = "orphan-call",
            runId = RUN_ID,
            eventId = "orphan-result",
            toolName = "memory.search",
            content = "孤立结果",
            success = true,
            errorMessage = null,
            durationMs = 4L,
            executorVerified = true,
            verificationStatus = null,
            verifiedEventId = null,
            memoryIdsUsed = emptyList(),
            replaySafety = ToolReplaySafety.RESTART_REQUIRED,
            executionReceipt = null,
            createdAt = 12L,
            verifiedAt = null,
        )
        val presentation = presentAgentToolLedger(
            detail(
                events = listOf(
                    event(
                        "orphan-result",
                        "tool.result",
                        RunEventMetadata.ToolResult(
                            toolName = orphanResult.toolName,
                            content = orphanResult.content,
                            durationMs = orphanResult.durationMs,
                            success = true,
                            verified = true,
                            toolCallId = orphanResult.toolCallId,
                        ),
                        12L,
                    ),
                ),
                ledger = AgentToolLedgerRecord(results = listOf(orphanResult)),
            ),
        )

        assertEquals(AgentToolDetailSource.LEDGER, presentation.source)
        assertEquals("orphan-call", presentation.calls.single().id)
        assertEquals("ORPHAN_LEDGER_RESULT", presentation.issues.single().code)
    }

    @Test
    fun runWithoutToolEvidenceDoesNotInventFallbackDetails() {
        val presentation = presentAgentToolLedger(detail(emptyList(), AgentToolLedgerRecord()))

        assertEquals(AgentToolDetailSource.NONE, presentation.source)
        assertTrue(presentation.calls.isEmpty())
        assertTrue(presentation.issues.isEmpty())
    }

    private fun detail(
        events: List<RunEventRecord>,
        ledger: AgentToolLedgerRecord,
    ) = AgentRunDetailRecord(
        snapshot = AgentRunSnapshot(
            run = AgentRunRecord(
                id = RUN_ID,
                conversationId = "conversation-1",
                userMessageId = "message-1",
                goal = "读取当前时间",
                status = AgentRunStatus.COMPLETED,
                result = "完成",
                errorMessage = null,
                createdAt = 1L,
                updatedAt = 20L,
                completedAt = 20L,
            ),
            steps = emptyList(),
            events = events,
        ),
        approvals = emptyList(),
        toolLedger = ledger,
    )

    private fun event(
        id: String,
        type: String,
        metadata: RunEventMetadata,
        createdAt: Long,
    ) = RunEventRecord(
        id = id,
        runId = RUN_ID,
        type = type,
        message = type,
        createdAt = createdAt,
        metadata = metadata,
    )

    private companion object {
        const val RUN_ID = "run-1"
    }
}
