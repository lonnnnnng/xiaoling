package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceSnapshot

internal object DeviceTypeTextSnapshotPrivacyProjection {
    // long: 页面可能拼接、规范化或截断输入，哈希完全匹配无法识别这些回显；输入尝试后的本 Run 持续省略显示文字，原始 Controller 数据仍用于精确回读和引用校验。
    fun redact(snapshot: DeviceSnapshot): DeviceSnapshot = snapshot.copy(
        windowTitle = null,
        nodes = snapshot.nodes.map { node ->
            node.copy(
                text = null,
                description = null,
                hint = null,
            )
        },
    )

    fun wasAttempted(detail: AgentRunDetailRecord): Boolean = detail.snapshot.events.any { event ->
        (event.metadata as? RunEventMetadata.ToolCall)?.toolName == DeviceTypeTextAuditPolicy.TOOL_NAME
    }
}
