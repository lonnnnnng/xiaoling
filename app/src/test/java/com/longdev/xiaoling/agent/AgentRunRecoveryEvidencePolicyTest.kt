package com.longdev.xiaoling.agent

import com.longdev.xiaoling.knowledge.KnowledgeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunRecoveryEvidencePolicyTest {
    @Test
    fun completeV20LedgerIsTheRecoveryEvidenceSource() {
        val call = ToolCall(
            id = "tool-call-ledger-recovery",
            name = "notes.create",
            arguments = mapOf("title" to "恢复", "content" to "只读验证"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = "note-ledger-recovery",
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val eventResult = RunEventMetadata.ToolResult(
            toolName = call.name,
            content = "已创建笔记",
            durationMs = 12L,
            success = true,
            verified = true,
            toolCallId = call.id,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = receipt,
        )
        val detail = detail(
            events = listOf(
                event(
                    id = "event-proposed",
                    type = "tool.call.proposed",
                    metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                    createdAt = 10L,
                ),
                event(
                    id = "event-validated",
                    type = "tool.call.validated",
                    metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                    createdAt = 11L,
                ),
                event("event-result", "tool.result", eventResult, 12L),
            ),
            ledger = AgentToolLedgerRecord(
                calls = listOf(
                    AgentToolCallRecord(
                        id = call.id,
                        runId = RUN_ID,
                        toolName = call.name,
                        risk = call.risk,
                        arguments = call.arguments,
                        proposedEventId = "event-proposed",
                        validatedEventId = "event-validated",
                        createdAt = 10L,
                        validatedAt = 11L,
                    ),
                ),
                results = listOf(
                    AgentToolResultRecord(
                        toolCallId = call.id,
                        runId = RUN_ID,
                        eventId = "event-result",
                        toolName = call.name,
                        content = eventResult.content,
                        success = true,
                        errorMessage = null,
                        durationMs = eventResult.durationMs,
                        executorVerified = true,
                        verificationStatus = null,
                        verifiedEventId = null,
                        memoryIdsUsed = emptyList(),
                        replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                        executionReceipt = receipt,
                        createdAt = 12L,
                        verifiedAt = null,
                    ),
                ),
            ),
        )

        val assessment = AgentRunRecoveryEvidencePolicy.read(detail)

        assertTrue(assessment is AgentRunRecoveryEvidenceAssessment.Available)
        assessment as AgentRunRecoveryEvidenceAssessment.Available
        assertEquals(AgentRunRecoveryEvidenceSource.LEDGER, assessment.source)
        assertEquals(call, assessment.executions.single().toolCall)
        assertEquals(eventResult, assessment.executions.single().result)
        assertEquals(null, assessment.executions.single().verificationStatus)
    }

    @Test
    fun legacyTypedEventsRemainTheFallbackWhenLedgerIsEmpty() {
        val call = ToolCall(
            id = "tool-call-event-fallback",
            name = "memory.remember",
            arguments = mapOf("note" to "用户喜欢紧凑界面"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val result = RunEventMetadata.ToolResult(
            toolName = call.name,
            content = "已保存长期记忆",
            durationMs = 9L,
            success = true,
            verified = true,
            toolCallId = call.id,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = ToolExecutionReceipt(
                toolCallId = call.id,
                operationId = "memory-event-fallback",
                idempotencyKey = call.id,
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
        )
        val detail = detail(
            events = listOf(
                event(
                    id = "legacy-validated",
                    type = "tool.call.validated",
                    metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                    createdAt = 10L,
                ),
                event("legacy-result", "tool.result", result, 11L),
            ),
            ledger = AgentToolLedgerRecord(),
        )

        val assessment = AgentRunRecoveryEvidencePolicy.read(detail)

        assertTrue(assessment is AgentRunRecoveryEvidenceAssessment.Available)
        assessment as AgentRunRecoveryEvidenceAssessment.Available
        assertEquals(AgentRunRecoveryEvidenceSource.EVENT_FALLBACK, assessment.source)
        assertEquals(call, assessment.executions.single().toolCall)
        assertEquals(result, assessment.executions.single().result)
        assertEquals(null, assessment.executions.single().verificationStatus)
    }

    @Test
    fun legacySameNameCallsWithoutVerificationIdsKeepSequentialFallback() {
        val firstCall = ToolCall(
            id = "legacy-same-name-first",
            name = "notes.create",
            arguments = mapOf("title" to "第一步"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val secondCall = ToolCall(
            id = "legacy-same-name-second",
            name = "notes.create",
            arguments = mapOf("title" to "第二步"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val firstResult = RunEventMetadata.ToolResult(
            toolName = firstCall.name,
            content = "第一步已完成",
            durationMs = 4L,
            success = true,
            verified = true,
            toolCallId = firstCall.id,
        )
        val secondResult = RunEventMetadata.ToolResult(
            toolName = secondCall.name,
            content = "第二步已提交",
            durationMs = 5L,
            success = true,
            verified = true,
            toolCallId = secondCall.id,
        )
        val assessment = AgentRunRecoveryEvidencePolicy.read(
            detail(
                events = listOf(
                    event(
                        "legacy-first-proposed",
                        "tool.call.proposed",
                        RunEventMetadata.ToolCall(firstCall.id, firstCall.name, firstCall.risk, firstCall.arguments),
                        1L,
                    ),
                    event(
                        "legacy-first-validated",
                        "tool.call.validated",
                        RunEventMetadata.ToolCall(firstCall.id, firstCall.name, firstCall.risk, firstCall.arguments),
                        2L,
                    ),
                    event("legacy-first-result", "tool.result", firstResult, 3L),
                    event(
                        "legacy-first-verify",
                        "tool.verify",
                        RunEventMetadata.ToolVerification(
                            toolName = firstCall.name,
                            status = ToolVerificationStatus.PASSED,
                        ),
                        4L,
                    ),
                    event(
                        "legacy-second-proposed",
                        "tool.call.proposed",
                        RunEventMetadata.ToolCall(secondCall.id, secondCall.name, secondCall.risk, secondCall.arguments),
                        5L,
                    ),
                    event(
                        "legacy-second-validated",
                        "tool.call.validated",
                        RunEventMetadata.ToolCall(secondCall.id, secondCall.name, secondCall.risk, secondCall.arguments),
                        6L,
                    ),
                    event("legacy-second-result", "tool.result", secondResult, 7L),
                ),
                ledger = AgentToolLedgerRecord(),
            ),
        )

        assertTrue(assessment is AgentRunRecoveryEvidenceAssessment.Available)
        assessment as AgentRunRecoveryEvidenceAssessment.Available
        assertEquals(AgentRunRecoveryEvidenceSource.EVENT_FALLBACK, assessment.source)
        assertEquals(listOf(firstCall.id, secondCall.id), assessment.executions.map { it.toolCall.id })
        assertEquals(
            listOf(ToolVerificationStatus.PASSED, null),
            assessment.executions.map { it.verificationStatus },
        )
    }

    @Test
    fun v20LedgerWithMismatchedVerificationTimeFailsClosed() {
        val call = ToolCall(
            id = "tool-call-verified-drift",
            name = "notes.create",
            arguments = mapOf("title" to "恢复", "content" to "验证漂移"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = "note-verified-drift",
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val result = RunEventMetadata.ToolResult(
            toolName = call.name,
            content = "已创建笔记",
            durationMs = 7L,
            success = true,
            verified = true,
            toolCallId = call.id,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = receipt,
        )
        val detail = detail(
            events = listOf(
                event(
                    "verified-drift-proposed",
                    "tool.call.proposed",
                    RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                    10L,
                ),
                event(
                    "verified-drift-validated",
                    "tool.call.validated",
                    RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                    11L,
                ),
                event("verified-drift-result", "tool.result", result, 12L),
                event(
                    "verified-drift-verify",
                    "tool.verify",
                    RunEventMetadata.ToolVerification(
                        toolName = call.name,
                        status = ToolVerificationStatus.PASSED,
                        toolCallId = call.id,
                    ),
                    13L,
                ),
            ),
            ledger = AgentToolLedgerRecord(
                calls = listOf(
                    AgentToolCallRecord(
                        id = call.id,
                        runId = RUN_ID,
                        toolName = call.name,
                        risk = call.risk,
                        arguments = call.arguments,
                        proposedEventId = "verified-drift-proposed",
                        validatedEventId = "verified-drift-validated",
                        createdAt = 10L,
                        validatedAt = 11L,
                    ),
                ),
                results = listOf(
                    AgentToolResultRecord(
                        toolCallId = call.id,
                        runId = RUN_ID,
                        eventId = "verified-drift-result",
                        toolName = call.name,
                        content = result.content,
                        success = true,
                        errorMessage = null,
                        durationMs = result.durationMs,
                        executorVerified = true,
                        verificationStatus = ToolVerificationStatus.PASSED,
                        verifiedEventId = "verified-drift-verify",
                        memoryIdsUsed = emptyList(),
                        replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                        executionReceipt = receipt,
                        createdAt = 12L,
                        verifiedAt = 14L,
                    ),
                ),
            ),
        )

        val assessment = AgentRunRecoveryEvidencePolicy.read(detail)

        assertTrue(assessment is AgentRunRecoveryEvidenceAssessment.Invalid)
        assertTrue((assessment as AgentRunRecoveryEvidenceAssessment.Invalid).reason.contains("验证"))
    }

    @Test
    fun v20LedgerWithCallResultTimeOrErrorDriftFailsClosed() {
        val complete = completeLedgerDetail()
        val variants = listOf(
            complete.copy(
                toolLedger = complete.toolLedger.copy(
                    calls = listOf(complete.toolLedger.calls.single().copy(createdAt = 9L)),
                ),
            ),
            complete.copy(
                toolLedger = complete.toolLedger.copy(
                    results = listOf(complete.toolLedger.results.single().copy(createdAt = 13L)),
                ),
            ),
            complete.copy(
                toolLedger = complete.toolLedger.copy(
                    results = listOf(complete.toolLedger.results.single().copy(errorMessage = "错误字段漂移")),
                ),
            ),
            complete.copy(
                toolLedger = complete.toolLedger.copy(
                    results = listOf(
                        complete.toolLedger.results.single().copy(
                            knowledgeReferences = listOf(
                                KnowledgeReference(
                                    retrievalId = "knowledge-retrieval-drift",
                                    documentId = "document-drift",
                                    documentName = "漂移证据.md",
                                    documentRevision = 2,
                                    chunkId = "chunk-drift-r2-0",
                                    chunkSequence = 0,
                                    startOffset = 0,
                                    endOffset = 20,
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )

        variants.forEach { detail ->
            val assessment = AgentRunRecoveryEvidencePolicy.read(detail)
            assertTrue(assessment is AgentRunRecoveryEvidenceAssessment.Invalid)
            assertTrue((assessment as AgentRunRecoveryEvidenceAssessment.Invalid).reason.contains("不一致"))
        }
    }

    @Test
    fun v20LedgerDoesNotIgnoreExtraToolResultEvent() {
        val call = ToolCall(
            id = "tool-call-complete-ledger",
            name = "notes.create",
            arguments = mapOf("title" to "账本", "content" to "完整记录"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = "note-complete-ledger",
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val result = RunEventMetadata.ToolResult(
            toolName = call.name,
            content = "已创建笔记",
            durationMs = 5L,
            success = true,
            verified = true,
            toolCallId = call.id,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = receipt,
        )
        val extraResult = RunEventMetadata.ToolResult(
            toolName = "memory.remember",
            content = "事件侧额外结果",
            durationMs = 6L,
            success = true,
            verified = true,
            toolCallId = "tool-call-extra-event",
        )
        val detail = detail(
            events = listOf(
                event(
                    "complete-proposed",
                    "tool.call.proposed",
                    RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                    10L,
                ),
                event(
                    "complete-validated",
                    "tool.call.validated",
                    RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                    11L,
                ),
                event("complete-result", "tool.result", result, 12L),
                event("extra-result", "tool.result", extraResult, 13L),
            ),
            ledger = AgentToolLedgerRecord(
                calls = listOf(
                    AgentToolCallRecord(
                        id = call.id,
                        runId = RUN_ID,
                        toolName = call.name,
                        risk = call.risk,
                        arguments = call.arguments,
                        proposedEventId = "complete-proposed",
                        validatedEventId = "complete-validated",
                        createdAt = 10L,
                        validatedAt = 11L,
                    ),
                ),
                results = listOf(
                    AgentToolResultRecord(
                        toolCallId = call.id,
                        runId = RUN_ID,
                        eventId = "complete-result",
                        toolName = call.name,
                        content = result.content,
                        success = true,
                        errorMessage = null,
                        durationMs = result.durationMs,
                        executorVerified = true,
                        verificationStatus = null,
                        verifiedEventId = null,
                        memoryIdsUsed = emptyList(),
                        replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                        executionReceipt = receipt,
                        createdAt = 12L,
                        verifiedAt = null,
                    ),
                ),
            ),
        )

        val assessment = AgentRunRecoveryEvidencePolicy.read(detail)

        assertTrue(assessment is AgentRunRecoveryEvidenceAssessment.Invalid)
        assertTrue((assessment as AgentRunRecoveryEvidenceAssessment.Invalid).reason.contains("事件"))
    }

    @Test
    fun v20LedgerWithAValidatedCallButNoResultFailsClosed() {
        val detail = completeLedgerDetail()
        val extraCall = ToolCall(
            id = "tool-call-without-result",
            name = "memory.remember",
            arguments = mapOf("note" to "尚未执行"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val extraProposed = event(
            "extra-call-proposed",
            "tool.call.proposed",
            RunEventMetadata.ToolCall(extraCall.id, extraCall.name, extraCall.risk, extraCall.arguments),
            20L,
        )
        val extraValidated = event(
            "extra-call-validated",
            "tool.call.validated",
            RunEventMetadata.ToolCall(extraCall.id, extraCall.name, extraCall.risk, extraCall.arguments),
            21L,
        )
        val incomplete = detail.copy(
            snapshot = detail.snapshot.copy(
                events = detail.snapshot.events + extraProposed + extraValidated,
            ),
            toolLedger = detail.toolLedger.copy(
                calls = detail.toolLedger.calls + AgentToolCallRecord(
                    id = extraCall.id,
                    runId = RUN_ID,
                    toolName = extraCall.name,
                    risk = extraCall.risk,
                    arguments = extraCall.arguments,
                    proposedEventId = extraProposed.id,
                    validatedEventId = extraValidated.id,
                    createdAt = 20L,
                    validatedAt = 21L,
                ),
            ),
        )

        val assessment = AgentRunRecoveryEvidencePolicy.read(incomplete)

        assertTrue(assessment is AgentRunRecoveryEvidenceAssessment.Invalid)
        assertTrue((assessment as AgentRunRecoveryEvidenceAssessment.Invalid).reason.contains("结果"))
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
                goal = "恢复已提交笔记",
                status = AgentRunStatus.EXECUTING,
                result = null,
                errorMessage = null,
                createdAt = 1L,
                updatedAt = 12L,
                completedAt = null,
            ),
            steps = emptyList(),
            events = events,
        ),
        approvals = emptyList(),
        toolLedger = ledger,
    )

    private fun completeLedgerDetail(): AgentRunDetailRecord {
        val call = ToolCall(
            id = "tool-call-complete-fixture",
            name = "notes.create",
            arguments = mapOf("title" to "完整", "content" to "恢复证据"),
            risk = ToolRisk.REQUIRES_APPROVAL,
        )
        val receipt = ToolExecutionReceipt(
            toolCallId = call.id,
            operationId = "note-complete-fixture",
            idempotencyKey = call.id,
            status = ToolExecutionReceiptStatus.COMMITTED,
        )
        val result = RunEventMetadata.ToolResult(
            toolName = call.name,
            content = "已创建笔记",
            durationMs = 5L,
            success = true,
            verified = true,
            toolCallId = call.id,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = receipt,
        )
        return detail(
            events = listOf(
                event(
                    "fixture-proposed",
                    "tool.call.proposed",
                    RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                    10L,
                ),
                event(
                    "fixture-validated",
                    "tool.call.validated",
                    RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments),
                    11L,
                ),
                event("fixture-result", "tool.result", result, 12L),
            ),
            ledger = AgentToolLedgerRecord(
                calls = listOf(
                    AgentToolCallRecord(
                        id = call.id,
                        runId = RUN_ID,
                        toolName = call.name,
                        risk = call.risk,
                        arguments = call.arguments,
                        proposedEventId = "fixture-proposed",
                        validatedEventId = "fixture-validated",
                        createdAt = 10L,
                        validatedAt = 11L,
                    ),
                ),
                results = listOf(
                    AgentToolResultRecord(
                        toolCallId = call.id,
                        runId = RUN_ID,
                        eventId = "fixture-result",
                        toolName = call.name,
                        content = result.content,
                        success = true,
                        errorMessage = null,
                        durationMs = result.durationMs,
                        executorVerified = true,
                        verificationStatus = null,
                        verifiedEventId = null,
                        memoryIdsUsed = emptyList(),
                        replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
                        executionReceipt = receipt,
                        createdAt = 12L,
                        verifiedAt = null,
                    ),
                ),
            ),
        )
    }

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
        const val RUN_ID = "run-ledger-recovery"
    }
}
