package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeReferenceStatusPolicyTest {
    @Test
    fun exactEnabledDocumentAndChunkAreCurrent() {
        val status = reference().assessAgainst(
            document = documentSummary(),
            chunk = chunk(),
        )

        assertEquals(KnowledgeReferenceAvailability.CURRENT, status.availability)
        assertEquals(KnowledgeReferenceIssue.NONE, status.issue)
        assertTrue(status.canOpenDocument)
        assertEquals(2, status.currentDocumentRevision)
    }

    @Test
    fun newerDocumentRevisionMarksReferenceAsHistorical() {
        val status = reference().assessAgainst(
            document = documentSummary(revision = 3, displayName = "运行手册-v3.md"),
            chunk = null,
        )

        assertEquals(KnowledgeReferenceAvailability.HISTORICAL, status.availability)
        assertEquals(KnowledgeReferenceIssue.DOCUMENT_REPLACED, status.issue)
        assertEquals("运行手册-v3.md", status.currentDocumentName)
        assertEquals(3, status.currentDocumentRevision)
        assertTrue(status.canOpenDocument)
    }

    @Test
    fun disabledCurrentDocumentMarksReferenceAsUnavailableButStillOpenable() {
        val status = reference().assessAgainst(
            document = documentSummary(enabled = false),
            chunk = chunk(),
        )

        assertEquals(KnowledgeReferenceAvailability.UNAVAILABLE, status.availability)
        assertEquals(KnowledgeReferenceIssue.DOCUMENT_DISABLED, status.issue)
        assertTrue(status.canOpenDocument)
    }

    @Test
    fun disabledNewerDocumentTakesPriorityOverHistoricalRevision() {
        val status = reference().assessAgainst(
            document = documentSummary(
                revision = 3,
                displayName = "运行手册-v3.md",
                enabled = false,
            ),
            chunk = null,
        )

        assertEquals(KnowledgeReferenceAvailability.UNAVAILABLE, status.availability)
        assertEquals(KnowledgeReferenceIssue.DOCUMENT_DISABLED, status.issue)
        assertEquals(3, status.currentDocumentRevision)
        assertTrue(status.canOpenDocument)
    }

    @Test
    fun deletedDocumentMarksReferenceAsUnavailableAndNotOpenable() {
        val status = reference().assessAgainst(document = null, chunk = null)

        assertEquals(KnowledgeReferenceAvailability.UNAVAILABLE, status.availability)
        assertEquals(KnowledgeReferenceIssue.DOCUMENT_DELETED, status.issue)
        assertFalse(status.canOpenDocument)
    }

    @Test
    fun changedChunkBoundaryCannotBePresentedAsCurrent() {
        val status = reference().assessAgainst(
            document = documentSummary(),
            chunk = chunk().copy(endOffset = 43),
        )

        assertEquals(KnowledgeReferenceAvailability.UNAVAILABLE, status.availability)
        assertEquals(KnowledgeReferenceIssue.EVIDENCE_CHANGED, status.issue)
        assertTrue(status.canOpenDocument)
    }

    private fun reference() = KnowledgeReference(
        retrievalId = "retrieval-status",
        documentId = "document-status",
        documentName = "运行手册.md",
        documentRevision = 2,
        chunkId = "chunk-status-r2-1",
        chunkSequence = 1,
        startOffset = 20,
        endOffset = 44,
    )

    private fun documentSummary(
        revision: Int = 2,
        displayName: String = "运行手册.md",
        enabled: Boolean = true,
    ) = KnowledgeDocumentSummary(
        id = "document-status",
        displayName = displayName,
        mimeType = "text/markdown",
        contentHash = "hash-status",
        revision = revision,
        parserVersion = 1,
        byteSize = 128,
        characterCount = 64,
        enabled = enabled,
        createdAt = 1L,
        updatedAt = 2L,
        chunkCount = 2,
    )

    private fun chunk() = KnowledgeChunkRecord(
        id = "chunk-status-r2-1",
        documentId = "document-status",
        documentRevision = 2,
        sequence = 1,
        startOffset = 20,
        endOffset = 44,
        text = "发布前只使用 Redmi 真机。",
    )
}
