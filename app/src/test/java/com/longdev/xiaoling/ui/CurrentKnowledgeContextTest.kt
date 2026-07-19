package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.model.MessageOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentKnowledgeContextTest {
    @Test
    fun staleKnowledgeAgentMessageIsExcludedAndInvalidatesStoredSummary() {
        val reference = reference()
        val ordinary = ChatMessage(role = "user", text = "继续", origin = MessageOrigin.USER)
        val knowledge = ChatMessage(
            role = "assistant",
            text = "旧知识正文",
            origin = MessageOrigin.AGENT_RESULT,
            verifiedAgentContext = context(reference),
        )

        val projected = listOf(knowledge, ordinary).projectCurrentKnowledgeContext(emptySet())

        assertEquals(listOf(ordinary), projected.messages)
        assertTrue(projected.removedStaleKnowledgeMessage)
    }

    @Test
    fun currentKnowledgeAgentMessageRemainsInContext() {
        val reference = reference()
        val knowledge = ChatMessage(
            role = "assistant",
            text = "当前知识正文",
            origin = MessageOrigin.AGENT_RESULT,
            verifiedAgentContext = context(reference),
        )

        val projected = listOf(knowledge).projectCurrentKnowledgeContext(setOf(reference))

        assertEquals(listOf(knowledge), projected.messages)
        assertFalse(projected.removedStaleKnowledgeMessage)
    }

    @Test
    fun knowledgeMessageWithLostReferenceJsonIsExcluded() {
        val knowledge = ChatMessage(
            role = "assistant",
            text = "无法证明来源的知识正文",
            origin = MessageOrigin.AGENT_RESULT,
            verifiedAgentContext = context(reference()).copy(knowledgeReferences = emptyList()),
        )

        val projected = listOf(knowledge).projectCurrentKnowledgeContext(emptySet())

        assertTrue(projected.messages.isEmpty())
        assertTrue(projected.removedStaleKnowledgeMessage)
    }

    private fun context(reference: KnowledgeReference) = VerifiedAgentContext(
        runId = "run-current-context",
        toolName = "knowledge.search",
        arguments = mapOf("query" to "资料"),
        success = true,
        verificationStatus = AgentVerificationStatus.READABLE_ONLY,
        rawResult = "知识正文",
        knowledgeReferences = listOf(reference),
    )

    private fun reference() = KnowledgeReference(
        retrievalId = "retrieval-context",
        documentId = "document-context",
        documentName = "上下文.md",
        documentRevision = 1,
        chunkId = "chunk-context-r1-0",
        chunkSequence = 0,
        startOffset = 0,
        endOffset = 8,
    )
}
