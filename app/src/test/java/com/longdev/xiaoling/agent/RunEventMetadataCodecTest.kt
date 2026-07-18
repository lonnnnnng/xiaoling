package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Test

class RunEventMetadataCodecTest {
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
    fun legacyToolResultWithoutMemoryIdsRemainsReadable() {
        val metadata = RunEventMetadataCodec.decode(
            type = "tool.result",
            raw = """{"toolName":"memory.search","content":"旧结果","durationMs":3,"success":true,"verified":null}""",
        ) as RunEventMetadata.ToolResult

        assertEquals(emptyList<String>(), metadata.memoryIdsUsed)
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
