package com.longdev.xiaoling.ui.memory

import com.longdev.xiaoling.agent.AgentMemoryCandidateRecord
import com.longdev.xiaoling.agent.AgentMemoryCandidateStatus
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemoryRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryManagementProjectionTest {
    @Test
    fun projectBindsStableMemoryStateAndKeepsOnlyActionableCandidates() {
        val first = memory("memory-1")
        val second = memory("memory-2")
        val pending = candidate("candidate-1", AgentMemoryCandidateStatus.PENDING)
        val conflict = candidate("candidate-2", AgentMemoryCandidateStatus.CONFLICT)
        val accepted = candidate("candidate-3", AgentMemoryCandidateStatus.ACCEPTED)

        val result = MemoryManagementProjection.project(
            loading = true,
            error = "读取失败",
            memories = listOf(first, second),
            candidatesEnabled = true,
            loadingCandidates = false,
            candidates = listOf(pending, conflict, accepted),
            searchQuery = "偏好",
            filter = AgentMemoryFilter.ENABLED,
            selectedMemoryId = second.id,
            mutatingMemoryIds = setOf(first.id),
            mutatingCandidateIds = setOf(conflict.id),
            deletedMemoryForUndo = first,
        )

        assertTrue(result.loading)
        assertEquals("读取失败", result.error)
        assertTrue(result.candidatesEnabled)
        assertFalse(result.loadingCandidates)
        assertEquals("偏好", result.searchQuery)
        assertEquals(AgentMemoryFilter.ENABLED, result.filter)
        assertEquals(first, result.deletedMemoryForUndo)
        assertEquals(listOf("memory-1", "memory-2"), result.memories.map { it.record.id })
        assertTrue(result.memories.first().mutating)
        assertFalse(result.memories.first().selected)
        assertFalse(result.memories.last().mutating)
        assertTrue(result.memories.last().selected)
        assertEquals(listOf("candidate-1", "candidate-2"), result.candidates.map { it.record.id })
        assertFalse(result.candidates.first().mutating)
        assertFalse(result.candidates.first().conflict)
        assertEquals("待确认", result.candidates.first().statusLabel)
        assertEquals("保存", result.candidates.first().acceptLabel)
        assertTrue(result.candidates.last().mutating)
        assertTrue(result.candidates.last().conflict)
        assertEquals("与旧记忆冲突", result.candidates.last().statusLabel)
        assertEquals("另存为新记忆", result.candidates.last().acceptLabel)
    }

    private fun memory(id: String) = AgentMemoryRecord(
        id = id,
        content = "偏好-$id",
        tags = "偏好",
        type = "Preference",
        sourceConversationId = "conversation-1",
        sourceRunId = "run-1",
        sourceSummary = "用户明确表达偏好",
        confidence = 0.9,
        enabled = true,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun candidate(
        id: String,
        status: AgentMemoryCandidateStatus,
    ) = AgentMemoryCandidateRecord(
        id = id,
        content = "候选-$id",
        normalizedContent = "候选-$id",
        type = "Preference",
        topicKey = "topic-$id",
        sourceConversationId = "conversation-1",
        sourceRunId = "run-1",
        sourceSummary = "用户明确表达偏好",
        confidence = 0.8,
        status = status,
        sensitiveCategory = null,
        relatedMemoryId = null,
        createdAt = 1L,
        updatedAt = 2L,
    )
}
