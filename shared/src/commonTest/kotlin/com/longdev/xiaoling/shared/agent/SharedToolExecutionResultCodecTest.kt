package com.longdev.xiaoling.shared.agent

import kotlinx.serialization.json.*
import kotlin.test.*

class SharedToolExecutionResultCodecTest {
    // long: 固定 literal 不由 encoder 生成，字段顺序、空值和引用版本都是新协议的独立基线。
    private val golden = """{"schemaVersion":1,"toolCallId":"call-中😀","result":{"success":false,"content":"旧结果\n\"原文\"","verified":null,"memoryIdsUsed":["m2","m1","m2"],"knowledgeReferences":[{"retrievalId":"r1","documentId":"d1","documentName":"路线图.md","documentRevision":4,"chunkId":"c1","chunkSequence":2,"startOffset":120,"endOffset":360}],"executionReceipt":{"toolCallId":"call-中😀","operationId":"op1","idempotencyKey":"private-key","status":"UNKNOWN"},"verificationEvidence":{"status":"FAILED","toolCallId":"call-中😀","reasonCode":"STALE"},"recoveryFailure":{"code":"REOBSERVE","reason":"页面已变","suggestedAction":"新 Run"},"readableEvidence":null}}"""
    private val expected = SharedToolResultFrame("call-中😀", SharedToolExecutionResult(
        false, "旧结果\n\"原文\"", null,
        SharedToolExecutionReceipt("call-中😀", "op1", SharedToolReceiptStatus.UNKNOWN, "private-key"),
        SharedToolVerificationEvidence(SharedToolVerificationStatus.FAILED, "call-中😀", "STALE"),
        memoryIdsUsed = listOf("m2", "m1", "m2"),
        knowledgeReferences = listOf(SharedKnowledgeReference("r1", "d1", "路线图.md", 4, "c1", 2, 120, 360)),
        recoveryFailure = SharedToolRecoveryFailure("REOBSERVE", "页面已变", "新 Run"),
    ))
    private fun decode(raw: String) = SharedToolExecutionResultCodec.decode(raw, expected.toolCallId)
    private fun invalid(raw: String) {
        val error = assertFailsWith<IllegalArgumentException> { decode(raw) }
        assertEquals("工具结果帧格式无效", error.message)
        assertNull(error.cause)
    }
    private fun resultChange(key: String, value: JsonElement): String {
        val frame = Json.parseToJsonElement(golden).jsonObject
        val result = frame.getValue("result").jsonObject
        return JsonObject(frame + ("result" to JsonObject(result + (key to value)))).toString()
    }

    @Test fun completeLiteralGoldenPreservesAllFieldsAndPrivateKey() {
        assertEquals(1, SharedToolExecutionResultCodec.SCHEMA_VERSION)
        assertEquals(expected, decode(golden))
        assertEquals(golden, SharedToolExecutionResultCodec.encode(expected))
        assertEquals(expected, decode(" \n$golden\t"))
    }

    @Test fun absentOptionalEvidenceIsExplicitNullAndNotInvented() {
        val frame = SharedToolResultFrame("old-call", SharedToolExecutionResult(true, ""))
        val literal = """{"schemaVersion":1,"toolCallId":"old-call","result":{"success":true,"content":"","verified":null,"memoryIdsUsed":[],"knowledgeReferences":[],"executionReceipt":null,"verificationEvidence":null,"recoveryFailure":null,"readableEvidence":null}}"""
        assertEquals(literal, SharedToolExecutionResultCodec.encode(frame))
        assertEquals(frame, SharedToolExecutionResultCodec.decode(literal, "old-call"))
    }

    @Test fun legacyTypedEvidenceWithoutIdentityStaysUnbound() {
        val result = expected.result.copy(verification = SharedToolVerificationEvidence(SharedToolVerificationStatus.PASSED, null, null))
        val decoded = decode(SharedToolExecutionResultCodec.encode(expected.copy(result = result))).result
        assertEquals(result, decoded)
        assertNull(decoded.verification?.toolCallId)
        assertFalse(decoded.success)
        assertNull(decoded.verified)
        assertEquals(SharedToolReceiptStatus.UNKNOWN, decoded.executionReceipt?.status)
    }

    @Test fun statusCombinationsRoundTripWithoutUpgradingEvidence() {
        for (success in listOf(false, true)) for (verified in listOf(null, false, true)) {
            for (receipt in SharedToolReceiptStatus.entries) for (verification in SharedToolVerificationStatus.entries) {
                val result = expected.result.copy(success = success, verified = verified,
                    executionReceipt = expected.result.executionReceipt!!.copy(status = receipt),
                    verification = expected.result.verification!!.copy(status = verification))
                assertEquals(result, decode(SharedToolExecutionResultCodec.encode(expected.copy(result = result))).result)
            }
        }
    }

    @Test fun callBindingRejectsFrameReceiptVerificationAndReadableDrift() {
        assertFailsWith<IllegalArgumentException> { SharedToolExecutionResultCodec.decode(golden, "other") }
        for (key in listOf("executionReceipt", "verificationEvidence")) {
            val value = Json.parseToJsonElement(golden).jsonObject.getValue("result").jsonObject.getValue(key).jsonObject
            invalid(resultChange(key, JsonObject(value + ("toolCallId" to JsonPrimitive("other")))))
        }
        assertFailsWith<IllegalArgumentException> { expected.copy(toolCallId = "other") }
        val readable = SharedToolReadableEvidence(SharedToolReadableEvidenceKind.BROWSER_PAGE, "other", "snap", "f".repeat(64), "https://example.com/")
        invalid(resultChange("readableEvidence", Json.parseToJsonElement(SharedToolReadableEvidenceCodec.encode(readable))))
    }

    @Test fun browserActionSurvivesWithoutCreatingBusinessReceipt() {
        val action = SharedBrowserActionReceipt("a".repeat(32), expected.toolCallId, SharedBrowserDomAction.TYPE_DRAFT,
            "b".repeat(16), "c".repeat(16), "input-ref", "d".repeat(16), "e".repeat(64))
        val readable = SharedToolReadableEvidence(SharedToolReadableEvidenceKind.BROWSER_PAGE, expected.toolCallId,
            "c".repeat(16), "f".repeat(64), "https://example.com/公开", action)
        val result = SharedToolExecutionResult(true, "仅本机草稿", true, readableEvidence = readable)
        val wire = SharedToolExecutionResultCodec.encode(expected.copy(result = result))
        assertEquals(result, decode(wire).result)
        assertNull(decode(wire).result.executionReceipt)
        invalid(wire.replace("\"schemaVersion\":1,\"actionId\"", "\"schemaVersion\":2,\"actionId\""))
        val malformed = Json.parseToJsonElement(wire).jsonObject
        val malformedResult = malformed.getValue("result").jsonObject
        val page = malformedResult.getValue("readableEvidence").jsonObject
        invalid(JsonObject(malformed + ("result" to JsonObject(malformedResult +
            ("readableEvidence" to JsonObject(page + ("browserAction" to JsonNull)))))).toString())
        invalid(wire.replace("\"outputSnapshotId\":\"${"c".repeat(16)}\"", "\"outputSnapshotId\":\"${"b".repeat(16)}\""))
    }

    @Test fun unknownMissingAndWrongNestedFieldsRejectWholeFrame() {
        invalid(golden.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        invalid(golden.replace("\"schemaVersion\":1", "\"extra\":null,\"schemaVersion\":1"))
        invalid(golden.replace("\"content\":", "\"extra\":null,\"content\":"))
        invalid(golden.replace("\"verified\":null,", ""))
        for (key in listOf("executionReceipt", "verificationEvidence", "recoveryFailure")) {
            invalid(resultChange(key, JsonPrimitive("{}")))
            invalid(resultChange(key, JsonObject(emptyMap())))
            val obj = Json.parseToJsonElement(golden).jsonObject.getValue("result").jsonObject.getValue(key).jsonObject
            invalid(resultChange(key, JsonObject(obj + ("unknown" to JsonNull))))
        }
        invalid(golden.replace("\"status\":\"UNKNOWN\"", "\"status\":\"COMMIT\""))
        invalid(golden.replace("\"reason\":\"页面已变\"", "\"reason\":\" \""))
        invalid(golden.replace("\"reasonCode\":\"STALE\"", "\"reasonCode\":false"))
        invalid(golden.replace("\"idempotencyKey\":\"private-key\"", "\"idempotencyKey\":\" \""))
    }

    @Test fun booleansAndStringFieldsNeverCoerce() {
        for (bad in listOf(JsonPrimitive("true"), JsonPrimitive(1), JsonNull)) invalid(resultChange("success", bad))
        for (bad in listOf(JsonPrimitive("false"), JsonPrimitive(0))) invalid(resultChange("verified", bad))
        invalid(resultChange("content", JsonPrimitive(false)))
        invalid(resultChange("memoryIdsUsed", JsonPrimitive("[\"m1\"]")))
        invalid(resultChange("memoryIdsUsed", JsonArray(listOf(JsonNull))))
        invalid(resultChange("memoryIdsUsed", JsonArray(listOf(JsonPrimitive(" ")))))
    }

    @Test fun knowledgeIntegerLexemesRangesAndBadItemsRejectWholeFrame() {
        for (value in listOf("\"4\"", "4.0", "4e0", "-0", "2147483648", "0")) {
            invalid(golden.replace("\"documentRevision\":4", "\"documentRevision\":$value"))
        }
        invalid(golden.replace("\"chunkSequence\":2", "\"chunkSequence\":-1"))
        invalid(golden.replace("\"endOffset\":360", "\"endOffset\":120"))
        invalid(resultChange("knowledgeReferences", JsonArray(listOf(JsonNull))))
        invalid(golden.replace("\"retrievalId\":\"r1\"", "\"retrievalId\":\" \""))
    }

    @Test fun referenceCountBoundaryPreservesOrderAndDuplicates() {
        val result = expected.result.copy(memoryIdsUsed = List(256) { "m${255-it}" },
            knowledgeReferences = List(256) { expected.result.knowledgeReferences.single() })
        assertEquals(result, decode(SharedToolExecutionResultCodec.encode(expected.copy(result = result))).result)
        for (bad in listOf(result.copy(memoryIdsUsed = result.memoryIdsUsed + "extra"),
            result.copy(knowledgeReferences = result.knowledgeReferences + result.knowledgeReferences.first()))) {
            assertFailsWith<IllegalArgumentException> { SharedToolExecutionResultCodec.encode(expected.copy(result = bad)) }
        }
    }

    @Test fun byteLimitIsExactForAsciiAndMultibyteContent() {
        val frame = SharedToolResultFrame("c", SharedToolExecutionResult(true, ""))
        val overhead = SharedToolExecutionResultCodec.encode(frame).encodeToByteArray().size
        val contentBytes = SharedToolExecutionResultCodec.MAX_FRAME_BYTES - overhead
        for (text in listOf("a".repeat(contentBytes), "中".repeat(contentBytes / 3) + "a".repeat(contentBytes % 3))) {
            val bounded = frame.copy(result = frame.result.copy(content = text))
            val wire = SharedToolExecutionResultCodec.encode(bounded)
            assertEquals(SharedToolExecutionResultCodec.MAX_FRAME_BYTES, wire.encodeToByteArray().size)
            assertEquals(bounded, SharedToolExecutionResultCodec.decode(wire, "c"))
            assertFailsWith<IllegalArgumentException> { SharedToolExecutionResultCodec.encode(bounded.copy(result = bounded.result.copy(content = text + "a"))) }
            assertFailsWith<IllegalArgumentException> { SharedToolExecutionResultCodec.decode(wire + " ", "c") }
        }
    }

    @Test fun invalidUnicodeAndDuplicateEscapedKeysCannotChangeIdentity() {
        invalid(golden.replace("\"content\":", "\"cont\\u0065nt\":\"replaced\",\"content\":"))
        invalid(golden.replace("\"operationId\":", "\"operationId\":\"foreign\",\"operationId\":"))
        invalid(golden.replace("旧结果", "\uD800"))
        invalid(golden.replace("旧结果", "\\uD800"))
        val result = expected.result.copy(memoryIdsUsed = listOf("\uDC00"))
        assertFailsWith<IllegalArgumentException> { SharedToolExecutionResultCodec.encode(expected.copy(result = result)) }
    }

    @Test fun nonStandardJsonAndNonCanonicalVersionsReject() {
        for (version in listOf("\"1\"", "1.0", "1e0", "-0")) invalid(golden.replace("\"schemaVersion\":1", "\"schemaVersion\":$version"))
        invalid(golden + " {}")
        invalid(golden.replace("\"schemaVersion\"", "schemaVersion"))
        invalid(golden.replace("\"schemaVersion\":1", "/*old*/\"schemaVersion\":1"))
        invalid(golden.dropLast(1) + ",}")
        assertFailsWith<IllegalArgumentException> { SharedToolExecutionResultCodec.encode(SharedToolResultFrame("c".repeat(257), SharedToolExecutionResult(true, ""))) }
    }
}
