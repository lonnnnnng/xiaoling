package com.longdev.xiaoling.agent

import com.longdev.xiaoling.device.DeviceSnapshot

internal data class DeviceTypeTextSnapshotPrivacyProjection(
    val textSha256: String,
    val textLength: Int,
) {
    init {
        require(textSha256.matches(SHA256_PATTERN)) { "设备文本隐私投影指纹无效" }
        require(textLength > 0) { "设备文本隐私投影长度必须大于 0" }
    }

    // long: 下一次前台 snapshot 的工具输出隐藏与输入完全匹配的显示字段；Controller 原始快照仍供精确回读和引用校验，节点身份、ref 与动作不能被投影改变。
    fun redact(snapshot: DeviceSnapshot): DeviceSnapshot = snapshot.copy(
        windowTitle = redactMatchingText(snapshot.windowTitle),
        nodes = snapshot.nodes.map { node ->
            node.copy(
                text = redactMatchingText(node.text),
                description = redactMatchingText(node.description),
                hint = redactMatchingText(node.hint),
            )
        },
    )

    private fun redactMatchingText(text: String?): String? = text?.takeUnless {
        it.length == textLength && DeviceTypeTextAuditPolicy.fingerprint(it) == textSha256
    }

    companion object {
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")

        fun fromText(text: String): DeviceTypeTextSnapshotPrivacyProjection {
            require(text.isNotEmpty()) { "设备文本隐私投影不能来自空文本" }
            return DeviceTypeTextSnapshotPrivacyProjection(
                textSha256 = DeviceTypeTextAuditPolicy.fingerprint(text),
                textLength = text.length,
            )
        }
    }
}
