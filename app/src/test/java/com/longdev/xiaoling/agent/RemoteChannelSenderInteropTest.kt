package com.longdev.xiaoling.agent

import java.io.File
import java.util.concurrent.TimeUnit
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** long: 独立 Python/OpenSSL 发送进程与生产 Kotlin 协议交叉验证，不用同一实现自签自验代替互通。 */
class RemoteChannelSenderInteropTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun persistentSenderBatchAndRestoredAckWorkWithProductionInbox() {
        val script = listOf(File("../tools/remote_channel_sender.py"), File("tools/remote_channel_sender.py"))
            .first { it.isFile }.canonicalFile
        val stateDirectory = File(temporary.root, "sender-state")
        fun sender(vararg arguments: String, input: String = ""): String {
            val process = ProcessBuilder(listOf("python3", script.absolutePath, "--state-dir", stateDirectory.absolutePath) + arguments).start()
            process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                error("独立发送进程未在门禁时限内结束")
            }
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val error = process.errorStream.readBytes().toString(Charsets.UTF_8).trim()
            assertEquals("发送进程失败：$error", 0, process.exitValue())
            return output
        }

        val info = JSONObject(sender("init", "--channel-id", "sender-queue-test", "--sender-id", "local-sender", "--key-id", "local-key-1"))
        val peer = RemoteChannelPairingPolicy.prepare("sender-queue-test", "local-sender", "local-key-1", "本地持久发送方",
            info.getString("publicKeySpkiBase64"))
        assertEquals(peer.fingerprintSha256, info.getString("fingerprintSha256"))
        val pairings = RemoteChannelPairingRegistry(object : RemoteChannelPairingStateStore {
            var raw: String? = null
            override fun loadState() = raw
            override fun saveState(state: String) { raw = state }
        }).apply { confirm(peer, peer.fingerprintSha256); setEnabled(peer.channelId, peer.senderId, peer.keyId, true) }
        val store = object : RemoteChannelInboxStateStore {
            var snapshot: RemoteChannelInboxSnapshot? = null
            override fun loadSnapshot() = snapshot
            override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { this.snapshot = snapshot }
        }
        fun inbox() = RemoteChannelInboxGateway(store, pairings)
        val source = RemoteChannelPullSource(peer.channelId, peer.senderId, peer.keyId, peer.fingerprintSha256, "https://sender-queue.example/batch")
        val initial = inbox().batchState(source, peer)
        sender("register", "--receiver-id", initial.receiverId)
        val firstText = "\u00a0\r\n😀 合成正文\r\n尾行 \u00a0"
        sender("enqueue", "--receiver-id", initial.receiverId, "--message-id", "message-1", "--conversation-key", "conversation", input = firstText)
        sender("enqueue", "--receiver-id", initial.receiverId, "--message-id", "message-2", "--conversation-key", "conversation", input = "第二条合成正文")
        val raw = sender("pull", "--receiver-id", initial.receiverId)
        assertEquals(raw, sender("pull", "--receiver-id", initial.receiverId))
        val batch = (RemoteChannelBatchProtocol.decodeResponse(raw) as RemoteChannelPullResponse.Batch).batch
        val result = inbox().receiveBatch(source, peer, batch)
        assertEquals(RemoteChannelBatchReceiveResult(2, 0), result)
        assertEquals(RemoteChannelPolicy.normalizeText(firstText), (inbox().status() as RemoteChannelInboxStatus.Ready).pending.first().draft.payload.text)

        val accepted = inbox().batchState(source, peer)
        val committedCursor = accepted.cursor ?: error("入箱后必须有下一游标")
        val request = RemoteChannelBatchProtocol.ackRequest(peer, accepted)
        val ack = sender("ack", input = request)
        RemoteChannelBatchProtocol.verifyAck(ack, peer, accepted)
        assertEquals(ack, sender("ack", input = request))
        inbox().acknowledgeBatch(source, peer, accepted.pendingAck!!)
        assertNull(inbox().batchState(source, peer).pendingAck)
        assertEquals(2, (inbox().status() as RemoteChannelInboxStatus.Ready).pending.size)

        // long: 旧 ACK 在后续空批次已经签发时重放，发送队列必须只返回原确认，不能清掉新批次或推进新游标。
        val emptyRaw = sender("pull", "--receiver-id", initial.receiverId, "--cursor", committedCursor)
        assertEquals(ack, sender("ack", input = request))
        assertEquals(emptyRaw, sender("pull", "--receiver-id", initial.receiverId, "--cursor", committedCursor))
        val empty = (RemoteChannelBatchProtocol.decodeResponse(emptyRaw) as RemoteChannelPullResponse.Batch).batch
        assertEquals(RemoteChannelBatchReceiveResult(0, 0), inbox().receiveBatch(source, peer, empty))
        val emptyState = inbox().batchState(source, peer)
        val emptyAck = sender("ack", input = RemoteChannelBatchProtocol.ackRequest(peer, emptyState))
        RemoteChannelBatchProtocol.verifyAck(emptyAck, peer, emptyState)
        inbox().acknowledgeBatch(source, peer, emptyState.pendingAck!!)
        assertEquals(accepted.cursor, inbox().batchState(source, peer).cursor)
        assertEquals(2, JSONObject(sender("stats")).getInt("acknowledgedMessages"))
    }
}
