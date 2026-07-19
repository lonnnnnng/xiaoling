package com.longdev.xiaoling.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkDebugLogSanitizerTest {
    @Test
    fun requestImageDataUrlIsRedactedWithoutHidingTextPrompt() {
        val sanitized = NetworkDebugLogSanitizer.sanitize(
            """{"input":[{"role":"user","content":[{"type":"input_text","text":"描述图片"},{"type":"input_image","image_url":"data:image/png;base64,PRIVATE_IMAGE_BYTES"}]}]}""",
        )

        assertTrue(sanitized.contains("描述图片"))
        assertFalse(sanitized.contains("PRIVATE_IMAGE_BYTES"))
        assertTrue(sanitized.contains("data:image/png;base64,***REDACTED***"))
    }

    @Test
    fun responsesPayloadKeepsSummaryButRedactsNestedRawReasoningText() {
        val sanitized = NetworkDebugLogSanitizer.sanitize(
            """{"output":[{"type":"reasoning","summary":[{"type":"summary_text","text":"可展示摘要"}],"content":[{"type":"reasoning_text","text":"原始思维链"}]}]}""",
        )

        assertTrue(sanitized.contains("可展示摘要"))
        assertFalse(sanitized.contains("原始思维链"))
        assertTrue(sanitized.contains("***REDACTED***"))
    }

    @Test
    fun reasoningTextStreamEventRedactsDelta() {
        val sanitized = NetworkDebugLogSanitizer.sanitize(
            """{"type":"response.reasoning_text.delta","delta":"流式原始思维链","item_id":"rs_1"}""",
        )

        assertFalse(sanitized.contains("流式原始思维链"))
        assertTrue(sanitized.contains("***REDACTED***"))
    }

    @Test
    fun chatCompatibilityReasoningContentIsRedacted() {
        val sanitized = NetworkDebugLogSanitizer.sanitize(
            """{"choices":[{"message":{"content":"最终回答","reasoning_content":"非标准原始推理"}}]}""",
        )

        assertTrue(sanitized.contains("最终回答"))
        assertFalse(sanitized.contains("非标准原始推理"))
    }

    @Test
    fun reasoningItemWithDirectContentIsRedactedWhileSummaryTextRemainsVisible() {
        val sanitized = NetworkDebugLogSanitizer.sanitize(
            """{"type":"reasoning","content":"兼容网关原始推理","summary":[{"type":"summary_text","text":"允许展示的摘要"}]}""",
        )

        assertFalse(sanitized.contains("兼容网关原始推理"))
        assertTrue(sanitized.contains("允许展示的摘要"))
    }

    @Test
    fun encryptedReasoningContentIsRedactedWhileFinalAnswerRemainsVisible() {
        val sanitized = NetworkDebugLogSanitizer.sanitize(
            """{"output":[{"type":"reasoning","encrypted_content":"OPAQUE_PRIVATE_REASONING","summary":[{"type":"summary_text","text":"允许展示的摘要"}]},{"type":"message","content":[{"type":"output_text","text":"IMAGE_OK"}]}]}""",
        )

        assertFalse(sanitized.contains("OPAQUE_PRIVATE_REASONING"))
        assertTrue(sanitized.contains("允许展示的摘要"))
        assertTrue(sanitized.contains("IMAGE_OK"))
    }

    @Test
    fun nestedReasoningObjectIsTreatedAsRawReasoningContext() {
        val sanitized = NetworkDebugLogSanitizer.sanitize(
            """{"reasoning":{"text":"嵌套原始推理","details":{"content":"更深层原始推理"}},"output_text":"最终回答"}""",
        )

        assertFalse(sanitized.contains("嵌套原始推理"))
        assertFalse(sanitized.contains("更深层原始推理"))
        assertTrue(sanitized.contains("最终回答"))
    }

    @Test
    fun malformedReasoningPayloadFailsClosed() {
        assertEquals(
            "***REDACTED_UNPARSEABLE_REASONING_PAYLOAD***",
            NetworkDebugLogSanitizer.sanitize("{reasoning_text: 原始思维链"),
        )
    }

    @Test
    fun ordinaryPayloadIsPreservedExactly() {
        val payload = """{"type":"response.output_text.delta","delta":"你好"}"""

        assertEquals(payload, NetworkDebugLogSanitizer.sanitize(payload))
    }
}
