package com.longdev.xiaoling.agent

import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** long: 认证失败必须在队列动作前收敛，跨实现用独立 Python/OpenSSL 验证生产 Kotlin 生成的签名。 */
class RemoteChannelReceiverAuthTest {
    @get:Rule val temporary = TemporaryFolder()
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded)
    private val peer = RemoteChannelPairingPolicy.prepare("auth-test", "sender", "key-1", "认证测试", publicKey)
    private val source = RemoteChannelPullSource(peer.channelId, peer.senderId, peer.keyId, peer.fingerprintSha256, "https://auth.example/batch")
    private val state = RemoteChannelBatchState(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer), RemoteChannelBatchProtocol.endpointHash(source), "a".repeat(32))
    private val authorization = RemoteChannelReceiverAuthProtocol.authorization(peer, source, state, publicKey)
    private val signer = RemoteChannelReceiverSigner { content -> Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(content); sign() } }
    private fun reject(operation: () -> Unit) { assertTrue(runCatching(operation).isFailure) }

    @Test fun signatureBindsStructuredRequestAndEveryProofField() {
        val raw = RemoteChannelReceiverAuthProtocol.signedRequest(authorization, RemoteChannelReceiverAuthProtocol.pullRequest(peer, state), 1_000_000, signer)
        val proof = JSONObject(raw).getJSONObject("proof")
        fun valid(value: JSONObject) = Signature.getInstance("SHA256withECDSA").run {
            initVerify(keys.public); update(RemoteChannelReceiverAuthProtocol.canonicalProofBytes(value))
            verify(Base64.getUrlDecoder().decode(proof.getString("signature")))
        }
        assertTrue(valid(proof))
        for ((name, replacement) in listOf("receiverId" to "b".repeat(32), "endpointSha256" to "0".repeat(64),
            "senderFingerprintSha256" to "0".repeat(64), "operation" to "ack", "nonce" to "c".repeat(32))) {
            assertFalse(valid(JSONObject(proof.toString()).put(name, replacement)))
        }
    }

    @Test fun requestCanonicalFormIgnoresJsonOrderButBindsNullUnicodeCursorAndAckOrder() {
        val request = RemoteChannelReceiverAuthProtocol.pullRequest(peer, state).put("requestCursor", "游标😀")
        val reversed = JSONObject().apply { request.keys().asSequence().toList().reversed().forEach { put(it, request.get(it)) } }
        assertArrayEquals(RemoteChannelReceiverAuthProtocol.canonicalRequestBytes(request), RemoteChannelReceiverAuthProtocol.canonicalRequestBytes(reversed))
        assertFalse(RemoteChannelReceiverAuthProtocol.canonicalRequestBytes(request).contentEquals(
            RemoteChannelReceiverAuthProtocol.canonicalRequestBytes(request.put("requestCursor", JSONObject.NULL))))
        val accepted = state.copy(cursor = "next", pendingAck = RemoteChannelBatchReceipt("batch-1", "0".repeat(64), "next", listOf("one", "two")))
        val ack = RemoteChannelReceiverAuthProtocol.ackRequest(peer, accepted)
        val first = RemoteChannelReceiverAuthProtocol.canonicalRequestBytes(ack)
        assertFalse(first.contentEquals(RemoteChannelReceiverAuthProtocol.canonicalRequestBytes(ack.put("messageIds", JSONArray(listOf("two", "one"))))))
    }

    @Test fun invalidTypesFieldsTimeAndConflictingIdentityNeverInvokeSigner() {
        val never = RemoteChannelReceiverSigner { error("无效输入不得使用私钥") }
        val request = RemoteChannelReceiverAuthProtocol.pullRequest(peer, state)
        for (bad in listOf(JSONObject(request.toString()).put("protocolVersion", true), JSONObject(request.toString()).put("extra", 1),
            JSONObject(request.toString()).put("receiverId", "b".repeat(32)), JSONObject(request.toString()).put("requestCursor", " padded "))) {
            reject { RemoteChannelReceiverAuthProtocol.signedRequest(authorization, bad, 1_000_000, never) }
        }
        for (now in listOf(0L, -1L, Long.MAX_VALUE)) reject { RemoteChannelReceiverAuthProtocol.signedRequest(authorization, request, now, never) }
        reject { RemoteChannelReceiverAuthProtocol.signedRequest(authorization, request, 1_000_000, never, "A".repeat(32)) }
    }

    @Test fun sourcePeerFingerprintAndReceiverKeyCannotBeSilentlyRebound() {
        reject { RemoteChannelReceiverAuthProtocol.authorization(peer, source.copy(url = "https://other.example/batch"), state, publicKey) }
        reject { RemoteChannelReceiverAuthProtocol.authorization(peer.copy(senderId = "other"), source, state, publicKey) }
        reject { RemoteChannelReceiverAuthProtocol.validate(authorization.copy(receiverFingerprintSha256 = "0".repeat(64))) }
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        reject { RemoteChannelReceiverAuthProtocol.authorization(peer, source, state, Base64.getEncoder().encodeToString(rsa.public.encoded)) }
    }

    @Test fun pendingOutboxMustBeAckedBeforeConstructingAnotherPull() {
        val accepted = state.copy(cursor = "next", pendingAck = RemoteChannelBatchReceipt("batch-1", "0".repeat(64), "next", listOf("one")))
        reject { RemoteChannelReceiverAuthProtocol.pullRequest(peer, accepted) }
        val ack = RemoteChannelReceiverAuthProtocol.ackRequest(peer, accepted)
        assertEquals("ack", ack.getString("operation"))
        assertEquals("one", ack.getJSONArray("messageIds").getString(0))
        val proof = JSONObject(RemoteChannelReceiverAuthProtocol.signedRequest(authorization, ack, 1_000_000, signer)).getJSONObject("proof")
        assertEquals(RemoteChannelReceiverAuthProtocol.sha256(RemoteChannelReceiverAuthProtocol.canonicalRequestBytes(ack)), proof.getString("requestDigestSha256"))
    }

    @Test fun signerCallbackCannotMutateAlreadyFrozenRequest() {
        val request = RemoteChannelReceiverAuthProtocol.pullRequest(peer, state)
        val mutating = RemoteChannelReceiverSigner { content -> request.put("requestCursor", "changed"); signer.sign(content) }
        val envelope = JSONObject(RemoteChannelReceiverAuthProtocol.signedRequest(authorization, request, 1_000_000, mutating))
        assertTrue(envelope.getJSONObject("request").isNull("requestCursor"))
    }

    @Test fun independentAuthorizedSenderAcceptsProductionProofAndAckAndRejectsRevokedRetry() {
        val tools = listOf(File("../tools"), File("tools")).first { File(it, "remote_channel_receiver_auth.py").isFile }.canonicalFile
        val directory = File(temporary.root, "queue")
        data class Result(val code: Int, val output: String)
        fun cli(script: String, vararg arguments: String, input: String = ""): Result {
            val process = ProcessBuilder(listOf("python3", File(tools, script).absolutePath, "--state-dir", directory.absolutePath) + arguments).start()
            process.outputStream.use { it.write(input.toByteArray(Charsets.UTF_8)) }
            assertTrue(process.waitFor(30, TimeUnit.SECONDS))
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
            val error = process.errorStream.readBytes().toString(Charsets.UTF_8).trim()
            assertTrue("只能返回固定错误", error.isEmpty() || error.startsWith("receiver auth error:"))
            return Result(process.exitValue(), output)
        }
        fun sender(vararg args: String, input: String = ""): String = cli("remote_channel_sender.py", *args, input = input).also { assertEquals(0, it.code) }.output
        fun auth(vararg args: String, input: String = ""): String = cli("remote_channel_receiver_auth.py", *args, input = input).also { assertEquals(0, it.code) }.output
        val info = JSONObject(sender("init", "--channel-id", "auth-test", "--sender-id", "sender", "--key-id", "key-1"))
        val realPeer = RemoteChannelPairingPolicy.prepare("auth-test", "sender", "key-1", "独立发送方", info.getString("publicKeySpkiBase64"))
        val realSource = source.copy(fingerprintSha256 = realPeer.fingerprintSha256)
        val pairs = RemoteChannelPairingRegistry(object : RemoteChannelPairingStateStore {
            var raw: String? = null
            override fun loadState() = raw
            override fun saveState(state: String) { raw = state }
        }).apply { confirm(realPeer, realPeer.fingerprintSha256); setEnabled(realPeer.channelId, realPeer.senderId, realPeer.keyId, true) }
        val store = object : RemoteChannelInboxStateStore {
            var value: RemoteChannelInboxSnapshot? = null
            override fun loadSnapshot() = value
            override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { value = snapshot }
        }
        val inbox = RemoteChannelInboxGateway(store, pairs)
        val initial = inbox.batchState(realSource, realPeer)
        val authorized = RemoteChannelReceiverAuthProtocol.authorization(realPeer, realSource, initial, publicKey)
        auth("init")
        auth("authorize", "--fingerprint", authorized.receiverFingerprintSha256, input = RemoteChannelReceiverAuthProtocol.manifest(authorized))
        sender("enqueue", "--receiver-id", initial.receiverId, "--message-id", "one", "--conversation-key", "conversation", input = "跨实现认证正文😀\r\n第二行")
        val get = RemoteChannelReceiverAuthProtocol.signedRequest(authorized, RemoteChannelReceiverAuthProtocol.pullRequest(realPeer, initial), System.currentTimeMillis(), signer)
        fun execute(input: String) = auth("execute", "--endpoint-sha256", authorized.endpointSha256, input = input)
        val raw = execute(get)
        assertEquals(raw, execute(get))
        val batch = (RemoteChannelBatchProtocol.decodeResponse(raw) as RemoteChannelPullResponse.Batch).batch
        assertEquals(RemoteChannelBatchReceiveResult(1, 0), inbox.receiveBatch(realSource, realPeer, batch))
        val accepted = inbox.batchState(realSource, realPeer)
        val ack = RemoteChannelReceiverAuthProtocol.signedRequest(authorized, RemoteChannelReceiverAuthProtocol.ackRequest(realPeer, accepted), System.currentTimeMillis(), signer)
        val reply = execute(ack)
        assertEquals(reply, execute(ack))
        RemoteChannelBatchProtocol.verifyAck(reply, realPeer, accepted)
        inbox.acknowledgeBatch(realSource, realPeer, accepted.pendingAck!!)
        assertEquals(1, (inbox.status() as RemoteChannelInboxStatus.Ready).pending.size)
        auth("revoke", "--receiver-id", initial.receiverId)
        assertEquals(2, cli("remote_channel_receiver_auth.py", "execute", "--endpoint-sha256", authorized.endpointSha256, input = ack).code)
        assertEquals(1, JSONObject(sender("stats")).getInt("acknowledgedMessages"))
    }
}
