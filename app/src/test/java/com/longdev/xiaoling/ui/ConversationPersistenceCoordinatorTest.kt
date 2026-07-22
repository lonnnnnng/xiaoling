package com.longdev.xiaoling.ui

import com.longdev.xiaoling.storage.StoredConversation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationPersistenceCoordinatorTest {
    @Test
    fun latestSaveCancelsOlderPendingSnapshot() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val persistedSelections = mutableListOf<String>()
        val coordinator = ConversationPersistenceCoordinator(
            scope = this,
            persistSnapshot = { snapshot ->
                if (snapshot.selectedConversationId == "old") {
                    firstStarted.complete(Unit)
                    awaitCancellation()
                }
                persistedSelections += snapshot.selectedConversationId
            },
        )

        val oldJob = coordinator.saveLatest(listOf(conversation("old")), "old")
        firstStarted.await()
        val newJob = coordinator.saveLatest(listOf(conversation("new")), "new")
        newJob.join()

        assertTrue(oldJob.isCancelled)
        assertEquals(listOf("new"), persistedSelections)
    }

    @Test
    fun cancelledCommitFinishesBeforeNewSnapshotWrites() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val persistedSelections = mutableListOf<String>()
        var concurrentWrites = 0
        var maxConcurrentWrites = 0
        val coordinator = ConversationPersistenceCoordinator(
            scope = this,
            persistSnapshot = { snapshot ->
                concurrentWrites += 1
                maxConcurrentWrites = maxOf(maxConcurrentWrites, concurrentWrites)
                try {
                    if (snapshot.selectedConversationId == "old") {
                        firstStarted.complete(Unit)
                        withContext(NonCancellable) { releaseFirst.await() }
                    }
                    persistedSelections += snapshot.selectedConversationId
                } finally {
                    concurrentWrites -= 1
                }
            },
        )

        coordinator.saveLatest(listOf(conversation("old")), "old")
        firstStarted.await()
        val newJob = coordinator.saveLatest(listOf(conversation("new")), "new")
        releaseFirst.complete(Unit)
        newJob.join()

        assertEquals(1, maxConcurrentWrites)
        assertEquals(listOf("old", "new"), persistedSelections)
    }

    @Test
    fun sendPersistenceWaitsForCancelledSaveCommitToFinish() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val sendStarted = CompletableDeferred<Unit>()
        val persistedSelections = mutableListOf<String>()
        val coordinator = ConversationPersistenceCoordinator(
            scope = this,
            persistSnapshot = { snapshot ->
                if (snapshot.selectedConversationId == "old") {
                    firstStarted.complete(Unit)
                    withContext(NonCancellable) { releaseFirst.await() }
                } else {
                    sendStarted.complete(Unit)
                }
                persistedSelections += snapshot.selectedConversationId
            },
        )
        coordinator.saveLatest(listOf(conversation("old")), "old")
        firstStarted.await()
        val sendPersistence = async {
            coordinator.cancelPendingSaveAndJoin()
            coordinator.persist(
                coordinator.captureSnapshot(listOf(conversation("send")), "send"),
            )
        }

        yield()
        assertFalse(sendStarted.isCompleted)
        releaseFirst.complete(Unit)
        sendPersistence.await()

        assertEquals(listOf("old", "send"), persistedSelections)
    }

    @Test
    fun successfulPersistenceAcknowledgesOnlyCapturedDeletionIds() = runTest {
        val persistedSnapshots = mutableListOf<ConversationPersistenceSnapshot>()
        val coordinator = ConversationPersistenceCoordinator(
            scope = this,
            persistSnapshot = persistedSnapshots::add,
        )
        coordinator.markConversationDeleted("deleted-before-snapshot")
        val snapshot = coordinator.captureSnapshot(listOf(conversation("kept")), "kept")
        coordinator.markConversationDeleted("deleted-after-snapshot")

        coordinator.persist(snapshot)

        assertEquals(setOf("deleted-before-snapshot"), persistedSnapshots.single().deletedConversationIds)
        assertEquals(
            setOf("deleted-after-snapshot"),
            coordinator.captureSnapshot(listOf(conversation("kept")), "kept").deletedConversationIds,
        )
    }

    @Test
    fun successfulOldSnapshotDoesNotAcknowledgeRemarkedDeletionIntent() = runTest {
        val coordinator = ConversationPersistenceCoordinator(
            scope = this,
            persistSnapshot = {},
        )
        val oldIntent = coordinator.markConversationDeleted("deleted-1")
        val oldSnapshot = coordinator.captureSnapshot(listOf(conversation("kept")), "kept")
        coordinator.rollbackConversationDeletion(oldIntent)
        coordinator.markConversationDeleted("deleted-1")

        coordinator.persist(oldSnapshot)

        assertEquals(
            setOf("deleted-1"),
            coordinator.captureSnapshot(listOf(conversation("kept")), "kept").deletedConversationIds,
        )
    }

    @Test
    fun failedPersistenceKeepsDeletionIntentForNextSnapshot() = runTest {
        val coordinator = ConversationPersistenceCoordinator(
            scope = this,
            persistSnapshot = { throw IllegalStateException("Room 写入失败") },
        )
        coordinator.markConversationDeleted("deleted-1")
        val snapshot = coordinator.captureSnapshot(listOf(conversation("kept")), "kept")

        try {
            coordinator.persist(snapshot)
        } catch (_: IllegalStateException) {
            // long: 删除事务失败后仍要让下一次保存携带同一 ID，不能把尚未落库的删除误判为完成。
        }

        assertEquals(
            setOf("deleted-1"),
            coordinator.captureSnapshot(listOf(conversation("kept")), "kept").deletedConversationIds,
        )
    }

    @Test
    fun rollbackRemovesDeletionIntentBeforePersistence() = runTest {
        val coordinator = ConversationPersistenceCoordinator(
            scope = this,
            persistSnapshot = {},
        )
        val deletionIntent = coordinator.markConversationDeleted("deleted-1")

        coordinator.rollbackConversationDeletion(deletionIntent)

        assertTrue(
            coordinator.captureSnapshot(listOf(conversation("kept")), "kept").deletedConversationIds.isEmpty(),
        )
    }

    @Test
    fun staleRollbackDoesNotRemoveRemarkedDeletionIntent() = runTest {
        val coordinator = ConversationPersistenceCoordinator(
            scope = this,
            persistSnapshot = {},
        )
        val oldIntent = coordinator.markConversationDeleted("deleted-1")
        coordinator.markConversationDeleted("deleted-1")

        coordinator.rollbackConversationDeletion(oldIntent)

        assertEquals(
            setOf("deleted-1"),
            coordinator.captureSnapshot(listOf(conversation("kept")), "kept").deletedConversationIds,
        )
    }

    private fun conversation(id: String) = StoredConversation(
        id = id,
        title = id,
        summary = "",
        summaryUntilMessageId = null,
        summaryUpdatedAt = null,
        summaryModel = null,
        messages = emptyList(),
        createdAt = 1L,
        updatedAt = 1L,
    )
}
