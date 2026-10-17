package com.longdev.xiaoling.agent

import com.longdev.xiaoling.storage.RemoteChannelInboxSnapshotCodec
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RemoteChannelBatchTest {
    private var now = 1_000_000L
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val peer = RemoteChannelPairingPolicy.prepare("channel", "sender", "key-1", "批次测试", Base64.getEncoder().encodeToString(keys.public.encoded))
    private val pairings = RemoteChannelPairingRegistry(object : RemoteChannelPairingStateStore {
        var state: String? = null
        override fun loadState() = state
        override fun saveState(state: String) { this.state = state }
    }).apply { confirm(peer, peer.fingerprintSha256); setEnabled(peer.channelId, peer.senderId, peer.keyId, true) }
    private val sources = RemoteChannelPullSourceRegistry(object : RemoteChannelPullSourceStateStore {
        var state: String? = null
        override fun loadState() = state
        override fun saveState(state: String) { this.state = state }
    }, pairings)
    private val source = sources.save(peer, "https://inbox.example/batch")
    private val store = InboxStore()
    private val inbox = gateway()
    private val state = inbox.batchState(source, peer)

    @Test fun outerSignatureBindsCursorReceiverExpiryAndEveryMessage() {
        val batch = batch(listOf(message("one"), message("two")))
        assertEquals(batch, (RemoteChannelBatchProtocol.decodeResponse(batchJson(batch)) as RemoteChannelPullResponse.Batch).batch)
        RemoteChannelBatchProtocol.verifyBatch(batch, peer, state, now)
        listOf(batch.copy(nextCursor = "skipped"), batch.copy(receiverId = "b".repeat(32)),
            batch.copy(expiresAtMillis = now + 200_000), batch.copy(messages = batch.messages.reversed())).forEach {
            assertTrue(runCatching { RemoteChannelBatchProtocol.verifyBatch(it, peer, state, now) }.isFailure)
        }
        assertTrue(runCatching { RemoteChannelBatchProtocol.verifyBatch(batch, peer, state, now + 61_000) }.isFailure)
        val invalid = JSONObject(batchJson(batch)).put("protocolVersion", "1")
        assertTrue(runCatching { RemoteChannelBatchProtocol.decodeResponse(invalid.toString()) }.isFailure)
        invalid.put("protocolVersion", 1).put("ackUrl", "https://other.example/ack")
        assertTrue(runCatching { RemoteChannelBatchProtocol.decodeResponse(invalid.toString()) }.isFailure)
    }

    @Test fun invalidSecondSignatureAndStorageFailureCannotPartiallyCommitBatch() {
        val original = store.snapshot
        val invalid = batch(listOf(message("one"), message("two").copy(text = "篡改")))
        assertTrue(runCatching { inbox.receiveBatch(source, peer, invalid) }.isFailure)
        assertEquals(original, store.snapshot)
        store.failWrites = true
        assertTrue(runCatching { inbox.receiveBatch(source, peer, batch(listOf(message("one"), message("two")))) }.isFailure)
        assertEquals(original, store.snapshot)
        store.failWrites = false
        assertEquals(RemoteChannelBatchReceiveResult(2, 0), inbox.receiveBatch(source, peer, batch(listOf(message("one"), message("two")))))
        val restored = gateway().batchState(source, peer)
        assertEquals("cursor-1", restored.cursor)
        assertEquals(listOf("one", "two"), restored.pendingAck?.messageIds)
        assertEquals(2, (gateway().status() as RemoteChannelInboxStatus.Ready).pending.size)
    }

    @Test fun fullInboxAndNonceCollisionCannotAdvanceCursorOrCreateAck() {
        repeat(7) { assertTrue(inbox.receive(message("old-$it")) is RemoteChannelReceiveResult.Accepted) }
        val original = store.snapshot
        assertTrue(runCatching { inbox.receiveBatch(source, peer, batch(listOf(message("new-1"), message("new-2")))) }.isFailure)
        assertEquals(original, store.snapshot)
        val collision = message("new-id", nonce = message("old-0").nonce!!)
        assertTrue(runCatching { inbox.receiveBatch(source, peer, batch(listOf(collision))) }.isFailure)
        assertEquals(original, store.snapshot)
        assertEquals(null, inbox.batchState(source, peer).pendingAck)
    }

    @Test fun duplicateMessagesAreAckableButOldBatchCursorCannotReplay() {
        val old = message("old")
        assertTrue(inbox.receive(old) is RemoteChannelReceiveResult.Accepted)
        val batch = batch(listOf(old))
        assertEquals(RemoteChannelBatchReceiveResult(0, 1), inbox.receiveBatch(source, peer, batch))
        val receipt = inbox.batchState(source, peer).pendingAck!!
        inbox.acknowledgeBatch(source, peer, receipt)
        val snapshot = store.snapshot
        assertTrue(runCatching { inbox.receiveBatch(source, peer, batch) }.isFailure)
        assertEquals(snapshot, store.snapshot)
    }

    @Test fun discardExpiryAndLegacyReceivePreserveOutboxAndCursor() {
        inbox.receiveBatch(source, peer, batch(listOf(message("one"))))
        val committed = inbox.batchState(source, peer)
        val draft = (inbox.status() as RemoteChannelInboxStatus.Ready).pending.single().draft
        assertTrue(inbox.discard(draft))
        assertTrue(inbox.receive(message("legacy")) is RemoteChannelReceiveResult.Accepted)
        assertEquals(committed, inbox.batchState(source, peer))
        now += RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS + 1
        assertTrue((inbox.status() as RemoteChannelInboxStatus.Ready).pending.isEmpty())
        assertEquals(committed, inbox.batchState(source, peer))
    }

    @Test fun v1SnapshotMigratesAndV2StateRoundTripRejectsMissingOrUnknownFields() {
        inbox.receiveBatch(source, peer, batch(listOf(message("one"))))
        val snapshot = store.snapshot!!
        val v2 = RemoteChannelInboxSnapshotCodec.encode(snapshot)
        assertEquals(snapshot, RemoteChannelInboxSnapshotCodec.decode(v2))
        val v1 = JSONObject(v2).put("schemaVersion", 1).apply { remove("batchStates") }
        assertTrue(RemoteChannelInboxSnapshotCodec.decode(v1.toString()).batchStates.isEmpty())
        assertEquals(snapshot.pendingDrafts, RemoteChannelInboxSnapshotCodec.decode(v1.toString()).pendingDrafts)
        val missing = JSONObject(v2).apply { remove("batchStates") }
        assertTrue(runCatching { RemoteChannelInboxSnapshotCodec.decode(missing.toString()) }.isFailure)
        val corrupt = JSONObject(v2)
        corrupt.getJSONArray("batchStates").getJSONObject(0).put("extra", true)
        assertTrue(runCatching { RemoteChannelInboxSnapshotCodec.decode(corrupt.toString()) }.isFailure)
    }

    @Test fun serviceCommitsBeforePostingAckAndEmptyBatchHasNoFakeDraft() = runBlocking {
        val methods = mutableListOf<String>()
        val service = service { request ->
            methods += request.method
            assertEquals(source.url, request.url.toString())
            if (request.method == "GET") {
                assertEquals(state.receiverId, request.header("X-Xiaoling-Receiver"))
                response(request, batchJson(batch(listOf(message("one"), message("two")))))
            } else {
                assertEquals(2, (gateway().status() as RemoteChannelInboxStatus.Ready).pending.size)
                response(request, ackJson(inbox.batchState(source, peer)))
            }
        }
        assertEquals(RemoteChannelPullSyncResult.Batch(2, 0, false), service.synchronize(peer))
        assertEquals(listOf("GET", "POST"), methods)
        assertEquals(null, inbox.batchState(source, peer).pendingAck)
        val current = inbox.batchState(source, peer)
        val empty = service { request ->
            if (request.method == "GET") {
                assertEquals(Base64.getUrlEncoder().withoutPadding().encodeToString("cursor-1".toByteArray()), request.header("X-Xiaoling-Cursor"))
                response(request, batchJson(batch(emptyList(), current, "cursor-1", "empty-batch")))
            } else response(request, ackJson(inbox.batchState(source, peer)))
        }
        assertEquals(RemoteChannelPullSyncResult.Batch(0, 0, false), empty.synchronize(peer))
        assertEquals(2, (inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
    }

    @Test fun lostAckResponseRecoversOutboxAcrossRecreationAndRetriesSameReceiptOnly() = runBlocking {
        val retryStore = CheckpointStore()
        var firstAck: String? = null
        val initial = service(retryStore) { request ->
            if (request.method == "GET") response(request, batchJson(batch(listOf(message("one")))))
            else {
                firstAck = body(request)
                throw java.io.IOException("fixture lost ACK response")
            }
        }
        assertEquals(RemoteChannelPullSyncResult.Batch(1, 0, true), initial.synchronize(peer))
        val receipt = gateway().batchState(source, peer).pendingAck!!
        var requests = 0
        val restored = service(retryStore, gateway()) { request ->
            requests += 1
            assertEquals("POST", request.method)
            assertEquals(firstAck, body(request))
            response(request, ackJson(gateway().batchState(source, peer)))
        }
        assertTrue(runCatching { restored.synchronize(peer) }.exceptionOrNull() is RemoteChannelPullDeferredException)
        assertEquals(0, requests)
        now += 30_000
        assertEquals(RemoteChannelPullSyncResult.Batch(0, 0, false), restored.synchronize(peer))
        assertEquals(1, requests)
        assertEquals(null, gateway().batchState(source, peer).pendingAck)
        assertEquals("cursor-1", gateway().batchState(source, peer).cursor)
        assertEquals(listOf("one"), receipt.messageIds)
        assertEquals(1, (inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
    }

    @Test fun forgedAckAndLateSourceChangeCannotClearOutbox() = runBlocking {
        val service = service { request ->
            if (request.method == "GET") response(request, batchJson(batch(listOf(message("one")))))
            else response(request, JSONObject(ackJson(inbox.batchState(source, peer))).put("batchId", "other").toString())
        }
        assertEquals(RemoteChannelPullSyncResult.Batch(1, 0, true), service.synchronize(peer))
        val committed = inbox.batchState(source, peer)
        val late = service { request ->
            assertEquals("POST", request.method)
            val reply = ackJson(committed)
            sources.save(peer, "https://other.example/batch")
            response(request, reply)
        }
        assertTrue(runCatching { late.synchronize(peer) }.isFailure)
        assertEquals(committed, gateway().batchState(source, peer))
    }

    @Test fun disabledPairingAndDamagedStorePreventAnyBatchNetworkRequest() = runBlocking {
        var calls = 0
        val service = service { calls += 1; error("network must not start") }
        pairings.setEnabled(peer.channelId, peer.senderId, peer.keyId, false)
        assertTrue(runCatching { service.synchronize(peer) }.isFailure)
        pairings.setEnabled(peer.channelId, peer.senderId, peer.keyId, true)
        store.failReads = true
        assertTrue(runCatching { service.synchronize(peer) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun concurrentGatewayRecreationCannotOverwriteBatchOrAdvanceTwice() = runBlocking {
        val value = batch(listOf(message("one")))
        val results = List(2) { async(Dispatchers.Default) { runCatching { gateway().receiveBatch(source, peer, value) } } }.awaitAll()
        assertEquals(1, results.count { it.isSuccess })
        assertEquals(1, (inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
        assertEquals("cursor-1", inbox.batchState(source, peer).cursor)
    }

    @Test fun legacyPullEntryCannotBypassCommittedCursorOrAckOutbox() = runBlocking {
        inbox.receiveBatch(source, peer, batch(listOf(message("one"))))
        var calls = 0
        val service = service { calls += 1; error("network must not start") }
        assertTrue(runCatching { service.pull(peer) }.isFailure)
        inbox.acknowledgeBatch(source, peer, inbox.batchState(source, peer).pendingAck!!)
        assertTrue(runCatching { service.pull(peer) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun cancellationDuringAckLeavesCommittedOutboxWithoutFailureBackoff() = runBlocking {
        val value = batch(listOf(message("one")))
        val ackState = state.copy(cursor = value.nextCursor, pendingAck = RemoteChannelBatchProtocol.receipt(value))
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(batchJson(value)))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBodyDelay(1, TimeUnit.SECONDS).setBody(ackJson(ackState)))
        server.start()
        try {
            val ackHeaders = CountDownLatch(1)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/fixture")).build()).also {
                    if (chain.request().method == "POST") ackHeaders.countDown()
                }
            }.build()
            val retry = CheckpointStore()
            val service = RemoteChannelPullService(pairings, sources, inbox, RemoteChannelHttpsPuller(client = client),
                RemoteChannelPullCheckpointRegistry(retry) { now })
            val job = launch(Dispatchers.Default) { service.synchronize(peer) }
            assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS)?.method)
            assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)?.method)
            assertTrue(ackHeaders.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertEquals(ackState, gateway().batchState(source, peer))
            assertEquals(1, (inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
            assertEquals(null, retry.state)
        } finally {
            Thread.sleep(1_100)
            server.shutdown()
        }
    }

    private fun gateway() = RemoteChannelInboxGateway(store, pairings) { now }
    @Test fun revokedBackgroundPermitAfterGetCannotCommitOrAck() = runBlocking {
        var allowed = true
        var requests = 0
        val service = service { request ->
            requests++
            allowed = false
            response(request, batchJson(batch(listOf(message("late")))))
        }
        assertTrue(runCatching { service.synchronize(peer) { allowed } }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(1, requests)
        assertTrue((inbox.status() as RemoteChannelInboxStatus.Ready).pending.isEmpty())
        assertEquals(null, inbox.batchState(source, peer).pendingAck)
    }

    @Test fun revokedBackgroundPermitAfterCommitPreservesAckOutbox() = runBlocking {
        var allowed = true
        val service = service { request ->
            if (request.method == "GET") response(request, batchJson(batch(listOf(message("committed")))))
            else {
                allowed = false
                response(request, ackJson(inbox.batchState(source, peer)))
            }
        }
        assertTrue(runCatching { service.synchronize(peer) { allowed } }.exceptionOrNull() is kotlinx.coroutines.CancellationException)
        assertEquals(1, (inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
        assertEquals("cursor-1", inbox.batchState(source, peer).cursor)
        assertNotNull(inbox.batchState(source, peer).pendingAck)
    }
    private fun message(id: String, nonce: String = "nonce-$id-1234567890123456"): RemoteChannelEnvelope {
        val envelope = RemoteChannelEnvelope(peer.channelId, id, peer.senderId, "conversation", "合成正文-$id", now, nonce, peer.keyId)
        return envelope.copy(signature = sign(RemoteChannelPolicy.canonicalBytes(envelope)))
    }
    private fun batch(messages: List<RemoteChannelEnvelope>, current: RemoteChannelBatchState = state, next: String = "cursor-1", id: String = "batch-1"): RemoteChannelSignedBatch {
        val value = RemoteChannelSignedBatch(peer.channelId, peer.senderId, peer.keyId, current.receiverId, id, current.cursor, next,
            now, now + 60_000, messages, "")
        return value.copy(signature = sign(RemoteChannelBatchProtocol.canonicalBatchBytes(value)))
    }
    private fun sign(content: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(Signature.getInstance("SHA256withECDSA").run {
        initSign(keys.private); update(content); sign()
    })
    private fun ackJson(current: RemoteChannelBatchState) = RemoteChannelBatchProtocol.ackFields(peer, current, "acknowledged")
        .put("signature", sign(RemoteChannelBatchProtocol.canonicalAckBytes(peer, current))).toString()
    private fun batchJson(value: RemoteChannelSignedBatch): String = JSONObject().put("protocolVersion", 1).put("channelId", value.channelId)
        .put("senderId", value.senderId).put("keyId", value.keyId).put("receiverId", value.receiverId).put("batchId", value.batchId)
        .put("requestCursor", value.requestCursor ?: JSONObject.NULL).put("nextCursor", value.nextCursor ?: JSONObject.NULL)
        .put("issuedAtMillis", value.issuedAtMillis).put("expiresAtMillis", value.expiresAtMillis).put("signature", value.signature)
        .put("messages", JSONArray(value.messages.map { message -> JSONObject().put("channelId", message.channelId).put("messageId", message.messageId)
            .put("senderId", message.senderId).put("conversationKey", message.conversationKey).put("text", message.text)
            .put("receivedAtMillis", message.receivedAtMillis).put("nonce", message.nonce).put("keyId", message.keyId).put("signature", message.signature) })).toString()
    private fun body(request: Request) = okio.Buffer().apply { request.body!!.writeTo(this) }.readUtf8()
    private fun response(request: Request, content: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(200).message("fixture").header("Content-Type", "application/json").body(content.toResponseBody("application/json".toMediaType())).build()
    private fun service(retry: CheckpointStore = CheckpointStore(), gateway: RemoteChannelInboxGateway = inbox, handler: (Request) -> Response) =
        RemoteChannelPullService(pairings, sources, gateway, RemoteChannelHttpsPuller(client = OkHttpClient.Builder().addInterceptor { handler(it.request()) }.build()),
            RemoteChannelPullCheckpointRegistry(retry) { now })
    private class InboxStore : RemoteChannelInboxStateStore {
        var snapshot: RemoteChannelInboxSnapshot? = null
        var failReads = false
        var failWrites = false
        override fun loadSnapshot(): RemoteChannelInboxSnapshot? { check(!failReads); return snapshot }
        override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { check(!failWrites); this.snapshot = snapshot }
    }
    private class CheckpointStore : RemoteChannelPullCheckpointStateStore {
        var state: String? = null
        override fun loadState() = state
        override fun saveState(state: String) { this.state = state }
    }
}
