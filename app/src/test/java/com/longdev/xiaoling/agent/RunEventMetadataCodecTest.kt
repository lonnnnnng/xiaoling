package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class RunEventMetadataCodecTest {
    @Test
    fun recoverySummaryReasonRoundTrips() {
        val metadata = RunEventMetadata.Reason("验证阶段恢复不恢复旧模型协程")

        val restored = RunEventMetadataCodec.decode(
            AgentEventTypes.RECOVERY_SUMMARY,
            RunEventMetadataCodec.encode(metadata),
        )

        assertEquals(metadata, restored)
    }

    @Test
    fun llmRequestTelemetryRoundTripsWithoutInventingMissingUsage() {
        val metadata = RunEventMetadata.LlmRequest(
            phase = AgentLlmPhase.PLAN,
            model = "gpt-test",
            latencyMs = 1_250L,
            firstByteLatencyMs = 320L,
            promptBytes = 4_096,
            inputTokens = 120L,
            outputTokens = 30L,
            totalTokens = 150L,
        )

        val decoded = RunEventMetadataCodec.decode(
            type = "llm.request.completed",
            raw = RunEventMetadataCodec.encode(metadata),
        )

        assertEquals(metadata, decoded)
    }

    @Test
    fun toolResultMemoryIdsRoundTrip() {
        val metadata = RunEventMetadata.ToolResult(
            toolName = "memory.search",
            content = "长期记忆：...",
            durationMs = 12,
            success = true,
            verified = null,
            memoryIdsUsed = listOf("memory-2", "memory-1"),
        )

        assertEquals(
            metadata,
            RunEventMetadataCodec.decode(
                type = "tool.result",
                raw = RunEventMetadataCodec.encode(metadata),
            ),
        )
    }

    @Test
    fun toolExecutionReceiptRoundTripsWithToolCallIdentity() {
        val metadata = RunEventMetadata.ToolResult(
            toolName = "notes.create",
            content = "已创建笔记",
            durationMs = 12,
            success = true,
            verified = true,
            toolCallId = "tool-call-1",
            replaySafety = ToolReplaySafety.IDEMPOTENT_BY_KEY,
            executionReceipt = ToolExecutionReceipt(
                toolCallId = "tool-call-1",
                operationId = "note-1",
                idempotencyKey = "run-1:step-1",
                status = ToolExecutionReceiptStatus.COMMITTED,
            ),
        )

        assertEquals(
            metadata,
            RunEventMetadataCodec.decode(
                type = "tool.result",
                raw = RunEventMetadataCodec.encode(metadata),
            ),
        )
    }

    @Test
    fun legacyToolResultWithoutMemoryIdsRemainsReadable() {
        val metadata = RunEventMetadataCodec.decode(
            type = "tool.result",
            raw = """{"toolName":"memory.search","content":"旧结果","durationMs":3,"success":true,"verified":null}""",
        ) as RunEventMetadata.ToolResult

        assertEquals(emptyList<String>(), metadata.memoryIdsUsed)
        assertEquals(null, metadata.toolCallId)
        assertEquals(null, metadata.executionReceipt)
        assertEquals(ToolReplaySafety.RESTART_REQUIRED, metadata.replaySafety)
    }

    @Test
    fun legacyStringifiedMemoryIdsRemainReadable() {
        val metadata = RunEventMetadataCodec.decode(
            type = "tool.result",
            raw = """{"toolName":"memory.search","content":"旧结果","durationMs":3,"success":true,"verified":true,"memoryIdsUsed":"[\"memory-1\",\"memory-2\"]"}""",
        ) as RunEventMetadata.ToolResult

        assertEquals(listOf("memory-1", "memory-2"), metadata.memoryIdsUsed)
    }

    @Test
    fun skillSelectionReasonRemainsReadableAfterRoomRoundTrip() {
        val metadata = RunEventMetadata.Reason("daily-review@2")

        assertEquals(
            metadata,
            RunEventMetadataCodec.decode(
                type = "skill.selected",
                raw = RunEventMetadataCodec.encode(metadata),
            ),
        )
    }
}
