package com.longdev.xiaoling.ui

import com.longdev.xiaoling.share.SharedDraftPayload

internal sealed interface SharedDraftProjectionPlan {
    data class OpenNow(val payload: SharedDraftPayload) : SharedDraftProjectionPlan
    data class ConfirmReplacement(val payload: SharedDraftPayload) : SharedDraftProjectionPlan
    data class KeepPending(val payload: SharedDraftPayload) : SharedDraftProjectionPlan
}

internal fun XiaoLingUiState.planSharedDraftProjection(
    payload: SharedDraftPayload,
    requiresForegroundConfirmation: Boolean = false,
): SharedDraftProjectionPlan {
    pendingSharedDraft?.let { return SharedDraftProjectionPlan.KeepPending(it) }
    val hasUnsentContent = prompt.isNotBlank() ||
        pendingImage != null ||
        pendingDocument != null ||
        attachingImage ||
        attachingDocument ||
        sendingMessage ||
        loadingConversationMessages
    // long: 外部草稿不能静默覆盖输入框或附件；远程消息即使编辑器空闲也要由用户决定是否打开。
    return if (hasUnsentContent || requiresForegroundConfirmation) {
        SharedDraftProjectionPlan.ConfirmReplacement(payload)
    } else {
        SharedDraftProjectionPlan.OpenNow(payload)
    }
}
