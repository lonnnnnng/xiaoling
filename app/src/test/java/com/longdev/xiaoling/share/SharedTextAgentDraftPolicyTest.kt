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
    }
}
