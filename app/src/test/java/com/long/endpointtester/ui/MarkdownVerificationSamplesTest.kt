package com.longdev.endpointtester.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

object MarkdownVerificationSamples {
    val samples: Map<String, String> = mapOf(
        "gfm_table" to """
            | 功能 | 状态 | 备注 |
            | --- | --- | --- |
            | Chat Completions | ✅ | 非流式和流式都要可读 |
            | Responses API | ✅ | 支持 SSE delta |
        """.trimIndent(),
        "code_fence" to """
            ```kotlin
            fun send(message: String) {
                println("stream: ${'$'}message")
            }
            ```
        """.trimIndent(),
        "nested_list" to """
            1. Provider 管理
               - 新增
               - 同步模型
            2. 测试页面
               - Resp 胶囊
               - 流式胶囊
        """.trimIndent(),
        "quote_and_link" to """
            > 接口失败时错误信息进入对话记录。

            参考：[OpenAI](https://platform.openai.com/)
        """.trimIndent(),
        "partial_stream_table" to """
            | 字段 | 说明 |
            | --- | --- |
            | firstToken
        """.trimIndent(),
        "partial_stream_code_fence" to """
            ```json
            {"type":"response.output_text.delta","delta":"hello"
        """.trimIndent(),
        "long_output" to List(80) { index ->
            "- 第 ${index + 1} 行：这是一段用于验证长 Markdown 输出滚动和重组稳定性的内容。"
        }.joinToString("\n"),
    )
}

class MarkdownVerificationSamplesTest {
    @Test
    fun `markdown samples cover common model outputs`() {
        assertEquals(7, MarkdownVerificationSamples.samples.size)
        assertTrue(MarkdownVerificationSamples.samples.getValue("gfm_table").contains("| --- | --- | --- |"))
        assertTrue(MarkdownVerificationSamples.samples.getValue("code_fence").contains("```kotlin"))
        assertTrue(MarkdownVerificationSamples.samples.getValue("partial_stream_table").contains("| firstToken"))
        assertTrue(MarkdownVerificationSamples.samples.getValue("long_output").lines().size >= 80)
    }

    @Test
    fun `markdown samples are never blank`() {
        // long: 这些样例是后续人工和自动化回归的固定输入，任何空样例都会让 Markdown 覆盖范围出现假阳性。
        MarkdownVerificationSamples.samples.forEach { (name, markdown) ->
            assertTrue("$name should not be blank", markdown.isNotBlank())
        }
    }
}
