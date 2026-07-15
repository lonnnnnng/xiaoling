package com.longdev.endpointtester.network

import com.longdev.endpointtester.model.ApiMode
import org.json.JSONArray
import org.json.JSONObject

object OpenAiResponseParser {
    fun parseModels(body: String): List<String> {
        val json = JSONObject(body)
        val models = json.optJSONArray("data") ?: json.optJSONArray("models") ?: JSONArray()
        return buildList {
            for (index in 0 until models.length()) {
                val value = models.opt(index)
                val id = when (value) {
                    is JSONObject -> value.optString("id").ifBlank { value.optString("name") }
                    is String -> value
                    else -> ""
                }
                if (id.isNotBlank()) add(id)
            }
        }.distinct().sorted()
    }

    fun parseChatText(body: String): String {
        val json = JSONObject(body)
        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.optJSONObject(0)
            val message = choice?.optJSONObject("message")
            val content = message?.opt("content")
            parseContent(content)?.let { return it }
            choice?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
            if (choice?.optString("finish_reason") == "length") {
                throw ApiFailure(FailureKind.RESPONSE, "输出被截断，请调高 max tokens")
            }
        }

        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        throw ApiFailure(FailureKind.RESPONSE, "响应中没有 choices[0].message.content")
    }

    fun parseResponsesText(body: String): String {
        val json = JSONObject(body)
        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }

        val output = json.optJSONArray("output")
        if (output != null) {
            val texts = buildList {
                for (outputIndex in 0 until output.length()) {
                    val item = output.optJSONObject(outputIndex) ?: continue
                    val content = item.optJSONArray("content") ?: continue
                    for (contentIndex in 0 until content.length()) {
                        val contentItem = content.optJSONObject(contentIndex) ?: continue
                        contentItem.optString("text").takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            texts.joinToString("\n").takeIf { it.isNotBlank() }?.let { return it }
        }

        throw ApiFailure(FailureKind.RESPONSE, "响应中没有 output_text 或 output[].content[].text")
    }

    fun parseStreamDelta(apiMode: ApiMode, data: String): String? {
        if (data == "[DONE]") return null
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return null
        return when (apiMode) {
            ApiMode.CHAT_COMPLETIONS -> parseChatStreamDelta(json)
            ApiMode.RESPONSES -> parseResponsesStreamDelta(json)
        }
    }

    private fun parseChatStreamDelta(json: JSONObject): String? {
        json.optString("delta").takeIf { it.isNotBlank() }?.let { return it }
        json.optString("text").takeIf { it.isNotBlank() }?.let { return it }

        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.optJSONObject(0)
            val delta = choice?.optJSONObject("delta")
            parseContent(delta?.opt("content"))?.let { return it }
            parseContent(choice?.optJSONObject("message")?.opt("content"))?.let { return it }
            choice?.optString("text")?.takeIf { it.isNotBlank() }?.let { return it }
        }

        return null
    }

    private fun parseResponsesStreamDelta(json: JSONObject): String? {
        val eventType = json.optString("type")
        if (eventType != "response.output_text.delta") return null
        // long: Responses API 流式返回是 typed SSE，只有 output_text.delta 才是增量文本；completed 事件里的累计文本不能再次追加。
        return json.optString("delta")
            .takeIf { it.isNotBlank() }
            ?: json.optString("text").takeIf { it.isNotBlank() }
    }

    private fun parseContent(content: Any?): String? = when (content) {
        is String -> content.takeIf { it.isNotBlank() }
        is JSONArray -> buildList {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                item.optString("text").takeIf { it.isNotBlank() }?.let(::add)
            }
        }.joinToString("\n").takeIf { it.isNotBlank() }
        else -> null
    }
}
