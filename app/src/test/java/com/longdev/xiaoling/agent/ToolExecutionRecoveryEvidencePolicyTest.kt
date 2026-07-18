package com.longdev.xiaoling.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class ToolExecutionRecoveryEvidencePolicyTest {
    @Test
    fun committedReceiptForIdempotentToolCanReuseCommittedEffect() {
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        )
        val result = RunEventMetadata.ToolResult(
            toolName = definition.name,
            content = "已创建笔记",
            durationMs = 12,
            success = true,
            verified = true,
            toolCallId = "tool-call-1",
            executionReceipt = ToolExecutionReceipt(
                toolCallId = "tool-call-1",
                operationId = "note-1",
                idempotencyKey = "run-1:step-1",
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
        )

        val assessment = ToolExecutionRecoveryEvidencePolicy.assess(definition, result)

        assertTrue(assessment.canReuseCommittedEffect)
    }

    @Test
    fun receiptFromAnotherToolCallCannotBeReused() {
        val definition = ToolDefinition(
            name = "notes.create",
            description = "创建笔记",
            risk = ToolRisk.REQUIRES_APPROVAL,
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
        )
        val result = RunEventMetadata.ToolResult(
            toolName = definition.name,
            content = "已创建笔记",
            durationMs = 12,
            success = true,
            verified = true,
            toolCallId = "tool-call-current",
            executionReceipt = ToolExecutionReceipt(
                toolCallId = "tool-call-other",
                operationId = "note-1",
                idempotencyKey = "run-1:step-1",
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
        )

        val assessment = ToolExecutionRecoveryEvidencePolicy.assess(definition, result)

        assertTrue(!assessment.canReuseCommittedEffect)
    }
}
