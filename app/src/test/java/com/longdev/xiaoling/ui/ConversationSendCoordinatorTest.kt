package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.ModelResponseResult
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.RequestMessage
import com.longdev.xiaoling.network.StreamDeltaUpdate
import com.longdev.xiaoling.prompt.PromptSettings
import java.util.concurrent.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ConversationSendCoordinatorTest {
    @Test
    fun successfulSendPersistsAndPreparesBeforeStreamingAndCompletion() = runTest {
        val operationOrder = mutableListOf<String>()
        val initialContext = preparedContext(summary = "旧摘要")
        val preparedContext = preparedContext(summary = "新摘要")
        val response = response("最终回答")
        val coordinator = ConversationSendCoordinator(
            persistSnapshot = {
                operationOrder += "persist"
            },
            prepareRequestContext = { _, _, _, _ ->
                operationOrder += "prepare"
                preparedContext
            },
            sendRequest = { _, messages, onStreamDelta ->
                operationOrder += "send:${messages.single().content}"
                onStreamDelta(StreamDeltaUpdate("分", "部分", 12L))
                response
            },
        )

        val events = mutableListOf<ConversationSendEvent>()
        coordinator.execute(
            request = request(initialContext),
            onEvent = { event ->
                events += event
                operationOrder += when (event) {
                    is ConversationSendEvent.SnapshotPersisted -> "snapshot-persisted"
                    is ConversationSendEvent.ContextPrepared -> "context-prepared"
                    is ConversationSendEvent.StreamDelta -> "stream:${event.update.accumulatedText}"
                    is ConversationSendEvent.Completed -> "completed:${event.response.responseText}"
                    is ConversationSendEvent.Cancelled -> "cancelled"
                    is ConversationSendEvent.Failed -> "failed"
                }
            },
        )

        assertEquals(
            listOf(
                "persist",
                "snapshot-persisted",
                "prepare",
                "context-prepared",
                "send:请求消息",
                "stream:部分",
                "completed:最终回答",
            ),
            operationOrder,
        )
        assertEquals(
            ConversationSendEvent.Completed(preparedContext, response),
            events.last(),
        )
    }

    @Test
    fun cancellationPublishesPreparedContextAndRemainsCoroutineCancellation() = runTest {
        val preparedContext = preparedContext(summary = "已准备摘要")
        val coordinator = ConversationSendCoordinator(
            persistSnapshot = {},
            prepareRequestContext = { _, _, _, _ -> preparedContext },
            sendRequest = { _, _, _ -> throw CancellationException("用户停止生成") },
        )
        val events = mutableListOf<ConversationSendEvent>()

        try {
            coordinator.execute(request(preparedContext(summary = "旧摘要")), events::add)
            fail("应继续抛出取消异常")
        } catch (error: CancellationException) {
            assertEquals("用户停止生成", error.message)
        }

        assertTrue(events.any { it == ConversationSendEvent.ContextPrepared(preparedContext) })
        assertEquals(ConversationSendEvent.Cancelled(preparedContext), events.last())
    }

    @Test
    fun persistenceFailureStopsBeforePreparationAndPublishesInitialContext() = runTest {
        val initialContext = preparedContext(summary = "仍可恢复的旧摘要")
        val persistenceError = IllegalStateException("Room 写入失败")
        var preparationRequested = false
        var networkRequested = false
        val coordinator = ConversationSendCoordinator(
            persistSnapshot = { throw persistenceError },
            prepareRequestContext = { _, _, _, _ ->
                preparationRequested = true
                preparedContext(summary = "不应生成")
            },
            sendRequest = { _, _, _ ->
                networkRequested = true
                response("不应返回")
            },
        )
        val events = mutableListOf<ConversationSendEvent>()

        coordinator.execute(request(initialContext), events::add)

        assertFalse(preparationRequested)
        assertFalse(networkRequested)
        assertEquals(listOf(ConversationSendEvent.Failed(initialContext, persistenceError)), events)
    }

    private fun request(initialContext: PreparedRequestContext) = ConversationSendRequest(
        config = config(),
        messages = listOf(ChatMessage(id = "user-1", role = "user", text = "你好")),
        conversation = null,
        promptSettings = PromptSettings(),
        persistenceSnapshot = ConversationPersistenceSnapshot(
            conversations = emptyList(),
            selectedConversationId = "conversation-1",
            deletedConversationIds = setOf("deleted-1"),
        ),
        initialContext = initialContext,
    )

    private fun preparedContext(summary: String) = PreparedRequestContext(
        requestMessages = listOf(RequestMessage(role = "user", content = "请求消息")),
        summary = summary,
        summaryUntilMessageId = null,
        summaryUpdatedAt = null,
        summaryModel = null,
    )

    private fun response(text: String) = ModelResponseResult(
        requestUrl = "https://example.com/v1/responses",
        model = "test-model",
        latencyMs = 20L,
        responseText = text,
    )

    private fun config() = ProviderRequestConfig(
        baseUrl = "https://example.com/v1",
        apiKey = "test-key",
        model = "test-model",
    )
}
