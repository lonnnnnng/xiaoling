package com.longdev.xiaoling.agent

import com.longdev.xiaoling.knowledge.KnowledgeReference
import com.longdev.xiaoling.knowledge.KnowledgeReferenceCodec
import com.longdev.xiaoling.shared.agent.*
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** long: JVM 的 org.json 和 Redmi 原生 org.json 共用独立 literal，验证私有帧与历史持久入口各自的边界。 */
abstract class SharedToolResultContractTests {
    private val callId = "call-中😀"
    private val reference = KnowledgeReference("r1", "d1", "路线图.md", 4, "c1", 2, 120, 360)
    private val result = ToolExecutionResult(false, "旧结果\n\"原文\"", null,
        memoryIdsUsed = listOf("m2", "m1", "m2"), knowledgeReferences = listOf(reference),
        executionReceipt = ToolExecutionReceipt(callId, "op1", "private-key", ToolExecutionReceiptStatus.UNKNOWN),
        verificationEvidence = ToolVerificationEvidence(ToolVerificationStatus.FAILED, callId, "STALE"),
        recoveryFailure = ToolRecoveryFailure("REOBSERVE", "页面已变", "新 Run"))
    private val literal = """{"schemaVersion":1,"toolCallId":"call-中😀","result":{"success":false,"content":"旧结果\n\"原文\"","verified":null,"memoryIdsUsed":["m2","m1","m2"],"knowledgeReferences":[{"retrievalId":"r1","documentId":"d1","documentName":"路线图.md","documentRevision":4,"chunkId":"c1","chunkSequence":2,"startOffset":120,"endOffset":360}],"executionReceipt":{"toolCallId":"call-中😀","operationId":"op1","idempotencyKey":"private-key","status":"UNKNOWN"},"verificationEvidence":{"status":"FAILED","toolCallId":"call-中😀","reasonCode":"STALE"},"recoveryFailure":{"code":"REOBSERVE","reason":"页面已变","suggestedAction":"新 Run"},"readableEvidence":null}}"""

    @Test fun literalAndIndependentJsonOracleKeepCompletePrivateResult() {
        val raw = ToolExecutionResultCodec.encode(callId, result)
        assertEquals(literal, raw)
        assertEquals(result, ToolExecutionResultCodec.decode(literal, callId))
        val oracle = JSONObject(raw).getJSONObject("result")
        assertFalse(oracle.getBoolean("success"))
        assertTrue(oracle.isNull("verified"))
        assertEquals(result.content, oracle.getString("content"))
        assertEquals(result.memoryIdsUsed, (0 until oracle.getJSONArray("memoryIdsUsed").length()).map { oracle.getJSONArray("memoryIdsUsed").getString(it) })
        assertEquals(result.knowledgeReferences, KnowledgeReferenceCodec.decode(oracle.getJSONArray("knowledgeReferences")))
        assertEquals("private-key", oracle.getJSONObject("executionReceipt").getString("idempotencyKey"))
        assertEquals("UNKNOWN", oracle.getJSONObject("executionReceipt").getString("status"))
        assertEquals("STALE", oracle.getJSONObject("verificationEvidence").getString("reasonCode"))
        assertEquals("新 Run", oracle.getJSONObject("recoveryFailure").getString("suggestedAction"))
    }

    @Test fun runtimeProjectionKeepsMetadataAndRedactsPrivateKeyAcrossEntrances() = runTest {
        val projected = result.toSharedAgentResult(callId)
        assertEquals(result.memoryIdsUsed, projected.memoryIdsUsed)
        assertEquals(result.knowledgeReferences, projected.knowledgeReferences)
        assertEquals(result.recoveryFailure, projected.recoveryFailure)
        assertNull(projected.executionReceipt?.idempotencyKey)
        assertFalse(SharedToolExecutionResultCodec.encode(SharedToolResultFrame(callId, projected)).contains("private-key"))
        val delegate = FakeToolRegistry()
        val registry = object : ToolRegistry {
            override fun availableTools() = delegate.availableTools()
            override fun definition(name: String) = delegate.definition(name)
            override suspend fun execute(call: ToolCall) = result
        }
        assertEquals(projected, AndroidSharedToolExecutor(registry).execute(SharedToolCall(callId, "fake.echo", emptyMap())))
        val execution = AgentToolExecution(ToolCall(callId, "fake.echo", emptyMap(), ToolRisk.SAFE), result)
        assertEquals(projected, listOf(execution).toSharedAgentExecutions().single().result)
    }

    @Test fun mismatchedResultsCannotLeakOldReferencesRecoveryOrReceipts() {
        for (foreign in listOf(
            result.copy(executionReceipt = result.executionReceipt!!.copy(toolCallId = "foreign")),
            result.copy(verificationEvidence = result.verificationEvidence!!.copy(toolCallId = "foreign")),
            result.copy(readableEvidence = ToolReadableEvidence(ToolReadableEvidenceKind.BROWSER_PAGE, "foreign", "snap", "f".repeat(64), "https://example.com/")),
        )) {
            assertThrows(IllegalArgumentException::class.java) { ToolExecutionResultCodec.encode(callId, foreign) }
            val projected = foreign.toSharedAgentResult(callId)
            assertFalse(projected.success)
            assertEquals(false, projected.verified)
            assertTrue(projected.memoryIdsUsed.isEmpty())
            assertTrue(projected.knowledgeReferences.isEmpty())
            assertNull(projected.recoveryFailure)
            assertNull(projected.executionReceipt)
            assertNull(projected.readableEvidence)
        }
        assertThrows(IllegalArgumentException::class.java) { ToolExecutionResultCodec.decode(literal, "foreign") }
    }

    @Test fun readableAndBrowserActionKeepEvidenceWithoutCommitReceipt() {
        val pageLiteral = """{"kind":"BROWSER_PAGE","toolCallId":"call-中😀","snapshotId":"cccccccccccccccc","contentHash":"ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff","sourceRef":"https://example.com/公开"}"""
        val page = ToolReadableEvidenceCodec.decode(pageLiteral)!!
        for (evidence in listOf(page, page.copy(browserAction = BrowserActionReceipt("a".repeat(32), callId,
            BrowserDomAction.TYPE_DRAFT, "b".repeat(16), "c".repeat(16), "ref", "d".repeat(16), "e".repeat(64))))) {
            val readResult = ToolExecutionResult(true, "已读取", if (evidence.browserAction == null) null else true, readableEvidence = evidence)
            val decoded = ToolExecutionResultCodec.decode(ToolExecutionResultCodec.encode(callId, readResult), callId)
            assertEquals(readResult, decoded)
            assertNull(decoded.executionReceipt)
            assertNull(decoded.verificationEvidence)
            assertEquals(evidence, readResult.toSharedAgentResult(callId).readableEvidence)
            assertEquals(evidence, listOf(AgentToolExecution(ToolCall(callId, "browser.read", emptyMap(), ToolRisk.SAFE), readResult))
                .toSharedAgentExecutions().single().result.readableEvidence)
            val event = RunEventMetadata.ToolResult("browser.read", "已读取", 1L, true, readResult.verified,
                toolCallId = callId, readableEvidence = evidence)
            assertEquals(event, RunEventMetadataCodec.decode("tool.result", RunEventMetadataCodec.encode(event)))
        }
    }

    @Test fun androidStatusMappingsAndNullablePrivateKeyAreLossless() {
        for (receiptStatus in ToolExecutionReceiptStatus.entries) for (verificationStatus in ToolVerificationStatus.entries) {
            for (key in listOf(null, "private-key")) {
                val variant = result.copy(executionReceipt = result.executionReceipt!!.copy(status = receiptStatus, idempotencyKey = key),
                    verificationEvidence = result.verificationEvidence!!.copy(status = verificationStatus))
                assertEquals(variant, ToolExecutionResultCodec.decode(ToolExecutionResultCodec.encode(callId, variant), callId))
                assertNull(variant.toSharedAgentResult(callId).executionReceipt?.idempotencyKey)
            }
        }
    }

    @Test fun strictPrivateWireRejectsFaultsThatLegacyMetadataMayTolerate() {
        val mutations = listOf(
            literal.replace("\"content\":", "\"content\":\"foreign\",\"content\":"),
            literal.replace("\"knowledgeReferences\":[{", "\"knowledgeReferences\":[null,{"),
            literal.replace("\"memoryIdsUsed\":[\"m2\",\"m1\",\"m2\"]", "\"memoryIdsUsed\":\"[]\""),
            literal.replace("\"recoveryFailure\":{", "\"recoveryFailure\":{\"unknown\":true,"),
            literal.replace("\"verificationEvidence\":{", "\"verificationEvidence\":{\"unknown\":true,"),
            literal.replace("\"idempotencyKey\":\"private-key\"", "\"idempotencyKey\":false"),
            literal + " {}",
        )
        for (raw in mutations) {
            val error = assertThrows(IllegalArgumentException::class.java) { ToolExecutionResultCodec.decode(raw, callId) }
            assertEquals("工具结果帧格式无效", error.message)
            assertNull(error.cause)
        }
    }

    @Test fun historicalRunEventFormatAndReplayDefaultsStaySeparate() {
        val legacy = """{"toolName":"memory.search","content":"旧结果","durationMs":3,"success":true,"verified":null,"memoryIdsUsed":"[\"m1\",\"m2\"]"}"""
        val event = RunEventMetadataCodec.decode("tool.result", legacy) as RunEventMetadata.ToolResult
        assertEquals(listOf("m1", "m2"), event.memoryIdsUsed)
        assertEquals(ToolReplaySafety.RESTART_REQUIRED, event.replaySafety)
        assertNull(event.toolCallId)
        assertNull(event.executionReceipt)
        assertThrows(IllegalArgumentException::class.java) { ToolExecutionResultCodec.decode(legacy, callId) }
        val historical = JSONObject(legacy).put("knowledgeReferences", JSONArray().put(JSONObject()).put(KnowledgeReferenceCodec.encode(listOf(reference)).getJSONObject(0)))
        assertEquals(listOf(reference), (RunEventMetadataCodec.decode("tool.result", historical.toString()) as RunEventMetadata.ToolResult).knowledgeReferences)
    }

    @Test fun historicalTrustedContextDoesNotAcquirePrivateKeyOrVerification() {
        val legacy = """{"runId":"old-run","toolName":"memory.search","arguments":{},"success":true,"verificationStatus":"READABLE_ONLY","rawResult":"旧正文","memoryIdsUsed":"[\"m1\"]"}"""
        val context = VerifiedAgentContextCodec.decode(legacy)!!
        assertEquals(listOf("m1"), context.memoryIdsUsed)
        assertNull(context.verificationEvidence)
        assertNull(context.executionReceipt)
        val trusted = context.copy(knowledgeReferences = listOf(reference), executionReceipt = TrustedToolExecutionReceipt(callId, "op1", ToolExecutionReceiptStatus.UNKNOWN))
        val wire = VerifiedAgentContextCodec.encode(trusted)
        assertFalse(wire.contains("idempotencyKey"))
        assertEquals(trusted, VerifiedAgentContextCodec.decode(wire))
    }

    @Test fun portableCapacityDoesNotConstrainExistingRuntimeProjection() {
        val many = result.copy(memoryIdsUsed = List(257) { "m$it" })
        assertEquals(many.memoryIdsUsed, many.toSharedAgentResult(callId).memoryIdsUsed)
        assertThrows(IllegalArgumentException::class.java) { ToolExecutionResultCodec.encode(callId, many) }
        val nullTypedId = result.copy(verificationEvidence = ToolVerificationEvidence(ToolVerificationStatus.PASSED))
        val decoded = ToolExecutionResultCodec.decode(ToolExecutionResultCodec.encode(callId, nullTypedId), callId)
        assertEquals(nullTypedId, decoded)
        assertNull(decoded.verificationEvidence?.toolCallId)
        assertFalse(decoded.success)
        assertNull(decoded.verified)
    }
}
