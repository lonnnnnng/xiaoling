package com.longdev.xiaoling.ui

import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityDecision
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowBinding
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowBindingReason
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowBindingStatus
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowCandidate
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationMode
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationOutcome
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationOrigin
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationStatus
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserNotice
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityUserState
import com.longdev.xiaoling.knowledge.KnowledgeReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

class AgentAnswerabilityShadowPublisherTest {
    @Test
    fun disabledShadowDoesNotWaitForPersistenceOrCallJudge() = runTest {
        var persistenceAwaited = false
        var judgeCalled = false
        val publisher = AgentAnswerabilityShadowPublisher(
            observe = {
                judgeCalled = true
                error("不应调用 Judge")
            },
            publishNotice = { _, _ -> error("不应发布 notice") },
        )

        publisher.publish(
            request = AgentAnswerabilityShadowPublishRequest(
                persistedMessageId = "message-disabled",
                candidate = candidate(),
                frozenBinding = null,
                origin = KnowledgeAnswerabilityShadowObservationOrigin.DIRECT_FOREGROUND,
            ),
            awaitAnswerPersistence = {
                persistenceAwaited = true
                true
            },
        )

        assertFalse(persistenceAwaited)
        assertFalse(judgeCalled)
    }

    @Test
    fun failedAnswerPersistenceSkipsJudgeAndNotice() = runTest {
        var judgeCalled = false
        val publisher = AgentAnswerabilityShadowPublisher(
            observe = {
                judgeCalled = true
                error("不应调用 Judge")
            },
            publishNotice = { _, _ -> error("不应发布 notice") },
        )

        publisher.publish(
            request = AgentAnswerabilityShadowPublishRequest(
                persistedMessageId = "message-persistence-failed",
                candidate = candidate(),
                frozenBinding = null,
                mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                origin = KnowledgeAnswerabilityShadowObservationOrigin.DIRECT_FOREGROUND,
            ),
            awaitAnswerPersistence = { false },
        )

        assertFalse(judgeCalled)
    }

    @Test
    fun cancellationPropagatesWithoutPublishingNotice() = runTest {
        var noticePublished = false
        val publisher = AgentAnswerabilityShadowPublisher(
            observe = { throw CancellationException("页面已离开") },
            publishNotice = { _, _ -> noticePublished = true },
        )

        try {
            publisher.publish(
                request = AgentAnswerabilityShadowPublishRequest(
                    persistedMessageId = "message-cancelled",
                    candidate = candidate(),
                    frozenBinding = null,
                    mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                    origin = KnowledgeAnswerabilityShadowObservationOrigin.DIRECT_FOREGROUND,
                ),
                awaitAnswerPersistence = { true },
            )
            fail("取消必须继续传播")
        } catch (error: CancellationException) {
            assertEquals("页面已离开", error.message)
        }

        assertFalse(noticePublished)
    }

    @Test
    fun enabledShadowWaitsForPersistedAnswerBeforePublishingNotice() = runTest {
        val events = mutableListOf<String>()
        val candidate = candidate()
        val notice = KnowledgeAnswerabilityUserNotice(
            state = KnowledgeAnswerabilityUserState.DIRECTLY_ANSWERED,
            title = "本地知识包含直接回答",
            detail = "只读观察",
        )
        val publisher = AgentAnswerabilityShadowPublisher(
            observe = { request ->
                events += "observe:${request.persistedMessageId}"
                KnowledgeAnswerabilityShadowObservationOutcome(
                    status = KnowledgeAnswerabilityShadowObservationStatus.COMPLETED,
                    binding = KnowledgeAnswerabilityShadowBinding(
                        candidate = candidate,
                        status = KnowledgeAnswerabilityShadowBindingStatus.BOUND,
                        reason = KnowledgeAnswerabilityShadowBindingReason.BOUND,
                        measurement = null,
                        decision = KnowledgeAnswerabilityDecision.ACCEPT,
                        references = candidate.references,
                        notice = notice,
                        observedAt = 1_234L,
                    ),
                )
            },
            publishNotice = { messageId, _ -> events += "notice:$messageId" },
        )

        publisher.publish(
            request = AgentAnswerabilityShadowPublishRequest(
                persistedMessageId = "message-final-answer",
                candidate = candidate,
                frozenBinding = null,
                mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                origin = KnowledgeAnswerabilityShadowObservationOrigin.DIRECT_FOREGROUND,
            ),
            awaitAnswerPersistence = {
                events += "persisted"
                true
            },
        )

        assertEquals(
            listOf("persisted", "observe:message-final-answer", "notice:message-final-answer"),
            events,
        )
    }

    @Test
    fun failedJudgeOutcomeDoesNotPublishUnknownNotice() = runTest {
        var noticePublished = false
        val publisher = AgentAnswerabilityShadowPublisher(
            observe = {
                KnowledgeAnswerabilityShadowObservationOutcome(
                    status = KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN,
                    binding = KnowledgeAnswerabilityShadowBinding(
                        candidate = candidate(),
                        status = KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN,
                        reason = KnowledgeAnswerabilityShadowBindingReason.MISSING_MEASUREMENT,
                        measurement = null,
                        decision = KnowledgeAnswerabilityDecision.UNKNOWN,
                        references = candidate().references,
                        notice = KnowledgeAnswerabilityUserNotice(
                            state = KnowledgeAnswerabilityUserState.UNKNOWN,
                            title = "尚未确认本地知识是否直接回答",
                            detail = "本次 Judge 失败",
                        ),
                        observedAt = null,
                    ),
                )
            },
            publishNotice = { _, _ -> noticePublished = true },
        )

        publisher.publish(
            request = AgentAnswerabilityShadowPublishRequest(
                persistedMessageId = "message-judge-failed",
                candidate = candidate(),
                frozenBinding = null,
                mode = KnowledgeAnswerabilityShadowObservationMode.SHADOW,
                origin = KnowledgeAnswerabilityShadowObservationOrigin.DIRECT_FOREGROUND,
            ),
            awaitAnswerPersistence = { true },
        )

        assertFalse(noticePublished)
    }

    private fun candidate() = KnowledgeAnswerabilityShadowCandidate(
        sourceRunId = "run-answerability-shadow",
        question = "项目采用什么备份策略？",
        candidateText = "采用三份副本、两种介质和一份异地保存。",
        references = listOf(
            KnowledgeReference(
                retrievalId = "retrieval-answerability-shadow",
                documentId = "document-backup",
                documentName = "备份规范.md",
                documentRevision = 1,
                chunkId = "chunk-backup",
                chunkSequence = 0,
                startOffset = 0,
                endOffset = 20,
            ),
        ),
    )
}
