package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus

internal fun MessagePart.Tool.localNoteIdForNavigation(): String? {
    if (!success || verificationStatus == MessageToolVerificationStatus.FAILED) return null
    if (toolName == NOTE_CREATE_TOOL_NAME) {
        if (!arguments.hasValidCreateArguments()) return null
        val firstLine = result.lineSequence().firstOrNull().orEmpty()
        val match = NOTE_CREATE_RESULT_PATTERN.matchEntire(firstLine) ?: return null
        if (match.groupValues[1] != arguments.getValue(NOTE_TITLE_ARGUMENT).trim()) return null
        return NOTE_ID_PATTERN.findAll(result).map { it.value }.toList().singleOrNull()
    }
    val expectedHeading = when (toolName) {
        NOTE_LIST_TOOL_NAME -> {
            if (!arguments.hasValidOptionalLimit()) return null
            NOTE_LIST_RESULT_HEADING
        }
        NOTE_SEARCH_TOOL_NAME -> {
            if (!arguments.hasValidSearchArguments()) return null
            NOTE_SEARCH_RESULT_HEADING
        }
        else -> return null
    }
    if (result.lineSequence().firstOrNull() != expectedHeading) return null

    // long: 历史 Tool part 没有单独保存 note ID；只接受笔记工具生成的唯一条目行和 UUID，正文或模型文本中的 id= 片段不能变成导航入口。
    if (NOTE_RESULT_ENTRY_LINE_PATTERN.findAll(result).count() != 1) return null
    NOTE_RESULT_ENTRY_PATTERN.findAll(result).singleOrNull() ?: return null
    return NOTE_ID_PATTERN.findAll(result).map { it.value }.toList().singleOrNull()
}

private fun Map<String, String>.hasValidOptionalLimit(): Boolean {
    if (keys.any { key -> key != NOTE_LIMIT_ARGUMENT }) return false
    return get(NOTE_LIMIT_ARGUMENT)?.toIntOrNull()?.let { limit -> limit in 1..10 } ?: true
}

private fun Map<String, String>.hasValidSearchArguments(): Boolean {
    if (keys.any { key -> key != NOTE_QUERY_ARGUMENT && key != NOTE_LIMIT_ARGUMENT }) return false
    val query = get(NOTE_QUERY_ARGUMENT)
        ?.trim()
        ?.takeIf { value -> value.isNotEmpty() && value.length <= 200 }
        ?: return false
    if (query.any { character -> character == '\n' || character == '\r' }) return false
    return get(NOTE_LIMIT_ARGUMENT)?.toIntOrNull()?.let { limit -> limit in 1..10 } ?: true
}

private fun Map<String, String>.hasValidCreateArguments(): Boolean {
    if (keys != setOf(NOTE_TITLE_ARGUMENT, NOTE_CONTENT_ARGUMENT)) return false
    val title = get(NOTE_TITLE_ARGUMENT)?.trim()?.takeIf { value -> value.length in 1..200 } ?: return false
    if (title.any { character -> character == '\n' || character == '\r' }) return false
    return get(NOTE_CONTENT_ARGUMENT)?.trim()?.length?.let { length -> length in 1..20_000 } == true
}

private const val NOTE_LIST_TOOL_NAME = "notes.list"
private const val NOTE_SEARCH_TOOL_NAME = "notes.search"
private const val NOTE_CREATE_TOOL_NAME = "notes.create"
private const val NOTE_LIMIT_ARGUMENT = "limit"
private const val NOTE_QUERY_ARGUMENT = "query"
private const val NOTE_TITLE_ARGUMENT = "title"
private const val NOTE_CONTENT_ARGUMENT = "content"
private const val NOTE_LIST_RESULT_HEADING = "最近笔记："
private const val NOTE_SEARCH_RESULT_HEADING = "匹配笔记："
private val NOTE_CREATE_RESULT_PATTERN = Regex("已创建并验证笔记：(.+) · id=(note-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})")
private val NOTE_ID_PATTERN = Regex("note-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
private val NOTE_RESULT_ENTRY_PATTERN = Regex(
    pattern = "(?m)^- .* · id=(note-[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12})$",
)
private val NOTE_RESULT_ENTRY_LINE_PATTERN = Regex("(?m)^- .*$")
