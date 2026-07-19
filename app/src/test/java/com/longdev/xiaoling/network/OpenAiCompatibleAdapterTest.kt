package com.longdev.xiaoling.network

import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenAiCompatibleAdapterTest {
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
}
