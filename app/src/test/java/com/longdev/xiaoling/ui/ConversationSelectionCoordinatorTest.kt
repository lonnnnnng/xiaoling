package com.longdev.xiaoling.ui

import com.longdev.xiaoling.storage.StoredConversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSelectionCoordinatorTest {
    @Test
    fun deleteLoadFailureRollsBackIntentBeforePublishingFailure() = runTest {
        val persistence = persistenceCoordinator(this)
        val coordinator = ConversationSelectionCoordinator(
            persistenceCoordinator = persistence,
            loadCoordinator = ConversationLoadCoordinator(
                scope = this,
                loadMessages = { error("Room 读取失败") },
            ),
        )
        val events = mutableListOf<ConversationSelectionEvent>()
        var pendingDeletionIdsAtFailure = setOf<String>()

        val job = coordinator.deleteCurrent(stateWithRemainingConversation()) { event ->
            events += event
            val loadEvent = (event as? ConversationSelectionEvent.Load)?.event
            if (loadEvent is ConversationLoadEvent.Failed) {
                pendingDeletionIdsAtFailure = persistence.pendingDeletionIds()
            }
        }
        job?.join()

        assertEquals(
            listOf(
                ConversationSelectionEvent.DeletionStarted::class,
                ConversationSelectionEvent.Load::class,
                ConversationSelectionEvent.Load::class,
            ),
            events.map { it::class },
        )
        assertTrue((events[1] as ConversationSelectionEvent.Load).event is ConversationLoadEvent.Loading)
        assertTrue((events[2] as ConversationSelectionEvent.Load).event is ConversationLoadEvent.Failed)
        assertTrue(pendingDeletionIdsAtFailure.isEmpty())
    }

    @Test
    fun staleDeleteFailureCannotRollbackNewDeletionIntent() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var loadCalls = 0
        val persistence = persistenceCoordinator(this)
        val coordinator = ConversationSelectionCoordinator(
            persistenceCoordinator = persistence,
            loadCoordinator = ConversationLoadCoordinator(
                scope = this,
                loadMessages = {
                    loadCalls += 1
                    if (loadCalls == 1) {
                        firstStarted.complete(Unit)
                        withContext(NonCancellable) { releaseFirst.await() }
                        error("旧删除读取失败")
                    }
                    emptyList()
                },
            ),
        )
        val events = mutableListOf<ConversationSelectionEvent>()

        val oldJob = coordinator.deleteCurrent(stateWithRemainingConversation(), events::add)
        firstStarted.await()
        val latestJob = coordinator.deleteCurrent(stateWithRemainingConversation(), events::add)
        releaseFirst.complete(Unit)
        oldJob?.join()
        latestJob?.join()

        assertTrue(
            events.filterIsInstance<ConversationSelectionEvent.Load>()
                .none { it.event is ConversationLoadEvent.Failed },
        )
        assertEquals(setOf("selected"), persistence.pendingDeletionIds())
    }

    @Test
    fun deletingLastConversationPublishesClearBeforeImmediateSelection() = runTest {
        var loadCalls = 0
        val persistence = persistenceCoordinator(this)
        val coordinator = ConversationSelectionCoordinator(
            persistenceCoordinator = persistence,
            loadCoordinator = ConversationLoadCoordinator(
                scope = this,
                loadMessages = {
                    loadCalls += 1
                    emptyList()
                },
            ),
        )
        val selected = session("selected", 10L)
        val events = mutableListOf<ConversationSelectionEvent>()

        val job = coordinator.deleteCurrent(state(listOf(selected), selected), events::add)

        assertNull(job)
        assertEquals(0, loadCalls)
        assertEquals(2, events.size)
        assertEquals("selected", (events[0] as ConversationSelectionEvent.DeletionStarted).conversationId)
        val immediate = (events[1] as ConversationSelectionEvent.Immediate).selection
        assertTrue(immediate.conversations.single().messages.isEmpty())
        assertTrue(persistence.pendingDeletionIds().contains("selected"))
    }

    @Test
    fun openingNewConversationInvalidatesLateSelectionLoad() = runTest {
        val loadStarted = CompletableDeferred<Unit>()
        val releaseLoad = CompletableDeferred<Unit>()
        val coordinator = ConversationSelectionCoordinator(
            persistenceCoordinator = persistenceCoordinator(this),
            loadCoordinator = ConversationLoadCoordinator(
                scope = this,
                loadMessages = {
                    loadStarted.complete(Unit)
                    withContext(NonCancellable) { releaseLoad.await() }
                    listOf(ChatMessage(role = "assistant", text = "迟到消息"))
                },
            ),
        )
        val selected = session("selected", 20L)
        val other = session("other", 10L)
        val state = state(listOf(selected, other), selected)
        val events = mutableListOf<ConversationSelectionEvent>()

        val loadJob = coordinator.select(state, other.id, events::add)
        loadStarted.await()
        coordinator.openNew(state, events::add)
        releaseLoad.complete(Unit)
        loadJob?.join()

        assertTrue(
            events.filterIsInstance<ConversationSelectionEvent.Load>()
                .none { it.event is ConversationLoadEvent.Loaded },
        )
        assertTrue(events.last() is ConversationSelectionEvent.Immediate)
    }

    private fun persistenceCoordinator(scope: CoroutineScope) = ConversationPersistenceCoordinator(
        scope = scope,
        persistSnapshot = {},
    )

    private fun ConversationPersistenceCoordinator.pendingDeletionIds(): Set<String> =
        captureSnapshot(emptyList<StoredConversation>(), "").deletedConversationIds

    private fun stateWithRemainingConversation(): XiaoLingUiState {
        val selected = session("selected", 20L)
        val remaining = session("remaining", 10L)
        return state(listOf(selected, remaining), selected)
    }

    private fun state(
        conversations: List<ConversationSession>,
        selected: ConversationSession,
    ) = XiaoLingUiState(
        conversations = conversations,
        selectedConversationId = selected.id,
        conversationTitle = selected.title,
        conversationSummary = selected.summary,
        chatMessages = selected.messages,
    )

    private fun session(id: String, updatedAt: Long) = ConversationSession(
        id = id,
        title = id,
        summary = "",
        summaryUntilMessageId = null,
        summaryUpdatedAt = null,
        summaryModel = null,
        messages = emptyList(),
        createdAt = updatedAt,
        updatedAt = updatedAt,
    )
}
