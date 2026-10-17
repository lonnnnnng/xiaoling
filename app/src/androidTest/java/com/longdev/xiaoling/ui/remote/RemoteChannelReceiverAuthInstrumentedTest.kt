package com.longdev.xiaoling.ui.remote

import android.os.Bundle
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.storage.KeystoreRemoteChannelInboxStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPairingStateStore
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** long: 三个手机进程与独立主机认证入口交换合成请求；隔离账本和 Keystore，失败不清状态。 */
class RemoteChannelReceiverAuthInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun phase() = args.getString("remoteAuthPhase")
    private fun ledger(): String {
        val value = args.getString("remoteAuthLedger").orEmpty()
        require(value.startsWith("remote_receiver_auth_test_"))
        val id = value.removePrefix("remote_receiver_auth_test_")
        require(UUID.fromString(id).toString() == id) { "认证门禁只接受独立 UUID 账本" }
        return value
    }
    private fun peer() = RemoteChannelPairingPolicy.prepare("receiver-auth-test", "local-sender", "local-key-1", "接收认证测试",
        args.getString("remoteAuthSenderPublicKey").orEmpty())
    private fun source(peer: RemoteChannelPeerCandidate) = RemoteChannelPullSource(peer.channelId, peer.senderId, peer.keyId,
        peer.fingerprintSha256, "https://receiver-auth.example/batch")
    private fun pairs(prefix: String) = RemoteChannelPairingRegistry(SharedPreferencesRemoteChannelPairingStateStore(context, prefix + "_pairings"))
    private fun store(prefix: String) = KeystoreRemoteChannelInboxStateStore(context, prefix + "_inbox", prefix + "_aes")
    private fun signer(prefix: String) = AndroidKeystoreRemoteChannelReceiverSigner(prefix + "_")
    private fun payload(name: String): String {
        val value = Base64.getDecoder().decode(args.getString(name).orEmpty())
        require(value.size <= RemoteChannelSignedEnvelopeCodec.MAX_JSON_BYTES)
        return value.toString(Charsets.UTF_8)
    }
    private fun report(values: Map<String, String>) = instrumentation.sendStatus(2, Bundle().apply {
        values.forEach { (name, value) -> putString(name, value) }
        putString("remoteAuthPid", Process.myPid().toString())
    })

    @Test fun createReceiverKeyAndExportEnrollmentAndSignedPull() {
        assumeTrue(phase() == "create")
        val prefix = ledger()
        val peer = peer(); val source = source(peer)
        val pairs = pairs(prefix); val store = store(prefix)
        require(pairs.list().isEmpty() && store.loadSnapshot() == null) { "不能覆盖已有认证测试账本" }
        pairs.confirm(peer, peer.fingerprintSha256)
        pairs.setEnabled(peer.channelId, peer.senderId, peer.keyId, true)
        val state = RemoteChannelInboxGateway(store, pairs).batchState(source, peer)
        val signer = signer(prefix)
        val authorization = signer.create(peer, source, state)
        assertEquals(authorization, signer.export(peer, source, state))
        assertTrue(runCatching { signer.create(peer, source, state) }.isFailure)
        val request = signer.sign(authorization, RemoteChannelReceiverAuthProtocol.pullRequest(peer, state), System.currentTimeMillis())
        report(mapOf("remoteAuthManifest" to RemoteChannelReceiverAuthProtocol.manifest(authorization), "remoteAuthPullEnvelope" to request))
    }

    @Test fun restoreReceiverKeyReceiveBatchAndExportSignedProductionAck() {
        assumeTrue(phase() == "receive")
        val prefix = ledger()
        val peer = peer(); val source = source(peer)
        val pairs = pairs(prefix)
        assertEquals(peer, pairs.active(peer.channelId, peer.senderId, peer.keyId))
        val inbox = RemoteChannelInboxGateway(store(prefix), pairs)
        val state = inbox.batchState(source, peer)
        val signer = signer(prefix)
        val authorization = signer.export(peer, source, state)
        assertEquals(args.getString("remoteAuthReceiverFingerprint"), authorization.receiverFingerprintSha256)
        val batch = (RemoteChannelBatchProtocol.decodeResponse(payload("remoteAuthBatch")) as RemoteChannelPullResponse.Batch).batch
        assertEquals(RemoteChannelBatchReceiveResult(2, 0), inbox.receiveBatch(source, peer, batch))
        val accepted = inbox.batchState(source, peer)
        val ciphertext = context.getSharedPreferences(prefix + "_inbox", 0).getString("snapshot", null)!!
        assertFalse(ciphertext.contains("接收认证合成正文"))
        assertFalse(ciphertext.contains(batch.batchId))
        // long: ACK 内容来自生产加密 outbox，复用已存在的接收私钥签名；不能让主机自行拼收据证明入箱。
        report(mapOf("remoteAuthAckEnvelope" to signer.sign(authorization,
            RemoteChannelReceiverAuthProtocol.ackRequest(peer, accepted), System.currentTimeMillis())))
    }

    @Test fun restoreOutboxVerifySignedAckAndDeleteOnlyFixtureKeys() {
        assumeTrue(phase() == "restore")
        val prefix = ledger()
        var completed = false
        var authorization: RemoteChannelReceiverAuthorization? = null
        try {
            val peer = peer(); val source = source(peer)
            val inbox = RemoteChannelInboxGateway(store(prefix), pairs(prefix))
            val state = inbox.batchState(source, peer)
            val receipt = state.pendingAck ?: error("跨进程必须保留待 ACK")
            val signer = signer(prefix)
            val authorized = signer.export(peer, source, state).also { authorization = it }
            assertEquals(args.getString("remoteAuthReceiverFingerprint"), authorized.receiverFingerprintSha256)
            assertEquals(2, (inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
            RemoteChannelBatchProtocol.verifyAck(payload("remoteAuthAck"), peer, state)
            val recoveredRequest = signer.sign(authorized, RemoteChannelReceiverAuthProtocol.ackRequest(peer, state), System.currentTimeMillis())
            inbox.acknowledgeBatch(source, peer, receipt)
            val restored = RemoteChannelInboxGateway(store(prefix), pairs(prefix))
            assertNull(restored.batchState(source, peer).pendingAck)
            assertEquals(state.cursor, restored.batchState(source, peer).cursor)
            assertEquals(2, (restored.status() as RemoteChannelInboxStatus.Ready).pending.size)
            report(mapOf("remoteAuthRestoredAckEnvelope" to recoveredRequest))
            completed = true
        } finally {
            if (completed) {
                // long: 成功后只清本轮 UUID 状态，随后证实 sign/export 缺钥失败而不生成替代身份。
                listOf("_pairings", "_inbox").forEach {
                    assertTrue(context.deleteSharedPreferences(prefix + it))
                    assertTrue(context.getSharedPreferences(prefix + it, 0).all.isEmpty())
                }
                val signer = signer(prefix)
                val authorized = authorization!!
                val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                val signingAlias = signer.alias(authorized)
                keyStore.deleteEntry(signingAlias); keyStore.deleteEntry(prefix + "_aes")
                assertFalse(keyStore.containsAlias(signingAlias))
                assertFalse(keyStore.containsAlias(prefix + "_aes"))
                val peer = peer()
                val emptyState = RemoteChannelBatchState(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer), authorized.endpointSha256, authorized.receiverId)
                assertTrue(runCatching { signer.sign(authorized, RemoteChannelReceiverAuthProtocol.pullRequest(peer, emptyState), System.currentTimeMillis()) }.isFailure)
                assertTrue(runCatching { signer.export(peer, source(peer), emptyState) }.isFailure)
                assertFalse(keyStore.containsAlias(signingAlias))
            }
        }
    }
}
