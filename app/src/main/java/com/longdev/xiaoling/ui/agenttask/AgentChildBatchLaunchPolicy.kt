package com.longdev.xiaoling.ui.agenttask

import com.longdev.xiaoling.ui.XiaoLingUiState

internal fun XiaoLingUiState.childBatchLaunchProblem(): String? = when {
    sendingMessage || runningWorkflowId != null || loadingConversationMessages || pendingPersonalTaskPlan != null ||
        pendingAgentRetryConfirmation != null -> "请先结束当前任务或确认操作"
    attachingImage || attachingDocument || pendingImage != null || pendingDocument != null -> "只读多 Agent 只接受文本目标，请先移除附件"
    sharedDraftImported || pendingSharedDraft != null -> "请先处理共享或远程草稿，再从本机前台新建目标"
    else -> null
}
