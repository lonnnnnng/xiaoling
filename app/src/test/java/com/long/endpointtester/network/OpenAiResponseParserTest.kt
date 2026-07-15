package com.longdev.endpointtester.network

import com.longdev.endpointtester.model.ApiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class OpenAiResponseParserTest {
    @Test
    fun `responses output text is parsed`() {
        assertEquals(
            "OK",
            OpenAiResponseParser.parseResponsesText("""{"output_text":"OK"}"""),
        )
    }

    @Test
    fun `responses content array text is parsed`() {
        assertEquals(
            "OK",
            OpenAiResponseParser.parseResponsesText(
                """{"output":[{"content":[{"type":"output_text","text":"OK"}]}]}""",
            ),
        )
    }

    @Test
    fun `chat stream delta is parsed`() {
        assertEquals(
            "OK",
            OpenAiResponseParser.parseStreamDelta(
                ApiMode.CHAT_COMPLETIONS,
                """{"choices":[{"delta":{"content":"OK"}}]}""",
            ),
        )
    }

    @Test
    fun `responses stream delta is parsed`() {
        assertEquals(
            "OK",
            OpenAiResponseParser.parseStreamDelta(
                ApiMode.RESPONSES,
                """{"type":"response.output_text.delta","delta":"OK"}""",
            ),
        )
    }

    @Test
    fun `responses stream newline delta is preserved`() {
        assertEquals(
            "\n\n",
            OpenAiResponseParser.parseStreamDelta(
                ApiMode.RESPONSES,
                """{"type":"response.output_text.delta","delta":"\n\n"}""",
            ),
        )
    }

    @Test
    fun `chat stream newline content is preserved`() {
        assertEquals(
            "\n",
            OpenAiResponseParser.parseStreamDelta(
                ApiMode.CHAT_COMPLETIONS,
                """{"choices":[{"delta":{"content":"\n"}}]}""",
            ),
        )
    }

    @Test
    fun `done stream marker is ignored`() {
        assertNull(OpenAiResponseParser.parseStreamDelta(ApiMode.CHAT_COMPLETIONS, "[DONE]"))
    }

    @Test
    fun `responses completed stream event is ignored to avoid duplicate accumulated text`() {
        assertNull(
            OpenAiResponseParser.parseStreamDelta(
                ApiMode.RESPONSES,
                """{"type":"response.completed","response":{"output_text":"OK"}}""",
            ),
        )
    }

    @Test
    fun `responses output text done event exposes final text`() {
        assertEquals(
            "# Checklist\n\n1. [ ] Define the goal.",
            OpenAiResponseParser.parseStreamFinalText(
                ApiMode.RESPONSES,
                """{"type":"response.output_text.done","text":"# Checklist\n\n1. [ ] Define the goal."}""",
            ),
        )
    }

    @Test
    fun `responses completed event exposes final output text`() {
        assertEquals(
            "# Report\n\n| Name | Status |\n| --- | --- |\n| Markdown | OK |",
            OpenAiResponseParser.parseStreamFinalText(
                ApiMode.RESPONSES,
                """{"type":"response.completed","response":{"output_text":"# Report\n\n| Name | Status |\n| --- | --- |\n| Markdown | OK |"}}""",
            ),
        )
    }

    @Test
    fun `chat completion length finish reason gives token guidance`() {
        try {
            OpenAiResponseParser.parseChatText(
                """
                    {
                      "choices": [
                        {
                          "message": {
                            "role": "assistant",
                            "content": "",
                            "reasoning_content": "模型仍在推理"
                          },
                          "finish_reason": "length"
                        }
                      ]
                    }
                """.trimIndent(),
            )
            fail("Expected ApiFailure")
        } catch (error: ApiFailure) {
            assertEquals(FailureKind.RESPONSE, error.kind)
            assertEquals("输出被截断，请调高 max tokens", error.message)
        }
    }
}
