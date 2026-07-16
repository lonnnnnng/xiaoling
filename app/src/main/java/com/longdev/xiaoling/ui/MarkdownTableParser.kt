package com.longdev.xiaoling.ui

import androidx.compose.ui.text.style.TextAlign

internal data class MarkdownTableBlock(
    val headers: List<String>,
    val rows: List<List<String>>,
    val alignments: List<TextAlign>,
) {
    val columnCount: Int = maxOf(
        headers.size,
        alignments.size,
        rows.maxOfOrNull { it.size } ?: 0,
    )
}

internal fun parseMarkdownTableBlock(rawTable: String): MarkdownTableBlock? {
    val lines = rawTable
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotBlank() && it.contains("|") }
        .toList()
    if (lines.size < 2) return null

    val separatorIndex = lines.indexOfFirst { line ->
        splitMarkdownTableLine(line).all { cell -> cell.matches(TABLE_SEPARATOR_CELL) }
    }
    if (separatorIndex <= 0) return null

    val headers = splitMarkdownTableLine(lines[separatorIndex - 1]).map(::cleanMarkdownTableCell)
    val alignments = splitMarkdownTableLine(lines[separatorIndex]).map(::tableTextAlign)
    val rows = lines
        .drop(separatorIndex + 1)
        .map { line -> splitMarkdownTableLine(line).map(::cleanMarkdownTableCell) }
        .filter { row -> row.any { it.isNotBlank() } }

    if (headers.isEmpty() || headers.all { it.isBlank() }) return null
    return MarkdownTableBlock(headers = headers, rows = rows, alignments = alignments)
}

private val TABLE_SEPARATOR_CELL = Regex(":?-{3,}:?")
private val MARKDOWN_LINK = Regex("!?\\[([^\\]]+)]\\(([^)]+)\\)")
private val MARKDOWN_CODE = Regex("`([^`]*)`")
private val MARKDOWN_EMPHASIS = Regex("(?<!\\\\)([*_]{1,3})(.+?)(?<!\\\\)\\1")

private fun splitMarkdownTableLine(line: String): List<String> {
    val normalized = line.trim().trimUnescapedTableEdge()
    val cells = mutableListOf<String>()
    val current = StringBuilder()
    var escaped = false
    var inCodeSpan = false

    normalized.forEach { char ->
        when {
            escaped -> {
                current.append(char)
                escaped = false
            }
            char == '\\' -> {
                escaped = true
            }
            char == '`' -> {
                inCodeSpan = !inCodeSpan
                current.append(char)
            }
            char == '|' && !inCodeSpan -> {
                cells += current.toString().trim()
                current.clear()
            }
            else -> current.append(char)
        }
    }
    if (escaped) current.append('\\')
    cells += current.toString().trim()
    return cells
}

private fun String.trimUnescapedTableEdge(): String {
    var start = 0
    var end = length
    if (firstOrNull() == '|') start += 1
    if (end > start && this[end - 1] == '|' && (end < 2 || this[end - 2] != '\\')) end -= 1
    return substring(start, end)
}

private fun tableTextAlign(separator: String): TextAlign {
    val trimmed = separator.trim()
    return when {
        trimmed.startsWith(":") && trimmed.endsWith(":") -> TextAlign.Center
        trimmed.endsWith(":") -> TextAlign.End
        else -> TextAlign.Start
    }
}

private fun cleanMarkdownTableCell(cell: String): String {
    // long: 表格组件只负责让数据网格可读；单元格里常见的链接、加粗和行内代码先降级为文本，避免为了边框把整个 Markdown inline 渲染器重新实现一遍。
    return cell
        .replace("<br>", "\n", ignoreCase = true)
        .replace(MARKDOWN_LINK) { result -> result.groupValues[1] }
        .replace(MARKDOWN_CODE) { result -> result.groupValues[1] }
        .replace(MARKDOWN_EMPHASIS) { result -> result.groupValues[2] }
        .replace("\\|", "|")
        .trim()
}
