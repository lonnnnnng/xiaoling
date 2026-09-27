package com.longdev.xiaoling.ui

/**
 * long: 当前事实只保存重新观察后的摘要，绝不把 snapshot id、ref、节点正文或输入原文带入会话 UI 状态。
 */
data class DirectAgentCurrentFactUiState(
    val runId: String,
    val packageName: String,
    val nodeCount: Int,
    val redactedNodeCount: Int,
    val truncated: Boolean,
    val capturedAt: Long,
)
