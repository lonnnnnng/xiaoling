package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.remote.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** long: JVM/Redmi 共用原 DataOutputStream 字节 oracle，验证平台适配不改既有签名域；全程只用合成密钥和内存账本。 */
abstract class SharedRemoteWireContractTests {
    private val message = RemoteChannelEnvelope(" 渠道 ", " msg ", " sender ", " 会话😀 ", "\u00a0中文\r\n😀\r尾\u00a0",
        1700000000123, " nonce-1234567890 ", " key-1 ", "synthetic-inner")
    private val codec = SharedRemoteChannelWireCodec
    private fun DataOutputStream.field(value: String) { val b = value.toByteArray(Charsets.UTF_8); writeInt(b.size); write(b) }
    private fun DataOutputStream.nullable(value: String?) { if (value == null) writeInt(-1) else field(value) }
    private fun bytes(domain: String? = null, body: DataOutputStream.() -> Unit): ByteArray = ByteArrayOutputStream().apply {
        DataOutputStream(this).use { if (domain != null) { it.field(domain); it.writeInt(1) }; body(it) }
    }.toByteArray()
    private fun legacyMessage(value: RemoteChannelEnvelope): ByteArray = bytes {
        writeInt(8)
        listOf(value.channelId.trim(), value.messageId.trim(), value.senderId.trim(), value.conversationKey.trim(),
            value.text.replace("\r\n", "\n").replace('\r', '\n').trim(), value.receivedAtMillis.toString(),
            value.nonce?.trim().orEmpty(), value.keyId?.trim().orEmpty()).forEach { field(it) }
    }
    private fun peer(): RemoteChannelPeerCandidate {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        return RemoteChannelPairingPolicy.prepare("渠道", "sender", "key-1", "合成来源", Base64.getEncoder().encodeToString(keys.public.encoded))
    }

    @Test fun messageAliasAndProductionCanonicalMatchIndependentLegacyBytes() {
        val shared: SharedRemoteChannelEnvelope = message
        assertEquals(message, shared)
        assertArrayEquals(legacyMessage(message), RemoteChannelPolicy.canonicalBytes(message))
        assertEquals(message, RemoteChannelSignedEnvelopeCodec.decode(codec.encodeEnvelope(message)))
        assertEquals(RemoteChannelRejectionReason.TEXT_TOO_LONG, RemoteChannelPolicy.validate(message.copy(text = "x".repeat(20001))))
        assertNull(RemoteChannelPolicy.validate(message))
    }

    @Test fun batchAndAckProductionBytesKeepDomainsOrderAndNullSentinel() {
        val peer = peer()
        val batch = RemoteChannelSignedBatch(peer.channelId, peer.senderId, peer.keyId, "a".repeat(32), "batch-1", null,
            "游标😀", 1700000000123, 1700000060123, listOf(message), "synthetic-outer")
        val expected = bytes("xiaoling-batch-v1") {
            listOf(batch.channelId, batch.senderId, batch.keyId, batch.receiverId, batch.batchId).forEach { field(it) }
            nullable(batch.requestCursor); nullable(batch.nextCursor); writeLong(batch.issuedAtMillis); writeLong(batch.expiresAtMillis); writeInt(1)
            val body = legacyMessage(message); writeInt(body.size); write(body); field(message.signature!!)
        }
        assertArrayEquals(expected, RemoteChannelBatchProtocol.canonicalBatchBytes(batch))
        assertEquals(batch, (RemoteChannelBatchProtocol.decodeResponse(codec.encodeBatch(batch)) as RemoteChannelPullResponse.Batch).batch)
        val state = RemoteChannelBatchState(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer), "1".repeat(64), batch.receiverId,
            batch.nextCursor, RemoteChannelBatchProtocol.receipt(batch))
        val receipt = state.pendingAck!!
        assertArrayEquals(bytes("xiaoling-ack-v1") {
            listOf(peer.channelId, peer.senderId, peer.keyId, state.receiverId, receipt.batchId, receipt.digestSha256).forEach { field(it) }
            nullable(receipt.nextCursor)
        }, RemoteChannelBatchProtocol.canonicalAckBytes(peer, state))
        val raw = JSONObject(RemoteChannelBatchProtocol.ackRequest(peer, state))
        assertEquals("msg", raw.getJSONArray("messageIds").getString(0))
        assertEquals(batch.nextCursor, raw.getString("nextCursor"))
        // long: 兼容裸 ACK 允许原批次的非空首尾空白 cursor；认证 request 仍拒绝它。
        val padded = state.copy(cursor = " padded ", pendingAck = receipt.copy(nextCursor = " padded "))
        val paddedAck = RemoteChannelBatchProtocol.ackRequest(peer, padded)
        assertEquals(" padded ", JSONObject(paddedAck).getString("nextCursor"))
        assertTrue(runCatching { RemoteChannelReceiverAuthProtocol.canonicalRequestBytes(JSONObject(paddedAck)) }.isFailure)
    }

    @Test fun requestAndProofPlatformAdaptersMatchLegacyStructuredBytes() {
        val peer = peer()
        val state = RemoteChannelBatchState(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer), "1".repeat(64), "a".repeat(32))
        val request = RemoteChannelReceiverAuthProtocol.pullRequest(peer, state)
        assertArrayEquals(bytes("xiaoling-receiver-request-v1") {
            listOf(peer.channelId, peer.senderId, peer.keyId, "pull", state.receiverId).forEach { field(it) }; writeInt(-1)
        }, RemoteChannelReceiverAuthProtocol.canonicalRequestBytes(request))
        val proof = SharedRemoteChannelReceiverProof(peer.channelId, peer.senderId, peer.keyId, "pull", "1".repeat(64), "2".repeat(64),
            state.receiverId, "3".repeat(64), 1700000000123, 1700000060123, "b".repeat(32), "4".repeat(64), "")
        assertArrayEquals(bytes("xiaoling-receiver-auth-v1") {
            listOf(proof.channelId, proof.senderId, proof.keyId, proof.operation, proof.senderFingerprintSha256, proof.endpointSha256,
                proof.receiverId, proof.receiverFingerprintSha256, proof.nonce, proof.requestDigestSha256).forEach { field(it) }
            writeLong(proof.issuedAtMillis); writeLong(proof.expiresAtMillis)
        }, RemoteChannelReceiverAuthProtocol.canonicalProofBytes(JSONObject(codec.encodeProof(proof))))
    }

    @Test fun productionRawDecodersRejectDialectDuplicateKeysAndFloatTimes() {
        val wire = codec.encodeEnvelope(message)
        for (bad in listOf(wire.replace("\"channelId\":", "\"channelId\":\"foreign\",\"channel\\u0049d\":"),
            wire.replace('"', '\''), wire + " {}", wire.replace("1700000000123", "1700000000123.0"),
            wire.replace("渠道", "\\ud800"), wire + " ".repeat(65536))) {
            assertTrue(runCatching { RemoteChannelSignedEnvelopeCodec.decode(bad) }.isFailure)
            assertTrue(runCatching { RemoteChannelBatchProtocol.decodeResponse(bad) }.isFailure)
        }
    }

    @Test fun validAckIsVerifiedAndBindingOrMalformedReplyCannotClearReceipt() {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val peer = RemoteChannelPairingPolicy.prepare("渠道", "sender", "key-1", "合成来源", Base64.getEncoder().encodeToString(keys.public.encoded))
        val state = RemoteChannelBatchState(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer), "1".repeat(64), "a".repeat(32),
            "next", RemoteChannelBatchReceipt("batch-1", "2".repeat(64), "next", listOf("msg")))
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(keys.private); update(RemoteChannelBatchProtocol.canonicalAckBytes(peer, state)); sign()
        }
        val response = RemoteChannelBatchProtocol.ackFields(peer, state, "acknowledged")
            .put("signature", Base64.getUrlEncoder().withoutPadding().encodeToString(signature)).toString()
        RemoteChannelBatchProtocol.verifyAck(response, peer, state)
        for (bad in listOf(response.replace("batch-1", "batch-2"), response.replace("\"protocolVersion\":1", "\"protocolVersion\":1.0"),
            response.replace("\"signature\":", "\"signature\":\"foreign\",\"signature\":"), response + " {}")) {
            assertTrue(runCatching { RemoteChannelBatchProtocol.verifyAck(bad, peer, state) }.isFailure)
        }
        assertNotNull(state.pendingAck)
    }

    @Test fun manifestSharedDtoStillRequiresPlatformP256AndFingerprintValidation() {
        val peer = peer()
        val manifest = SharedRemoteChannelReceiverAuthorization(peer.channelId, peer.senderId, peer.keyId, peer.fingerprintSha256,
            "1".repeat(64), "a".repeat(32), peer.publicKeySpkiBase64, peer.fingerprintSha256)
        assertEquals(manifest, RemoteChannelReceiverAuthProtocol.decodeManifest(JSONObject(RemoteChannelReceiverAuthProtocol.manifest(manifest))))
        val structuralOnly = codec.encodeManifest(manifest.copy(publicKeySpkiBase64 = "synthetic-SPKI"))
        assertTrue(runCatching { RemoteChannelReceiverAuthProtocol.decodeManifest(JSONObject(structuralOnly)) }.isFailure)
        assertTrue(runCatching { RemoteChannelReceiverAuthProtocol.validate(manifest.copy(receiverFingerprintSha256 = "0".repeat(64))) }.isFailure)
    }

    @Test fun structuredCompatibilityChecksIntegerTypesBeforeJSONObjectCanNormalizeFloats() {
        val peer = peer()
        val state = RemoteChannelBatchState(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer), "1".repeat(64), "a".repeat(32))
        val pull = RemoteChannelReceiverAuthProtocol.pullRequest(peer, state)
        val proof = SharedRemoteChannelReceiverProof(peer.channelId, peer.senderId, peer.keyId, "pull", "1".repeat(64), "2".repeat(64),
            state.receiverId, "3".repeat(64), 1700000000123, 1700000060123, "b".repeat(32), "4".repeat(64), "")
        val manifest = SharedRemoteChannelReceiverAuthorization(peer.channelId, peer.senderId, peer.keyId, peer.fingerprintSha256,
            "1".repeat(64), state.receiverId, peer.publicKeySpkiBase64, peer.fingerprintSha256)
        for (value in listOf<Any>(1.0, "1", true)) {
            assertTrue(runCatching { RemoteChannelReceiverAuthProtocol.canonicalRequestBytes(JSONObject(pull.toString()).put("protocolVersion", value)) }.isFailure)
            assertTrue(runCatching { RemoteChannelReceiverAuthProtocol.canonicalProofBytes(JSONObject(codec.encodeProof(proof)).put("authVersion", value)) }.isFailure)
            assertTrue(runCatching { RemoteChannelReceiverAuthProtocol.decodeManifest(JSONObject(codec.encodeManifest(manifest)).put("authVersion", value)) }.isFailure)
        }
        for (field in listOf("issuedAtMillis", "expiresAtMillis")) {
            assertTrue(runCatching { RemoteChannelReceiverAuthProtocol.canonicalProofBytes(JSONObject(codec.encodeProof(proof)).put(field, 1700000000123.0)) }.isFailure)
        }
    }
}
