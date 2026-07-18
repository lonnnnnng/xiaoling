package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryDecayPolicyTest {
    @Test
    fun legacyMemoryWithoutExpiryRemainsActive() {
        val memory = memory(expiresAt = null)

        assertTrue(!AgentMemoryDecayPolicy.isExpired(memory, NOW))
        assertEquals(null, AgentMemoryDecayPolicy.expiresAt(AgentMemoryExpiryOption.NEVER, NOW))
    }

    @Test
    fun explicitExpiryExcludesMemoryAtBoundary() {
        val memory = memory(expiresAt = NOW)

        assertTrue(AgentMemoryDecayPolicy.isExpired(memory, NOW))
        assertTrue(!AgentMemoryDecayPolicy.isExpired(memory.copy(expiresAt = NOW + 1), NOW))
    }

    @Test
    fun pinnedMemoryKeepsMaximumDecayScore() {
        val old = memory(updatedAt = NOW - 365L * AgentMemoryDecayPolicy.DAY_MILLIS, pinned = false)
        val pinned = old.copy(pinned = true)

        assertTrue(AgentMemoryDecayPolicy.score(pinned, NOW) > AgentMemoryDecayPolicy.score(old, NOW))
        assertEquals(Double.MAX_VALUE, AgentMemoryDecayPolicy.score(pinned, NOW), 0.0)
    }

    @Test
    fun preferenceHalfLifeHalvesScoreAfterOneYear() {
        val memory = memory(updatedAt = NOW - 365L * AgentMemoryDecayPolicy.DAY_MILLIS)

        assertEquals(0.45, AgentMemoryDecayPolicy.score(memory, NOW), 0.06)
    }

    private fun memory(
        expiresAt: Long?,
        updatedAt: Long = NOW,
        pinned: Boolean = false,
    ) = AgentMemoryRecord(
        id = "memory-1",
        content = "用户喜欢紧凑界面",
        tags = "ui",
        type = "Preference",
        sourceConversationId = "conversation-1",
        sourceRunId = "run-1",
        sourceSummary = "用户明确表达",
        confidence = 0.9,
        enabled = true,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        pinned = pinned,
        expiresAt = expiresAt,
    )

    companion object {
        private const val NOW = 2_000_000_000_000L
    }
}
