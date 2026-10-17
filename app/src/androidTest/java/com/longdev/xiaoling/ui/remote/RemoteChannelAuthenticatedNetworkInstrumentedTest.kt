package com.longdev.xiaoling.ui.remote

import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.storage.*
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** long: 显式合成公网门禁只使用默认生产 HTTPS 传输，不注入 DNS、TLS、OkHttp 或响应。 */
class RemoteChannelAuthenticatedNetworkInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()

    private fun fixture(phase: String): Fixture {
        assumeTrue(args.getString("remoteNetworkPhase") == phase)
        val prefix = args.getString("remoteNetworkLedger").orEmpty()
        require(prefix.startsWith("remote_auth_network_test_"))
        val uuid = prefix.removePrefix("remote_auth_network_test_")
        require(UUID.fromString(uuid).toString() == uuid)
        val endpoint = args.getString("remoteNetworkEndpoint").orEmpty()
        require(RemoteChannelPullPolicy.endpoint(endpoint).toString() == endpoint)
        val peer = RemoteChannelPairingPolicy.prepare("auth-network-test", "local-sender", "local-key-1", "认证公网合成来源",
            args.getString("remoteNetworkPublicKey").orEmpty())
        val pairs = RemoteChannelPairingRegistry(SharedPreferencesRemoteChannelPairingStateStore(context, prefix + "_pairings"))
        val sources = RemoteChannelPullSourceRegistry(SharedPreferencesRemoteChannelPullSourceStateStore(context, prefix + "_sources"), pairs)
        val inbox = RemoteChannelInboxGateway(KeystoreRemoteChannelInboxStateStore(context, prefix + "_inbox", prefix + "_aes"), pairs)
        val checkpoints = RemoteChannelPullCheckpointRegistry(SharedPreferencesRemoteChannelPullCheckpointStateStore(context, prefix + "_retry"))
        val signer = AndroidKeystoreRemoteChannelReceiverSigner(prefix + "_")
        return Fixture(prefix, endpoint, peer, pairs, sources, inbox, checkpoints, signer,
            RemoteChannelPullService(pairs, sources, inbox, checkpoints = checkpoints, receiverIdentity = signer))
    }

    private fun report(phase: String, values: Map<String, String> = emptyMap()) = instrumentation.sendStatus(2, Bundle().apply {
        putString("remoteNetworkPhase", phase)
        putString("remoteNetworkPid", Process.myPid().toString())
        values.forEach { (key, value) -> putString(key, value) }
    })

    @Test fun createPublicEnrollmentAndRejectUnauthorizedRealHttpsPull() = runBlocking {
        val f = fixture("create")
        assertTrue(f.pairs.list().isEmpty()); assertTrue(f.sources.list().isEmpty())
        f.pairs.confirm(f.peer, f.peer.fingerprintSha256)
        f.pairs.setEnabled(f.peer.channelId, f.peer.senderId, f.peer.keyId, true)
        f.sources.save(f.peer, f.endpoint)
        val auth = f.service.createReceiver(f.peer)
        assertEquals(auth, f.service.exportReceiver(f.peer))
        val error = runCatching { f.service.synchronize(f.peer) }.exceptionOrNull()
        assertTrue(error is RemoteChannelPullHttpException)
        assertEquals(403, (error as RemoteChannelPullHttpException).statusCode)
        assertNull(f.inbox.batchState(f.source(), f.peer).pendingAck)
        report("create", mapOf("remoteNetworkManifest" to RemoteChannelReceiverAuthProtocol.manifest(auth)))
    }

    @Test fun receiveRealSignedBatchAndPreserveOutboxAfterCommittedAckFault() = runBlocking {
        val f = fixture("receive")
        f.checkFingerprint()
        // long: 主机测试适配器在认证 ACK 提交后返回 503；手机仍须保留原 receipt，不能把入箱判为失败。
        assertEquals(RemoteChannelPullSyncResult.Batch(2, 0, true), f.service.synchronize(f.peer))
        val state = f.inbox.batchState(f.source(), f.peer)
        assertEquals(listOf("auth-network-1", "auth-network-2"), state.pendingAck!!.messageIds)
        assertNotNull(state.cursor)
        assertEquals(2, (f.inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
        val raw = context.getSharedPreferences(f.prefix + "_inbox", 0).getString("snapshot", null)!!
        assertFalse(raw.contains("认证公网合成正文")); assertFalse(raw.contains(state.receiverId))
        val checkpoint = f.checkpoints.get(RemoteChannelPullReliabilityPolicy.sourceIdentity(f.peer))!!
        assertEquals(RemoteChannelPullErrorCode.SERVER_5XX.name, checkpoint.lastErrorCode)
        assertTrue(runCatching { f.service.synchronize(f.peer) }.exceptionOrNull() is RemoteChannelPullDeferredException)
        report("receive", mapOf("remoteNetworkBatchId" to state.pendingAck.batchId,
            "remoteNetworkDigest" to state.pendingAck.digestSha256,
            "remoteNetworkReceiver" to state.receiverId, "remoteNetworkCursor" to state.cursor!!))
    }

    @Test fun revokedAuthorizationRejectsRestoredAckOverRealHttps() = runBlocking {
        val f = fixture("revoked")
        f.checkFingerprint()
        val before = f.inbox.batchState(f.source(), f.peer)
        assertNotNull(before.pendingAck)
        f.checkReceipt(before)
        val checkpoint = f.checkpoints.get(RemoteChannelPullReliabilityPolicy.sourceIdentity(f.peer))!!
        val wait = (checkpoint.nextAttemptAtMillis - System.currentTimeMillis()).coerceAtLeast(0)
        require(wait <= 30_000)
        if (wait > 0) Thread.sleep(wait + 25)
        val error = runCatching { f.service.synchronize(f.peer) }.exceptionOrNull()
        assertTrue(error is RemoteChannelPullHttpException)
        assertEquals(403, (error as RemoteChannelPullHttpException).statusCode)
        assertEquals(before, f.inbox.batchState(f.source(), f.peer))
        assertEquals(2, (f.inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
        report("revoked")
    }

    @Test fun restoreSameKeyAndAckAfterServerRestartThenClearOnlyFixture() = runBlocking {
        val f = fixture("restore")
        f.checkFingerprint()
        val before = f.inbox.batchState(f.source(), f.peer)
        assertNotNull(before.pendingAck)
        f.checkReceipt(before)
        assertEquals(RemoteChannelPullSyncResult.Batch(0, 0, false), f.service.synchronize(f.peer))
        val after = f.inbox.batchState(f.source(), f.peer)
        assertEquals(before.cursor, after.cursor); assertNull(after.pendingAck)
        assertEquals(2, (f.inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
        assertEquals(RemoteChannelPullSyncResult.Batch(0, 0, false), f.service.synchronize(f.peer))
        assertEquals(2, (f.inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
        val auth = f.service.exportReceiver(f.peer)
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        keyStore.deleteEntry(f.signer.alias(auth))
        assertTrue(runCatching { f.service.synchronize(f.peer) }.isFailure)
        assertTrue(runCatching { f.service.exportReceiver(f.peer) }.isFailure)
        assertFalse(keyStore.containsAlias(f.signer.alias(auth)))
        // long: 所有恢复断言通过后才删除 UUID 合成账本和密钥；失败现场及正式用户配置继续保留。
        listOf("_pairings", "_sources", "_inbox", "_retry").forEach { suffix ->
            assertTrue(context.deleteSharedPreferences(f.prefix + suffix))
            assertTrue(context.getSharedPreferences(f.prefix + suffix, 0).all.isEmpty())
        }
        keyStore.deleteEntry(f.prefix + "_aes")
        assertFalse(keyStore.containsAlias(f.prefix + "_aes"))
        report("restore")
    }

    private inner class Fixture(
        val prefix: String, val endpoint: String, val peer: RemoteChannelPeerCandidate,
        val pairs: RemoteChannelPairingRegistry, val sources: RemoteChannelPullSourceRegistry,
        val inbox: RemoteChannelInboxGateway, val checkpoints: RemoteChannelPullCheckpointRegistry,
        val signer: AndroidKeystoreRemoteChannelReceiverSigner, val service: RemoteChannelPullService,
    ) {
        fun source() = sources.sourceFor(peer)!!
        suspend fun checkFingerprint() = assertEquals(args.getString("remoteNetworkReceiverFingerprint"),
            service.exportReceiver(peer).receiverFingerprintSha256)
        fun checkReceipt(state: RemoteChannelBatchState) {
            assertEquals(args.getString("remoteNetworkBatchId"), state.pendingAck!!.batchId)
            assertEquals(args.getString("remoteNetworkDigest"), state.pendingAck.digestSha256)
            assertEquals(args.getString("remoteNetworkReceiver"), state.receiverId)
            assertEquals(args.getString("remoteNetworkCursor"), state.cursor)
        }
    }
}
