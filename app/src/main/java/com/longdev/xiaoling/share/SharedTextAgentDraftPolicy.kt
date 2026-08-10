package com.longdev.xiaoling.share

internal object SharedTextAgentDraftPolicy {
    fun createNoteDraft(sharedText: String): String? {
        val normalizedText = sharedText.trim()
        if (normalizedText.isBlank()) return null
        // long: 外部分享只有在用户点击“保存为笔记”后才升级为 Agent 草稿，避免系统 Intent 自动触发发送或写入。
        return "/agent 使用 notes.create 将以下分享文本保存为一条本机笔记。请根据正文生成简洁标题，并完整保留正文内容：\n\n$normalizedText"
    }

    fun createMemoryDraft(sharedText: String): String? {
        val normalizedText = sharedText.trim()
        if (normalizedText.isBlank()) return null
        // long: 长期记忆会影响后续会话的个性化上下文，因此外部文本必须先变成可编辑草稿，再由用户发送并逐次批准写入。
        return "/agent 使用 memory.remember 将以下分享文本保存为一条长期记忆。请完整保留正文，不补充或推断未提供的事实；只在用户批准后写入，并选择最合适的记忆类型与少量标签：\n\n$normalizedText"
    }
}
