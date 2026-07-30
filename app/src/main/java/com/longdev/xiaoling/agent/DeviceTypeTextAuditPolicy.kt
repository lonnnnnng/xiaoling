package com.longdev.xiaoling.agent

import java.security.MessageDigest

internal data class DeviceTypeTextAuditProjection(
    val persistedToolCall: ToolCall,
    val textLength: Int,
)

internal object DeviceTypeTextAuditPolicy {
    const val TOOL_NAME = "device.type_text"

    fun project(toolCall: ToolCall): DeviceTypeTextAuditProjection? {
        if (toolCall.name != TOOL_NAME || toolCall.arguments.keys != REQUIRED_ARGUMENT_NAMES) return null
        val snapshotId = toolCall.arguments[SNAPSHOT_ARGUMENT_NAME]?.takeIf(String::isNotBlank) ?: return null
        val ref = toolCall.arguments[REFERENCE_ARGUMENT_NAME]?.takeIf(String::isNotBlank) ?: return null
        val text = toolCall.arguments[TEXT_ARGUMENT_NAME] ?: return null
        // long: 文本原文只供当前执行链完成用户确认、设备输入和精确回读；所有持久审计统一投影为目标引用、指纹和长度，避免 Runtime、审批与 Tool Ledger 各自脱敏后出现旁路。
        return DeviceTypeTextAuditProjection(
            persistedToolCall = toolCall.copy(
                arguments = mapOf(
                    SNAPSHOT_ARGUMENT_NAME to snapshotId,
                    REFERENCE_ARGUMENT_NAME to ref,
                    TEXT_FINGERPRINT_ARGUMENT_NAME to text.sha256(),
                    TEXT_LENGTH_ARGUMENT_NAME to text.length.toString(),
                ),
            ),
            textLength = text.length,
        )
    }

    fun toolCallForPersistence(toolCall: ToolCall): ToolCall {
        if (toolCall.name != TOOL_NAME) return toolCall
        project(toolCall)?.let { return it.persistedToolCall }
        require(toolCall.isPersistedProjection()) { "设备文本输入审计参数不完整" }
        return toolCall
    }

    private fun ToolCall.isPersistedProjection(): Boolean {
        if (arguments.keys != PERSISTED_ARGUMENT_NAMES) return false
        if (arguments[SNAPSHOT_ARGUMENT_NAME].isNullOrBlank()) return false
        if (arguments[REFERENCE_ARGUMENT_NAME].isNullOrBlank()) return false
        if (!arguments[TEXT_FINGERPRINT_ARGUMENT_NAME].orEmpty().matches(LOWERCASE_SHA256)) return false
        return arguments[TEXT_LENGTH_ARGUMENT_NAME]?.toIntOrNull()?.let { it > 0 } == true
    }

    private fun String.sha256(): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private const val SNAPSHOT_ARGUMENT_NAME = "snapshot_id"
    private const val REFERENCE_ARGUMENT_NAME = "ref"
    private const val TEXT_ARGUMENT_NAME = "text"
    private const val TEXT_FINGERPRINT_ARGUMENT_NAME = "text_sha256"
    private const val TEXT_LENGTH_ARGUMENT_NAME = "text_length"
    private val REQUIRED_ARGUMENT_NAMES = setOf(
        SNAPSHOT_ARGUMENT_NAME,
        REFERENCE_ARGUMENT_NAME,
        TEXT_ARGUMENT_NAME,
    )
    private val PERSISTED_ARGUMENT_NAMES = setOf(
        SNAPSHOT_ARGUMENT_NAME,
        REFERENCE_ARGUMENT_NAME,
        TEXT_FINGERPRINT_ARGUMENT_NAME,
        TEXT_LENGTH_ARGUMENT_NAME,
    )
    private val LOWERCASE_SHA256 = Regex("[0-9a-f]{64}")
}
