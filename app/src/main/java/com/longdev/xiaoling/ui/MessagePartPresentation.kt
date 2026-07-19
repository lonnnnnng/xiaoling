package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus

data class ToolMessagePartPresentation(
    val toolName: String,
    val statusLabel: String,
    val argumentsLabel: String?,
    val result: String,
    val memoryLabel: String?,
)

fun MessagePart.Tool.toPresentation(): ToolMessagePartPresentation {
    val status = when {
        !success -> "执行失败"
        verificationStatus == MessageToolVerificationStatus.VERIFIED -> "已验证"
        verificationStatus == MessageToolVerificationStatus.FAILED -> "验证失败"
        else -> "结果可读"
    }
    val argumentsLabel = arguments.toSortedMap()
        .entries
        .joinToString(" · ") { (key, value) -> "$key=$value" }
        .ifBlank { null }
    val memoryCount = memoryIdsUsed.distinct().size
    return ToolMessagePartPresentation(
        toolName = toolName,
        statusLabel = status,
        argumentsLabel = argumentsLabel,
        result = result,
        memoryLabel = memoryCount.takeIf { it > 0 }?.let { "引用记忆 $it 条" },
    )
}
