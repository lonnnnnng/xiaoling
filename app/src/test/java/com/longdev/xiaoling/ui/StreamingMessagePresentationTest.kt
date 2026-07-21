package com.longdev.xiaoling.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingMessagePresentationTest {
    @Test
    fun activeStreamRemainsInProgress() {
        val message = ChatMessage(
            role = "assistant",
            text = "部分答案",
            createdAt = 1L,
            meta = MessageMeta(
                streaming = true,
                firstTokenLatencyMs = 120L,
            ),
        )

        assertTrue(message.isStreamingInProgress())
        assertEquals("首字 0.12 s · 接收中", message.assistantFooterLabel())
    }

    @Test
    fun failedPartialStreamIsTerminalAndClearlyMarkedIncomplete() {
        val message = ChatMessage(
            role = "assistant",
            text = "部分答案",
            createdAt = 1L,
            meta = MessageMeta(
                streaming = true,
                firstTokenLatencyMs = 120L,
                finishReason = "failed",
                errorKind = "连接失败",
                errorMessage = "unexpected end of stream",
            ),
        )

        assertFalse(message.isStreamingInProgress())
        assertEquals("内容不完整 · 连接失败", message.assistantFooterLabel())
        assertFalse(message.isEligibleForConversationContext())
    }

    @Test
    fun cancelledPartialStreamIsTerminal() {
        val message = ChatMessage(
            role = "assistant",
            text = "部分答案",
            createdAt = 1L,
            meta = MessageMeta(
                streaming = true,
                firstTokenLatencyMs = 120L,
                finishReason = "cancelled",
                errorKind = "已取消",
                errorMessage = "用户停止生成",
            ),
        )

        assertFalse(message.isStreamingInProgress())
        assertEquals("已停止 · 首字 0.12 s", message.assistantFooterLabel())
        assertFalse(message.isEligibleForConversationContext())
    }

    @Test
    fun userPromptAndCompletedAssistantRemainEligibleForLaterContext() {
        val user = ChatMessage(role = "user", text = "原问题", createdAt = 1L)
        val assistant = ChatMessage(
            role = "assistant",
            text = "完整答案",
            createdAt = 2L,
            meta = MessageMeta(streaming = true, latencyMs = 420L),
        )

        assertTrue(user.isEligibleForConversationContext())
        assertTrue(assistant.isEligibleForConversationContext())
    }

    @Test
    fun failureConvergesExistingPartialAssistantWithoutChangingItsContent() {
        val user = ChatMessage(role = "user", text = "原问题", createdAt = 1L)
        val assistant = ChatMessage(
            role = "assistant",
            text = "部分答案",
            createdAt = 2L,
            meta = MessageMeta(streaming = true, firstTokenLatencyMs = 120L),
        )

        val result = listOf(user, assistant).withFailedStreamingGeneration(
            baseMeta = MessageMeta(streaming = true),
            errorKind = "连接失败",
            errorMessage = "unexpected end of stream",
        )

        assertEquals(2, result.size)
        assertEquals("部分答案", result.last().text)
        assertEquals("failed", result.last().meta?.finishReason)
        assertEquals("连接失败", result.last().meta?.errorKind)
        assertEquals("unexpected end of stream", result.last().meta?.errorMessage)
    }

    @Test
    fun failureWithoutDeliveredDeltaDoesNotInventAssistantMessage() {
        val user = ChatMessage(role = "user", text = "原问题", createdAt = 1L)

        val result = listOf(user).withFailedStreamingGeneration(
            baseMeta = MessageMeta(streaming = true),
            errorKind = "连接失败",
            errorMessage = "connection reset",
        )

        assertEquals(listOf(user), result)
    }
}
