package com.longdev.xiaoling.ui

internal fun presentPersonalTaskPlanContextUsage(
    memoryUsedCount: Int,
    memoryOmittedCount: Int,
    knowledgeUsedCount: Int,
    knowledgeOmittedCount: Int,
    contextBytes: Int,
): String = buildString {
    append("计划上下文：长期记忆 $memoryUsedCount 条")
    append(" · 本地知识 $knowledgeUsedCount 个片段")
    append(" · 占用 ${contextBytes.toLong().toCompactByteText()}")
    if (memoryOmittedCount > 0 || knowledgeOmittedCount > 0) {
        // long: 只有真实省略过检索结果才展示精简声明，避免把空上下文或短请求包装成优化收益。
        append("\n上下文精简：省略长期记忆 $memoryOmittedCount 条")
        append(" · 本地知识 $knowledgeOmittedCount 个片段")
    }
}
