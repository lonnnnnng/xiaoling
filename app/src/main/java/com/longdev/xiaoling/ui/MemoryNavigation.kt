package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus

internal fun MessagePart.Tool.memoryIdForNavigation(): String? {
    if (!success || verificationStatus == MessageToolVerificationStatus.FAILED) return null
    val memoryId = memoryIdsUsed.singleOrNull()?.takeIf(MEMORY_ID_PATTERN::matches) ?: return null

    return when (toolName) {
        MEMORY_GET_TOOL_NAME -> {
            if (arguments.keys != setOf(MEMORY_ID_ARGUMENT)) return null
            if (arguments.getValue(MEMORY_ID_ARGUMENT).trim() != memoryId) return null
            if (result.lineSequence().firstOrNull() != "$MEMORY_GET_RESULT_PREFIX$memoryId") return null
            memoryId
        }

        MEMORY_REMEMBER_TOOL_NAME -> {
            if (verificationStatus != MessageToolVerificationStatus.VERIFIED) return null
            if (!arguments.hasValidMemoryRememberArguments()) return null
            // long: 写入结果只有在应用固定回执、唯一 Store 身份和 Executor 验证同时成立时才允许进入记忆管理页。
            val resultId = MEMORY_REMEMBER_RESULT_PATTERN.matchEntire(result)
                ?.groupValues
                ?.get(1)
                ?: return null
            if (MEMORY_ID_PATTERN.findAll(result).count() != 1) return null
            memoryId.takeIf { it == resultId }
        }

        MEMORY_SEARCH_TOOL_NAME -> {
            if (!arguments.hasValidMemorySearchArguments()) return null
            if (result.lineSequence().firstOrNull() != MEMORY_SEARCH_RESULT_HEADING) return null
            // long: 搜索正文来自用户可编辑的本地记忆，导航只信任工具回执中的唯一 ID，并要求应用生成的单条结果外壳与它一致，正文中的伪造 ID 不能制造入口。
            if (MEMORY_RESULT_ID_SUFFIX_PATTERN.findAll(result).count() != 1) return null
            val resultId = MEMORY_SEARCH_RESULT_PATTERN.matchEntire(result)
                ?.groupValues
                ?.get(1)
                ?: return null
            memoryId.takeIf { it == resultId }
        }

        else -> null
    }
}

private fun Map<String, String>.hasValidMemoryRememberArguments(): Boolean {
    if (keys.any { key -> key != MEMORY_NOTE_ARGUMENT && key != MEMORY_TYPE_ARGUMENT && key != MEMORY_TAGS_ARGUMENT }) {
        return false
    }
    val note = get(MEMORY_NOTE_ARGUMENT)?.trim()?.takeIf { value -> value.isNotEmpty() && value.length <= 2_000 }
        ?: return false
    if (note.isEmpty()) return false
    val type = get(MEMORY_TYPE_ARGUMENT)
    if (type != null && type !in MEMORY_TYPES) return false
    val tags = get(MEMORY_TAGS_ARGUMENT) ?: return true
    if (tags.length > 500) return false
    val normalizedTags = tags.split(',').map(String::trim).filter(String::isNotBlank)
    return normalizedTags.size <= 10 && normalizedTags.all { tag -> tag.length <= 50 }
}

private fun Map<String, String>.hasValidMemorySearchArguments(): Boolean {
    if (keys.any { key -> key != MEMORY_QUERY_ARGUMENT && key != MEMORY_LIMIT_ARGUMENT }) return false
    if (get(MEMORY_QUERY_ARGUMENT)?.length?.let { length -> length > 200 } == true) return false
    return get(MEMORY_LIMIT_ARGUMENT)?.toIntOrNull()?.let { limit -> limit in 1..10 } ?: true
}

private const val MEMORY_SEARCH_TOOL_NAME = "memory.search"
private const val MEMORY_GET_TOOL_NAME = "memory.get"
private const val MEMORY_REMEMBER_TOOL_NAME = "memory.remember"
private const val MEMORY_QUERY_ARGUMENT = "query"
private const val MEMORY_LIMIT_ARGUMENT = "limit"
private const val MEMORY_ID_ARGUMENT = "memory_id"
private const val MEMORY_NOTE_ARGUMENT = "note"
private const val MEMORY_TYPE_ARGUMENT = "type"
private const val MEMORY_TAGS_ARGUMENT = "tags"
private const val MEMORY_SEARCH_RESULT_HEADING = "长期记忆："
private const val MEMORY_GET_RESULT_PREFIX = "长期记忆详情：id="
private const val MEMORY_ID_VALUE_PATTERN = "memory-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
private val MEMORY_TYPES = setOf("Preference", "ProfileFact", "Episode", "Procedure")
private val MEMORY_ID_PATTERN = Regex(MEMORY_ID_VALUE_PATTERN)
private val MEMORY_REMEMBER_RESULT_PATTERN = Regex(
    pattern = "(?s)^已保存并验证长期记忆：.+ · 类型：(?:Preference|ProfileFact|Episode|Procedure)(?: · 标签：.+)? · 来源：.+ · id=($MEMORY_ID_VALUE_PATTERN)$",
)
private val MEMORY_SEARCH_RESULT_PATTERN = Regex(
    pattern = "长期记忆：\\n- (?s:.+) · 类型：(?s:.+) · 来源：(?s:.+) · id=($MEMORY_ID_VALUE_PATTERN)",
)
private val MEMORY_RESULT_ID_SUFFIX_PATTERN = Regex("(?m) · id=($MEMORY_ID_VALUE_PATTERN)$")
