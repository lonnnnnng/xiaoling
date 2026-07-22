package com.longdev.xiaoling.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationLoadCoordinatorTest {
    @Test
    fun staleLoadSuccessDoesNotOverwriteLatestSelection() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<ConversationLoadEvent>()
        val coordinator = ConversationLoadCoordinator(
            scope = this,
            loadMessages = { conversationId ->
                if (conversationId == "first") {
                    firstStarted.complete(Unit)
                    withContext(NonCancellable) { releaseFirst.await() }
                }
                listOf(ChatMessage(role = "assistant", text = conversationId))
            },
        )

        coordinator.load(request("first"), events::add)
        firstStarted.await()
        val latestJob = coordinator.load(request("second"), events::add)
        releaseFirst.complete(Unit)
        latestJob.join()

        assertEquals(
            listOf("second"),
            events.filterIsInstance<ConversationLoadEvent.Loaded>().map { it.request.conversation.id },
        )
    }

    @Test
    fun staleLoadFailureDoesNotReplaceLatestSelectionResult() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val events = mutableListOf<ConversationLoadEvent>()
        val coordinator = ConversationLoadCoordinator(
            scope = this,
            loadMessages = { conversationId ->
                if (conversationId == "first") {
                    firstStarted.complete(Unit)
                    withContext(NonCancellable) { releaseFirst.await() }
                    throw IllegalStateException("旧会话读取失败")
                }
                listOf(ChatMessage(role = "assistant", text = conversationId))
            },
        )

        coordinator.load(request("first"), events::add)
        firstStarted.await()
        val latestJob = coordinator.load(request("second"), events::add)
        releaseFirst.complete(Unit)
        latestJob.join()

        assertTrue(events.filterIsInstance<ConversationLoadEvent.Failed>().isEmpty())
        assertEquals(
            listOf("second"),
            events.filterIsInstance<ConversationLoadEvent.Loaded>().map { it.request.conversation.id },
        )
    }

    @Test
    fun cancellingLoadInvalidatesLateResult() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<ConversationLoadEvent>()
        val coordinator = ConversationLoadCoordinator(
            scope = this,
            loadMessages = {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                listOf(ChatMessage(role = "assistant", text = "late"))
            },
        )

        val loadJob = coordinator.load(request("first"), events::add)
        started.await()
        coordinator.cancelPendingLoad()
        release.complete(Unit)
        loadJob.join()

        assertTrue(events.filterIsInstance<ConversationLoadEvent.Loaded>().isEmpty())
        assertTrue(events.filterIsInstance<ConversationLoadEvent.Failed>().isEmpty())
    }

    @Test
    fun reentrantLoadingKeepsLatestJobRegisteredForCancellation() = runTest {
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val events = mutableListOf<ConversationLoadEvent>()
        lateinit var coordinator: ConversationLoadCoordinator
        lateinit var nestedJob: Job
        var firstLoadCalls = 0
        coordinator = ConversationLoadCoordinator(
            scope = this,
            loadMessages = { conversationId ->
                if (conversationId == "first") {
                    firstLoadCalls += 1
                    error("第一轮加载在重入后不应启动")
                }
                secondStarted.complete(Unit)
                withContext(NonCancellable) { releaseSecond.await() }
                listOf(ChatMessage(role = "assistant", text = conversationId))
            },
        )

        val firstJob = coordinator.load(request("first")) { event ->
            if (event == ConversationLoadEvent.Loading) {
                nestedJob = coordinator.load(request("second"), events::add)
            } else {
                events += event
            }
        }
        secondStarted.await()
        coordinator.cancelPendingLoad()
        releaseSecond.complete(Unit)
        firstJob.join()
        nestedJob.join()

        assertEquals(0, firstLoadCalls)
        assertTrue(nestedJob.isCancelled)
        assertTrue(events.filterIsInstance<ConversationLoadEvent.Loaded>().isEmpty())
    }

    private fun request(id: String) = ConversationLoadRequest(
        conversation = ConversationSession(
            id = id,
            title = id,
            summary = "",
            summaryUntilMessageId = null,
            summaryUpdatedAt = null,
            summaryModel = null,
            messages = emptyList(),
            createdAt = 1L,
            updatedAt = 1L,
        ),
        conversations = emptyList(),
        result = null,
    )
}
