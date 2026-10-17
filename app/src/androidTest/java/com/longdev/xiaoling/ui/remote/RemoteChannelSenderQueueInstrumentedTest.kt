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

/** long: 独立本地发送进程与 Redmi Keystore 交换合成批次和 ACK；不把离线协议验收计作公网或认证闭环。 */
class RemoteChannelSenderQueueInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val args get() = InstrumentationRegistry.getArguments()
    private fun phase() = args.getString("remoteSenderPhase")
    private fun ledger(): String {
        val value = args.getString("remoteSenderLedger").orEmpty()
        require(value.startsWith("remote_sender_queue_test_"))
        val id = value.removePrefix("remote_sender_queue_test_")
        require(UUID.fromString(id).toString() == id) { "发送队列门禁只接受独立 UUID 账本" }
        return value
    }
    private fun peer() = RemoteChannelPairingPolicy.prepare("sender-queue-test", "local-sender", "local-key-1", "本地持久发送方",
        args.getString("remoteSenderPublicKey").orEmpty())
    private fun pairings(prefix: String) = RemoteChannelPairingRegistry(SharedPreferencesRemoteChannelPairingStateStore(context, prefix + "_pairings"))
    private fun source(peer: RemoteChannelPeerCandidate) = RemoteChannelPullSource(peer.channelId, peer.senderId, peer.keyId,
        peer.fingerprintSha256, "https://sender-queue.example/batch")
    private fun store(prefix: String) = KeystoreRemoteChannelInboxStateStore(context, prefix + "_inbox", prefix + "_aes")
    private fun payload(name: String): String {
        val raw = Base64.getDecoder().decode(args.getString(name).orEmpty())
        require(raw.size <= RemoteChannelSignedEnvelopeCodec.MAX_JSON_BYTES)
        return raw.toString(Charsets.UTF_8)
    }

    @Test fun seedPersistentSenderBatchAndExportProductionReceipt() {
        assumeTrue(phase() == "seed")
        val prefix = ledger()
        val peer = peer()
        val source = source(peer)
        val pairs = pairings(prefix)
        val store = store(prefix)
        require(pairs.list().isEmpty() && store.loadSnapshot() == null) { "不能覆盖已存在的门禁账本" }
        pairs.confirm(peer, peer.fingerprintSha256)
        pairs.setEnabled(peer.channelId, peer.senderId, peer.keyId, true)
        val receiver = args.getString("remoteSenderReceiver").orEmpty()
        val initial = RemoteChannelBatchState(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer), RemoteChannelBatchProtocol.endpointHash(source), receiver)
        RemoteChannelBatchProtocol.validateState(initial)
        store.saveSnapshot(RemoteChannelInboxSnapshot(emptyList(), emptyList(), batchStates = listOf(initial)))
        val inbox = RemoteChannelInboxGateway(store, pairs)
        val batch = (RemoteChannelBatchProtocol.decodeResponse(payload("remoteSenderBatch")) as RemoteChannelPullResponse.Batch).batch
        assertEquals(RemoteChannelBatchReceiveResult(2, 0), inbox.receiveBatch(source, peer, batch))
        val accepted = inbox.batchState(source, peer)
        val ciphertext = context.getSharedPreferences(prefix + "_inbox", 0).getString("snapshot", null)!!
        assertFalse(ciphertext.contains("本地发送队列合成正文"))
        assertFalse(ciphertext.contains(batch.batchId))
        assertEquals(2, (RemoteChannelInboxGateway(store(prefix), pairings(prefix)).status() as RemoteChannelInboxStatus.Ready).pending.size)
        // long: ACK 必须由生产收件快照生成；主机只能转交这份请求，不能用发送方自己算的收据冒充接收端提交。
        instrumentation.sendStatus(2, Bundle().apply {
            putString("remoteSenderAckRequest", RemoteChannelBatchProtocol.ackRequest(peer, accepted))
            putString("remoteSenderPid", Process.myPid().toString())
        })
    }

    @Test fun restoreOutboxVerifySenderAckAndKeepPendingDrafts() {
        assumeTrue(phase() == "restore")
        val prefix = ledger()
        var completed = false
        try {
            val peer = peer()
            val source = source(peer)
            val pairs = pairings(prefix)
            assertEquals(peer, pairs.active(peer.channelId, peer.senderId, peer.keyId))
            val inbox = RemoteChannelInboxGateway(store(prefix), pairs)
            val accepted = inbox.batchState(source, peer)
            val receipt = accepted.pendingAck ?: error("跨进程重建必须保留待 ACK 快照")
            assertEquals(2, (inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
            RemoteChannelBatchProtocol.verifyAck(payload("remoteSenderAck"), peer, accepted)
            inbox.acknowledgeBatch(source, peer, receipt)
            val restored = RemoteChannelInboxGateway(store(prefix), pairings(prefix))
            assertNull(restored.batchState(source, peer).pendingAck)
            assertEquals(accepted.cursor, restored.batchState(source, peer).cursor)
            assertEquals(2, (restored.status() as RemoteChannelInboxStatus.Ready).pending.size)
            instrumentation.sendStatus(2, Bundle().apply { putString("remoteSenderPid", Process.myPid().toString()) })
            completed = true
        } finally {
            // long: 成功后只移除本轮 UUID 配对/密文及 AES 别名；失败账本保留，不能用清空状态遮盖恢复错误。
            if (completed) {
                listOf("_pairings", "_inbox").forEach {
                    assertTrue("本轮隔离偏好必须完成删除", context.deleteSharedPreferences(prefix + it))
                    assertTrue(context.getSharedPreferences(prefix + it, 0).all.isEmpty())
                }
                KeyStore.getInstance("AndroidKeyStore").apply {
                    load(null)
                    deleteEntry(prefix + "_aes")
                    assertFalse("本轮隔离密钥必须完成移除", containsAlias(prefix + "_aes"))
                }
            }
        }
    }
}
