package com.longdev.xiaoling.share

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTextAgentDraftPolicyTest {
    @Test
    fun sharedTextBecomesExplicitAgentNoteDraftWithoutChangingBody() {
        val sharedText = "第一行\n第二行"

        assertEquals(
            "/agent 使用 notes.create 将以下分享文本保存为一条本机笔记。请根据正文生成简洁标题，并完整保留正文内容：\n\n第一行\n第二行",
            SharedTextAgentDraftPolicy.createNoteDraft(sharedText),
        )
    }

    @Test
    fun blankSharedTextCannotCreateAgentDraft() {
        assertNull(SharedTextAgentDraftPolicy.createNoteDraft(" \n "))
        assertNull(SharedTextAgentDraftPolicy.createMemoryDraft(" \n "))
    }

    @Test
    fun sharedTextBecomesExplicitAgentMemoryDraftWithoutChangingBody() {
        val sharedText = "我偏好紧凑界面\n回答请先给结论"

        assertEquals(
            "/agent 使用 memory.remember 将以下分享文本保存为一条长期记忆。请完整保留正文，不补充或推断未提供的事实；只在用户批准后写入，并选择最合适的记忆类型与少量标签：\n\n我偏好紧凑界面\n回答请先给结论",
            SharedTextAgentDraftPolicy.createMemoryDraft(sharedText),
        )
    }
}
