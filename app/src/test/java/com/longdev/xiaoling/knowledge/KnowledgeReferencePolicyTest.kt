package com.longdev.xiaoling.knowledge

import com.longdev.xiaoling.agent.AgentVerificationStatus
import com.longdev.xiaoling.agent.VerifiedAgentContext
import com.longdev.xiaoling.agent.VerifiedToolExecution
import com.longdev.xiaoling.agent.retainCurrentKnowledgeReferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeReferencePolicyTest {
    @Test
    fun malformedReferencePayloadDoesNotBreakMessageDecode() {
        assertTrue(KnowledgeReferenceCodec.decode("not-json").isEmpty())
        assertTrue(KnowledgeReferenceCodec.decode("[1,{\"documentId\":\"missing-fields\"}]").isEmpty())
    }

    @Test
    fun validReferencesSurviveAlongsideMalformedEntries() {
        val valid = reference()
        val array = KnowledgeReferenceCodec.encode(listOf(valid)).apply {
            put(JSONObject().put("documentId", "broken"))
        }

        assertEquals(listOf(valid), KnowledgeReferenceCodec.decode(array))
    }

    @Test
    fun staleKnowledgeExecutionIsRemovedFromModelProjection() {
        val stale = reference()
        val context = context(
            toolExecutions = listOf(
                VerifiedToolExecution(
                    toolName = "knowledge.search",
                    arguments = mapOf("query" to "旧资料"),
                    success = true,
                    verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                    rawResult = "旧资料正文不应再次发送",
                    knowledgeReferences = listOf(stale),
                ),
            ),
            knowledgeReferences = listOf(stale),
        )

        assertNull(context.retainCurrentKnowledgeReferences(emptySet()))
    }

    @Test
    fun knowledgeResultWithMissingReferencesAlwaysFailsClosed() {
        val missingEvidence = context(
            toolExecutions = listOf(
                VerifiedToolExecution(
                    toolName = "knowledge.search",
                    arguments = mapOf("query" to "损坏引用"),
                    success = true,
                    verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                    rawResult = "本地知识检索结果：\n- 这段正文已经失去引用",
                ),
            ),
            knowledgeReferences = emptyList(),
        )
        assertNull(missingEvidence.retainCurrentKnowledgeReferences(emptySet()))
    }

    @Test
    fun validKnowledgeExecutionRemainsAuditable() {
        val current = reference()
        val projected = context(
            toolExecutions = listOf(
                VerifiedToolExecution(
                    toolName = "knowledge.search",
                    arguments = mapOf("query" to "当前资料"),
                    success = true,
                    verificationStatus = AgentVerificationStatus.READABLE_ONLY,
                    rawResult = "当前资料正文",
                    knowledgeReferences = listOf(current),
                ),
            ),
            knowledgeReferences = listOf(current),
        ).retainCurrentKnowledgeReferences(setOf(current))

        assertEquals(listOf(current), projected?.knowledgeReferences)
        assertEquals("当前资料正文", projected?.rawResult)
    }

    private fun context(
        toolExecutions: List<VerifiedToolExecution>,
        knowledgeReferences: List<KnowledgeReference>,
    ) = VerifiedAgentContext(
        runId = "run-knowledge-policy",
        toolName = toolExecutions.last().toolName,
        arguments = toolExecutions.last().arguments,
        success = true,
        verificationStatus = AgentVerificationStatus.READABLE_ONLY,
        rawResult = toolExecutions.last().rawResult,
        knowledgeReferences = knowledgeReferences,
        toolExecutions = toolExecutions,
    )

    private fun reference() = KnowledgeReference(
        retrievalId = "retrieval-1",
        documentId = "document-1",
        documentName = "资料.md",
        documentRevision = 2,
        chunkId = "chunk-1-r2-0-hash",
        chunkSequence = 0,
        startOffset = 0,
        endOffset = 12,
    )
}
