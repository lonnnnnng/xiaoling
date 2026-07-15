package com.longdev.endpointtester.ui

internal fun normalizeModelMarkdown(markdown: String): String {
    if (markdown.isBlank()) return markdown

    var insideFence = false
    return markdown
        .lineSequence()
        .map { line ->
            val trimmed = line.trimStart()
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                insideFence = !insideFence
                return@map line
            }
            if (insideFence || trimmed.isHorizontalRule()) return@map line

            // long: 很多模型会输出“###标题”“-项目”“1.步骤”这类人能看懂但 Markdown 解析器不认的写法；这里只补分隔空格，不改变原始语义。
            line
                .normalizeHeadingMarker()
                .normalizeUnorderedListMarker()
                .normalizeOrderedListMarker()
                .normalizeBlockQuoteMarker()
        }
        .joinToString("\n")
}

private fun String.normalizeHeadingMarker(): String {
    return replace(Regex("""^(\s{0,3}#{1,6})([^\s#].*)$"""), "$1 $2")
}

private fun String.normalizeUnorderedListMarker(): String {
    return replace(Regex("""^(\s*[-*+])([^\s-*+].*)$"""), "$1 $2")
}

private fun String.normalizeOrderedListMarker(): String {
    return replace(Regex("""^(\s*\d+[.)])([^\s].*)$"""), "$1 $2")
}

private fun String.normalizeBlockQuoteMarker(): String {
    return replace(Regex("""^(\s*>)([^\s].*)$"""), "$1 $2")
}

private fun String.isHorizontalRule(): Boolean {
    return matches(Regex("""[-*_]{3,}"""))
}
