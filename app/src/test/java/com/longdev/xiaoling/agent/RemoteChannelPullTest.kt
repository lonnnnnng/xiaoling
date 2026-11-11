package com.longdev.xiaoling.agent

import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteChannelPullTest {
    private val now = 1_000_000L
    private val keys = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val peer = RemoteChannelPairingPolicy.prepare(
        "channel", "sender", "key-1", "测试来源", Base64.getEncoder().encodeToString(keys.public.encoded),
    )
    private val pairStore = TestPairingStore()
    private val pairings = RemoteChannelPairingRegistry(pairStore).apply {
        confirm(peer, peer.fingerprintSha256)
        setEnabled(peer.channelId, peer.senderId, peer.keyId, true)
    }
    private val sourceStore = TestSourceStore()
    private val sources = RemoteChannelPullSourceRegistry(sourceStore, pairings)
    private val inboxStore = TestInboxStore()
    private val inbox = RemoteChannelInboxGateway(inboxStore, pairings) { now }

    @Test fun registrationBindsEndpointToCurrentFingerprintAndCorruptionFreezesReads() {
        assertTrue(runCatching { sources.save(peer.copy(fingerprintSha256 = "0".repeat(64)), "https://inbox.example/next") }.isFailure)
        val source = sources.save(peer, "https://inbox.example/next")
        assertEquals(peer.fingerprintSha256, source.fingerprintSha256)
        assertEquals(source, RemoteChannelPullSourceRegistry(sourceStore, pairings).sourceFor(peer))
        assertEquals(null, sources.sourceFor(peer.copy(fingerprintSha256 = "0".repeat(64))))
        sourceStore.state = "{broken"
        assertTrue(runCatching { sources.list() }.isFailure)
        assertTrue(runCatching { sources.save(peer, "https://inbox.example/new") }.isFailure)
    }

    @Test fun endpointAndDnsPolicyRejectUnsafeTargets() {
        listOf(
            "http://inbox.example/next", "https://localhost/next", "https://127.0.0.1/next",
            "https://[::1]/next", "https://user:pass@inbox.example/next",
            "https://inbox.example/next?token=secret", "https://inbox.example/next#part",
        ).forEach { assertTrue(it, runCatching { RemoteChannelPullPolicy.endpoint(it) }.isFailure) }
        listOf("127.0.0.1", "10.2.3.4", "100.64.1.2", "169.254.3.4", "192.168.1.1", "198.18.0.1", "2001:db8::1")
            .forEach { address ->
                assertTrue(address, runCatching {
                    RemoteChannelPullPolicy.publicAddresses(listOf(InetAddress.getByName(address)))
                }.isFailure)
            }
        assertEquals(1, RemoteChannelPullPolicy.publicAddresses(listOf(InetAddress.getByName("8.8.8.8"))).size)
        assertTrue(runCatching {
            RemoteChannelPullPolicy.publicAddresses(listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1")))
        }.isFailure)
    }

    @Test fun oneSignedResponseEntersDurableInboxButCannotAutoExecuteOrReplay() = runBlocking {
        sources.save(peer, "https://inbox.example/next")
        val envelope = signed("message-1", "sender")
        var calls = 0
        val puller = fakePuller { request ->
            calls += 1
            assertEquals("https://inbox.example/next", request.url.toString())
            assertEquals("GET", request.method)
            jsonResponse(request, envelopeJson(envelope))
        }
        val checkpoints = RemoteChannelPullCheckpointRegistry(TestCheckpointStore()) { now }
        val service = RemoteChannelPullService(pairings, sources, inbox, puller, checkpoints)
        assertTrue(service.pull(peer) is RemoteChannelReceiveResult.Accepted)
        assertEquals("message-1", (inbox.status() as RemoteChannelInboxStatus.Ready).pending.single().draft.messageId)
        assertEquals(RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (service.pull(peer) as RemoteChannelReceiveResult.Rejected).reason)
        assertEquals(2, calls)
        assertEquals(emptyList<String>(),
            checkpoints.get(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer))?.pendingAckMessageIds)
        pairings.setEnabled(peer.channelId, peer.senderId, peer.keyId, false)
        assertTrue(runCatching { service.pull(peer) }.isFailure)
        assertEquals(2, calls)
    }

    @Test fun rejectedDnsFailsThroughNetworkCallbackWithoutUncaughtPolicyException() = runBlocking {
        val dns = object : okhttp3.Dns {
            override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
        }
        // long: 使用生产 puller 的异步请求链，私网解析在建连前拒绝，不访问公网或测试设备。
        val failure = runCatching {
            withTimeout(2_000) { RemoteChannelHttpsPuller(dns = dns).fetch("https://inbox.example/next") }
        }.exceptionOrNull()
        assertTrue(failure.toString(), failure is java.net.UnknownHostException)
        assertTrue(failure?.message.orEmpty().contains("解析到本机、私有或保留网络"))
    }

    @Test fun publicDnsPreservesAllowedAddressesAndRejectsEmptyOrMixedAnswers() {
        val public = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        listOf(emptyList(), listOf(public, InetAddress.getLoopbackAddress())).forEach { answer ->
            val dns = RemoteChannelPublicDns(object : okhttp3.Dns {
                override fun lookup(hostname: String) = answer
            })
            assertTrue(runCatching { dns.lookup("inbox.example") }.exceptionOrNull() is java.net.UnknownHostException)
        }
        val dns = RemoteChannelPublicDns(object : okhttp3.Dns {
            override fun lookup(hostname: String) = listOf(public)
        })
        assertEquals(listOf(public), dns.lookup("inbox.example"))
    }

    @Test fun wrongIdentityRedirectAndOversizedResponseNeverEnterInbox() = runBlocking {
        sources.save(peer, "https://inbox.example/next")
        val wrongPeer = fakePuller { request -> jsonResponse(request, envelopeJson(signed("wrong", "other"))) }
        assertTrue(runCatching { RemoteChannelPullService(pairings, sources, inbox, wrongPeer).pull(peer) }.isFailure)
        val redirected = fakePuller { request -> response(request, 302, "", "application/json") }
        assertTrue(runCatching { RemoteChannelPullService(pairings, sources, inbox, redirected).pull(peer) }.isFailure)
        val oversized = fakePuller { request -> response(request, 200, "x".repeat(65 * 1024), "application/json") }
        assertTrue(runCatching { RemoteChannelPullService(pairings, sources, inbox, oversized).pull(peer) }.isFailure)
        assertTrue((inbox.status() as RemoteChannelInboxStatus.Ready).pending.isEmpty())
    }

    @Test fun cancellationClosesCallWhileJsonBodyIsStalled() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBodyDelay(1, TimeUnit.SECONDS).setBody("{}"))
        server.start()
        try {
            val headersReceived = CountDownLatch(1)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/fixture")).build()).also {
                    headersReceived.countDown()
                }
            }.build()
            val puller = RemoteChannelHttpsPuller(client = client)
            val job = launch(Dispatchers.Default) { puller.fetch("https://inbox.example/next") }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(headersReceived.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(job.isCancelled)
        } finally {
            // long: MockWebServer 的延迟响应队列要在关闭前走完；取消时限已经在上面独立断言。
            Thread.sleep(1_100)
            server.shutdown()
        }
    }

    @Test fun serverBackoffSurvivesRegistryRecreationAndBlocksNetworkUntilDue() = runBlocking {
        sources.save(peer, "https://inbox.example/next")
        var clock = now
        var calls = 0
        var code = 503
        val store = TestCheckpointStore()
        val puller = fakePuller { request ->
            calls += 1
            response(request, code, envelopeJson(signed("message-1", "sender")), "application/json")
        }
        val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
        var checkpoints = RemoteChannelPullCheckpointRegistry(store) { clock }
        var service = RemoteChannelPullService(pairings, sources, inbox, puller, checkpoints)
        assertTrue(runCatching { service.pull(peer) }.exceptionOrNull() is RemoteChannelPullHttpException)
        assertEquals(RemoteChannelPullErrorCode.SERVER_5XX.name, checkpoints.get(identity)?.lastErrorCode)
        checkpoints = RemoteChannelPullCheckpointRegistry(store) { clock }
        service = RemoteChannelPullService(pairings, sources, inbox, puller, checkpoints)
        assertTrue(runCatching { service.pull(peer) }.exceptionOrNull() is RemoteChannelPullDeferredException)
        assertEquals(1, calls)
        clock += 30_000L
        code = 200
        assertTrue(service.pull(peer) is RemoteChannelReceiveResult.Accepted)
        assertEquals(emptyList<String>(), checkpoints.get(identity)?.pendingAckMessageIds)
        assertEquals("message-1", checkpoints.get(identity)?.lastMessageId)
        assertEquals(0, checkpoints.get(identity)?.consecutiveFailures)
        assertEquals(null, checkpoints.get(identity)?.lastErrorCode)
        assertEquals(2, calls)
    }

    @Test fun timeoutRemainsTimeoutAndCorruptLedgerNeverStartsRequest() = runBlocking {
        sources.save(peer, "https://inbox.example/next")
        var calls = 0
        val puller = fakePuller { calls += 1; throw java.net.SocketTimeoutException("fixture timeout") }
        val checkpoints = RemoteChannelPullCheckpointRegistry(TestCheckpointStore()) { now }
        assertTrue(runCatching {
            RemoteChannelPullService(pairings, sources, inbox, puller, checkpoints).pull(peer)
        }.exceptionOrNull() is java.net.SocketTimeoutException)
        assertEquals(RemoteChannelPullErrorCode.TIMEOUT.name,
            checkpoints.get(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer))?.lastErrorCode)
        val corrupt = RemoteChannelPullCheckpointRegistry(TestCheckpointStore("{broken")) { now }
        assertTrue(runCatching { RemoteChannelPullService(pairings, sources, inbox, puller, corrupt).pull(peer) }.isFailure)
        assertEquals(1, calls)
    }

    @Test fun badIdentityAndHttpRejectionRemainManuallyRecoverable() = runBlocking {
        sources.save(peer, "https://inbox.example/next")
        val checkpoints = RemoteChannelPullCheckpointRegistry(TestCheckpointStore()) { now }
        val identity = RemoteChannelPullReliabilityPolicy.sourceIdentity(peer)
        val wrong = fakePuller { request -> jsonResponse(request, envelopeJson(signed("wrong", "other"))) }
        assertTrue(runCatching {
            RemoteChannelPullService(pairings, sources, inbox, wrong, checkpoints).pull(peer)
        }.exceptionOrNull() is RemoteChannelPullIdentityMismatchException)
        assertEquals(RemoteChannelPullErrorCode.IDENTITY_MISMATCH.name, checkpoints.get(identity)?.lastErrorCode)
        val denied = fakePuller { request -> response(request, 403, "", "application/json") }
        assertTrue(runCatching { RemoteChannelPullService(pairings, sources, inbox, denied, checkpoints).pull(peer) }.isFailure)
        assertEquals(RemoteChannelPullErrorCode.SERVER_4XX.name, checkpoints.get(identity)?.lastErrorCode)
        val good = fakePuller { request -> jsonResponse(request, envelopeJson(signed("good", "sender"))) }
        assertTrue(RemoteChannelPullService(pairings, sources, inbox, good, checkpoints).pull(peer) is RemoteChannelReceiveResult.Accepted)
    }

    @Test fun checkpointWriteFailureDoesNotRetryStoreOrLoseAcceptedInboxMessage() = runBlocking {
        sources.save(peer, "https://inbox.example/next")
        val store = TestCheckpointStore().apply { failWrites = true }
        val checkpoints = RemoteChannelPullCheckpointRegistry(store) { now }
        var calls = 0
        val puller = fakePuller { request -> calls += 1; jsonResponse(request, envelopeJson(signed("message-1", "sender"))) }
        val service = RemoteChannelPullService(pairings, sources, inbox, puller, checkpoints)
        assertTrue(runCatching { service.pull(peer) }.isFailure)
        assertEquals(1, store.writes)
        assertTrue(!checkpoints.isAvailable())
        assertEquals("message-1", (inbox.status() as RemoteChannelInboxStatus.Ready).pending.single().draft.messageId)
        assertTrue(runCatching { service.pull(peer) }.isFailure)
        assertEquals(1, calls)
        assertEquals(1, store.writes)
    }

    @Test fun signatureRejectionIsRecordedWithoutCreatingAckOrInboxDraft() = runBlocking {
        sources.save(peer, "https://inbox.example/next")
        val store = TestCheckpointStore()
        val checkpoints = RemoteChannelPullCheckpointRegistry(store) { now }
        val envelope = signed("message-1", "sender").copy(text = "篡改后的正文")
        val puller = fakePuller { request -> jsonResponse(request, envelopeJson(envelope)) }
        val result = RemoteChannelPullService(pairings, sources, inbox, puller, checkpoints).pull(peer)
        assertEquals(RemoteChannelRejectionReason.INVALID_SIGNATURE, (result as RemoteChannelReceiveResult.Rejected).reason)
        val checkpoint = checkpoints.get(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer))!!
        assertEquals(RemoteChannelPullErrorCode.REJECTED_MESSAGE.name, checkpoint.lastErrorCode)
        assertTrue(checkpoint.pendingAckMessageIds.isEmpty())
        assertTrue((inbox.status() as RemoteChannelInboxStatus.Ready).pending.isEmpty())
    }

    @Test fun queuedPullCannotBypassFailureBackoff() = runBlocking {
        sources.save(peer, "https://inbox.example/next")
        var calls = 0
        val puller = fakePuller { request -> calls += 1; response(request, 503, "", "application/json") }
        val checkpoints = RemoteChannelPullCheckpointRegistry(TestCheckpointStore()) { now }
        val service = RemoteChannelPullService(pairings, sources, inbox, puller, checkpoints)
        val results = List(2) { async(Dispatchers.Default) { runCatching { service.pull(peer) } } }.awaitAll()
        assertEquals(1, calls)
        assertEquals(1, results.count { it.exceptionOrNull() is RemoteChannelPullHttpException })
        assertEquals(1, results.count { it.exceptionOrNull() is RemoteChannelPullDeferredException })
    }

    @Test fun cancelledServicePullDoesNotPoisonCheckpoint() = runBlocking {
        sources.save(peer, "https://inbox.example/next")
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBodyDelay(1, TimeUnit.SECONDS).setBody("{}"))
        server.start()
        try {
            val headers = CountDownLatch(1)
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().url(server.url("/fixture")).build()).also { headers.countDown() }
            }.build()
            val store = TestCheckpointStore()
            val checkpoints = RemoteChannelPullCheckpointRegistry(store) { now }
            val service = RemoteChannelPullService(pairings, sources, inbox, RemoteChannelHttpsPuller(client = client), checkpoints)
            val job = launch(Dispatchers.Default) { service.pull(peer) }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            assertTrue(headers.await(2, TimeUnit.SECONDS))
            withTimeout(2_000) { job.cancelAndJoin() }
            assertTrue(checkpoints.isAvailable())
            assertEquals(null, store.state)
            assertTrue((inbox.status() as RemoteChannelInboxStatus.Ready).pending.isEmpty())
        } finally {
            Thread.sleep(1_100)
            server.shutdown()
        }
    }

    private fun signed(id: String, sender: String): RemoteChannelEnvelope {
        val unsigned = RemoteChannelEnvelope("channel", id, sender, "conversation", "待确认正文", now,
            nonce = "nonce-$id-1234567890123456", keyId = "key-1")
        return unsigned.copy(signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private)
            update(RemoteChannelPolicy.canonicalBytes(unsigned))
            Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
        })
    }

    private fun envelopeJson(value: RemoteChannelEnvelope): String = JSONObject()
        .put("channelId", value.channelId).put("messageId", value.messageId)
        .put("senderId", value.senderId).put("conversationKey", value.conversationKey)
        .put("text", value.text).put("receivedAtMillis", value.receivedAtMillis)
        .put("nonce", value.nonce).put("keyId", value.keyId).put("signature", value.signature).toString()

    private fun fakePuller(reply: (okhttp3.Request) -> Response): RemoteChannelHttpsPuller =
        RemoteChannelHttpsPuller(client = OkHttpClient.Builder().addInterceptor(Interceptor { chain -> reply(chain.request()) }).build())

    private fun jsonResponse(request: okhttp3.Request, body: String) = response(request, 200, body, "application/json")

    private fun response(request: okhttp3.Request, code: Int, body: String, type: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("test")
            .header("Content-Type", type).body(body.toResponseBody(type.toMediaType())).build()

    private class TestPairingStore : RemoteChannelPairingStateStore {
        var state: String? = null
        override fun loadState(): String? = state
        override fun saveState(state: String) { this.state = state }
    }
    private class TestSourceStore : RemoteChannelPullSourceStateStore {
        var state: String? = null
        override fun loadState(): String? = state
        override fun saveState(state: String) { this.state = state }
    }
    private class TestInboxStore : RemoteChannelInboxStateStore {
        var snapshot: RemoteChannelInboxSnapshot? = null
        override fun loadSnapshot(): RemoteChannelInboxSnapshot? = snapshot
        override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { this.snapshot = snapshot }
    }
    private class TestCheckpointStore(var state: String? = null) : RemoteChannelPullCheckpointStateStore {
        var failWrites = false
        var writes = 0
        override fun loadState(): String? = state
        override fun saveState(state: String) {
            writes += 1
            check(!failWrites) { "fixture commit failure" }
            this.state = state
        }
    }
}
