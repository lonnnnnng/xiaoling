package com.longdev.xiaoling.ui

import com.longdev.xiaoling.storage.StoredConversation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class ConversationPersistenceSnapshot(
    val conversations: List<StoredConversation>,
    val selectedConversationId: String,
    val deletedConversationIds: Set<String>,
    internal val deletionIntentVersions: Map<String, Long> = emptyMap(),
)

internal data class ConversationDeletionIntent(
    val conversationId: String,
    val version: Long,
)

internal class ConversationPersistenceCoordinator(
    private val scope: CoroutineScope,
    private val persistSnapshot: suspend (ConversationPersistenceSnapshot) -> Unit,
) {
    private val writeMutex = Mutex()
    private val deletionLock = Any()
    private val pendingDeletionVersions = linkedMapOf<String, Long>()
    private var nextDeletionVersion = 0L
    private var latestSaveJob: Job? = null

    fun markConversationDeleted(conversationId: String): ConversationDeletionIntent =
        synchronized(deletionLock) {
            nextDeletionVersion += 1L
            pendingDeletionVersions[conversationId] = nextDeletionVersion
            ConversationDeletionIntent(conversationId, nextDeletionVersion)
        }

    fun rollbackConversationDeletion(intent: ConversationDeletionIntent) {
        synchronized(deletionLock) {
            // long: 会话读取失败只能撤销触发该次切换的删除代次；同 ID 后续再次删除属于新操作，不能被旧失败回调误清除。
            if (pendingDeletionVersions[intent.conversationId] == intent.version) {
                pendingDeletionVersions -= intent.conversationId
            }
        }
    }

    fun captureSnapshot(
        conversations: List<StoredConversation>,
        selectedConversationId: String,
    ): ConversationPersistenceSnapshot {
        val deletionIntentVersions = synchronized(deletionLock) {
            pendingDeletionVersions.toMap()
        }
        return ConversationPersistenceSnapshot(
            conversations = conversations,
            selectedConversationId = selectedConversationId,
            deletedConversationIds = deletionIntentVersions.keys,
            deletionIntentVersions = deletionIntentVersions,
        )
    }

    fun saveLatest(
        conversations: List<StoredConversation>,
        selectedConversationId: String,
    ): Job {
        latestSaveJob?.cancel()
        val snapshot = captureSnapshot(conversations, selectedConversationId)
        // long: 快速切换会话时取消尚未提交的旧快照；若旧 Room 事务已进入不可取消提交区，单写者锁会等待其结束，再让最新快照成为最终状态。
        return scope.launch {
            persist(snapshot)
        }.also { latestSaveJob = it }
    }

    suspend fun cancelPendingSaveAndJoin() {
        val pendingJob = latestSaveJob
        latestSaveJob = null
        pendingJob?.cancelAndJoin()
    }

    suspend fun persist(snapshot: ConversationPersistenceSnapshot) {
        writeMutex.withLock {
            persistSnapshot(snapshot)
        }
        // long: 只有包含显式删除 ID 的 Room 事务成功返回后才能确认同一代删除；事务期间重新标记的同 ID 属于新用户动作，不能被旧提交误清除。
        synchronized(deletionLock) {
            snapshot.deletionIntentVersions.forEach { (conversationId, capturedVersion) ->
                if (pendingDeletionVersions[conversationId] == capturedVersion) {
                    pendingDeletionVersions -= conversationId
                }
            }
        }
    }
}
