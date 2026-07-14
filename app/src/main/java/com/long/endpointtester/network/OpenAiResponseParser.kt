package com.longdev.endpointtester.network

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
        }

        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        throw ApiFailure(FailureKind.RESPONSE, "响应中没有 choices[0].message.content")
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
