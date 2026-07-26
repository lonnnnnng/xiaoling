package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentMemoryCandidateRecord
import com.longdev.xiaoling.agent.AgentMemoryCandidateStatus
import com.longdev.xiaoling.agent.AgentMemorySensitiveCategory
import com.longdev.xiaoling.agent.AgentMemorySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryCandidateCoordinatorTest {
    @Test
    fun loadReturnsCandidatesThroughTheConfiguredBoundedQuery() = runTest {
        var requestedLimit = 0
        val candidate = candidate(id = "candidate-1")
        val coordinator = coordinator(
            candidateLimit = 25,
            listCandidates = { limit ->
                requestedLimit = limit
                listOf(candidate)
            },
        )

        val outcome = coordinator.load()

        assertEquals(25, requestedLimit)
        assertEquals(AgentMemoryCandidateLoadOutcome.Loaded(listOf(candidate)), outcome)
    }

    @Test
    fun captureBuildsStableSourceIdentityForConversationAndAgentTurns() = runTest {
        val sources = mutableListOf<AgentMemorySource>()
        val coordinator = coordinator(
            createCandidate = { _, source ->
                sources += source
                candidate(id = "candidate-${sources.size}", source = source)
            },
        )

        val conversationOutcome = coordinator.capture(
            AgentMemoryCandidateTurn(
                userText = "我喜欢紧凑界面",
                conversationId = "conversation-1",
                runId = null,
            ),
        )
        val agentOutcome = coordinator.capture(
            AgentMemoryCandidateTurn(
                userText = "请记住我的回答格式",
                conversationId = "conversation-1",
                runId = "run-1",
            ),
        )

        assertTrue(conversationOutcome is AgentMemoryCandidateCaptureOutcome.Captured)
        assertTrue(agentOutcome is AgentMemoryCandidateCaptureOutcome.Captured)
        assertEquals(
            listOf(
                AgentMemorySource("conversation-1", null, "普通对话结束后生成的候选"),
                AgentMemorySource("conversation-1", "run-1", "Agent Run 结束后生成的候选"),
            ),
            sources,
        )
    }

    @Test
    fun captureDistinguishesNoCandidateFromStorageFailure() = runTest {
        val ignored = coordinator(createCandidate = { _, _ -> null }).capture(turn())
        val failed = coordinator(createCandidate = { _, _ -> error("Room 写入失败") }).capture(turn())

        assertEquals(AgentMemoryCandidateCaptureOutcome.Ignored, ignored)
        assertEquals(
            AgentMemoryCandidateCaptureOutcome.Failed("Room 写入失败"),
            failed,
        )
    }

    @Test
    fun decisionsRouteToTheMatchingManagerOperationAndKeepMissingExplicit() = runTest {
        val calls = mutableListOf<String>()
        val accepted = candidate(id = "candidate-accept", status = AgentMemoryCandidateStatus.ACCEPTED)
        val coordinator = coordinator(
            acceptCandidate = { id ->
                calls += "accept:$id"
                accepted
            },
            rejectCandidate = { id ->
                calls += "reject:$id"
                null
            },
        )

        val acceptOutcome = coordinator.decide("candidate-accept", AgentMemoryCandidateDecision.ACCEPT)
        val rejectOutcome = coordinator.decide("candidate-missing", AgentMemoryCandidateDecision.REJECT)

        assertEquals(listOf("accept:candidate-accept", "reject:candidate-missing"), calls)
        assertEquals(
            AgentMemoryCandidateDecisionOutcome.Updated(AgentMemoryCandidateDecision.ACCEPT, accepted),
            acceptOutcome,
        )
        assertEquals(
            AgentMemoryCandidateDecisionOutcome.Missing(AgentMemoryCandidateDecision.REJECT, "candidate-missing"),
            rejectOutcome,
        )
    }

    @Test
    fun sameCandidateDecisionReturnsBusyWhileTheFirstDecisionOwnsTheClaim() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var acceptCount = 0
        val accepted = candidate(id = "candidate-1", status = AgentMemoryCandidateStatus.ACCEPTED)
        val coordinator = coordinator(
            acceptCandidate = {
                acceptCount += 1
                started.complete(Unit)
                release.await()
                accepted
            },
        )
        val first = async {
            coordinator.decide("candidate-1", AgentMemoryCandidateDecision.ACCEPT)
        }
        started.await()

        val concurrent = coordinator.decide("candidate-1", AgentMemoryCandidateDecision.REJECT)
        val unrelated = coordinator.decide("candidate-2", AgentMemoryCandidateDecision.REJECT)
        release.complete(Unit)

        assertEquals(AgentMemoryCandidateDecisionOutcome.Busy("candidate-1"), concurrent)
        assertEquals(
            AgentMemoryCandidateDecisionOutcome.Missing(AgentMemoryCandidateDecision.REJECT, "candidate-2"),
            unrelated,
        )
        assertEquals(1, acceptCount)
        assertEquals(
            AgentMemoryCandidateDecisionOutcome.Updated(AgentMemoryCandidateDecision.ACCEPT, accepted),
            first.await(),
        )
    }

    @Test
    fun cancellationPropagatesAndReleasesTheCandidateDecisionClaim() = runTest {
        val cancellation = CancellationException("用户取消候选决定")
        var acceptCount = 0
        val accepted = candidate(id = "candidate-1", status = AgentMemoryCandidateStatus.ACCEPTED)
        val coordinator = coordinator(
            acceptCandidate = {
                acceptCount += 1
                if (acceptCount == 1) {
                    throw cancellation
                }
                accepted
            },
        )
        val thrown = try {
            coordinator.decide("candidate-1", AgentMemoryCandidateDecision.ACCEPT)
            null
        } catch (error: CancellationException) {
            error
        }

        val retry = coordinator.decide("candidate-1", AgentMemoryCandidateDecision.ACCEPT)

        assertSame(cancellation, thrown)
        assertEquals(2, acceptCount)
        assertEquals(
            AgentMemoryCandidateDecisionOutcome.Updated(AgentMemoryCandidateDecision.ACCEPT, accepted),
            retry,
        )
    }

    @Test
    fun externalJobCancellationCannotLeaveTheCandidateDecisionClaimLocked() = runTest {
        val started = CompletableDeferred<Unit>()
        var acceptCount = 0
        val accepted = candidate(id = "candidate-1", status = AgentMemoryCandidateStatus.ACCEPTED)
        val coordinator = coordinator(
            acceptCandidate = {
                acceptCount += 1
                if (acceptCount == 1) {
                    started.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                accepted
            },
        )
        val cancelled = async {
            coordinator.decide("candidate-1", AgentMemoryCandidateDecision.ACCEPT)
        }
        started.await()

        cancelled.cancelAndJoin()
        val retry = coordinator.decide("candidate-1", AgentMemoryCandidateDecision.ACCEPT)

        assertTrue(cancelled.isCancelled)
        assertEquals(2, acceptCount)
        assertEquals(
            AgentMemoryCandidateDecisionOutcome.Updated(AgentMemoryCandidateDecision.ACCEPT, accepted),
            retry,
        )
    }

    private fun coordinator(
        candidateLimit: Int = 100,
        listCandidates: suspend (Int) -> List<AgentMemoryCandidateRecord> = { emptyList() },
        createCandidate: suspend (String, AgentMemorySource) -> AgentMemoryCandidateRecord? = { _, _ -> null },
        acceptCandidate: suspend (String) -> AgentMemoryCandidateRecord? = { null },
        rejectCandidate: suspend (String) -> AgentMemoryCandidateRecord? = { null },
    ) = AgentMemoryCandidateCoordinator(
        candidateLimit = candidateLimit,
        listCandidates = listCandidates,
        createCandidate = createCandidate,
        acceptCandidate = acceptCandidate,
        rejectCandidate = rejectCandidate,
    )

    private fun turn() = AgentMemoryCandidateTurn(
        userText = "我喜欢紧凑界面",
        conversationId = "conversation-1",
        runId = null,
    )

    private fun candidate(
        id: String,
        status: AgentMemoryCandidateStatus = AgentMemoryCandidateStatus.PENDING,
        source: AgentMemorySource = AgentMemorySource("conversation-1", null, "普通对话结束后生成的候选"),
    ) = AgentMemoryCandidateRecord(
        id = id,
        content = "我喜欢紧凑界面",
        normalizedContent = "我喜欢紧凑界面",
        type = "Preference",
        topicKey = "ui",
        sourceConversationId = source.conversationId,
        sourceRunId = source.runId,
        sourceSummary = source.summary,
        confidence = 0.9,
        status = status,
        sensitiveCategory = AgentMemorySensitiveCategory.API_KEY.takeIf {
            status == AgentMemoryCandidateStatus.BLOCKED_SENSITIVE
        },
        relatedMemoryId = null,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
