package com.longdev.xiaoling.ui

import com.longdev.xiaoling.share.SharedDraftPayload
import org.junit.Assert.assertEquals
import org.junit.Test

class SharedDraftProjectionPolicyTest {
    private val sharedDraft = SharedDraftPayload(
        text = "待确认的分享内容",
        imageUri = null,
    )

    @Test
    fun emptyComposerOpensSharedDraftImmediately() {
        assertEquals(
            SharedDraftProjectionPlan.OpenNow(sharedDraft),
            XiaoLingUiState().planSharedDraftProjection(sharedDraft),
        )
    }

    @Test
    fun existingComposerContentRequiresExplicitReplacement() {
        val state = XiaoLingUiState(prompt = "不要丢失的原草稿")

        assertEquals(
            SharedDraftProjectionPlan.ConfirmReplacement(sharedDraft),
            state.planSharedDraftProjection(sharedDraft),
        )
    }

    @Test
    fun secondShareDoesNotReplaceAnUnresolvedShareRequest() {
        val first = sharedDraft.copy(text = "第一个分享")
        val state = XiaoLingUiState(pendingSharedDraft = first)

        assertEquals(
            SharedDraftProjectionPlan.KeepPending(first),
            state.planSharedDraftProjection(sharedDraft.copy(text = "第二个分享")),
        )
    }
}
