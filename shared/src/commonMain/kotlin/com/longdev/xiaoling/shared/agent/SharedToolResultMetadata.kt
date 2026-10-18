package com.longdev.xiaoling.shared.agent

/** long: 引用冻结检索时的文档版本及片段范围；跨平台传递不能把旧片段换成当前文档或只留下名称。 */
data class SharedKnowledgeReference(
    val retrievalId: String,
    val documentId: String,
    val documentName: String,
    val documentRevision: Int,
    val chunkId: String,
    val chunkSequence: Int,
    val startOffset: Int,
    val endOffset: Int,
) {
    init {
        require(retrievalId.isNotBlank()) { "知识检索引用缺少 retrieval ID" }
        require(documentId.isNotBlank()) { "知识检索引用缺少 document ID" }
        require(documentName.isNotBlank()) { "知识检索引用缺少文档名称" }
        require(documentRevision > 0) { "知识检索引用的文档 revision 必须大于 0" }
        require(chunkId.isNotBlank()) { "知识检索引用缺少 chunk ID" }
        require(chunkSequence >= 0) { "知识检索引用的 chunk sequence 不能小于 0" }
        require(startOffset >= 0 && endOffset > startOffset) { "知识检索引用的 offset 范围无效" }
    }
}

/** long: 恢复失败保留结构化原因及下一步动作，不能靠传输成功把“重新观察/新 Run”改成可重放。 */
data class SharedToolRecoveryFailure(
    val code: String,
    val reason: String,
    val suggestedAction: String,
) {
    init {
        require(code.isNotBlank()) { "恢复失败码不能为空" }
        require(reason.isNotBlank()) { "恢复失败原因不能为空" }
        require(suggestedAction.isNotBlank()) { "恢复失败建议不能为空" }
    }
}

/** long: 外层调用身份与每份证据必须一致；旧 typed 验证缺少身份时仍保留 null，不能猜造绑定。 */
data class SharedToolResultFrame(
    val toolCallId: String,
    val result: SharedToolExecutionResult,
) {
    init {
        require(toolCallId.isNotBlank()) { "结果帧的工具调用 ID 不能为空" }
        require(result.executionReceipt == null || result.executionReceipt.toolCallId == toolCallId)
        require(result.verification?.toolCallId == null || result.verification.toolCallId == toolCallId)
        require(result.readableEvidence == null || result.readableEvidence.toolCallId == toolCallId)
    }
}
