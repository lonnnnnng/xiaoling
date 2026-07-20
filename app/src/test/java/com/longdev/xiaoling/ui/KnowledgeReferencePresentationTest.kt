package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceAvailability
import com.longdev.xiaoling.knowledge.KnowledgeReferenceIssue
import com.longdev.xiaoling.knowledge.KnowledgeReferenceStatus
import com.longdev.xiaoling.model.MessageOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeReferencePresentationTest {
    @Test
    fun agentMessageProjectsReferencesFromVerifiedContext() {
        val reference = reference()
        val message = ChatMessage(
            id = "message-knowledge-presentation",
            role = "assistant",
            text = "答案正文",
            origin = MessageOrigin.AGENT_RESULT,
            verifiedAgentContext = VerifiedAgentContext(
                runId = "run-knowledge-presentation",
                toolName = "knowledge.search",
                arguments = mapOf("query" to "验收"),
                success = true,
                verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                rawResult = "检索结果",
                knowledgeReferences = listOf(reference),
            ),
        )

        assertEquals(listOf(reference), message.knowledgeReferencesForDisplay())
    }

    @Test
    fun ordinaryAssistantTextCannotForgeKnowledgeReference() {
        val message = ChatMessage(
            role = "assistant",
            text = "引用 handbook.md revision=9 chunk=4 offset=0-100",
            origin = MessageOrigin.ORDINARY_ASSISTANT,
        )

        assertTrue(message.knowledgeReferencesForDisplay().isEmpty())
    }

    @Test
    fun currentAndHistoricalStatusesUseDistinctLabels() {
        val reference = reference()
        val current = status(
            reference = reference,
            availability = KnowledgeReferenceAvailability.CURRENT,
            issue = KnowledgeReferenceIssue.NONE,
            currentRevision = 2,
        ).toPresentation()
        val historical = status(
            reference = reference,
            availability = KnowledgeReferenceAvailability.HISTORICAL,
            issue = KnowledgeReferenceIssue.DOCUMENT_REPLACED,
            currentRevision = 3,
            currentName = "handbook-v3.md",
        ).toPresentation()

        assertEquals("当前有效", current.statusLabel)
        assertEquals("revision 2 · chunk 1 · offset [20, 44)", current.locationLabel)
        assertTrue(current.canOpenDocument)
        assertEquals("历史版本", historical.statusLabel)
        assertEquals("当前为 revision 3 · handbook-v3.md", historical.statusDetail)
        assertTrue(historical.canOpenDocument)
    }

    @Test
    fun deletedReferenceIsClearlyUnavailableAndCannotNavigate() {
        val presentation = status(
            reference = reference(),
            availability = KnowledgeReferenceAvailability.UNAVAILABLE,
            issue = KnowledgeReferenceIssue.DOCUMENT_DELETED,
            currentRevision = null,
            currentName = null,
        ).toPresentation()

        assertEquals("当前不可用", presentation.statusLabel)
        assertEquals("文档已删除", presentation.statusDetail)
        assertFalse(presentation.canOpenDocument)
    }

    @Test
    fun failedStatusCheckIsNotLeftAsLoadingOrReportedAsUnavailable() {
        val presentation = reference().toFailedStatusPresentation()

        assertEquals("暂无法核验", presentation.statusLabel)
        assertEquals("引用状态读取失败，请稍后重试", presentation.statusDetail)
        assertFalse(presentation.canOpenDocument)
    }

    private fun status(
        reference: KnowledgeReference,
        availability: KnowledgeReferenceAvailability,
        issue: KnowledgeReferenceIssue,
        currentRevision: Int?,
        currentName: String? = reference.documentName,
    ) = KnowledgeReferenceStatus(
        reference = reference,
        availability = availability,
        issue = issue,
        currentDocumentName = currentName,
        currentDocumentRevision = currentRevision,
        currentDocumentEnabled = currentRevision?.let { true },
    )

    private fun reference() = KnowledgeReference(
        retrievalId = "retrieval-presentation",
        documentId = "document-presentation",
        documentName = "handbook.md",
        documentRevision = 2,
        chunkId = "chunk-presentation-r2-1",
        chunkSequence = 1,
        startOffset = 20,
        endOffset = 44,
    )
}
