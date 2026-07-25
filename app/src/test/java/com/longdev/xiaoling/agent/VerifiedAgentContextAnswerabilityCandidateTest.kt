package com.longdev.xiaoling.agent

import com.longdev.xiaoling.knowledge.KnowledgeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifiedAgentContextAnswerabilityCandidateTest {
    @Test
    fun selectsLatestVerifiedKnowledgeSearchAndIgnoresOtherTools() {
        val old = execution("旧片段", reference("old"))
        val latest = execution("最新片段", reference("latest"))
        val context = context(
            executions = listOf(
                execution("普通工具", reference("ignored"), toolName = "notes.search"),
                old,
                latest,
            ),
        )

        val candidate = context.latestKnowledgeAnswerabilityCandidate("用户原问题")

        assertEquals("最新片段", candidate?.candidateText)
        assertEquals("用户原问题", candidate?.question)
        assertEquals(listOf(reference("latest")), candidate?.references)
    }

    @Test
    fun failedOrUnreferencedKnowledgeSearchCannotBecomeJudgeCandidate() {
        val context = context(
            executions = listOf(
                execution("失败", reference("failed"), success = false),
                execution("无引用", null),
            ),
        )

        assertNull(context.latestKnowledgeAnswerabilityCandidate("用户原问题"))
    }

    @Test
    fun legacyTopLevelKnowledgeSearchStillBecomesCandidate() {
        val reference = reference("legacy")
        val context = VerifiedAgentContext(
            runId = "run-legacy",
            toolName = "knowledge.search",
            arguments = mapOf("query" to "旧检索词"),
            success = true,
            verificationStatus = AgentVerificationStatus.READABLE_ONLY,
            rawResult = "旧消息片段",
            knowledgeReferences = listOf(reference),
        )

        val candidate = context.latestKnowledgeAnswerabilityCandidate("旧消息问题")

        assertEquals("run-legacy", candidate?.sourceRunId)
        assertEquals("旧消息片段", candidate?.candidateText)
        assertEquals(listOf(reference), candidate?.references)
    }

    @Test
    fun blankRunIdCannotProduceJudgeCandidate() {
        val context = context(executions = listOf(execution("片段", reference("blank-run"))))
            .copy(runId = " ")

        assertNull(context.latestKnowledgeAnswerabilityCandidate("用户原问题"))
    }

    private fun context(executions: List<VerifiedToolExecution>) = VerifiedAgentContext(
        runId = "run-candidate",
        toolName = executions.last().toolName,
        arguments = executions.last().arguments,
        success = executions.all { it.success },
        verificationStatus = executions.last().verificationStatus,
        rawResult = executions.last().rawResult,
        toolExecutions = executions,
    )

    private fun execution(
        text: String,
        reference: KnowledgeReference?,
        toolName: String = "knowledge.search",
        success: Boolean = true,
    ) = VerifiedToolExecution(
        toolName = toolName,
        arguments = mapOf("query" to "检索词"),
        success = success,
        verificationStatus = if (success) AgentVerificationStatus.READABLE_ONLY else AgentVerificationStatus.FAILED,
        rawResult = text,
        knowledgeReferences = reference?.let(::listOf).orEmpty(),
    )

    private fun reference(id: String) = KnowledgeReference(
        retrievalId = "retrieval-$id",
        documentId = "document-$id",
        documentName = "$id.md",
        documentRevision = 1,
        chunkId = "chunk-$id",
        chunkSequence = 0,
        startOffset = 0,
        endOffset = 12,
    )
}
