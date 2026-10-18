package com.longdev.xiaoling.shared.agent

enum class SharedBrowserDomAction { NAVIGATE_LINK, TYPE_DRAFT }

data class SharedBrowserActionReceipt(
    val actionId: String,
    val toolCallId: String,
    val action: SharedBrowserDomAction,
    val inputSnapshotId: String,
    val outputSnapshotId: String,
    val ref: String,
    val sessionId: String,
    val inputContentHash: String,
) {
    init {
        require(actionId.matches(Regex("[a-f0-9]{32}")) && sessionId.matches(Regex("[a-f0-9]{16}")))
        require(inputSnapshotId.matches(Regex("[a-f0-9]{16}")) && outputSnapshotId.matches(Regex("[a-f0-9]{16}")) && inputSnapshotId != outputSnapshotId)
        require(toolCallId.length <= 256 && ref.length in 1..128 && inputContentHash.matches(Regex("[a-f0-9]{64}")))
    }
}

enum class SharedToolReadableEvidenceKind { BROWSER_PAGE }

/** long: 页面和动作描述已读取的版本及本机变化；这些字段不是审批许可，也不构成可恢复的业务提交回执。 */
data class SharedToolReadableEvidence(
    val kind: SharedToolReadableEvidenceKind,
    val toolCallId: String,
    val snapshotId: String,
    val contentHash: String,
    val sourceRef: String,
    val browserAction: SharedBrowserActionReceipt? = null,
) {
    init {
        require(toolCallId.isNotBlank()) { "只读证据的工具调用 ID 不能为空" }
        require(snapshotId.length in 1..64) { "只读证据的快照引用长度无效" }
        require(contentHash.matches(Regex("[0-9a-f]{64}"))) { "只读证据的内容指纹必须是 64 位小写 SHA-256" }
        require(sourceRef.length in 1..512) { "只读证据的来源引用长度无效" }
        require('?' !in sourceRef && '#' !in sourceRef && '@' !in sourceRef) {
            "只读证据的来源引用不能包含查询参数、片段或账号信息"
        }
        // long: 同一动作必须归属于页面调用及输出快照，跨调用的证据不能通过重新编码变成本次事实。
        require(browserAction == null || (browserAction.toolCallId == toolCallId && browserAction.outputSnapshotId == snapshotId)) {
            "浏览器动作证据与页面调用不一致"
        }
    }
}
