package com.longdev.xiaoling.ui

import com.longdev.xiaoling.agent.AgentMemoryCandidateRecord
import com.longdev.xiaoling.agent.AgentMemorySource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AgentMemoryCandidateTurn(
    val userText: String,
    val conversationId: String,
    val runId: String?,
)

internal sealed interface AgentMemoryCandidateLoadOutcome {
    data class Loaded(
        val candidates: List<AgentMemoryCandidateRecord>,
    ) : AgentMemoryCandidateLoadOutcome

    data class Failed(
        val message: String,
    ) : AgentMemoryCandidateLoadOutcome
}

internal sealed interface AgentMemoryCandidateCaptureOutcome {
    data object Ignored : AgentMemoryCandidateCaptureOutcome

    data class Captured(
        val candidate: AgentMemoryCandidateRecord,
    ) : AgentMemoryCandidateCaptureOutcome

    data class Failed(
        val message: String,
    ) : AgentMemoryCandidateCaptureOutcome
}

internal enum class AgentMemoryCandidateDecision {
    ACCEPT,
    REJECT,
}

internal sealed interface AgentMemoryCandidateDecisionOutcome {
    data class Updated(
        val decision: AgentMemoryCandidateDecision,
        val candidate: AgentMemoryCandidateRecord,
    ) : AgentMemoryCandidateDecisionOutcome

    data class Missing(
        val decision: AgentMemoryCandidateDecision,
        val candidateId: String,
    ) : AgentMemoryCandidateDecisionOutcome

    data class Busy(
        val candidateId: String,
    ) : AgentMemoryCandidateDecisionOutcome

    data class Failed(
        val decision: AgentMemoryCandidateDecision,
        val candidateId: String,
        val message: String,
    ) : AgentMemoryCandidateDecisionOutcome
}

internal class AgentMemoryCandidateCoordinator(
    private val candidateLimit: Int,
    private val listCandidates: suspend (Int) -> List<AgentMemoryCandidateRecord>,
    private val createCandidate: suspend (String, AgentMemorySource) -> AgentMemoryCandidateRecord?,
    private val acceptCandidate: suspend (String) -> AgentMemoryCandidateRecord?,
    private val rejectCandidate: suspend (String) -> AgentMemoryCandidateRecord?,
) {
    private val decisionMutex = Mutex()
    private val decidingCandidateIds = mutableSetOf<String>()

    init {
        require(candidateLimit in 1..MAX_CANDIDATE_LIMIT) {
            "候选记忆读取上限必须在 1..$MAX_CANDIDATE_LIMIT 之间"
        }
    }

    suspend fun load(): AgentMemoryCandidateLoadOutcome {
        return try {
            AgentMemoryCandidateLoadOutcome.Loaded(listCandidates(candidateLimit))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AgentMemoryCandidateLoadOutcome.Failed(error.message ?: "无法读取候选记忆")
        }
    }

    suspend fun capture(turn: AgentMemoryCandidateTurn): AgentMemoryCandidateCaptureOutcome {
        return try {
            val source = AgentMemorySource(
                conversationId = turn.conversationId,
                runId = turn.runId,
                summary = if (turn.runId == null) {
                    "普通对话结束后生成的候选"
                } else {
                    "Agent Run 结束后生成的候选"
                },
            )
            val candidate = createCandidate(turn.userText, source)
                ?: return AgentMemoryCandidateCaptureOutcome.Ignored
            AgentMemoryCandidateCaptureOutcome.Captured(candidate)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AgentMemoryCandidateCaptureOutcome.Failed(error.message ?: "生成候选记忆失败")
        }
    }

    suspend fun decide(
        candidateId: String,
        decision: AgentMemoryCandidateDecision,
    ): AgentMemoryCandidateDecisionOutcome {
        if (!claimDecision(candidateId)) {
            return AgentMemoryCandidateDecisionOutcome.Busy(candidateId)
        }
        return try {
            val candidate = when (decision) {
                AgentMemoryCandidateDecision.ACCEPT -> acceptCandidate(candidateId)
                AgentMemoryCandidateDecision.REJECT -> rejectCandidate(candidateId)
            }
            if (candidate == null) {
                AgentMemoryCandidateDecisionOutcome.Missing(decision, candidateId)
            } else {
                AgentMemoryCandidateDecisionOutcome.Updated(decision, candidate)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            AgentMemoryCandidateDecisionOutcome.Failed(
                decision = decision,
                candidateId = candidateId,
                message = error.message ?: "更新候选记忆失败",
            )
        } finally {
            // long: 外层 Job 取消不能中断 claim 清理；否则恰逢其他候选持有 Mutex 时，本 ID 会永久留在忙碌集合。
            withContext(NonCancellable) {
                releaseDecision(candidateId)
            }
        }
    }

    private suspend fun claimDecision(candidateId: String): Boolean {
        return decisionMutex.withLock {
            // long: claim 按候选 ID 隔离；同一候选的接受/拒绝不能并发，无关候选仍可独立落库。
            decidingCandidateIds.add(candidateId)
        }
    }

    private suspend fun releaseDecision(candidateId: String) {
        decisionMutex.withLock {
            // long: 失败和取消都必须释放同一 claim，否则一次 Room 异常会让候选永久无法重试。
            decidingCandidateIds.remove(candidateId)
        }
    }

    private companion object {
        const val MAX_CANDIDATE_LIMIT = 200
    }
}
