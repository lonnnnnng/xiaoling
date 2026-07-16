package com.longdev.xiaoling.network

import com.longdev.xiaoling.model.ApiMode
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

    fun parseStreamFinalText(apiMode: ApiMode, data: String): String? {
        if (apiMode != ApiMode.RESPONSES || data == "[DONE]") return null
        val json = runCatching { JSONObject(data) }.getOrNull() ?: return null
        return parseResponsesStreamFinalText(json)
    }

    private fun parseChatStreamDelta(json: JSONObject): String? {
        json.streamString("delta")?.let { return it }
        json.streamString("text")?.let { return it }

        val choices = json.optJSONArray("choices")
        if (choices != null && choices.length() > 0) {
            val choice = choices.optJSONObject(0)
            val delta = choice?.optJSONObject("delta")
            parseStreamContent(delta?.opt("content"))?.let { return it }
            parseStreamContent(choice?.optJSONObject("message")?.opt("content"))?.let { return it }
            choice?.streamString("text")?.let { return it }
        }

        return null
    }

    private fun parseResponsesStreamDelta(json: JSONObject): String? {
        val eventType = json.optString("type")
        if (eventType != "response.output_text.delta") return null
        // long: Responses API 的纯换行也会作为独立 delta 到达，Markdown 段落、列表和表格都依赖这些换行，不能用 isNotBlank 过滤掉。
        return json.streamString("delta")
            ?: json.streamString("text")
    }

    private fun parseResponsesStreamFinalText(json: JSONObject): String? {
        return when (json.optString("type")) {
            "response.output_text.done" -> json.streamString("text")
            "response.content_part.done" -> json.optJSONObject("part")?.streamString("text")
            "response.output_item.done" -> json.optJSONObject("item")?.parseOutputItemText()
            "response.completed" -> json.optJSONObject("response")?.let { response ->
                response.streamString("output_text") ?: runCatching { parseResponsesText(response.toString()) }.getOrNull()
            }
            else -> null
        }
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

    private fun parseStreamContent(content: Any?): String? = when (content) {
        is String -> content.takeIf { it.isNotEmpty() }
        is JSONArray -> buildString {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                item.streamString("text")?.let(::append)
            }
        }.takeIf { it.isNotEmpty() }
        else -> null
    }

    private fun JSONObject.parseOutputItemText(): String? {
        val content = optJSONArray("content") ?: return null
        return buildList {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                item.streamString("text")?.let(::add)
            }
        }.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.streamString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotEmpty() }
    }
}
