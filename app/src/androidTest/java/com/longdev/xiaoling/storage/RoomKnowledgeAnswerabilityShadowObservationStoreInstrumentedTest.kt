package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.data.KnowledgeAnswerabilityShadowObservationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityDecision
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityJudgeFailureKind
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityJudgeIdentity
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowBindingReason
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowBindingStatus
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationRecord
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowObservationStatus
import com.longdev.xiaoling.knowledge.KnowledgeAnswerabilityShadowTelemetry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class RoomKnowledgeAnswerabilityShadowObservationStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: XiaoLingDatabase
    private lateinit var store: RoomKnowledgeAnswerabilityShadowObservationStore

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
        reopen()
    }

    @After
    fun tearDown() {
        database.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun duplicateWriteAndDatabaseReopenKeepOneAnonymousObservation() = runBlocking {
        val completed = record(
            idempotencyKey = "a".repeat(64),
            candidateFingerprint = "b".repeat(64),
            telemetry = KnowledgeAnswerabilityShadowTelemetry(
                attempts = 2,
                latencyMs = 120L,
                firstByteLatencyMs = 90L,
                promptBytes = 512L,
                inputTokens = 7L,
                outputTokens = 3L,
                totalTokens = 10L,
                usageSamples = 1,
                failureCounts = mapOf(KnowledgeAnswerabilityJudgeFailureKind.TRANSIENT_NETWORK to 1),
            ),
        )
        store.persist(completed)
        store.persist(completed.copy(recordedAt = 9_999L))
        store.persist(
            record(
                idempotencyKey = "c".repeat(64),
                candidateFingerprint = "d".repeat(64),
                status = KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN,
                bindingStatus = KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN,
                bindingReason = KnowledgeAnswerabilityShadowBindingReason.MISSING_MEASUREMENT,
                decision = KnowledgeAnswerabilityDecision.UNKNOWN,
                failureKind = KnowledgeAnswerabilityJudgeFailureKind.AUTHENTICATION,
                telemetry = KnowledgeAnswerabilityShadowTelemetry(
                    attempts = 1,
                    failureCounts = mapOf(KnowledgeAnswerabilityJudgeFailureKind.AUTHENTICATION to 1),
                ),
                recordedAt = 2_000L,
            ),
        )

        database.close()
        reopen()
        val summary = store.summary()

        assertEquals(2, summary.observationCount)
        assertEquals(1, summary.judgeIdentityCount)
        assertEquals(1, summary.completedCount)
        assertEquals(1, summary.unknownCount)
        assertEquals(3, summary.judgeAttemptCount)
        assertEquals(120L, summary.latencyMs)
        assertEquals(10L, summary.totalTokens)
        assertEquals(1, summary.failureCounts[KnowledgeAnswerabilityJudgeFailureKind.TRANSIENT_NETWORK])
        assertEquals(1, summary.failureCounts[KnowledgeAnswerabilityJudgeFailureKind.AUTHENTICATION])
        assertEquals(1_000L, summary.oldestRecordedAt)
        assertEquals(2_000L, summary.latestRecordedAt)

        val sqlite = database.openHelper.readableDatabase
        val columns = buildSet {
            sqlite.query("PRAGMA table_info(knowledge_answerability_shadow_observations)").use { cursor ->
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertFalse(columns.contains("sourceRunId"))
        assertFalse(columns.contains("persistedMessageId"))
        assertFalse(columns.contains("providerId"))
        assertFalse(columns.contains("model"))
        assertFalse(columns.contains("question"))
        assertFalse(columns.contains("candidateText"))
        assertFalse(columns.contains("baseUrl"))
        assertFalse(columns.contains("apiKey"))
        sqlite.query("SELECT * FROM knowledge_answerability_shadow_observations").use { cursor ->
            while (cursor.moveToNext()) {
                repeat(cursor.columnCount) { index ->
                    if (cursor.getType(index) == android.database.Cursor.FIELD_TYPE_STRING) {
                        val value = cursor.getString(index)
                        assertFalse(value.contains(SENSITIVE_MESSAGE_ID))
                        assertFalse(value.contains(SENSITIVE_RUN_ID))
                        assertFalse(value.contains("provider-sensitive"))
                        assertFalse(value.contains("model-sensitive"))
                        assertFalse(value.contains("prompt-sensitive"))
                    }
                }
            }
        }
        sqlite.query("SELECT judgeFingerprint FROM knowledge_answerability_shadow_observations").use { cursor ->
            assertTrue(cursor.moveToFirst())
            val persistedFingerprint = cursor.getString(0)
            assertTrue(persistedFingerprint.matches(Regex("^[0-9a-f]{64}$")))
            assertNotEquals(unkeyedJudgeFingerprint(), persistedFingerprint)
        }
    }

    @Test
    fun twoThousandAndFirstObservationPrunesTheOldestEntry() = runBlocking {
        val dao = database.knowledgeAnswerabilityShadowObservationDao()
        database.withTransaction {
            repeat(MAX_RETAINED_OBSERVATIONS) { index ->
                dao.insert(seedEntity(index))
            }
        }

        store.persist(
            record(
                idempotencyKey = sha256("observation-$MAX_RETAINED_OBSERVATIONS"),
                candidateFingerprint = "b".repeat(64),
                telemetry = KnowledgeAnswerabilityShadowTelemetry(attempts = 1),
                recordedAt = MAX_RETAINED_OBSERVATIONS.toLong(),
            ),
        )

        val summary = store.summary()
        assertEquals(MAX_RETAINED_OBSERVATIONS, summary.observationCount)
        assertEquals(1L, summary.oldestRecordedAt)
        assertEquals(MAX_RETAINED_OBSERVATIONS.toLong(), summary.latestRecordedAt)
    }

    @Test
    fun rawCandidateTextCannotBeWrittenThroughFingerprintField() = runBlocking {
        try {
            store.persist(
                record(
                    idempotencyKey = "1".repeat(64),
                    candidateFingerprint = SENSITIVE_QUESTION,
                    telemetry = KnowledgeAnswerabilityShadowTelemetry(attempts = 1),
                ),
            )
            fail("正文不能作为候选指纹写入匿名账本")
        } catch (_: IllegalArgumentException) {
            assertEquals(0, store.summary().observationCount)
        }
    }

    @Test
    fun unknownNumericTelemetryRemainsUnknownInsteadOfBecomingZero() = runBlocking {
        store.persist(
            record(
                idempotencyKey = "e".repeat(64),
                candidateFingerprint = "f".repeat(64),
                status = KnowledgeAnswerabilityShadowObservationStatus.UNKNOWN,
                bindingStatus = KnowledgeAnswerabilityShadowBindingStatus.UNKNOWN,
                bindingReason = KnowledgeAnswerabilityShadowBindingReason.MISSING_MEASUREMENT,
                decision = KnowledgeAnswerabilityDecision.UNKNOWN,
                failureKind = KnowledgeAnswerabilityJudgeFailureKind.UNEXPECTED,
                telemetry = KnowledgeAnswerabilityShadowTelemetry(attempts = 1),
            ),
        )

        val summary = store.summary()

        assertNull(summary.latencyMs)
        assertNull(summary.firstByteLatencyMs)
        assertNull(summary.promptBytes)
        assertNull(summary.inputTokens)
        assertNull(summary.outputTokens)
        assertNull(summary.totalTokens)
        assertEquals(1, summary.failureCounts[KnowledgeAnswerabilityJudgeFailureKind.UNEXPECTED])
    }

    private fun reopen() {
        database = Room.databaseBuilder(context, XiaoLingDatabase::class.java, DATABASE_NAME)
            .addMigrations(*XiaoLingDatabase.migrations())
            .build()
        store = RoomKnowledgeAnswerabilityShadowObservationStore(context, database)
    }

    private fun record(
        idempotencyKey: String,
        candidateFingerprint: String,
        status: KnowledgeAnswerabilityShadowObservationStatus = KnowledgeAnswerabilityShadowObservationStatus.COMPLETED,
        bindingStatus: KnowledgeAnswerabilityShadowBindingStatus = KnowledgeAnswerabilityShadowBindingStatus.BOUND,
        bindingReason: KnowledgeAnswerabilityShadowBindingReason = KnowledgeAnswerabilityShadowBindingReason.BOUND,
        decision: KnowledgeAnswerabilityDecision = KnowledgeAnswerabilityDecision.ACCEPT,
        failureKind: KnowledgeAnswerabilityJudgeFailureKind? = null,
        telemetry: KnowledgeAnswerabilityShadowTelemetry,
        recordedAt: Long = 1_000L,
    ) = KnowledgeAnswerabilityShadowObservationRecord(
        sourceRunId = SENSITIVE_RUN_ID,
        persistedMessageId = SENSITIVE_MESSAGE_ID,
        candidateFingerprint = candidateFingerprint,
        idempotencyKey = idempotencyKey,
        judgeIdentity = KnowledgeAnswerabilityJudgeIdentity(
            providerId = "provider-sensitive",
            model = "model-sensitive",
            configurationFingerprint = "9".repeat(64),
            promptVersion = "prompt-sensitive",
        ),
        attemptCount = telemetry.attempts,
        observationStatus = status,
        bindingStatus = bindingStatus,
        bindingReason = bindingReason,
        decision = decision,
        failureKind = failureKind,
        telemetry = telemetry,
        recordedAt = recordedAt,
    )

    private fun seedEntity(index: Int) = KnowledgeAnswerabilityShadowObservationEntity(
        idempotencyKey = sha256("observation-$index"),
        candidateFingerprint = "b".repeat(64),
        judgeFingerprint = null,
        attemptCount = 1,
        observationStatus = KnowledgeAnswerabilityShadowObservationStatus.COMPLETED.name,
        bindingStatus = KnowledgeAnswerabilityShadowBindingStatus.BOUND.name,
        bindingReason = KnowledgeAnswerabilityShadowBindingReason.BOUND.name,
        decision = KnowledgeAnswerabilityDecision.ACCEPT.name,
        failureKind = null,
        latencyMs = null,
        firstByteLatencyMs = null,
        promptBytes = null,
        inputTokens = null,
        outputTokens = null,
        totalTokens = null,
        usageSamples = 0,
        transientNetworkFailureCount = 0,
        rateLimitFailureCount = 0,
        serverFailureCount = 0,
        protocolFailureCount = 0,
        authenticationFailureCount = 0,
        clientRequestFailureCount = 0,
        identityFailureCount = 0,
        modelFailureCount = 0,
        invalidCandidateFailureCount = 0,
        unexpectedFailureCount = 0,
        recordedAt = index.toLong(),
    )

    private fun unkeyedJudgeFingerprint(): String {
        val canonical = listOf(
            "provider-sensitive",
            "model-sensitive",
            "9".repeat(64),
            "prompt-sensitive",
        ).joinToString(separator = "") { value -> "${value.length}:$value|" }
        return sha256(canonical)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte) }

    private companion object {
        const val MAX_RETAINED_OBSERVATIONS = 2_000
        const val DATABASE_NAME = "answerability-shadow-store-test.db"
        const val SENSITIVE_QUESTION = "这是绝不能落库的问题"
        const val SENSITIVE_MESSAGE_ID = "message-sensitive"
        const val SENSITIVE_RUN_ID = "run-sensitive"
    }
}
