package com.longdev.xiaoling.network

import com.longdev.xiaoling.model.ApiMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class OpenAiResponseParserTest {
    @Test
    fun `responses reasoning parser keeps provider summaries and ignores raw chain of thought`() {
        val summaries = OpenAiResponseParser.parseResponsesReasoningSummaries(
            """
                {
                  "output": [
                    {
                      "id": "rs-provider-1",
                      "type": "reasoning",
                      "summary": [
                        {"type": "summary_text", "text": "先核对输入，再生成结论。"},
                        {"type": "unknown", "text": "不能展示"}
                      ],
                      "content": [
                        {"type": "reasoning_text", "text": "原始思维链不能展示"}
                      ]
                    },
                    {
                      "id": "msg-1",
                      "type": "message",
                      "content": [{"type": "output_text", "text": "最终答案"}]
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(listOf("先核对输入，再生成结论。"), summaries.map { it.text })
        assertEquals(listOf("rs-provider-1"), summaries.map { it.providerItemId })
    }

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
            OpenAiResponseParser.parseStreamEvent(
                ApiMode.CHAT_COMPLETIONS,
                """{"choices":[{"delta":{"content":"OK"}}]}""",
            )?.deltaText,
        )
    }

    @Test
    fun `responses stream delta is parsed`() {
        assertEquals(
            "OK",
            OpenAiResponseParser.parseStreamEvent(
                ApiMode.RESPONSES,
                """{"type":"response.output_text.delta","delta":"OK"}""",
            )?.deltaText,
        )
    }

    @Test
    fun `responses stream newline delta is preserved`() {
        assertEquals(
            "\n\n",
            OpenAiResponseParser.parseStreamEvent(
                ApiMode.RESPONSES,
                """{"type":"response.output_text.delta","delta":"\n\n"}""",
            )?.deltaText,
        )
    }

    @Test
    fun `chat stream newline content is preserved`() {
        assertEquals(
            "\n",
            OpenAiResponseParser.parseStreamEvent(
                ApiMode.CHAT_COMPLETIONS,
                """{"choices":[{"delta":{"content":"\n"}}]}""",
            )?.deltaText,
        )
    }

    @Test
    fun `done stream marker is ignored`() {
        assertNull(OpenAiResponseParser.parseStreamEvent(ApiMode.CHAT_COMPLETIONS, "[DONE]"))
    }

    @Test
    fun `responses completed stream event is ignored to avoid duplicate accumulated text`() {
        assertNull(
            OpenAiResponseParser.parseStreamEvent(
                ApiMode.RESPONSES,
                """{"type":"response.completed","response":{"output_text":"OK"}}""",
            )?.deltaText,
        )
    }

    @Test
    fun `responses output text done event exposes final text`() {
        assertEquals(
            "# Checklist\n\n1. [ ] Define the goal.",
            OpenAiResponseParser.parseStreamEvent(
                ApiMode.RESPONSES,
                """{"type":"response.output_text.done","text":"# Checklist\n\n1. [ ] Define the goal."}""",
            )?.finalText,
        )
    }

    @Test
    fun `responses completed event exposes final output text`() {
        assertEquals(
            "# Report\n\n| Name | Status |\n| --- | --- |\n| Markdown | OK |",
            OpenAiResponseParser.parseStreamEvent(
                ApiMode.RESPONSES,
                """{"type":"response.completed","response":{"output_text":"# Report\n\n| Name | Status |\n| --- | --- |\n| Markdown | OK |"}}""",
            )?.finalText,
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
