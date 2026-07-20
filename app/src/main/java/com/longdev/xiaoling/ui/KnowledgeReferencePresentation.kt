package com.longdev.xiaoling.ui

import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceAvailability
import com.longdev.xiaoling.knowledge.KnowledgeReferenceIssue
import com.longdev.xiaoling.knowledge.KnowledgeReferenceStatus
import com.longdev.xiaoling.model.MessagePart

data class KnowledgeReferencePresentation(
    val reference: KnowledgeReference,
    val documentName: String,
    val locationLabel: String,
    val statusLabel: String,
    val statusDetail: String?,
    val availability: KnowledgeReferenceAvailability?,
    val canOpenDocument: Boolean,
)

internal fun ChatMessage.knowledgeReferencesForDisplay(): List<KnowledgeReference> {
    // long: `effectiveParts()` 已执行 Agent 来源和可信上下文校验；这里不扫描答案正文，避免模型伪造 document/revision/chunk 字样形成假引用。
    return effectiveParts()
        .filterIsInstance<MessagePart.Tool>()
        .flatMap { it.knowledgeReferences }
        .distinct()
}

fun KnowledgeReferenceStatus.toPresentation(): KnowledgeReferencePresentation {
    val detail = when (issue) {
        KnowledgeReferenceIssue.NONE -> null
        KnowledgeReferenceIssue.DOCUMENT_REPLACED -> buildString {
            append("当前为 revision ${currentDocumentRevision ?: "?"}")
            currentDocumentName?.let { append(" · $it") }
            if (currentDocumentEnabled == false) append(" · 已停用")
        }
        KnowledgeReferenceIssue.DOCUMENT_DISABLED -> "文档已停用"
        KnowledgeReferenceIssue.DOCUMENT_DELETED -> "文档已删除"
        KnowledgeReferenceIssue.EVIDENCE_CHANGED -> "引用证据已变化"
    }
    return KnowledgeReferencePresentation(
        reference = reference,
        documentName = reference.documentName,
        locationLabel = reference.locationLabel(),
        statusLabel = when (availability) {
            KnowledgeReferenceAvailability.CURRENT -> "当前有效"
            KnowledgeReferenceAvailability.HISTORICAL -> "历史版本"
            KnowledgeReferenceAvailability.UNAVAILABLE -> "当前不可用"
        },
        statusDetail = detail,
        availability = availability,
        canOpenDocument = canOpenDocument,
    )
}

fun KnowledgeReference.toPendingPresentation(): KnowledgeReferencePresentation {
    return KnowledgeReferencePresentation(
        reference = this,
        documentName = documentName,
        locationLabel = locationLabel(),
        statusLabel = "正在核验",
        statusDetail = null,
        availability = null,
        canOpenDocument = false,
    )
}

fun KnowledgeReference.toFailedStatusPresentation(): KnowledgeReferencePresentation {
    return KnowledgeReferencePresentation(
        reference = this,
        documentName = documentName,
        locationLabel = locationLabel(),
        statusLabel = "暂无法核验",
        statusDetail = "引用状态读取失败，请稍后重试",
        availability = null,
        canOpenDocument = false,
    )
}

private fun KnowledgeReference.locationLabel(): String {
    return "revision $documentRevision · chunk $chunkSequence · offset [$startOffset, $endOffset)"
}

internal fun List<KnowledgeReference>.toKnowledgeAuditText(): String? {
    if (isEmpty()) return null
    return joinToString("\n") { reference ->
        "${reference.retrievalId} · ${reference.documentName} (${reference.documentId}) · revision=${reference.documentRevision} · " +
            "chunk=${reference.chunkSequence} (${reference.chunkId}) · offset=${reference.startOffset}-${reference.endOffset}"
    }
}
