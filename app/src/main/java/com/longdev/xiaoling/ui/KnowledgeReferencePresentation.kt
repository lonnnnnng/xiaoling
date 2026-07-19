package com.longdev.xiaoling.ui

import com.longdev.xiaoling.knowledge.KnowledgeReference

internal fun List<KnowledgeReference>.toKnowledgeAuditText(): String? {
    if (isEmpty()) return null
    return joinToString("\n") { reference ->
        "${reference.retrievalId} · ${reference.documentName} (${reference.documentId}) · revision=${reference.documentRevision} · " +
            "chunk=${reference.chunkSequence} (${reference.chunkId}) · offset=${reference.startOffset}-${reference.endOffset}"
    }
}
