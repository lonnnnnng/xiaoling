package com.longdev.xiaoling.shared.remote

import kotlin.test.*

class SharedRemoteChannelWireCodecTest {
    private val codec = SharedRemoteChannelWireCodec
    private fun message() = codec.decodeEnvelope(RemoteWireGoldens.messageWire)
    private fun batch() = (codec.decodeResponse(RemoteWireGoldens.batchWire) as SharedRemoteChannelPullResponse.Batch).batch
    private fun ack() = codec.decodeRequest(RemoteWireGoldens.ackWire) as SharedRemoteChannelRequest.Ack
    private fun pull() = codec.decodeRequest(RemoteWireGoldens.pullWire) as SharedRemoteChannelRequest.Pull
    private fun proof() = codec.decodeProof(RemoteWireGoldens.proofWire)
    private fun ByteArray.hex() = joinToString("") { it.toUByte().toString(16).padStart(2, '0') }

    @Test fun independentPythonMessageGoldenPreservesNormalizationAndDecimalTime() {
        assertEquals(RemoteWireGoldens.messageHex, codec.canonicalEnvelopeBytes(message()).hex())
        assertEquals("中文\nemoji😀\n尾", codec.normalizeText(message().text))
        assertEquals(message(), codec.decodeEnvelope(codec.encodeEnvelope(message())))
        assertEquals(message(), (codec.decodeResponse(RemoteWireGoldens.messageWire) as SharedRemoteChannelPullResponse.Single).envelope)
        assertContentEquals(codec.canonicalEnvelopeBytes(message()), codec.canonicalEnvelopeBytes(message().copy(signature = "changed")))
        assertContentEquals(codec.canonicalEnvelopeBytes(message().copy(nonce = null, keyId = null)),
            codec.canonicalEnvelopeBytes(message().copy(nonce = "", keyId = "")))
    }

    @Test fun independentPythonBatchGoldenBindsInnerSignatureAndNullableCursor() {
        val b = batch()
        assertEquals(RemoteWireGoldens.batchHex, codec.canonicalBatchBytes(b).hex())
        assertEquals(b, (codec.decodeResponse(codec.encodeBatch(b)) as SharedRemoteChannelPullResponse.Batch).batch)
        assertFalse(codec.canonicalBatchBytes(b).contentEquals(codec.canonicalBatchBytes(b.copy(requestCursor = ""))))
        assertFalse(codec.canonicalBatchBytes(b).contentEquals(codec.canonicalBatchBytes(b.copy(messages = b.messages.map { it.copy(signature = "changed") }))))
        assertContentEquals(codec.canonicalBatchBytes(b), codec.canonicalBatchBytes(b.copy(signature = "changed")))
    }

    @Test fun independentPythonAckGoldenAndResponseRoundTrip() {
        val a = ack()
        assertEquals(RemoteWireGoldens.ackHex, codec.canonicalAckBytes(a.binding).hex())
        assertEquals(a, codec.decodeRequest(codec.encodeAckRequest(a.binding, a.messageIds)))
        val response = SharedRemoteChannelAcknowledgement(a.binding, "synthetic-DER")
        assertEquals(response, codec.decodeAcknowledgement(codec.encodeAcknowledgement(response)))
        assertFailsWith<IllegalArgumentException> { codec.decodeAcknowledgement(RemoteWireGoldens.ackWire) }
        assertFalse(codec.canonicalAckBytes(a.binding).contentEquals(codec.canonicalAckBytes(a.binding.copy(nextCursor = null))))
    }

    @Test fun independentPythonRequestAndProofGoldensUseSeparateDomains() {
        assertEquals(RemoteWireGoldens.pullHex, codec.canonicalRequestBytes(pull()).hex())
        assertEquals(RemoteWireGoldens.ackRequestHex, codec.canonicalRequestBytes(ack()).hex())
        assertEquals(RemoteWireGoldens.proofHex, codec.canonicalProofBytes(proof()).hex())
        assertEquals(pull(), codec.decodeRequest(codec.encodeRequest(pull())))
        assertEquals(ack(), codec.decodeRequest(codec.encodeRequest(ack())))
        assertEquals(proof(), codec.decodeProof(codec.encodeProof(proof())))
        assertContentEquals(codec.canonicalProofBytes(proof()), codec.canonicalProofBytes(proof().copy(signature = "")))
    }

    @Test fun signedRequestAndPublicManifestRoundTripWithoutGrantingAuthorization() {
        val signed = SharedRemoteChannelSignedRequest(pull(), proof())
        assertEquals(signed, codec.decodeSignedRequest(codec.encodeSignedRequest(signed)))
        assertFailsWith<IllegalArgumentException> { codec.encodeSignedRequest(signed.copy(proof = proof().copy(operation = "ack"))) }
        val manifest = SharedRemoteChannelReceiverAuthorization("渠道", "sender", "key-1", "1".repeat(64), "2".repeat(64),
            "a".repeat(32), "synthetic-SPKI", "3".repeat(64))
        // long: 此处只证明 wire 往返；合成 SPKI 没有通过平台曲线/指纹校验，不能计为真实公钥登记。
        assertEquals(manifest, codec.decodeManifest(codec.encodeManifest(manifest)))
    }

    @Test fun exactKeysAndStringTypesRejectMissingNullExtraAndForeignRoot() {
        for (raw in listOf(RemoteWireGoldens.messageWire.replace("\"nonce\":", "\"extra\":0,\"nonce\":"),
            RemoteWireGoldens.messageWire.replace("\"signature\":\"synthetic-DER\"", "\"signature\":null"),
            RemoteWireGoldens.messageWire.replace("\"channelId\":\" 渠道 \"", "\"channelId\":false"),
            RemoteWireGoldens.messageWire.replace(",\"signature\":\"synthetic-DER\"", ""), "[]", "null")) {
            assertFailsWith<IllegalArgumentException> { codec.decodeEnvelope(raw) }
        }
        assertFailsWith<IllegalArgumentException> { codec.encodeEnvelope(message().copy(signature = null)) }
        assertFailsWith<IllegalArgumentException> { codec.decodeRequest(RemoteWireGoldens.pullWire.replace("\"requestCursor\":null", "\"requestCursor\":7")) }
        assertFailsWith<IllegalArgumentException> { codec.decodeResponse(RemoteWireGoldens.batchWire.replace("\"requestCursor\":null,", "")) }
    }

    @Test fun duplicatesEscapedKeysAndNonstandardJsonRejectAtEveryRawBoundary() {
        val cases = listOf(RemoteWireGoldens.messageWire to codec::decodeEnvelope,
            RemoteWireGoldens.batchWire to codec::decodeResponse, RemoteWireGoldens.pullWire to codec::decodeRequest,
            RemoteWireGoldens.proofWire to codec::decodeProof)
        for ((wire, decode) in cases) {
            for (raw in listOf(wire.replace("\"channelId\":", "\"channelId\":\"foreign\",\"channel\\u0049d\":"),
                wire.replace('"', '\''), wire.dropLast(1) + ",}", wire + " {}", "/* comment */$wire")) {
                assertFailsWith<IllegalArgumentException> { decode(raw) }
            }
        }
        val nested = "{\"request\":${RemoteWireGoldens.pullWire},\"proof\":${RemoteWireGoldens.proofWire.replace("\"nonce\":", "\"nonce\":\"foreign\",\"nonce\":")}}"
        assertFailsWith<IllegalArgumentException> { codec.decodeSignedRequest(nested) }
    }

    @Test fun integersRejectStringFloatExponentBooleanAndOverflow() {
        for (value in listOf("\"1\"", "1.0", "1e0", "true", "null", "9223372036854775808")) {
            assertFailsWith<IllegalArgumentException> { codec.decodeEnvelope(RemoteWireGoldens.messageWire.replace("1700000000123", value)) }
            assertFailsWith<IllegalArgumentException> { codec.decodeResponse(RemoteWireGoldens.batchWire.replace("\"protocolVersion\":1", "\"protocolVersion\":$value")) }
            assertFailsWith<IllegalArgumentException> { codec.decodeProof(RemoteWireGoldens.proofWire.replace("1700000000123", value)) }
        }
        assertEquals(Long.MAX_VALUE, codec.decodeEnvelope(RemoteWireGoldens.messageWire.replace("1700000000123", Long.MAX_VALUE.toString())).receivedAtMillis)
    }

    @Test fun sixtyFourKiBBudgetIncludesUtf8AndEscapesAtExactBoundary() {
        val base = message().copy(text = "")
        val overhead = codec.encodeEnvelope(base).encodeToByteArray().size
        for (fill in listOf("x".repeat(65536 - overhead), "中".repeat((65536 - overhead) / 3) + "x".repeat((65536 - overhead) % 3))) {
            val value = base.copy(text = fill); val wire = codec.encodeEnvelope(value)
            assertEquals(65536, wire.encodeToByteArray().size)
            assertEquals(value, codec.decodeEnvelope(wire))
            assertFailsWith<IllegalArgumentException> { codec.decodeEnvelope(wire + " ") }
            assertFailsWith<IllegalArgumentException> { codec.encodeEnvelope(value.copy(text = fill + "x")) }
        }
        assertFailsWith<IllegalArgumentException> { codec.encodeEnvelope(base.copy(text = "\u0000".repeat(11000))) }
        // long: 64 KiB 是 wire 限额，20,000 字符正文政策仍在平台收件校验；decoder 不擅自改变其拒绝原因。
    }

    @Test fun isolatedSurrogatesRejectBeforeSignatureTextCanBeReplaced() {
        for (bad in listOf("\uD800", "\uDC00")) {
            assertFailsWith<IllegalArgumentException> { codec.encodeEnvelope(message().copy(text = bad)) }
            assertFailsWith<IllegalArgumentException> { codec.canonicalEnvelopeBytes(message().copy(text = bad)) }
            assertFailsWith<IllegalArgumentException> { codec.canonicalBatchBytes(batch().copy(nextCursor = bad)) }
        }
        assertFailsWith<IllegalArgumentException> { codec.decodeEnvelope(RemoteWireGoldens.messageWire.replace("渠道", "\\ud800")) }
    }

    @Test fun batchIdentityLifetimeDuplicatesAndProgressRemainBounded() {
        val b = batch(); val m = b.messages.single()
        for (bad in listOf(b.copy(channelId = " padded "), b.copy(keyId = "bad key"), b.copy(receiverId = "A".repeat(32)),
            b.copy(batchId = "bad/id"), b.copy(nextCursor = null), b.copy(nextCursor = " "), b.copy(nextCursor = "x".repeat(1025)),
            b.copy(issuedAtMillis = 0), b.copy(expiresAtMillis = b.issuedAtMillis),
            b.copy(expiresAtMillis = b.issuedAtMillis + 300001), b.copy(signature = ""), b.copy(signature = "x".repeat(129)),
            b.copy(messages = listOf(m, m.copy(nonce = "other"))), b.copy(messages = listOf(m, m.copy(messageId = "other"))),
            b.copy(requestCursor = b.nextCursor))) {
            assertFailsWith<IllegalArgumentException> { codec.validateBatch(bad) }
        }
        val eight = b.copy(messages = List(8) { m.copy(messageId = "m$it", nonce = "nonce-$it") })
        codec.validateBatch(eight)
        assertFailsWith<IllegalArgumentException> { codec.validateBatch(eight.copy(messages = eight.messages + m)) }
        codec.validateBatch(b.copy(messages = emptyList(), requestCursor = b.nextCursor))
    }

    @Test fun batchAndAuthenticatedRequestKeepHistoricalCursorDifference() {
        codec.validateBatch(batch().copy(nextCursor = " padded "))
        codec.encodeAckRequest(ack().binding.copy(nextCursor = " padded "), ack().messageIds)
        for (cursor in listOf(" padded ", "", "\u0000", "x".repeat(1025))) {
            assertFailsWith<IllegalArgumentException> { codec.canonicalRequestBytes(pull().copy(requestCursor = cursor)) }
        }
        assertFalse(codec.canonicalRequestBytes(pull()).contentEquals(codec.canonicalRequestBytes(pull().copy(requestCursor = "游标😀"))))
        val ordered = ack().copy(messageIds = listOf("one", "two"))
        assertFalse(codec.canonicalRequestBytes(ordered).contentEquals(codec.canonicalRequestBytes(ordered.copy(messageIds = ordered.messageIds.reversed()))))
        assertFailsWith<IllegalArgumentException> { codec.encodeRequest(ack().copy(messageIds = listOf("same", "same"))) }
        assertFailsWith<IllegalArgumentException> { codec.encodeRequest(ack().copy(messageIds = List(9) { "m$it" })) }
    }

    @Test fun proofRulesKeepUnsignedConstructionAndStrictLifetime() {
        val p = proof()
        codec.canonicalProofBytes(p.copy(signature = "", keyId = "historical proof identity"))
        for (bad in listOf(p.copy(operation = "execute"), p.copy(nonce = "A".repeat(32)), p.copy(requestDigestSha256 = "bad"),
            p.copy(issuedAtMillis = 0), p.copy(expiresAtMillis = p.issuedAtMillis), p.copy(expiresAtMillis = p.issuedAtMillis + 60001))) {
            assertFailsWith<IllegalArgumentException> { codec.canonicalProofBytes(bad) }
        }
        assertFailsWith<IllegalArgumentException> { codec.decodeProof(RemoteWireGoldens.proofWire.replace("\"signature\":\"synthetic-proof\"", "\"signature\":false")) }
    }

    @Test fun rawDecodersDetachListsAndDoNotEchoUntrustedBodyInErrors() {
        val source = batch().messages.toMutableList()
        val raw = codec.encodeBatch(batch().copy(messages = source))
        val frozen = (codec.decodeResponse(raw) as SharedRemoteChannelPullResponse.Batch).batch
        source.clear(); assertEquals(1, frozen.messages.size)
        val marker = "PRIVATE-BODY-marker"
        val error = assertFailsWith<IllegalArgumentException> { codec.decodeRequest("{\"text\":\"$marker\"}") }
        assertEquals("Remote Channel wire 格式无效", error.message); assertNull(error.cause)
    }

    @Test fun bareAckPublicEncoderChecksBoundariesWhilePreservingPaddedCursor() {
        val a = ack()
        for (ids in listOf(List(9) { "m$it" }, listOf("same", "same"), listOf(""), listOf(" bad "), listOf("x\u0000"))) {
            assertFailsWith<IllegalArgumentException> { codec.encodeAckRequest(a.binding, ids) }
        }
        for (binding in listOf(a.binding.copy(nextCursor = ""), a.binding.copy(nextCursor = "\u0000"),
            a.binding.copy(nextCursor = "x".repeat(1025)), a.binding.copy(batchDigestSha256 = "bad"), a.binding.copy(receiverId = "bad"))) {
            assertFailsWith<IllegalArgumentException> { codec.encodeAckRequest(binding, a.messageIds) }
        }
        val padded = a.copy(binding = a.binding.copy(nextCursor = " padded "))
        val wire = codec.encodeAckRequest(padded.binding, padded.messageIds)
        assertEquals(padded, codec.decodeAckRequest(wire))
        assertFailsWith<IllegalArgumentException> { codec.decodeRequest(wire) }
        assertFailsWith<IllegalArgumentException> { codec.decodeAckRequest(wire.replace(" padded ", "")) }
        assertFailsWith<IllegalArgumentException> { codec.decodeEnvelope("x".repeat(65537)) }
    }
}
