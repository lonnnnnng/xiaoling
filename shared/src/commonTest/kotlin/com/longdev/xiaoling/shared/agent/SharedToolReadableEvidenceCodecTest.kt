package com.longdev.xiaoling.shared.agent

import com.longdev.xiaoling.shared.json.StrictJsonObjectSyntax
import kotlin.test.*

class SharedToolReadableEvidenceCodecTest {
    private val legacy = """{"kind":"BROWSER_PAGE","toolCallId":"call-中文😀","snapshotId":"cccccccccccccccc","contentHash":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","sourceRef":"https://example.com/公开"}"""
    private val actionJson = """{"schemaVersion":1,"actionId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","toolCallId":"call-中文😀","action":"TYPE_DRAFT","sessionId":"bbbbbbbbbbbbbbbb","inputSnapshotId":"dddddddddddddddd","outputSnapshotId":"cccccccccccccccc","ref":"input-ref","inputContentHash":"eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"}"""
    private val actionWire get() = legacy.dropLast(1) + ",\"browserAction\":" + actionJson + "}"
    private val evidence get() = SharedToolReadableEvidence(SharedToolReadableEvidenceKind.BROWSER_PAGE,
        "call-中文😀", "c".repeat(16), "f".repeat(64), "https://example.com/公开")
    private val receipt get() = SharedBrowserActionReceipt("a".repeat(32), "call-中文😀", SharedBrowserDomAction.TYPE_DRAFT,
        "d".repeat(16), "c".repeat(16), "input-ref", "b".repeat(16), "e".repeat(64))

    @Test fun legacyGoldenRetainsUnicodeAndDoesNotInventActionOrCommit() {
        assertEquals(evidence, SharedToolReadableEvidenceCodec.decode(legacy))
        assertEquals(legacy, SharedToolReadableEvidenceCodec.encode(evidence))
        val result = SharedToolExecutionResult(true, "页面", readableEvidence = evidence)
        assertNull(result.executionReceipt)
        assertNull(result.verification)
        assertNull(result.verified)
    }

    @Test fun actionGoldenBindsCallAndOutputAndHasDeterministicWire() {
        assertEquals(evidence.copy(browserAction = receipt), SharedToolReadableEvidenceCodec.decode(actionWire))
        assertEquals(actionWire, SharedToolReadableEvidenceCodec.encode(evidence.copy(browserAction = receipt)))
        assertEquals(evidence.copy(browserAction = receipt), SharedToolReadableEvidenceCodec.decode(
            SharedToolReadableEvidenceCodec.encode(evidence.copy(browserAction = receipt))))
    }

    @Test fun fieldOrderEscapesWhitespaceAndLegacyExtensionsAreCompatible() {
        val reordered = """ {"sourceRef":"https:\/\/example.com\/公开","snapshotId":"cccccccccccccccc","toolCallId":"call-\u4e2d\u6587\ud83d\ude00","contentHash":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","legacyExtension":[true,null,{"future":1}],"kind":"BROWSER_PAGE"} """
        assertEquals(evidence, SharedToolReadableEvidenceCodec.decode(reordered))
        assertEquals(legacy, SharedToolReadableEvidenceCodec.encode(assertNotNull(SharedToolReadableEvidenceCodec.decode(reordered))))
    }

    @Test fun unknownKindOrActionAndNonIntegerVersionRejectWholeEvidence() {
        for (raw in listOf(legacy.replace("BROWSER_PAGE", "UNKNOWN"), actionWire.replace("TYPE_DRAFT", "SUBMIT_FORM")) +
            listOf("2", "1.0", "1e0", "\"1\"", "true", "null").map { actionWire.replace("\"schemaVersion\":1", "\"schemaVersion\":$it") }) {
            assertNull(SharedToolReadableEvidenceCodec.decode(raw), raw)
        }
    }

    @Test fun damagedOrNullActionNeverFallsBackToLegacyPage() {
        for (raw in listOf(legacy.dropLast(1) + ",\"browserAction\":null}",
            actionWire.replace("\"sessionId\":\"bbbbbbbbbbbbbbbb\",", ""),
            actionWire.replace("\"schemaVersion\":1", "\"extra\":true,\"schemaVersion\":1"),
            actionWire.replace("\"outputSnapshotId\":\"cccccccccccccccc\"", "\"outputSnapshotId\":\"dddddddddddddddd\""),
            actionWire.replace("\"toolCallId\":\"call-中文😀\",\"action\"", "\"toolCallId\":\"foreign\",\"action\""))) {
            assertNull(SharedToolReadableEvidenceCodec.decode(raw))
        }
    }

    @Test fun decodedDuplicateKeysRejectBeforeJsonTreeOverwritesIdentity() {
        for (raw in listOf(legacy.replace("\"kind\":", "\"kind\":\"BROWSER_PAGE\",\"kind\":"),
            legacy.replace("\"toolCallId\":", "\"toolCallId\":\"foreign\",\"toolCall\\u0049d\":"),
            actionWire.replace("\"actionId\":", "\"actionId\":\"foreign\",\"actionId\":"))) {
            assertNull(SharedToolReadableEvidenceCodec.decode(raw))
        }
    }

    @Test fun strictSyntaxRejectsPermissiveJsonAndTrailingInput() {
        for (raw in listOf(legacy + " {}", legacy + " {\"unfinished\":", legacy.replace('"', '\''),
            legacy.replace("\"kind\"", "kind"), legacy.dropLast(1) + ",}", legacy.replace("\"kind\":", "/*comment*/\"kind\":"))) {
            assertNull(SharedToolReadableEvidenceCodec.decode(raw))
        }
    }

    @Test fun wrongFieldTypesAndMissingRequiredFieldsAreRejected() {
        for (value in listOf("1", "true", "null", "[]", "{}", "\"\"", "\" \"")) {
            assertNull(SharedToolReadableEvidenceCodec.decode(legacy.replace("\"call-中文😀\"", value)))
        }
        assertNull(SharedToolReadableEvidenceCodec.decode(legacy.replace("\"toolCallId\":\"call-中文😀\",", "")))
        assertNull(SharedToolReadableEvidenceCodec.decode(null))
    }

    @Test fun hashSourceAndActionBindingsCannotBeReinterpretedAsAuthorization() {
        for (source in listOf("https://example.com/?query", "https://example.com/#fragment", "https://user@example.com/")) {
            assertNull(SharedToolReadableEvidenceCodec.decode(legacy.replace("https://example.com/公开", source)))
        }
        assertNull(SharedToolReadableEvidenceCodec.decode(legacy.replace("f".repeat(64), "F".repeat(64))))
        assertFailsWith<IllegalArgumentException> { evidence.copy(browserAction = receipt.copy(toolCallId = "foreign")) }
        assertFailsWith<IllegalArgumentException> { receipt.copy(outputSnapshotId = receipt.inputSnapshotId) }
        assertFailsWith<IllegalArgumentException> { receipt.copy(ref = "x".repeat(129)) }
    }

    @Test fun directModelCannotEncodeUnreadableOrOversizedWire() {
        for (invalid in listOf(evidence.copy(toolCallId = "x".repeat(SharedToolReadableEvidenceCodec.MAX_JSON_CHARS)),
            evidence.copy(toolCallId = "call-\uD800"), evidence.copy(sourceRef = " "), evidence.copy(snapshotId = " "),
            evidence.copy(browserAction = receipt.copy(ref = " ")))) {
            assertFailsWith<IllegalArgumentException> { SharedToolReadableEvidenceCodec.encode(invalid) }
        }
    }

    @Test fun boundedParserAndUnicodeAvoidCrossPlatformTextReplacement() {
        assertNull(SharedToolReadableEvidenceCodec.decode(legacy.replace("call-中文😀", "call-\\ud800")))
        assertNull(SharedToolReadableEvidenceCodec.decode(legacy.replace("call-中文😀", "call-\\udc00")))
        assertNull(SharedToolReadableEvidenceCodec.decode(" ".repeat(SharedToolReadableEvidenceCodec.MAX_JSON_CHARS) + legacy))
        assertFailsWith<IllegalArgumentException> {
            StrictJsonObjectSyntax.validate("{\"deep\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}")
        }
        StrictJsonObjectSyntax.validate("{\"number\":-0.125e+2,\"array\":[true,false,null,\"\\uD83D\\uDE00\"]}")
    }
}
