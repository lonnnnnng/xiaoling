package com.longdev.xiaoling.network

import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ModelReasoningSummary
import com.longdev.xiaoling.model.ModelTokenUsage
import org.json.JSONArray
import org.json.JSONObject

object OpenAiResponseParser {
    // long: 可读正文不代表可执行卡片已完整完成；只有显式正常终止能签发 action，缺失、截断或失败均保留只读展示。
    fun confirmsActionCompletion(apiMode: ApiMode, body: String): Boolean = runCatching {
        val json = com.longdev.xiaoling.json.StrictJsonObject.parse(body)
        when (apiMode) {
            ApiMode.CHAT_COMPLETIONS -> json.optJSONArray("choices")?.optJSONObject(0)?.opt("finish_reason") == "stop"
            ApiMode.RESPONSES -> json.opt("status") == "completed" && json.isNull("incomplete_details") && json.isNull("error")
        }
    }.getOrDefault(false)

    fun parseResponsesReasoningSummaries(body: String): List<ModelReasoningSummary> {
        val output = runCatching { JSONObject(body).optJSONArray("output") }.getOrNull() ?: return emptyList()
        return buildList {
            for (outputIndex in 0 until output.length()) {
                val item = output.optJSONObject(outputIndex) ?: continue
                if (item.optString("type") != "reasoning") continue
                val providerItemId = item.optString("id").takeIf { it.isNotBlank() }
                val summaries = item.optJSONArray("summary") ?: continue
                // long: 终端用户只能看到供应商生成的 summary_text；原始 reasoning_text 可能包含内部指令或不安全内容，解析器刻意不读取 content 字段。
                for (summaryIndex in 0 until summaries.length()) {
                    val summary = summaries.optJSONObject(summaryIndex) ?: continue
                    if (summary.optString("type") != "summary_text") continue
                    summary.optString("text").takeIf { it.isNotBlank() }?.let { text ->
                        add(ModelReasoningSummary(providerItemId = providerItemId, summaryIndex = summaryIndex, text = text))
                    }
                }
            }
        }
    }

    fun parseTokenUsage(body: String): ModelTokenUsage? {
        val usage = runCatching { JSONObject(body).optJSONObject("usage") }.getOrNull() ?: return null
        val inputTokens = usage.longOrNull("input_tokens") ?: usage.longOrNull("prompt_tokens")
        val outputTokens = usage.longOrNull("output_tokens") ?: usage.longOrNull("completion_tokens")
        val totalTokens = usage.longOrNull("total_tokens")
            ?: if (inputTokens != null && outputTokens != null) inputTokens + outputTokens else null
        return ModelTokenUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
        ).takeIf { inputTokens != null || outputTokens != null || totalTokens != null }
    }

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
        rejectFailedGeneration(ApiMode.CHAT_COMPLETIONS, json)
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
        rejectFailedGeneration(ApiMode.RESPONSES, json)
        json.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }

        val output = json.optJSONArray("output")
        if (output != null) {
            val texts = buildList {
                for (outputIndex in 0 until output.length()) {
                    val item = output.optJSONObject(outputIndex) ?: continue
                    if (item.optString("type") == "reasoning") continue
                    val content = item.optJSONArray("content") ?: continue
                    for (contentIndex in 0 until content.length()) {
                        val contentItem = content.optJSONObject(contentIndex) ?: continue
                        if (contentItem.optString("type").let { it.isNotBlank() && it != "output_text" }) continue
                        // long: Responses output 可能同时包含 reasoning_text；正文只接受 output_text，避免原始思维链混入聊天消息或后续摘要上下文。
                        contentItem.optString("text").takeIf { it.isNotBlank() }?.let(::add)
                    }
                }
            }
            texts.joinToString("\n").takeIf { it.isNotBlank() }?.let { return it }
        }

        throw ApiFailure(FailureKind.RESPONSE, "响应中没有 output_text 或 message.output_text")
    }

    private fun rejectFailedGeneration(mode: ApiMode, json: JSONObject) {
        val reason = json.optJSONArray("choices")?.optJSONObject(0)?.streamString("finish_reason")
        val error = json.optJSONObject("error")
        // long: 非流式也会在 HTTP 200 中携带局部正文与失败终态；必须先拒绝业务失败，再读取正文，避免绕过流式终态保护。
        if (!json.isNull("error") || (mode == ApiMode.RESPONSES && json.optString("status") in setOf("failed", "incomplete", "cancelled")) ||
            (mode == ApiMode.CHAT_COMPLETIONS && reason != null && reason != "stop")) {
            throw ApiFailure(FailureKind.RESPONSE, if (reason == "length") "输出被截断，请调高 max tokens"
                else error?.streamString("message")?.take(300) ?: "模型响应未完整完成")
        }
    }

    fun parseStreamEvent(apiMode: ApiMode, data: String): LlmStreamEvent? {
        if (data == "[DONE]") return null
        val json = runCatching { com.longdev.xiaoling.json.StrictJsonObject.parse(data) }.getOrNull() ?: return null
        // long: 同一条 SSE 数据只解析一次，再同时提取增量和服务端最终文本，避免协议层重复反序列化并让 Client 依赖事件类型细节。
        val deltaText = when (apiMode) {
            ApiMode.CHAT_COMPLETIONS -> parseChatStreamDelta(json)
            ApiMode.RESPONSES -> parseResponsesStreamDelta(json)
        }
        val finalText = if (apiMode == ApiMode.RESPONSES) parseResponsesStreamFinalText(json) else null
        val reasoningSummaryDelta = if (apiMode == ApiMode.RESPONSES) parseReasoningSummaryDelta(json) else null
        val reasoningSummaries = if (apiMode == ApiMode.RESPONSES) parseFinalReasoningSummaries(json) else emptyList()
        // long: HTTP 200 与已收到正文都不证明生成完成；保留业务终态，避免失败和截断正文被当成完整回答或长期记忆来源。
        val finishReason = json.optJSONArray("choices")?.optJSONObject(0)?.streamString("finish_reason")
        val response = json.optJSONObject("response")
        val terminalStatus = when {
            !json.isNull("error") || json.optString("type") == "error" -> LlmStreamStatus.FAILED
            apiMode == ApiMode.CHAT_COMPLETIONS && finishReason != null ->
                if (finishReason == "stop") LlmStreamStatus.COMPLETED else LlmStreamStatus.INCOMPLETE
            apiMode == ApiMode.RESPONSES -> when (json.optString("type")) {
                "response.failed" -> LlmStreamStatus.FAILED
                "response.incomplete" -> LlmStreamStatus.INCOMPLETE
                "response.completed" -> if (response != null && confirmsActionCompletion(apiMode, response.toString())) {
                    LlmStreamStatus.COMPLETED
                } else LlmStreamStatus.FAILED
                else -> null
            }
            else -> null
        }
        val failureMessage = when (terminalStatus) {
            LlmStreamStatus.FAILED -> (json.optJSONObject("error") ?: response?.optJSONObject("error"))
                ?.streamString("message")?.take(300) ?: "服务端报告流式生成失败"
            LlmStreamStatus.INCOMPLETE -> if (finishReason == "length" ||
                response?.optJSONObject("incomplete_details")?.optString("reason") == "max_output_tokens") {
                "输出被截断，请调高 max tokens"
            } else "流式生成未完整结束"
            else -> null
        }
        val completion = when (apiMode) {
            ApiMode.CHAT_COMPLETIONS -> json.optJSONArray("choices")?.optJSONObject(0)?.let { choice ->
                if (choice.isNull("finish_reason")) null else choice.opt("finish_reason") == "stop"
            }
            ApiMode.RESPONSES -> when (json.opt("type")) {
                "response.completed" -> json.optJSONObject("response")?.let {
                    // long: 状态本身不能确认此前缓存的卡片；终态必须携带可解析的最终正文，空输出或 refusal 只能只读展示。
                    finalText != null && confirmsActionCompletion(apiMode, it.toString())
                } ?: false
                "response.failed", "response.incomplete", "error" -> false
                else -> null
            }
        }
        return if (deltaText == null && finalText == null && reasoningSummaryDelta == null && reasoningSummaries.isEmpty() && completion == null && terminalStatus == null) {
            null
        } else {
            LlmStreamEvent(
                actionCompletionConfirmed = completion,
                deltaText = deltaText,
                finalText = finalText,
                reasoningSummaryDelta = reasoningSummaryDelta,
                reasoningSummaries = reasoningSummaries,
                terminalStatus = terminalStatus,
                failureMessage = failureMessage,
            )
        }
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
            "response.content_part.done" -> json.optJSONObject("part")?.parseOutputText()
            "response.output_item.done" -> json.optJSONObject("item")?.parseOutputItemText()
            "response.completed" -> json.optJSONObject("response")?.let { response ->
                response.streamString("output_text") ?: runCatching { parseResponsesText(response.toString()) }.getOrNull()
            }
            else -> null
        }
    }

    private fun parseReasoningSummaryDelta(json: JSONObject): ModelReasoningSummary? {
        if (json.optString("type") != "response.reasoning_summary_text.delta") return null
        val delta = json.streamString("delta") ?: return null
        return ModelReasoningSummary(
            providerItemId = json.optString("item_id").takeIf { it.isNotBlank() },
            summaryIndex = json.optInt("summary_index", 0).coerceAtLeast(0),
            text = delta,
        )
    }

    private fun parseFinalReasoningSummaries(json: JSONObject): List<ModelReasoningSummary> {
        if (json.optString("type") != "response.reasoning_summary_text.done") return emptyList()
        val text = json.streamString("text") ?: return emptyList()
        return listOf(
            ModelReasoningSummary(
                providerItemId = json.optString("item_id").takeIf { it.isNotBlank() },
                summaryIndex = json.optInt("summary_index", 0).coerceAtLeast(0),
                text = text,
            ),
        )
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
        if (optString("type") == "reasoning") return null
        val content = optJSONArray("content") ?: return null
        return buildList {
            for (index in 0 until content.length()) {
                val item = content.optJSONObject(index) ?: continue
                item.parseOutputText()?.let(::add)
            }
        }.joinToString("\n").takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.parseOutputText(): String? {
        if (optString("type").let { it.isNotBlank() && it != "output_text" }) return null
        return streamString("text")
    }

    private fun JSONObject.streamString(name: String): String? {
        if (!has(name) || isNull(name)) return null
        return optString(name).takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.longOrNull(name: String): Long? {
        if (!has(name) || isNull(name)) return null
        return runCatching { getLong(name) }.getOrNull()?.takeIf { it >= 0L }
    }
}
