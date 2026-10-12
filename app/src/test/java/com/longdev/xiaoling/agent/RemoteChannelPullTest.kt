package com.longdev.xiaoling.agent

import java.net.InetAddress
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
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
        val service = RemoteChannelPullService(pairings, sources, inbox, puller)
        assertTrue(service.pull(peer) is RemoteChannelReceiveResult.Accepted)
        assertEquals("message-1", (inbox.status() as RemoteChannelInboxStatus.Ready).pending.single().draft.messageId)
        assertEquals(RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (service.pull(peer) as RemoteChannelReceiveResult.Rejected).reason)
        assertEquals(2, calls)
        pairings.setEnabled(peer.channelId, peer.senderId, peer.keyId, false)
        assertTrue(runCatching { service.pull(peer) }.isFailure)
        assertEquals(2, calls)
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
}
