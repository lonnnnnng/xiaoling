package com.longdev.xiaoling.agent

import com.longdev.xiaoling.shared.agent.SharedToolReadableEvidenceCodec
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** long: JVM 和 Redmi 使用相同旧 wire 夹具，验证 Android 原接口仍与 common codec 互通。 */
abstract class SharedReadableEvidenceContractTests {
    private val legacy = """{"sourceRef":"https://example.com/公开","kind":"BROWSER_PAGE","snapshotId":"cccccccccccccccc","toolCallId":"old-call","contentHash":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"}"""

    @Test fun androidLegacyObjectAndCommonCodecAgreeWithoutCreatingAction() {
        val expected = SharedToolReadableEvidenceCodec.decode(legacy)!!
        assertEquals(expected, ToolReadableEvidenceCodec.decode(JSONObject(legacy)))
        assertEquals(expected, SharedToolReadableEvidenceCodec.decode(ToolReadableEvidenceCodec.encodeToString(expected)))
        assertNull(expected.browserAction)
        assertNull(ToolExecutionResult(true, "old", readableEvidence = expected).toSharedAgentResult("old-call").executionReceipt)
    }

    @Test fun actionSurvivesRunEventAndSharedResultWithoutGrantingCommit() {
        val action = BrowserActionReceipt("a".repeat(32), "old-call", BrowserDomAction.TYPE_DRAFT,
            "d".repeat(16), "c".repeat(16), "input-ref", "b".repeat(16), "e".repeat(64))
        val evidence = ToolReadableEvidenceCodec.decode(legacy)!!.copy(browserAction = action)
        val metadata = RunEventMetadata.ToolResult("browser.type", "长度和摘要", 1L, true, true,
            toolCallId = "old-call", readableEvidence = evidence)
        val wire = RunEventMetadataCodec.encode(metadata)
        assertEquals(metadata, RunEventMetadataCodec.decode("tool.result", wire))
        val result = ToolExecutionResult(true, "长度和摘要", true, readableEvidence = evidence).toSharedAgentResult("old-call")
        assertEquals(evidence, result.readableEvidence)
        assertNull(result.executionReceipt)
        assertEquals(evidence, SharedToolReadableEvidenceCodec.decode(ToolReadableEvidenceCodec.encode(evidence).toString()))
    }

    @Test fun mismatchedReadableCallFailsSharedProjectionWithoutLeakingOldEvidence() {
        val evidence = ToolReadableEvidenceCodec.decode(legacy)!!
        val result = ToolExecutionResult(true, "wrong", true, readableEvidence = evidence).toSharedAgentResult("new-call")
        assertFalse(result.success)
        assertEquals(false, result.verified)
        assertEquals("VERIFICATION_TOOL_CALL_MISMATCH", result.verification?.reasonCode)
        assertNull(result.readableEvidence)
        assertNull(result.executionReceipt)
    }

    @Test fun androidRawDecoderRejectsDuplicateKeysAndMalformedAction() {
        assertNull(ToolReadableEvidenceCodec.decode(legacy.replace("\"toolCallId\":", "\"toolCallId\":\"foreign\",\"toolCallId\":")))
        assertNull(ToolReadableEvidenceCodec.decode(legacy + " {}"))
        assertNull(ToolReadableEvidenceCodec.decode(legacy.dropLast(1) + ",\"browserAction\":null}"))
        assertNull(ToolReadableEvidenceCodec.decode(JSONObject(legacy).put("toolCallId", 42)))
    }
}
