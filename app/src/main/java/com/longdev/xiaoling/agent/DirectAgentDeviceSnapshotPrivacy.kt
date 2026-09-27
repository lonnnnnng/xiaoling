package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceSnapshot

internal data class DirectAgentDeviceSnapshotPrivacyProjection(
    val textSha256: String,
    val textLength: Int,
) {
    init {
        require(textSha256.matches(SHA256_PATTERN)) { "设备文本隐私投影指纹无效" }
        require(textLength > 0) { "设备文本隐私投影长度必须大于 0" }
    }

    // long: 只在下一次直接 Agent snapshot 的 ToolResult 边界隐藏命中的节点文本；不改节点身份、ref 或动作，保证 Controller 仍可用内存回读完成精确验证。
    fun redact(snapshot: DeviceSnapshot): DeviceSnapshot = snapshot.copy(
        nodes = snapshot.nodes.map { node ->
            val text = node.text
            if (text != null && text.length == textLength && DeviceTypeTextAuditPolicy.fingerprint(text) == textSha256) {
                node.copy(text = null)
            } else {
                node
            }
        },
    )

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun fromText(text: String): DirectAgentDeviceSnapshotPrivacyProjection {
            require(text.isNotEmpty()) { "设备文本隐私投影不能来自空文本" }
            return DirectAgentDeviceSnapshotPrivacyProjection(
                textSha256 = DeviceTypeTextAuditPolicy.fingerprint(text),
                textLength = text.length,
            )
        }
    }
}
