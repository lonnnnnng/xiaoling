package com.longdev.xiaoling.ui

import com.longdev.xiaoling.share.SharedDraftPayload

internal sealed interface SharedDraftProjectionPlan {
    data class OpenNow(val payload: SharedDraftPayload) : SharedDraftProjectionPlan
    data class ConfirmReplacement(val payload: SharedDraftPayload) : SharedDraftProjectionPlan
    data class KeepPending(val payload: SharedDraftPayload) : SharedDraftProjectionPlan
}

internal fun XiaoLingUiState.planSharedDraftProjection(
    payload: SharedDraftPayload,
): SharedDraftProjectionPlan {
    pendingSharedDraft?.let { return SharedDraftProjectionPlan.KeepPending(it) }
    val hasUnsentContent = prompt.isNotBlank() ||
        pendingImage != null ||
        pendingDocument != null ||
        attachingImage ||
        attachingDocument ||
        sendingMessage ||
        loadingConversationMessages
    // long: 外部分享不能静默覆盖输入框或附件；只要编辑器并非稳定空闲状态，就把替换决定留给用户。
    return if (hasUnsentContent) {
        SharedDraftProjectionPlan.ConfirmReplacement(payload)
    } else {
        SharedDraftProjectionPlan.OpenNow(payload)
    }
}
