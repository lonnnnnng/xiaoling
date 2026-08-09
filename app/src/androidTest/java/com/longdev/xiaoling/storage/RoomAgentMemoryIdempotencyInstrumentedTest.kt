package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentMemoryIdempotencyConflictException
import com.longdev.xiaoling.agent.AgentMemoryDeleteOperationVerification
import com.longdev.xiaoling.agent.AgentMemoryDeleteOperationVerificationFailure
import com.longdev.xiaoling.agent.AgentMemoryOperationVerification
import com.longdev.xiaoling.agent.AgentMemoryOperationVerificationFailure
import com.longdev.xiaoling.agent.AgentMemoryWriteRequest
import com.longdev.xiaoling.agent.AgentMemorySource
import com.longdev.xiaoling.agent.AgentMemoryUpdate
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentMemoryIdempotencyInstrumentedTest {
    private lateinit var context: Context
    private var database: XiaoLingDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun sameToolCallReturnsOriginalMemoryAfterDatabaseReopenAndRejectsPayloadDrift() = runBlocking {
        val source = AgentMemorySource("conversation-memory", "run-memory", "用户确认保存")
        var store = openStore()
        val first = store.remember(
            content = "用户喜欢紧凑界面",
            tags = "ui",
            type = "Preference",
            source = source,
            confidence = 0.8,
            idempotencyKey = "tool-call-memory-recovery",
        )

        database?.close()
        database = null
        store = openStore()

        val replay = store.remember(
            content = "用户喜欢紧凑界面",
            tags = "ui",
            type = "Preference",
            source = source,
            confidence = 0.8,
            idempotencyKey = "tool-call-memory-recovery",
        )
        val conflict = runCatching {
            store.remember(
                content = "用户喜欢宽松界面",
                tags = "ui",
                type = "Preference",
                source = source,
                confidence = 0.8,
                idempotencyKey = "tool-call-memory-recovery",
            )
        }.exceptionOrNull()

        assertEquals(first.id, replay.id)
        assertEquals(listOf(first.id), store.list("", com.longdev.xiaoling.agent.AgentMemoryFilter.ALL).map { it.id })
        assertTrue(conflict is AgentMemoryIdempotencyConflictException)

        store.delete(first.id)
        val deletedTargetReplay = runCatching {
            store.remember(
                content = "用户喜欢紧凑界面",
                tags = "ui",
                type = "Preference",
                source = source,
                confidence = 0.8,
                idempotencyKey = "tool-call-memory-recovery",
            )
        }.exceptionOrNull()

        assertTrue(deletedTargetReplay is IllegalStateException)
        assertTrue(deletedTargetReplay?.message.orEmpty().contains("已不存在"))
        assertTrue(store.list("", com.longdev.xiaoling.agent.AgentMemoryFilter.ALL).isEmpty())
    }

    @Test
    fun unchangedRememberOperationCanBeVerifiedReadOnly() = runBlocking {
        val request = AgentMemoryWriteRequest(
            content = "用户喜欢紧凑界面",
            tags = "ui",
            type = "Preference",
            source = AgentMemorySource("conversation-verify", "run-verify", "用户确认保存"),
            confidence = 0.8,
        )
        val store = openStore()
        val created = store.remember(
            content = request.content,
            tags = request.tags,
            type = request.type,
            source = request.source,
            confidence = request.confidence,
            idempotencyKey = "tool-call-memory-verify",
        )

        val verification = store.verifyRememberedOperation(
            idempotencyKey = "tool-call-memory-verify",
            memoryId = created.id,
            request = request,
            nowMillis = System.currentTimeMillis(),
        )

        assertEquals(created, (verification as AgentMemoryOperationVerification.Verified).memory)
    }

    @Test
    fun everyEditedBusinessFieldFailsWithoutOverwritingUserChanges() = runBlocking {
        val store = openStore()
        val updates = listOf<(AgentMemoryWriteRequest) -> AgentMemoryUpdate>(
            { request -> AgentMemoryUpdate("用户后来改为喜欢宽松界面", request.tags, request.type, request.confidence) },
            { request -> AgentMemoryUpdate(request.content, "changed-tag", request.type, request.confidence) },
            { request -> AgentMemoryUpdate(request.content, request.tags, "Procedure", request.confidence) },
            { request -> AgentMemoryUpdate(request.content, request.tags, request.type, 0.3) },
        )
        updates.forEachIndexed { index, update ->
            val request = verificationRequest("edit-$index")
            val key = "tool-call-memory-edit-$index"
            val created = rememberOperation(store, key, request)
            store.update(created.id, update(request))

            val verification = store.verifyRememberedOperation(
                key,
                created.id,
                request,
                System.currentTimeMillis(),
            )

            assertEquals(
                AgentMemoryOperationVerificationFailure.MEMORY_CHANGED,
                (verification as AgentMemoryOperationVerification.Failed).reason,
            )
        }

        val sourceRequest = verificationRequest("edit-source")
        val sourceKey = "tool-call-memory-edit-source"
        val sourceMemory = rememberOperation(store, sourceKey, sourceRequest)
        database?.openHelper?.writableDatabase?.execSQL(
            "UPDATE agent_memories SET sourceSummary = ? WHERE id = ?",
            arrayOf("来源已被修改", sourceMemory.id),
        )
        val sourceVerification = store.verifyRememberedOperation(
            sourceKey,
            sourceMemory.id,
            sourceRequest,
            System.currentTimeMillis(),
        )
        assertEquals(
            AgentMemoryOperationVerificationFailure.MEMORY_CHANGED,
            (sourceVerification as AgentMemoryOperationVerification.Failed).reason,
        )
    }

    @Test
    fun governanceMetadataDoesNotInvalidateOriginalBusinessSnapshot() = runBlocking {
        val request = verificationRequest("governance")
        val store = openStore()
        val key = "tool-call-memory-governance"
        val created = rememberOperation(store, key, request)
        val now = System.currentTimeMillis()
        store.setPinned(created.id, true)
        store.setExpiresAt(created.id, now + 60_000L)
        store.search(request.content, 10, enabledOnly = true)

        val verification = store.verifyRememberedOperation(
            key,
            created.id,
            request,
            now + 1L,
        )

        val verified = (verification as AgentMemoryOperationVerification.Verified).memory
        assertEquals(true, verified.pinned)
        assertEquals(now + 60_000L, verified.expiresAt)
        assertTrue(verified.lastReferencedAt != null)
    }

    @Test
    fun disabledAndExpiredRememberOperationsFailWithExplicitState() = runBlocking {
        val store = openStore()
        val disabledRequest = verificationRequest("disabled")
        val disabled = rememberOperation(store, "tool-call-memory-disabled", disabledRequest)
        store.setEnabled(disabled.id, false)
        val disabledVerification = store.verifyRememberedOperation(
            "tool-call-memory-disabled",
            disabled.id,
            disabledRequest,
            System.currentTimeMillis(),
        )

        val expiredRequest = verificationRequest("expired")
        val expired = rememberOperation(store, "tool-call-memory-expired", expiredRequest)
        val expiresAt = System.currentTimeMillis() + 60_000L
        store.setExpiresAt(expired.id, expiresAt)
        val expiredVerification = store.verifyRememberedOperation(
            "tool-call-memory-expired",
            expired.id,
            expiredRequest,
            expiresAt + 1L,
        )

        assertEquals(
            AgentMemoryOperationVerificationFailure.MEMORY_DISABLED,
            (disabledVerification as AgentMemoryOperationVerification.Failed).reason,
        )
        assertEquals(
            AgentMemoryOperationVerificationFailure.MEMORY_EXPIRED,
            (expiredVerification as AgentMemoryOperationVerification.Failed).reason,
        )
    }

    @Test
    fun deletedRememberOperationFailsUntilOriginalSnapshotIsRestored() = runBlocking {
        val request = verificationRequest("restore")
        val store = openStore()
        val created = rememberOperation(store, "tool-call-memory-restore", request)
        val deleted = checkNotNull(store.delete(created.id))

        val deletedVerification = store.verifyRememberedOperation(
            "tool-call-memory-restore",
            created.id,
            request,
            System.currentTimeMillis(),
        )
        store.restore(deleted)
        val restoredVerification = store.verifyRememberedOperation(
            "tool-call-memory-restore",
            created.id,
            request,
            System.currentTimeMillis(),
        )

        assertEquals(
            AgentMemoryOperationVerificationFailure.MEMORY_NOT_FOUND,
            (deletedVerification as AgentMemoryOperationVerification.Failed).reason,
        )
        assertEquals(created, (restoredVerification as AgentMemoryOperationVerification.Verified).memory)
    }

    @Test
    fun legacyOperationWithoutResultSnapshotFailsClosed() = runBlocking {
        val request = verificationRequest("legacy")
        val store = openStore()
        val created = rememberOperation(store, "tool-call-memory-legacy", request)
        database?.openHelper?.writableDatabase?.execSQL(
            "UPDATE agent_memory_operations SET resultHash = NULL WHERE idempotencyKey = ?",
            arrayOf("tool-call-memory-legacy"),
        )

        val verification = store.verifyRememberedOperation(
            "tool-call-memory-legacy",
            created.id,
            request,
            System.currentTimeMillis(),
        )

        assertEquals(
            AgentMemoryOperationVerificationFailure.EVIDENCE_INCOMPLETE,
            (verification as AgentMemoryOperationVerification.Failed).reason,
        )
    }

    @Test
    fun memoryDeleteOperationSurvivesReopenAndFailsAfterUserRestoresTarget() = runBlocking {
        var store = openStore()
        val created = store.remember(
            content = "用户要求删除的长期记忆",
            tags = "delete",
            type = "Preference",
            source = AgentMemorySource("conversation-delete", "run-delete", "用户确认保存"),
            confidence = 0.8,
        )

        assertTrue(store.deleteForAgent(created.id, "tool-call-memory-delete"))
        assertEquals(null, store.get(created.id))
        database?.close()
        database = null
        store = openStore()

        assertEquals(
            AgentMemoryDeleteOperationVerification.Verified,
            store.verifyDeletedOperation("tool-call-memory-delete", created.id),
        )
        assertTrue(store.deleteForAgent(created.id, "tool-call-memory-delete"))
        store.restore(checkNotNull(store.latestDeleted()))

        assertEquals(
            AgentMemoryDeleteOperationVerificationFailure.MEMORY_STILL_EXISTS,
            (store.verifyDeletedOperation("tool-call-memory-delete", created.id) as AgentMemoryDeleteOperationVerification.Failed).reason,
        )
        assertTrue(
            runCatching { store.deleteForAgent(created.id, "tool-call-memory-delete") }
                .exceptionOrNull() is AgentMemoryIdempotencyConflictException,
        )
    }

    private suspend fun rememberOperation(
        store: RoomAgentMemoryStore,
        idempotencyKey: String,
        request: AgentMemoryWriteRequest,
    ) = store.remember(
        content = request.content,
        tags = request.tags,
        type = request.type,
        source = request.source,
        confidence = request.confidence,
        idempotencyKey = idempotencyKey,
    )

    private fun verificationRequest(suffix: String) = AgentMemoryWriteRequest(
        content = "用户喜欢紧凑界面 $suffix",
        tags = "ui $suffix",
        type = "Preference",
        source = AgentMemorySource("conversation-$suffix", "run-$suffix", "用户确认保存"),
        confidence = 0.8,
    )

    private fun openStore(): RoomAgentMemoryStore {
        val opened = Room.databaseBuilder(context, XiaoLingDatabase::class.java, DATABASE_NAME)
            .addMigrations(*XiaoLingDatabase.migrations())
            .allowMainThreadQueries()
            .build()
        database = opened
        return RoomAgentMemoryStore(context, opened)
    }

    companion object {
        private const val DATABASE_NAME = "xiaoling-memory-idempotency-instrumented-test.db"
    }
}
