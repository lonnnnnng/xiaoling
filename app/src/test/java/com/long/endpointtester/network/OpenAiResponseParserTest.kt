package com.longdev.endpointtester.network

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
            OpenAiResponseParser.parseStreamDelta("""{"choices":[{"delta":{"content":"OK"}}]}"""),
        )
    }

    @Test
    fun `responses stream delta is parsed`() {
        assertEquals(
            "OK",
            OpenAiResponseParser.parseStreamDelta("""{"type":"response.output_text.delta","delta":"OK"}"""),
        )
    }

    @Test
    fun `done stream marker is ignored`() {
        assertNull(OpenAiResponseParser.parseStreamDelta("[DONE]"))
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
