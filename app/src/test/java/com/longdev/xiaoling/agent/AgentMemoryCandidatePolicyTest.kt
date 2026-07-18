package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMemoryCandidatePolicyTest {
    private val source = AgentMemorySource(
        conversationId = "conversation-1",
        runId = "run-1",
        summary = "用户本轮明确陈述",
    )

    @Test
    fun explicitPreferenceCreatesPendingCandidateButOrdinaryChatDoesNot() {
        val candidate = AgentMemoryCandidatePolicy.evaluateTurn(
            userText = "我喜欢紧凑的界面布局",
            source = source,
            existingMemories = emptyList(),
        )

        assertEquals(AgentMemoryCandidateStatus.PENDING, candidate?.status)
        assertEquals("Preference", candidate?.type)
        assertEquals("我喜欢紧凑的界面布局", candidate?.content)
        assertEquals("ui", candidate?.topicKey)
        assertNull(
            AgentMemoryCandidatePolicy.evaluateTurn(
                userText = "帮我解释一下 Room Migration",
                source = source,
                existingMemories = emptyList(),
            ),
        )
    }

    @Test
    fun sensitiveCandidatesAreBlockedWithoutRetainingRawValue() {
        val sensitiveStatements = mapOf(
            AgentMemorySensitiveCategory.API_KEY to "请记住我的 API Key 是 sk-test-secret-1234567890",
            AgentMemorySensitiveCategory.TOKEN to "请记住我的 token 是 eyJhbGciOiJIUzI1NiJ9.payload.signature",
            AgentMemorySensitiveCategory.PASSWORD to "请记住我的密码是 MySecret!2026",
            AgentMemorySensitiveCategory.BANK_CARD to "请记住我的银行卡号是 6222020202020202020",
            AgentMemorySensitiveCategory.NATIONAL_ID to "请记住我的身份证号是 11010519491231002X",
            AgentMemorySensitiveCategory.PHONE_NUMBER to "请记住我的手机号是 13800138000",
        )

        sensitiveStatements.forEach { (category, statement) ->
            val candidate = AgentMemoryCandidatePolicy.evaluateTurn(
                userText = statement,
                source = source,
                existingMemories = emptyList(),
            )

            assertEquals(category, candidate?.sensitiveCategory)
            assertEquals(AgentMemoryCandidateStatus.BLOCKED_SENSITIVE, candidate?.status)
            assertEquals("", candidate?.content)
            assertEquals("检测到${category.displayName}，未保存原文", candidate?.displaySummary)
            assertFalse(candidate?.displaySummary.orEmpty().contains(statement.substringAfter("是 ")))
        }
    }

    @Test
    fun commonApiKeyPrefixesAreBlocked() {
        listOf(
            "请记住我的 GitHub Key 是 ghp_1234567890abcdef",
            "请记住我的 Google Key 是 AIza1234567890abcdef",
            "请记住我的 API Key 是 AKIA1234567890abcdef",
        ).forEach { statement ->
            val candidate = AgentMemoryCandidatePolicy.evaluateTurn(statement, source, emptyList())
            assertEquals(AgentMemoryCandidateStatus.BLOCKED_SENSITIVE, candidate?.status)
            assertEquals(AgentMemorySensitiveCategory.API_KEY, candidate?.sensitiveCategory)
            assertEquals("", candidate?.content)
        }
    }

    @Test
    fun normalizedSameFactIsMarkedDuplicate() {
        val existing = memory(
            id = "memory-existing",
            content = "我喜欢紧凑的界面布局。",
            type = "Preference",
        )

        val candidate = AgentMemoryCandidatePolicy.evaluateTurn(
            userText = "  我喜欢紧凑的界面布局  ",
            source = source,
            existingMemories = listOf(existing),
        )

        assertEquals(AgentMemoryCandidateStatus.DUPLICATE, candidate?.status)
        assertEquals(existing.id, candidate?.relatedMemoryId)
    }

    @Test
    fun sameTopicDifferentFactIsMarkedConflictWithoutChangingOldMemory() {
        val existing = memory(
            id = "memory-existing",
            content = "我喜欢宽松的界面布局",
            type = "Preference",
        )

        val candidate = AgentMemoryCandidatePolicy.evaluateTurn(
            userText = "我喜欢紧凑的界面布局",
            source = source,
            existingMemories = listOf(existing),
        )

        assertEquals(AgentMemoryCandidateStatus.CONFLICT, candidate?.status)
        assertEquals(existing.id, candidate?.relatedMemoryId)
        assertEquals("我喜欢宽松的界面布局", existing.content)
        assertTrue(candidate?.content.orEmpty().contains("紧凑"))
    }

    private fun memory(id: String, content: String, type: String) = AgentMemoryRecord(
        id = id,
        content = content,
        tags = "",
        type = type,
        sourceConversationId = "old-conversation",
        sourceRunId = null,
        sourceSummary = "历史记忆",
        confidence = 0.9,
        enabled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )
}
