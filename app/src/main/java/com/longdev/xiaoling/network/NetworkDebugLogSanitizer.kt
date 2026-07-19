package com.longdev.xiaoling.network

import org.json.JSONArray
import org.json.JSONObject

internal object NetworkDebugLogSanitizer {
    private const val REDACTED = "***REDACTED***"
    private const val REDACTED_UNPARSEABLE = "***REDACTED_UNPARSEABLE_REASONING_PAYLOAD***"

    fun sanitize(payload: String): String {
        if (!payload.contains("reasoning", ignoreCase = true) &&
            !payload.contains("data:image/", ignoreCase = true) &&
            !payload.contains("file_data", ignoreCase = true) &&
            !payload.contains("encrypted_content", ignoreCase = true)
        ) {
            return payload
        }
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
        val generatedImageItem = type == "image_generation_call"
        val keys = source.keys()
        // long: 调试日志保留普通 Prompt 与最终回答，但图片原始字节和不可展示推理必须按字段递归清除，避免兼容网关换一层 JSON 后绕过脱敏。
        while (keys.hasNext()) {
            val key = keys.next()
            val value = source.opt(key)
            val redactedValue = when {
                key == "reasoning_text" || key == "reasoning_content" || key == "encrypted_content" -> REDACTED
                key == "file_data" -> REDACTED
                value is String && value.startsWith("data:image/", ignoreCase = true) -> value.redactedDataUrl()
                generatedImageItem && key == "result" -> REDACTED
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

    private fun String.redactedDataUrl(): String {
        val prefix = substringBefore(',', missingDelimiterValue = "data:image")
        return "$prefix,***REDACTED***"
    }

    private val RAW_REASONING_VALUE_KEYS = setOf("text", "delta", "content")
}
