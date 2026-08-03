package com.longdev.xiaoling.network

import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import com.longdev.xiaoling.model.ImageAttachmentPolicy
import com.longdev.xiaoling.model.ProviderRequestConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleAdapterTest {
    @Test
    fun `chat completions structured output uses strict json schema`() {
        val request = OpenAiCompatibleAdapter().prepareGenerationRequest(
            config = requestConfig(ApiMode.CHAT_COMPLETIONS),
            messages = listOf(RequestMessage(role = "user", content = "生成计划")),
            outputFormat = structuredOutputFormat(),
        )

        val format = JSONObject(request.body)
            .getJSONObject("response_format")
            .getJSONObject("json_schema")
        assertEquals("personal_task_plan", format.getString("name"))
        assertTrue(format.getBoolean("strict"))
        assertFalse(format.getJSONObject("schema").getBoolean("additionalProperties"))
    }

    @Test
    fun `responses structured output uses text format contract`() {
        val request = OpenAiCompatibleAdapter().prepareGenerationRequest(
            config = requestConfig(ApiMode.RESPONSES),
            messages = listOf(RequestMessage(role = "user", content = "生成计划")),
            outputFormat = structuredOutputFormat(),
        )

        val format = JSONObject(request.body)
            .getJSONObject("text")
            .getJSONObject("format")
        assertEquals("json_schema", format.getString("type"))
        assertEquals("personal_task_plan", format.getString("name"))
        assertTrue(format.getBoolean("strict"))
        assertFalse(format.getJSONObject("schema").getBoolean("additionalProperties"))
    }

    @Test
    fun `responses request maps user document to input file data url after text`() {
        val attachment = DocumentAttachmentPolicy.create(
            fileName = "notes.md",
            mimeType = "text/markdown",
            data = "stage 27 document".toByteArray(),
        )

        val request = OpenAiCompatibleAdapter().prepareGenerationRequest(
            config = requestConfig(ApiMode.RESPONSES),
            messages = listOf(RequestMessage(role = "user", content = "总结文档", documents = listOf(attachment))),
        )

        val content = JSONObject(request.body)
            .getJSONArray("input")
            .getJSONObject(0)
            .getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("input_file", content.getJSONObject(1).getString("type"))
        assertEquals("notes.md", content.getJSONObject(1).getString("filename"))
        assertTrue(content.getJSONObject(1).getString("file_data").startsWith("data:text/markdown;base64,"))
        assertFalse(content.getJSONObject(1).has("detail"))
    }

    @Test
    fun `responses pdf document uses auto detail`() {
        val attachment = DocumentAttachmentPolicy.create(
            fileName = "report.pdf",
            mimeType = "application/pdf",
            data = "%PDF-1.7".toByteArray(Charsets.US_ASCII),
            pageCount = 1,
        )

        val request = OpenAiCompatibleAdapter().prepareGenerationRequest(
            config = requestConfig(ApiMode.RESPONSES),
            messages = listOf(RequestMessage(role = "user", content = "总结 PDF", documents = listOf(attachment))),
        )

        val file = JSONObject(request.body)
            .getJSONArray("input")
            .getJSONObject(0)
            .getJSONArray("content")
            .getJSONObject(1)
        assertEquals("auto", file.getString("detail"))
    }

    @Test
    fun `responses request maps user image to input image data url after text`() {
        val adapter: LlmProviderAdapter = OpenAiCompatibleAdapter()
        val attachment = ImageAttachmentPolicy.create(
            fileName = "chart.png",
            mimeType = "image/png",
            data = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3,
            ),
        )

        val request = adapter.prepareGenerationRequest(
            config = requestConfig(ApiMode.RESPONSES),
            messages = listOf(RequestMessage(role = "user", content = "解释图片", images = listOf(attachment))),
        )

        val content = JSONObject(request.body)
            .getJSONArray("input")
            .getJSONObject(0)
            .getJSONArray("content")
        assertEquals("input_text", content.getJSONObject(0).getString("type"))
        assertEquals("解释图片", content.getJSONObject(0).getString("text"))
        assertEquals("input_image", content.getJSONObject(1).getString("type"))
        assertEquals("auto", content.getJSONObject(1).getString("detail"))
        assertTrue(content.getJSONObject(1).getString("image_url").startsWith("data:image/png;base64,"))
    }

    @Test
    fun `chat completions rejects request messages containing image parts`() {
        val attachment = ImageAttachmentPolicy.create(
            fileName = "chart.png",
            mimeType = "image/png",
            data = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1,
            ),
        )

        try {
            OpenAiCompatibleAdapter().prepareGenerationRequest(
                config = requestConfig(ApiMode.CHAT_COMPLETIONS),
                messages = listOf(RequestMessage(role = "user", content = "解释图片", images = listOf(attachment))),
            )
            throw AssertionError("Expected Chat Completions image rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("图片"))
        }
    }

    @Test
    fun `chat completions rejects request messages containing document parts`() {
        val attachment = DocumentAttachmentPolicy.create(
            fileName = "notes.txt",
            mimeType = "text/plain",
            data = "hello".toByteArray(),
        )

        try {
            OpenAiCompatibleAdapter().prepareGenerationRequest(
                config = requestConfig(ApiMode.CHAT_COMPLETIONS),
                messages = listOf(RequestMessage(role = "user", content = "总结", documents = listOf(attachment))),
            )
            throw AssertionError("Expected Chat Completions document rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("文档"))
        }
    }


    @Test
    fun `request message rejects multiple user images`() {
        val attachment = ImageAttachmentPolicy.create(
            fileName = "chart.png",
            mimeType = "image/png",
            data = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1,
            ),
        )

        try {
            RequestMessage(role = "user", content = "解释图片", images = listOf(attachment, attachment))
            throw AssertionError("Expected single-image contract rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("最多"))
        }
    }

    @Test
    fun `request message rejects multiple or mixed attachments`() {
        val document = DocumentAttachmentPolicy.create(
            fileName = "notes.txt",
            mimeType = "text/plain",
            data = "hello".toByteArray(),
        )
        val image = ImageAttachmentPolicy.create(
            fileName = "chart.png",
            mimeType = "image/png",
            data = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1),
        )

        try {
            RequestMessage(role = "user", content = "总结", documents = listOf(document, document))
            throw AssertionError("Expected single-document contract rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("最多"))
        }
        try {
            RequestMessage(role = "user", content = "解释", images = listOf(image), documents = listOf(document))
            throw AssertionError("Expected mixed attachment contract rejection")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().contains("一种附件"))
        }
    }

    @Test
    fun `reasoning summary is explicit opt in for responses requests only`() {
        val adapter: LlmProviderAdapter = OpenAiCompatibleAdapter()
        val messages = listOf(RequestMessage(role = "user", content = "解释结论"))

        val enabledResponses = JSONObject(
            adapter.prepareGenerationRequest(
                config = requestConfig(ApiMode.RESPONSES).copy(reasoningSummaryEnabled = true),
                messages = messages,
            ).body,
        )
        val disabledResponses = JSONObject(
            adapter.prepareGenerationRequest(
                config = requestConfig(ApiMode.RESPONSES),
                messages = messages,
            ).body,
        )
        val chatCompletions = JSONObject(
            adapter.prepareGenerationRequest(
                config = requestConfig(ApiMode.CHAT_COMPLETIONS).copy(reasoningSummaryEnabled = true),
                messages = messages,
            ).body,
        )

        assertEquals("auto", enabledResponses.getJSONObject("reasoning").getString("summary"))
        assertFalse(disabledResponses.has("reasoning"))
        assertFalse(chatCompletions.has("reasoning"))
    }

    @Test
    fun `responses request preserves structured conversation roles`() {
        val adapter: LlmProviderAdapter = OpenAiCompatibleAdapter()

        val request = adapter.prepareGenerationRequest(
            config = requestConfig(ApiMode.RESPONSES),
            messages = listOf(
                RequestMessage(role = "system", content = "只回答已知事实"),
                RequestMessage(role = "user", content = "第一问"),
                RequestMessage(role = "assistant", content = "第一答"),
                RequestMessage(role = "user", content = "第二问"),
            ),
        )

        val payload = JSONObject(request.body)
        val input = payload.getJSONArray("input")
        assertEquals(4, input.length())
        assertEquals("system", input.getJSONObject(0).getString("role"))
        assertEquals("只回答已知事实", input.getJSONObject(0).getString("content"))
        assertEquals("user", input.getJSONObject(1).getString("role"))
        assertEquals("第一问", input.getJSONObject(1).getString("content"))
        assertEquals("assistant", input.getJSONObject(2).getString("role"))
        assertEquals("第一答", input.getJSONObject(2).getString("content"))
        assertEquals("user", input.getJSONObject(3).getString("role"))
        assertEquals("第二问", input.getJSONObject(3).getString("content"))
        assertTrue(payload.get("input") !is String)
    }

    @Test
    fun `chat completions request keeps messages contract`() {
        val adapter: LlmProviderAdapter = OpenAiCompatibleAdapter()
        val config = requestConfig(ApiMode.CHAT_COMPLETIONS).copy(
            streamingEnabled = true,
            temperature = 0.3,
            maxTokens = 321,
            topP = 0.7,
        )

        val request = adapter.prepareGenerationRequest(
            config = config,
            messages = listOf(
                RequestMessage(role = "system", content = "系统边界"),
                RequestMessage(role = "user", content = "你好"),
            ),
        )

        val payload = JSONObject(request.body)
        val messages = payload.getJSONArray("messages")
        assertEquals("https://api.example.com/v1/chat/completions", request.requestUrl)
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals("系统边界", messages.getJSONObject(0).getString("content"))
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("你好", messages.getJSONObject(1).getString("content"))
        assertEquals(321, payload.getInt("max_tokens"))
        assertEquals(0.3, payload.getDouble("temperature"), 0.0)
        assertEquals(0.7, payload.getDouble("top_p"), 0.0)
        assertTrue(payload.getBoolean("stream"))
        assertFalse(payload.has("input"))
        assertFalse(payload.has("max_output_tokens"))
    }

    @Test
    fun `adapter maps model list and non streaming responses`() {
        val adapter: LlmProviderAdapter = OpenAiCompatibleAdapter()

        assertEquals(
            "https://api.example.com/v1/models",
            adapter.modelsUrl(requestConfig(ApiMode.CHAT_COMPLETIONS)),
        )
        assertEquals(
            listOf("model-a", "model-b"),
            adapter.parseModelsResponse("""{"data":[{"id":"model-b"},{"id":"model-a"}]}"""),
        )
        assertEquals(
            "chat answer",
            adapter.parseGenerationResponse(
                ApiMode.CHAT_COMPLETIONS,
                """{"choices":[{"message":{"content":"chat answer"}}]}""",
            ),
        )
        assertEquals(
            "responses answer",
            adapter.parseGenerationResponse(ApiMode.RESPONSES, """{"output_text":"responses answer"}"""),
        )
    }

    @Test
    fun `adapter maps chat delta and responses final stream events`() {
        val adapter: LlmProviderAdapter = OpenAiCompatibleAdapter()

        assertEquals(
            LlmStreamEvent(deltaText = "delta"),
            adapter.parseStreamEvent(
                ApiMode.CHAT_COMPLETIONS,
                """{"choices":[{"delta":{"content":"delta"}}]}""",
            ),
        )
        assertEquals(
            LlmStreamEvent(finalText = "final"),
            adapter.parseStreamEvent(
                ApiMode.RESPONSES,
                """{"type":"response.output_text.done","text":"final"}""",
            ),
        )
    }

    @Test
    fun `responses request preserves function call and output typed items`() {
        val adapter: LlmProviderAdapter = OpenAiCompatibleAdapter()

        val request = adapter.prepareGenerationRequest(
            config = requestConfig(ApiMode.RESPONSES),
            messages = listOf(
                RequestMessage(role = "user", content = "查询天气"),
                RequestFunctionCall(
                    callId = "call-weather-1",
                    name = "get_weather",
                    arguments = mapOf("city" to "上海"),
                ),
                RequestFunctionCallOutput(
                    callId = "call-weather-1",
                    output = "晴，28°C",
                ),
            ),
        )

        val input = JSONObject(request.body).getJSONArray("input")
        val functionCall = input.getJSONObject(1)
        val functionOutput = input.getJSONObject(2)
        assertEquals("function_call", functionCall.getString("type"))
        assertEquals("call-weather-1", functionCall.getString("call_id"))
        assertEquals("get_weather", functionCall.getString("name"))
        assertEquals("上海", JSONObject(functionCall.getString("arguments")).getString("city"))
        assertEquals("function_call_output", functionOutput.getString("type"))
        assertEquals("call-weather-1", functionOutput.getString("call_id"))
        assertEquals("晴，28°C", functionOutput.getString("output"))
    }

    private fun requestConfig(apiMode: ApiMode) = ProviderRequestConfig(
        baseUrl = "https://api.example.com/v1",
        apiKey = "test-key",
        model = "test-model",
        apiMode = apiMode,
    )

    private fun structuredOutputFormat() = LlmStructuredOutputFormat(
        name = "personal_task_plan",
        schema = JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().put("name", JSONObject().put("type", "string")))
            .put("required", org.json.JSONArray().put("name"))
            .put("additionalProperties", false),
    )
}
