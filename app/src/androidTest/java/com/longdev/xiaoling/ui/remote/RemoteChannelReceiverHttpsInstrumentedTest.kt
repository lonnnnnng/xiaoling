package com.longdev.xiaoling.ui.remote

import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.storage.KeystoreRemoteChannelInboxStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPairingStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPullSourceStateStore
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** long: 三进程使用真实生产同步服务和 Keystore，通过拦截器与主机交换请求；此门禁不伪称公网 TLS。 */
class RemoteChannelReceiverHttpsInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun ledger(): String {
        val value = args.getString("remoteHttpsLedger").orEmpty()
        require(value.startsWith("remote_receiver_https_test_"))
        val uuid = value.removePrefix("remote_receiver_https_test_")
        require(UUID.fromString(uuid).toString() == uuid)
        return value
    }
    private fun peer() = RemoteChannelPairingPolicy.prepare("receiver-https-test", "local-sender", "local-key-1", "认证客户端测试",
        args.getString("remoteHttpsSenderPublicKey").orEmpty())
    private fun pairs(prefix: String) = RemoteChannelPairingRegistry(SharedPreferencesRemoteChannelPairingStateStore(context, prefix + "_pairings"))
    private fun sources(prefix: String, pairs: RemoteChannelPairingRegistry) = RemoteChannelPullSourceRegistry(
        SharedPreferencesRemoteChannelPullSourceStateStore(context, prefix + "_sources"), pairs)
    private fun inbox(prefix: String, pairs: RemoteChannelPairingRegistry) = RemoteChannelInboxGateway(
        KeystoreRemoteChannelInboxStateStore(context, prefix + "_inbox", prefix + "_aes"), pairs)
    private fun signer(prefix: String) = AndroidKeystoreRemoteChannelReceiverSigner(prefix + "_")
    private fun payload(name: String) = Base64.getDecoder().decode(args.getString(name).orEmpty()).also {
        require(it.size <= RemoteChannelSignedEnvelopeCodec.MAX_JSON_BYTES)
    }.toString(Charsets.UTF_8)
    private fun report(values: Map<String, String>) = instrumentation.sendStatus(2, Bundle().apply {
        values.forEach { (name, value) -> putString(name, value) }
        putString("remoteHttpsPid", Process.myPid().toString())
    })
    private fun transport(handler: (Request, String) -> String) = RemoteChannelHttpsPuller(client = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        assertEquals("POST", request.method); assertEquals("https://receiver-https.example/batch", request.url.toString())
        assertEquals("1", request.header("X-Xiaoling-Receiver-Auth")); assertNull(request.url.query)
        assertNull(request.header("X-Xiaoling-Receiver")); assertNull(request.header("X-Xiaoling-Cursor"))
        val raw = okio.Buffer().apply { request.body!!.writeTo(this) }.readUtf8()
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .header("Content-Type", "application/json").body(handler(request, raw).toResponseBody("application/json".toMediaType())).build()
    }.build())

    @Test fun createEnrollmentAndCaptureProductionPull() = runBlocking {
        assumeTrue(args.getString("remoteHttpsPhase") == "create")
        val prefix = ledger(); val peer = peer(); val pairs = pairs(prefix); val sources = sources(prefix, pairs); val inbox = inbox(prefix, pairs)
        require(pairs.list().isEmpty() && sources.list().isEmpty())
        pairs.confirm(peer, peer.fingerprintSha256); pairs.setEnabled(peer.channelId, peer.senderId, peer.keyId, true)
        sources.save(peer, "https://receiver-https.example/batch")
        var captured: String? = null
        val service = RemoteChannelPullService(pairs, sources, inbox, transport { _, raw ->
            assertEquals("pull", JSONObject(raw).getJSONObject("request").getString("operation")); captured = raw
            throw java.io.IOException("fixture capture")
        }, receiverIdentity = signer(prefix))
        val auth = service.createReceiver(peer)
        assertEquals(auth, service.exportReceiver(peer))
        assertTrue(runCatching { service.createReceiver(peer) }.isFailure)
        assertTrue(runCatching { service.synchronize(peer) }.isFailure)
        assertNull(inbox.batchState(sources.sourceFor(peer)!!, peer).pendingAck)
        report(mapOf("remoteHttpsManifest" to RemoteChannelReceiverAuthProtocol.manifest(auth), "remoteHttpsPull" to captured!!))
    }

    @Test fun restoreEnrollmentReceiveAndCaptureProductionAck() = runBlocking {
        assumeTrue(args.getString("remoteHttpsPhase") == "receive")
        val prefix = ledger(); val peer = peer(); val pairs = pairs(prefix); val sources = sources(prefix, pairs); val inbox = inbox(prefix, pairs)
        var captured: String? = null; var calls = 0
        val service = RemoteChannelPullService(pairs, sources, inbox, transport { _, raw ->
            calls++
            if (JSONObject(raw).getJSONObject("request").getString("operation") == "pull") payload("remoteHttpsBatch") else {
                captured = raw
                assertNotNull(inbox.batchState(sources.sourceFor(peer)!!, peer).pendingAck)
                throw java.io.IOException("fixture ACK response lost")
            }
        }, receiverIdentity = signer(prefix))
        assertEquals(args.getString("remoteHttpsReceiverFingerprint"), service.exportReceiver(peer).receiverFingerprintSha256)
        assertEquals(RemoteChannelPullSyncResult.Batch(1, 0, true), service.synchronize(peer))
        assertEquals(2, calls)
        assertEquals(1, (inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
        val ciphertext = context.getSharedPreferences(prefix + "_inbox", 0).getString("snapshot", null)!!
        assertFalse(ciphertext.contains("认证客户端合成正文"))
        report(mapOf("remoteHttpsAck" to captured!!))
    }

    @Test fun restoreProductionOutboxAndRejectMissingKeyWithoutNetwork() = runBlocking {
        assumeTrue(args.getString("remoteHttpsPhase") == "restore")
        val prefix = ledger(); val peer = peer(); val pairs = pairs(prefix); val sources = sources(prefix, pairs); val inbox = inbox(prefix, pairs)
        val signer = signer(prefix); var captured: String? = null; var calls = 0
        val service = RemoteChannelPullService(pairs, sources, inbox, transport { _, raw ->
            calls++; captured = raw
            assertEquals("ack", JSONObject(raw).getJSONObject("request").getString("operation"))
            payload("remoteHttpsAckReply")
        }, receiverIdentity = signer)
        val source = sources.sourceFor(peer)!!; val auth = service.exportReceiver(peer)
        assertEquals(args.getString("remoteHttpsReceiverFingerprint"), auth.receiverFingerprintSha256)
        val committed = inbox.batchState(source, peer)
        assertNotNull(committed.pendingAck)
        assertEquals(RemoteChannelPullSyncResult.Batch(0, 0, false), service.synchronize(peer))
        assertEquals(1, calls); assertNull(inbox.batchState(source, peer).pendingAck)
        assertEquals(committed.cursor, inbox.batchState(source, peer).cursor)
        assertEquals(1, (inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = signer.alias(auth)
        // long: 只删除独立 UUID 私钥来验缺钥边界；生产配置、正文和权限不参与本轮夹具。
        keyStore.deleteEntry(alias)
        assertTrue(runCatching { service.synchronize(peer) }.isFailure)
        assertTrue(runCatching { service.exportReceiver(peer) }.isFailure)
        assertTrue(runCatching { service.pull(peer) }.isFailure)
        assertEquals(1, calls); assertFalse(keyStore.containsAlias(alias))
        report(mapOf("remoteHttpsRestoredAck" to captured!!))
        listOf("_pairings", "_sources", "_inbox").forEach { suffix ->
            assertTrue(context.deleteSharedPreferences(prefix + suffix))
            assertTrue(context.getSharedPreferences(prefix + suffix, 0).all.isEmpty())
        }
        keyStore.deleteEntry(prefix + "_aes")
        assertFalse(keyStore.containsAlias(prefix + "_aes"))
    }
}
