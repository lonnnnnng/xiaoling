package com.longdev.xiaoling.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
            2. 对话页面
               - Resp 胶囊
               - 流式胶囊
        """.trimIndent(),
        "quote_and_link" to """
            > 接口失败时错误信息进入对话记录。

            参考：[OpenAI](https://platform.openai.com/)
        """.trimIndent(),
        "remote_image" to """
            ![httpbin png](https://httpbin.org/image/png)
        """.trimIndent(),
        "sources_link_list" to """
            ## Sources
            [grok2api-sources]: #
            - [World Cup | The Guardian](https://www.theguardian.com/football/world-cup-football)
            - [World Cup LIVE: Turkey beat USA, Australia advance | Flashscore.com](https://www.flashscore.com/news/soccer-fifa-world-cup-2026-day-15-live-updates/EmMBTvy8/)
            - [FIFA World Cup - TSN](https://www.tsn.ca/soccer/fifa-world-cup/;3614/)
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
        assertEquals(9, MarkdownVerificationSamples.samples.size)
        assertTrue(MarkdownVerificationSamples.samples.getValue("gfm_table").contains("| --- | --- | --- |"))
        assertTrue(MarkdownVerificationSamples.samples.getValue("code_fence").contains("```kotlin"))
        assertTrue(MarkdownVerificationSamples.samples.getValue("remote_image").contains("https://httpbin.org/image/png"))
        assertTrue(MarkdownVerificationSamples.samples.getValue("sources_link_list").contains("## Sources"))
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

    @Test
    fun `gfm table sample can be parsed for bordered rendering`() {
        val table = parseMarkdownTableBlock(
            """
                | 字段 | 说明 |
                | --- | --- |
                | Provider | 已保存的模型提供方 |
                | Model | 对话页面选中的上游模型 |
            """.trimIndent(),
        )

        assertNotNull(table)
        table!!
        assertEquals(listOf("字段", "说明"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals("Provider", table.rows[0][0])
        assertEquals("已保存的模型提供方", table.rows[0][1])
    }

    @Test
    fun `table parser preserves common cell content as readable text`() {
        val table = parseMarkdownTableBlock(
            """
                | 左对齐 | 居中 | 右对齐 |
                | :--- | :---: | ---: |
                | A\|B | [OpenAI](https://openai.com) | `code` 和 **重点** |
            """.trimIndent(),
        )

        assertNotNull(table)
        table!!
        assertEquals("A|B", table.rows[0][0])
        assertEquals("OpenAI", table.rows[0][1])
        assertEquals("code 和 重点", table.rows[0][2])
    }

    @Test
    fun `model markdown without marker spaces is normalized`() {
        assertEquals(
            """
                ### 怎么让我看你的环境
                - 截图你的屏幕
                1. 保存图片
                > 发送给我
            """.trimIndent(),
            normalizeModelMarkdown(
                """
                    ###怎么让我看你的环境
                    -截图你的屏幕
                    1.保存图片
                    >发送给我
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `markdown normalization keeps fenced code unchanged`() {
        val markdown = """
            ```markdown
            ###不要改代码块里的标题
            -不要改代码块里的列表
            ```
            ###外部标题
        """.trimIndent()

        assertEquals(
            """
                ```markdown
                ###不要改代码块里的标题
                -不要改代码块里的列表
                ```
                ### 外部标题
            """.trimIndent(),
            normalizeModelMarkdown(markdown),
        )
    }
}
