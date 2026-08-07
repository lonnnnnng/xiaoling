package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus

/**
 * 答案级历史会话导航的安全投影。
 *
 * Tool 结果可能包含模型转述的本地文本，因此这里只接受应用生成的固定结果外壳、
 * 唯一稳定 ID 和与参数一致的详情；点击后仍必须重新读取 Room，不能把消息里的 ID 当作事实。
 * 作者：long
 */
internal fun MessagePart.Tool.conversationIdForNavigation(): String? {
    if (!success || verificationStatus == MessageToolVerificationStatus.FAILED) return null

    return when (toolName) {
        CONVERSATION_LIST_TOOL_NAME -> {
            if (!arguments.hasValidOptionalLimit()) return null
            trustedConversationListId(result, CONVERSATION_LIST_RESULT_HEADING)
        }

        CONVERSATION_SEARCH_TOOL_NAME -> {
            if (!arguments.hasValidSearchArguments()) return null
            trustedConversationListId(result, CONVERSATION_SEARCH_RESULT_HEADING)
        }

        CONVERSATION_GET_TOOL_NAME -> {
            if (arguments.keys != setOf(CONVERSATION_ID_ARGUMENT)) return null
            val requestedId = ConversationNavigationPolicy.normalizeId(arguments[CONVERSATION_ID_ARGUMENT].orEmpty())
                ?: return null
            val firstLine = result.lineSequence().firstOrNull().orEmpty()
            if (!firstLine.startsWith(CONVERSATION_DETAIL_RESULT_PREFIX)) return null
            val detailId = CONVERSATION_DETAIL_ID_PATTERN.findAll(result).singleOrNull()?.groupValues?.get(1)
                ?: return null
            if (detailId != requestedId) return null
            detailId.takeIf { result.countStableConversationIds() == 1 }
        }

        else -> null
    }
}

private fun trustedConversationListId(result: String, heading: String): String? {
    if (result.lineSequence().firstOrNull() != heading) return null
    val entry = CONVERSATION_RESULT_ENTRY_PATTERN.findAll(result).singleOrNull() ?: return null
    val id = entry.groupValues[1]
    // long: 标题来自本地可编辑文本；若正文里还出现第二个稳定 ID，就不能证明这一行仍是应用唯一投影。
    return id.takeIf { result.countStableConversationIds() == 1 }
}

private fun String.countStableConversationIds(): Int = CONVERSATION_ID_PATTERN.findAll(this).count()

private fun Map<String, String>.hasValidOptionalLimit(): Boolean {
    if (keys.any { key -> key != CONVERSATION_LIMIT_ARGUMENT }) return false
    return get(CONVERSATION_LIMIT_ARGUMENT)?.toIntOrNull()?.let { limit -> limit in 1..10 } ?: true
}

private fun Map<String, String>.hasValidSearchArguments(): Boolean {
    if (keys.any { key -> key != CONVERSATION_QUERY_ARGUMENT && key != CONVERSATION_LIMIT_ARGUMENT }) return false
    val rawQuery = get(CONVERSATION_QUERY_ARGUMENT) ?: return false
    if (rawQuery.any { character -> character == '\n' || character == '\r' }) return false
    val query = rawQuery.trim()
    if (query.isEmpty() || query.length > 200) return false
    return get(CONVERSATION_LIMIT_ARGUMENT)?.toIntOrNull()?.let { limit -> limit in 1..10 } ?: true
}

internal object ConversationNavigationPolicy {
    private const val MAX_ID_LENGTH = 200
    private val conversationIdPattern = Regex("conversation-[A-Za-z0-9._:-]{1,180}")

    fun normalizeId(raw: String): String? {
        val value = raw.trim()
        return value
            .takeIf { it.length <= MAX_ID_LENGTH }
            ?.takeIf(conversationIdPattern::matches)
    }

    fun resolveUniqueId(conversationIds: List<String>, raw: String): String? {
        val normalized = normalizeId(raw) ?: return null
        return normalized.takeIf { id -> conversationIds.count { it == id } == 1 }
    }
}

private const val CONVERSATION_LIST_TOOL_NAME = "app.list_conversations"
private const val CONVERSATION_SEARCH_TOOL_NAME = "app.search_conversations"
private const val CONVERSATION_GET_TOOL_NAME = "app.get_conversation"
private const val CONVERSATION_LIMIT_ARGUMENT = "limit"
private const val CONVERSATION_QUERY_ARGUMENT = "query"
private const val CONVERSATION_ID_ARGUMENT = "conversation_id"
private const val CONVERSATION_LIST_RESULT_HEADING = "最近会话："
private const val CONVERSATION_SEARCH_RESULT_HEADING = "匹配会话："
private const val CONVERSATION_DETAIL_RESULT_PREFIX = "会话详情："
private const val CONVERSATION_ID_VALUE_PATTERN = "conversation-[A-Za-z0-9._:-]{1,180}"
private val CONVERSATION_ID_PATTERN = Regex(CONVERSATION_ID_VALUE_PATTERN)
private val CONVERSATION_RESULT_ENTRY_PATTERN = Regex(
    pattern = "(?m)^- .* · [0-9]+ 条消息 · id=($CONVERSATION_ID_VALUE_PATTERN)$",
)
private val CONVERSATION_DETAIL_ID_PATTERN = Regex(
    pattern = "(?m)^会话 ID：($CONVERSATION_ID_VALUE_PATTERN)$",
)
