package com.longdev.xiaoling.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class RemoteChannelPullReliabilityTest {
    private val source = "channel\u0000sender\u0000key\u0000${"a".repeat(64)}"

    @Test fun checkpointRoundTripKeepsCursorAckAndNullableFields() {
        val checkpoint = RemoteChannelPullCheckpoint(
            sourceIdentity = source,
            cursor = "cursor-17",
            pendingAckMessageIds = listOf("message-1", "message-2"),
            consecutiveFailures = 2,
            nextAttemptAtMillis = 2_000L,
            lastMessageId = "message-2",
            lastErrorCode = RemoteChannelPullErrorCode.NETWORK.name,
            updatedAtMillis = 1_000L,
        )
        val decoded = RemoteChannelPullCheckpointJsonCodec.decode(
            RemoteChannelPullCheckpointJsonCodec.encode(listOf(checkpoint)),
        )
        assertEquals(listOf(checkpoint), decoded)
        val empty = RemoteChannelPullCheckpointJsonCodec.decode(
            RemoteChannelPullCheckpointJsonCodec.encode(listOf(RemoteChannelPullCheckpoint(checkpoint.sourceIdentity))),
        )
        assertEquals(null, empty.single().cursor)
        assertEquals(null, empty.single().lastMessageId)
        assertEquals(null, empty.single().lastErrorCode)
    }

    @Test fun corruptCheckpointStateFailsClosed() {
        val registry = RemoteChannelPullCheckpointRegistry(TestStore("{broken"))
        assertTrue(!registry.isAvailable())
        assertTrue(runCatching { registry.beforeAttempt(source) }.isFailure)
        assertTrue(runCatching { registry.recordAccepted(source, "message-1") }.isFailure)
    }

    @Test fun retryBackoffGrowsAndSuccessClearsFailure() {
        var now = 1_000L
        val store = TestStore()
        val registry = RemoteChannelPullCheckpointRegistry(store) { now }
        registry.recordRetryableFailure(source, java.io.IOException("offline"))
        assertEquals(1, registry.get(source)?.consecutiveFailures)
        assertEquals(31_000L, registry.get(source)?.nextAttemptAtMillis)
        assertTrue(runCatching { registry.beforeAttempt(source) }.isFailure)
        assertTrue(runCatching { RemoteChannelPullCheckpointRegistry(store) { now }.beforeAttempt(source) }.isFailure)
        now = 31_000L
        registry.beforeAttempt(source)
        registry.recordRetryableFailure(source, java.io.IOException("offline"))
        assertEquals(2, registry.get(source)?.consecutiveFailures)
        assertEquals(91_000L, registry.get(source)?.nextAttemptAtMillis)
        registry.recordAccepted(source, "message-1", cursor = "cursor-2")
        assertEquals(0, registry.get(source)?.consecutiveFailures)
        assertEquals("cursor-2", registry.get(source)?.cursor)
        assertEquals(listOf("message-1"), registry.get(source)?.pendingAckMessageIds)
        registry.acknowledge(source, "message-1")
        registry.acknowledge(source, "message-1")
        assertEquals(emptyList<String>(), registry.get(source)?.pendingAckMessageIds)
        assertEquals("cursor-2", registry.get(source)?.cursor)
    }

    @Test fun retryDelayIsBounded() {
        assertEquals(30_000L, RemoteChannelPullReliabilityPolicy.retryDelayMillis(1))
        assertEquals(60_000L, RemoteChannelPullReliabilityPolicy.retryDelayMillis(2))
        assertEquals(60 * 60 * 1_000L, RemoteChannelPullReliabilityPolicy.retryDelayMillis(20))
    }

    @Test fun strictCodecRejectsCoercedTypesUnknownFieldsAndInvalidNullableStrings() {
        val encoded = RemoteChannelPullCheckpointJsonCodec.encode(listOf(RemoteChannelPullCheckpoint(source)))
        val badEntries = listOf<Pair<String, Any>>(
            "sourceIdentity" to "arbitrary", "cursor" to "", "cursor" to 4,
            "pendingAckMessageIds" to JSONArray().put(123), "consecutiveFailures" to "2",
            "consecutiveFailures" to 1.5, "consecutiveFailures" to 2_147_483_648L,
            "nextAttemptAtMillis" to "30000", "updatedAtMillis" to 1.5,
            "lastMessageId" to "", "lastErrorCode" to "UNKNOWN", "extra" to true,
        )
        badEntries.forEach { (field, value) ->
            val json = JSONObject(encoded)
            json.getJSONArray("checkpoints").getJSONObject(0).put(field, value)
            assertTrue(field, runCatching { RemoteChannelPullCheckpointJsonCodec.decode(json.toString()) }.isFailure)
        }
        assertTrue(runCatching {
            RemoteChannelPullCheckpointJsonCodec.decode(JSONObject(encoded).put("schemaVersion", "1").toString())
        }.isFailure)
    }

    @Test fun writeFailureFreezesEveryMutationAndPreservesPreviouslyCommittedState() {
        val store = TestStore()
        val registry = RemoteChannelPullCheckpointRegistry(store) { 1_000L }
        registry.recordAccepted(source, "message-1", "cursor-1")
        val original = store.state
        store.failWrites = true
        assertTrue(runCatching { registry.recordAccepted(source, "message-2", "cursor-2") }.isFailure)
        assertTrue(!registry.isAvailable())
        assertEquals(original, store.state)
        assertTrue(runCatching { registry.recordDuplicate(source, "message-1") }.isFailure)
        assertTrue(runCatching { registry.acknowledge(source, "message-1") }.isFailure)
        assertTrue(runCatching { registry.beforeAttempt(source) }.isFailure)
        val restored = RemoteChannelPullCheckpointRegistry(store) { 1_000L }.get(source)!!
        assertEquals("cursor-1", restored.cursor)
        assertEquals(listOf("message-1"), restored.pendingAckMessageIds)
    }

    @Test fun fullAckQueueBlocksPullWithoutDroppingAnyPendingIdentity() {
        val registry = RemoteChannelPullCheckpointRegistry(TestStore()) { 1_000L }
        repeat(RemoteChannelPullReliabilityPolicy.MAX_ACK_MESSAGE_IDS) { registry.recordAccepted(source, "message-$it") }
        val original = registry.get(source)
        assertTrue(runCatching { registry.beforeAttempt(source) }.isFailure)
        assertTrue(runCatching { registry.recordAccepted(source, "overflow") }.isFailure)
        assertEquals(original, registry.get(source))
        registry.acknowledge(source, "message-0")
        registry.beforeAttempt(source)
    }

    @Test fun fullSourceLedgerRejectsNewIdentityInsteadOfEvictingOldSource() {
        val store = TestStore()
        val registry = RemoteChannelPullCheckpointRegistry(store) { 1_000L }
        repeat(RemoteChannelPullReliabilityPolicy.MAX_CHECKPOINTS) {
            registry.recordAccepted(source.replace("sender", "sender-$it"), "message-$it")
        }
        val original = store.state
        assertTrue(runCatching { registry.beforeAttempt(source) }.isFailure)
        assertTrue(runCatching { registry.recordAccepted(source, "overflow") }.isFailure)
        assertEquals(original, store.state)
        assertEquals(32, RemoteChannelPullCheckpointJsonCodec.decode(store.state).size)
    }

    @Test fun protocolRejectionAllowsManualRecoveryAndDuplicateDoesNotInventAck() {
        val registry = RemoteChannelPullCheckpointRegistry(TestStore()) { 1_000L }
        registry.recordAccepted(source, "message-1", "cursor-1")
        registry.recordNonRetryableFailure(source, RemoteChannelPullErrorCode.INVALID_RESPONSE)
        registry.beforeAttempt(source)
        assertEquals(RemoteChannelPullErrorCode.INVALID_RESPONSE.name, registry.get(source)?.lastErrorCode)
        registry.recordDuplicate(source, "unaccepted-nonce-replay")
        assertEquals(listOf("message-1"), registry.get(source)?.pendingAckMessageIds)
        assertEquals("cursor-1", registry.get(source)?.cursor)
    }

    @Test fun counterAndTimeOverflowCannotResetBackoff() {
        val store = TestStore(RemoteChannelPullCheckpointJsonCodec.encode(listOf(
            RemoteChannelPullCheckpoint(source, consecutiveFailures = Int.MAX_VALUE),
        )))
        val registry = RemoteChannelPullCheckpointRegistry(store) { Long.MAX_VALUE - 1 }
        registry.recordRetryableFailure(source, java.io.IOException("offline"))
        assertEquals(Int.MAX_VALUE, registry.get(source)?.consecutiveFailures)
        assertEquals(Long.MAX_VALUE, registry.get(source)?.nextAttemptAtMillis)
    }

    @Test fun classificationKeepsTimeoutProtocolAndIdentityErrorsDistinct() {
        assertEquals(RemoteChannelPullErrorCode.TIMEOUT, RemoteChannelPullReliabilityPolicy.classify(java.net.SocketTimeoutException()))
        assertEquals(RemoteChannelPullErrorCode.TIMEOUT, RemoteChannelPullReliabilityPolicy.classify(java.io.InterruptedIOException()))
        assertEquals(RemoteChannelPullErrorCode.NETWORK, RemoteChannelPullReliabilityPolicy.classify(java.io.IOException()))
        assertEquals(RemoteChannelPullErrorCode.SERVER_5XX, RemoteChannelPullReliabilityPolicy.classify(RemoteChannelPullHttpException(503)))
        assertEquals(RemoteChannelPullErrorCode.SERVER_4XX, RemoteChannelPullReliabilityPolicy.classify(RemoteChannelPullHttpException(403)))
        assertEquals(RemoteChannelPullErrorCode.INVALID_RESPONSE, RemoteChannelPullReliabilityPolicy.classify(RemoteChannelPullHttpException(302)))
        assertEquals(RemoteChannelPullErrorCode.INVALID_RESPONSE, RemoteChannelPullReliabilityPolicy.classify(org.json.JSONException("broken")))
        assertEquals(RemoteChannelPullErrorCode.INVALID_RESPONSE, RemoteChannelPullReliabilityPolicy.classify(java.nio.charset.MalformedInputException(1)))
        assertEquals(RemoteChannelPullErrorCode.IDENTITY_MISMATCH, RemoteChannelPullReliabilityPolicy.classify(RemoteChannelPullIdentityMismatchException()))
    }

    @Test fun identityRotationDoesNotInheritOldSenderBackoff() {
        val registry = RemoteChannelPullCheckpointRegistry(TestStore()) { 1_000L }
        registry.recordRetryableFailure(source, java.io.IOException("offline"))
        val rotated = source.replace("key\u0000", "key-2\u0000").replace("a".repeat(64), "b".repeat(64))
        registry.beforeAttempt(rotated)
        registry.recordAccepted(rotated, "new-message")
        assertEquals(31_000L, registry.get(source)?.nextAttemptAtMillis)
        assertEquals(listOf("new-message"), registry.get(rotated)?.pendingAckMessageIds)
        assertTrue(runCatching { registry.beforeAttempt(source) }.isFailure)
    }

    @Test fun legacySingleItemSuccessDoesNotFillUnsendableAckQueue() {
        val registry = RemoteChannelPullCheckpointRegistry(TestStore()) { 1_000L }
        repeat(RemoteChannelPullReliabilityPolicy.MAX_ACK_MESSAGE_IDS + 1) {
            registry.beforeAttempt(source)
            registry.recordAccepted(source, "legacy-message-$it", awaitingAck = false)
        }
        assertEquals(emptyList<String>(), registry.get(source)?.pendingAckMessageIds)
        assertEquals("legacy-message-64", registry.get(source)?.lastMessageId)
    }

    private class TestStore(var state: String? = null) : RemoteChannelPullCheckpointStateStore {
        var failWrites = false
        override fun loadState(): String? = state

        override fun saveState(state: String) {
            check(!failWrites) { "fixture write failure" }
            this.state = state
        }
    }
}
