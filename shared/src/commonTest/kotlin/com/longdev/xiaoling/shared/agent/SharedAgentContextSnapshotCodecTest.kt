package com.longdev.xiaoling.shared.agent

import kotlin.test.*

class SharedAgentContextSnapshotCodecTest {
    private val wire = """{"schemaVersion":1,"phase":"PLAN","model":"fixture","providerId":null,"apiMode":"CHAT_COMPLETIONS","projection":"EXACT_TEXT","imageCount":0,"documentCount":0,"verifiedToolCallIds":["verified-1"],"messages":[{"role":"system","content":"系统"},{"role":"user","content":"中文😀"}]}"""
    private fun input() = SharedAgentContextInput(SharedAgentLlmPhase.PLAN, "fixture", null, "CHAT_COMPLETIONS",
        listOf(SharedAgentContextMessage("system", "系统"), SharedAgentContextMessage("user", "中文😀")), listOf("verified-1"))

    @Test fun v1GoldenPreservesFieldsAndCanonicalEncoding() {
        assertEquals(input(), SharedAgentContextSnapshotCodec.decode(wire))
        assertEquals(wire, SharedAgentContextSnapshotCodec.encode(input()))
        assertEquals(1, SharedAgentContextSnapshotCodec.SCHEMA_VERSION)
        assertEquals(64, SharedAgentContextSnapshotCodec.MAX_FRAMES)
        assertEquals(16_777_216L, SharedAgentContextSnapshotCodec.MAX_RUN_BYTES)
    }

    @Test fun summaryRedactedAndAttachmentShapesRoundTripWithoutInventingContent() {
        for (value in listOf(input().copy(phase = SharedAgentLlmPhase.SUMMARIZE, providerId = "provider-中文"),
            input().copy(projection = SharedAgentContextProjection.DEVICE_INPUT_REDACTED),
            input().copy(apiMode = "RESPONSES", imageCount = 1), input().copy(apiMode = "RESPONSES", documentCount = 1),
            input().copy(messages = listOf(SharedAgentContextMessage("system", ""), SharedAgentContextMessage("user", ""))))) {
            assertEquals(value, SharedAgentContextSnapshotCodec.decode(SharedAgentContextSnapshotCodec.encode(value)))
        }
    }

    @Test fun nullMissingUnknownAndWrongStringTypesRejectFixedShape() {
        for (raw in listOf(wire.replace("\"providerId\":null,", ""), wire.replace("\"providerId\":null", "\"providerId\":42"),
            wire.replace("\"schemaVersion\":1", "\"extra\":true,\"schemaVersion\":1"), wire.replace("PLAN", "UNKNOWN"),
            wire.replace("EXACT_TEXT", "UNKNOWN"), wire.replace("\"model\":\"fixture\"", "\"model\":false"),
            wire.replace("\"content\":\"系统\"", "\"content\":null"), wire.replace("\"verified-1\"", "42"),
            wire.replace("\"role\":\"system\"", "\"extra\":true,\"role\":\"system\""))) {
            assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.decode(raw) }
        }
    }

    @Test fun numericStringsFractionsExponentNegativeZeroAndUnknownVersionAreRejected() {
        for (value in listOf("\"1\"", "1.0", "1e0", "2", "null", "true")) {
            assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.decode(wire.replace("\"schemaVersion\":1", "\"schemaVersion\":$value")) }
        }
        for (value in listOf("\"0\"", "0.0", "0e0", "-0", "-1", "2", "2147483648", "null")) {
            assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.decode(wire.replace("\"imageCount\":0", "\"imageCount\":$value")) }
        }
    }

    @Test fun duplicateDecodedKeysAndPermissiveJsonCannotChangeIdentity() {
        for (raw in listOf(wire.replace("\"model\":", "\"model\":\"foreign\",\"mo\\u0064el\":"),
            wire.replace("\"role\":\"system\"", "\"role\":\"user\",\"role\":\"system\""), wire + " {}",
            wire.replace('"', '\''), wire.replace("\"schemaVersion\"", "schemaVersion"), wire.dropLast(1) + ",}")) {
            assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.decode(raw) }
        }
    }

    @Test fun roleIdentityAndAttachmentBusinessBoundariesRemainFailClosed() {
        for (value in listOf(input().copy(model = " "), input().copy(model = "x".repeat(201)), input().copy(providerId = " "),
            input().copy(apiMode = "UNKNOWN"), input().copy(messages = input().messages.reversed()),
            input().copy(verifiedToolCallIds = listOf("same", "same")), input().copy(verifiedToolCallIds = List(33) { "$it" }),
            input().copy(verifiedToolCallIds = listOf("x".repeat(201))), input().copy(imageCount = 1),
            input().copy(imageCount = 1, documentCount = 1, apiMode = "RESPONSES"))) {
            assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.encode(value) }
        }
    }

    @Test fun exactlyOneMiBAsciiFrameSurvivesScannerAndOneExtraByteIsRejected() {
        val base = input().copy(messages = listOf(SharedAgentContextMessage("system", "s"), SharedAgentContextMessage("user", "")))
        val overhead = SharedAgentContextSnapshotCodec.utf8Size(SharedAgentContextSnapshotCodec.encode(base))
        val bounded = base.copy(messages = listOf(base.messages.first(), SharedAgentContextMessage("user", "x".repeat(1_048_576 - overhead))))
        val encoded = SharedAgentContextSnapshotCodec.encode(bounded)
        assertEquals(1_048_576, encoded.length)
        assertEquals(bounded, SharedAgentContextSnapshotCodec.decode(encoded))
        assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.decode(encoded + " ") }
        assertFailsWith<IllegalArgumentException> {
            SharedAgentContextSnapshotCodec.encode(bounded.copy(messages = listOf(base.messages.first(), SharedAgentContextMessage("user", bounded.messages.last().content + "x"))))
        }
    }

    @Test fun multibyteAndEscapedTextLimitsCountEncodedUtf8WithoutTruncation() {
        for (text in listOf("中".repeat(400_000), "\u0000".repeat(200_000))) {
            assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.encode(input().copy(messages = listOf(input().messages.first(), SharedAgentContextMessage("user", text)))) }
        }
        assertEquals(10, SharedAgentContextSnapshotCodec.utf8Size("中😀abc"))
    }

    @Test fun invalidUnicodeInBodyIdentityOrHashFieldsNeverGetsReplaced() {
        for (text in listOf("\uD800", "\uDC00")) {
            assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.utf8Bytes(text) }
            assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.encode(input().copy(model = text)) }
            assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.encode(input().copy(providerId = text)) }
            assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.encode(input().copy(verifiedToolCallIds = listOf(text))) }
            assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.recordHashBytes("r", "s", 1, text) }
        }
        assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.decode(wire.replace("中文😀", "\\ud800")) }
    }

    @Test fun independentBigEndianGoldenBindsRawPayloadAndCompressionDomain() {
        // long: 这些固定字节由独立 Python struct/UTF-8 生成，原文中的换行不得被重编码或忽略。
        val payload = "{\"raw\":\"中😀\"}\n"
        val ordinary = "000000197869616f6c696e672d6167656e742d636f6e746578742d76310000000a72756e2de4b8ade696870000000873746570f09f9880000000023137000000127b22726177223a22e4b8adf09f9880227d0a"
        val compressed = "000000247869616f6c696e672d6167656e742d636f6e746578742d636f6d707265737365642d76310000000a72756e2de4b8ade696870000000873746570f09f98800000000231370000000133000000127b22726177223a22e4b8adf09f9880227d0a"
        assertEquals(ordinary, SharedAgentContextSnapshotCodec.recordHashBytes("run-中文", "step😀", 17, payload).hex())
        assertEquals(compressed, SharedAgentContextSnapshotCodec.recordHashBytes("run-中文", "step😀", 17, payload, 3).hex())
        assertFalse(SharedAgentContextSnapshotCodec.recordHashBytes("ab", "c", 1, payload).contentEquals(SharedAgentContextSnapshotCodec.recordHashBytes("a", "bc", 1, payload)))
        assertFalse(SharedAgentContextSnapshotCodec.recordHashBytes("r", "s", 1, payload).contentEquals(SharedAgentContextSnapshotCodec.recordHashBytes("r", "s", 1, payload.trim())))
    }

    @Test fun decodingDetachesListsAndRejectsInvalidRecordIdentity() {
        val messages = input().messages.toMutableList()
        val source = input().copy(messages = messages)
        val raw = SharedAgentContextSnapshotCodec.encode(source)
        val frozen = SharedAgentContextSnapshotCodec.decode(" \n$raw \n")
        messages[1] = SharedAgentContextMessage("user", "later")
        assertEquals(input(), frozen)
        assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.recordHashBytes("r", "s", 0, raw) }
        assertFailsWith<IllegalArgumentException> { SharedAgentContextSnapshotCodec.recordHashBytes("r", "s", 1, raw, 0) }
    }

    private fun ByteArray.hex(): String = joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
}
