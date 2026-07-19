package com.longdev.xiaoling.network

import org.json.JSONArray
import org.json.JSONObject

internal object NetworkDebugLogSanitizer {
    private const val REDACTED = "***REDACTED***"
    private const val REDACTED_UNPARSEABLE = "***REDACTED_UNPARSEABLE_REASONING_PAYLOAD***"

    fun sanitize(payload: String): String {
        if (!payload.contains("reasoning", ignoreCase = true)) return payload
        val parsed = runCatching {
            when (payload.firstOrNull { !it.isWhitespace() }) {
                '{' -> JSONObject(payload)
                '[' -> JSONArray(payload)
                else -> null
            }
        }.getOrNull()
        if (parsed == null) {
            // long: 无法解析且带推理标记的上游内容不能原样进入 logcat，宁可牺牲调试细节也不能泄露原始思维链。
            return REDACTED_UNPARSEABLE
        }
        return sanitizeValue(parsed, rawReasoningContext = false).toString()
    }

    private fun sanitizeValue(value: Any?, rawReasoningContext: Boolean): Any = when (value) {
        is JSONObject -> sanitizeObject(value, rawReasoningContext)
        is JSONArray -> JSONArray().apply {
            for (index in 0 until value.length()) {
                put(sanitizeValue(value.opt(index), rawReasoningContext))
            }
        }
        null -> JSONObject.NULL
        is String -> if (rawReasoningContext) REDACTED else value
        else -> value
    }

    private fun sanitizeObject(source: JSONObject, rawReasoningContext: Boolean): JSONObject {
        val result = JSONObject()
        val type = source.optString("type")
        val rawReasoningEvent = type == "reasoning_text" || type.contains("reasoning_text")
        val reasoningItem = type == "reasoning"
        val keys = source.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = source.opt(key)
            val redactedValue = when {
                key == "reasoning_text" || key == "reasoning_content" -> REDACTED
                key == "reasoning" -> sanitizeValue(value, rawReasoningContext = true)
                rawReasoningEvent && key in RAW_REASONING_VALUE_KEYS -> REDACTED
                reasoningItem && key in RAW_REASONING_VALUE_KEYS -> sanitizeValue(value, rawReasoningContext = true)
                rawReasoningContext -> sanitizeValue(value, rawReasoningContext = true)
                else -> sanitizeValue(value, rawReasoningContext = false)
            }
            result.put(key, redactedValue)
        }
        return result
    }

    private val RAW_REASONING_VALUE_KEYS = setOf("text", "delta", "content")
}
