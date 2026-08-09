package com.longdev.xiaoling.share

internal object SharedTextAgentDraftPolicy {
    fun createNoteDraft(sharedText: String): String? {
        val normalizedText = sharedText.trim()
        if (normalizedText.isBlank()) return null
        // long: 外部分享只有在用户点击“保存为笔记”后才升级为 Agent 草稿，避免系统 Intent 自动触发发送或写入。
        return "/agent 使用 notes.create 将以下分享文本保存为一条本机笔记。请根据正文生成简洁标题，并完整保留正文内容：\n\n$normalizedText"
    }
}
