package com.longdev.xiaoling.genui

import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.network.OpenAiResponseParser
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

fun genUiFixtureText(form: Boolean = false): String {
    val input = if (form) """{"type":"input","version":1,"id":"count","label":"会话条数","maxLength":2,"sensitive":false},""" else ""
    val tool = if (form) "app.list_conversations" else "app.current_time"
    val fields = if (form) """{"limit":"count"}""" else "{}"
    return "```xiaoling-ui\n" + """{"type":"card","version":2,"id":"demo","revision":1,"title":"本机只读操作","items":[{"type":"facts","version":1,"id":"facts","entries":[{"label":"范围","value":"本机只读"}]},${input}{"type":"action","version":1,"id":"read","label":"申请读取","action":{"type":"host_read","version":1,"tool":"$tool","arguments":{},"fields":$fields}}]}""" + "\n```"
}

/** long: 同一协议/作用域向量在 JVM 和 Android JSON 实现上验收，防止平台宽松解析扩大执行范围。 */
open class GenUiActionContractTests {
    private fun document(text: String = genUiFixtureText()) = GenUiPolicy.projectAssistantText(text)?.document
    @Test fun v2ParsesVersionedFactsFormAndFixedBinding() {
        val doc = requireNotNull(document(genUiFixtureText(true)))
        assertEquals(2, doc.version); assertEquals("demo", doc.id); assertEquals(1, doc.revision)
        assertTrue(doc.items[0] is GenUiItem.Facts); assertTrue(doc.items[1] is GenUiItem.Input)
        assertEquals(mapOf("limit" to "count"), (doc.items[2] as GenUiItem.Action).fields)
    }
    @Test fun unknownVersionsTypesExtraKeysAndSensitiveInputsFallBack() {
        val text = genUiFixtureText(true)
        listOf(text.replace("\"version\":2", "\"version\":3"), text.replace("\"version\":1", "\"version\":2"),
            text.replace("\"facts\"", "\"web\""), text.replace("\"sensitive\":false", "\"sensitive\":true"),
            text.replace("\"sensitive\":false", "\"sensitive\":\"false\""), text.replace("\"maxLength\":2", "\"maxLength\":2,\"value\":\"secret\""),
            text.replace("\"revision\":1", "\"revision\":\"1\""), text.replace("\"revision\":1", "\"revision\":1.1"),
            text.replace("\"title\"", "\"script\""), text.replace("\"revision\":1", "\"revision\":0"))
            .forEach { assertNull(it, document(it)) }
    }
    @Test fun foreignToolsTemplateBindingsDuplicatesAndOversizeFailClosed() {
        val text = genUiFixtureText(true)
        listOf(text.replace("app.list_conversations", "notes.create"), text.replace("app.list_conversations", "mcp.call_tool"),
            text.replace("\"count\"}", "\"foreign\"}"), text.replace("\"id\":\"facts\"", "\"id\":\"read\""),
            text.replace("\"maxLength\":2", "\"maxLength\":121"), text.replace("本机只读", "x".repeat(501)))
            .forEach { assertNull(it.take(120), document(it)) }
        assertNull(document(text.dropLast(4)))
    }
    @Test fun strictJsonRejectsTrailingHalfObjectDialectAndDecodedDuplicateKeys() {
        val text = genUiFixtureText()
        listOf(text.replace("\n```", " {\"unfinished\":\n```"), text.replace('"', '\''),
            text.replace("\"revision\":1", "\"revision\":1,\"revision\":2"),
            text.replace("\"revision\":1", "\"revision\":1,\"\\u0072evision\":2"),
            text.replace("\"version\":2", "\"version\":02"), text.replace("\"version\":2", "\"version\":2,"))
            .forEach { assertNull(document(it)) }
        assertFalse(OpenAiResponseParser.confirmsActionCompletion(ApiMode.RESPONSES,"""{"status":"completed"} garbage"""))
    }
    @Test fun leaseBindsMessageBodyConversationAndProcessWithoutReissue() {
        val issuer = GenUiActionLeases(); val text = genUiFixtureText(); val lease = requireNotNull(issuer.issue("c", "m", text))
        listOf(Triple("foreign", "m", text), Triple("c", "other", text), Triple("c", "m", "$text drift"))
            .forEach { (c,m,t) -> assertTrue(runCatching { issuer.select(lease,c,m,t,"read", emptyMap()) }.isFailure) }
        assertTrue(runCatching { GenUiActionLeases().select(lease,"c","m",text,"read",emptyMap()) }.isFailure)
        val selection = issuer.select(lease,"c","m",text,"read",emptyMap()); assertTrue(selection.isCurrent())
        assertTrue(runCatching { issuer.select(lease,"c","m",text,"read",emptyMap()) }.isFailure)
        issuer.revokeAll(); assertFalse(selection.isCurrent())
    }
    @Test fun expiryCapacityAndExplicitClaimCannotBeReused() {
        var now = 100L; val issuer = GenUiActionLeases { now }; val text = genUiFixtureText()
        val lease = requireNotNull(issuer.issue("c","m",text)); now += GenUiActionPolicy.TTL_MS
        assertTrue(runCatching { issuer.select(lease,"c","m",text,"read",emptyMap()) }.isFailure)
        val old = requireNotNull(issuer.issue("c","old",text)); repeat(32) { issuer.issue("c","$it",text) }
        assertTrue(runCatching { issuer.select(old,"c","old",text,"read",emptyMap()) }.isFailure)
        val fresh = requireNotNull(issuer.issue("c","fresh",text)); val selection = issuer.select(fresh,"c","fresh",text,"read",emptyMap())
        selection.claim(); assertTrue(runCatching { selection.claim() }.isFailure)
    }
    @Test fun numericDraftValidationFailsBeforeConsumptionAndNeverSubmitsText() {
        val issuer = GenUiActionLeases(); val text = genUiFixtureText(true); val lease = requireNotNull(issuer.issue("c","m",text))
        listOf("", "0", "11", "01", "secret", "2\n3").forEach { value ->
            assertTrue(runCatching { issuer.select(lease,"c","m",text,"read",mapOf("count" to value)) }.isFailure)
        }
        val selection = issuer.select(lease,"c","m",text,"read",mapOf("count" to "10"))
        assertEquals(mapOf("limit" to "10"), selection.arguments)
    }
    @Test fun actionCompletionRequiresNormalTerminalEvidence() {
        assertFalse(OpenAiResponseParser.confirmsActionCompletion(ApiMode.CHAT_COMPLETIONS, """{"choices":[{"finish_reason":"length"}]}"""))
        assertFalse(OpenAiResponseParser.confirmsActionCompletion(ApiMode.CHAT_COMPLETIONS, """{"choices":[{}]}"""))
        assertTrue(OpenAiResponseParser.confirmsActionCompletion(ApiMode.CHAT_COMPLETIONS, """{"choices":[{"finish_reason":"stop"}]}"""))
        assertFalse(OpenAiResponseParser.confirmsActionCompletion(ApiMode.RESPONSES, """{"status":"incomplete"}"""))
        assertTrue(OpenAiResponseParser.confirmsActionCompletion(ApiMode.RESPONSES, """{"status":"completed"}"""))
        assertNull(OpenAiResponseParser.parseStreamEvent(ApiMode.CHAT_COMPLETIONS,"[DONE]"))
        assertEquals(false, OpenAiResponseParser.parseStreamEvent(ApiMode.CHAT_COMPLETIONS,"""{"choices":[{"finish_reason":"length"}]}""")!!.actionCompletionConfirmed)
        assertEquals(true, OpenAiResponseParser.parseStreamEvent(ApiMode.CHAT_COMPLETIONS,"""{"choices":[{"finish_reason":"stop"}]}""")!!.actionCompletionConfirmed)
        assertNull(OpenAiResponseParser.parseStreamEvent(ApiMode.RESPONSES,"""{"type":"response.output_text.done","text":"partial"}""")!!.actionCompletionConfirmed)
        assertEquals(false,OpenAiResponseParser.parseStreamEvent(ApiMode.RESPONSES,"""{"type":"response.completed","response":{"status":"completed","output":[]}}""")!!.actionCompletionConfirmed)
    }
    private class Fixture {
        val issuer = GenUiActionLeases(); val foreground = AgentPluginForegroundState(); val owner = Any()
        val tools = PluginExecutionTools(); val ledger = GenUiTestLedger()
        init { foreground.resumed(owner) }
        val selection = issuer.select(requireNotNull(issuer.issue("c","m",genUiFixtureText())),"c","m",genUiFixtureText(),"read",emptyMap())
        fun gateway() = GenUiActionToolRegistry(tools, selection, foreground, foreground.capture())
        suspend fun run(g: GenUiActionToolRegistry, gate: ApprovalGate) = g.guardRun {
            MinimalAgentRuntime(ledger, g, g.fixedModel(), approvalGate = gate, options = AgentRuntimeOptions(maxToolCalls = 1))
                .run("c","new-user","读取时间",memoryRecallEnabled = false)
        }
    }
    @Test fun clickWaitsForApprovalWithZeroExecutorThenVerifiesExactlyOneCall() = runBlocking {
        val f = Fixture(); val waiting = CompletableDeferred<Unit>(); val decision = CompletableDeferred<ApprovalDecision>()
        val gate = object : ApprovalGate { override suspend fun requestApproval(runId: String, toolCall: ToolCall, definition: ToolDefinition): ApprovalDecision {
            assertEquals(ToolApprovalPolicy.REQUIRE_CONFIRMATION, definition.approvalPolicy)
            waiting.complete(Unit); return decision.await()
        } }
        val job = async { f.run(f.gateway(),gate) }; waiting.await()
        assertEquals(0,f.tools.calls); assertEquals(AgentRunStatus.WAITING_APPROVAL, f.ledger.snapshot(f.ledger.lastRunId!!).run.status)
        decision.complete(ApprovalDecision(true,"用户批准")); val summary = job.await()
        assertEquals(AgentRunStatus.COMPLETED, summary.status); assertEquals(1,f.tools.calls); assertTrue(f.tools.finished)
        assertTrue(f.ledger.snapshot(summary.runId).events.any { it.type == "tool.verify" && (it.metadata as RunEventMetadata.ToolVerification).status == ToolVerificationStatus.PASSED })
        assertTrue(runCatching { f.gateway() }.isFailure)
    }
    @Test fun rejectionAndDefinitionDriftBeforeApprovalNeverExecute() = runBlocking {
        for (drift in listOf(false,true)) {
            val f=Fixture(); val g=f.gateway(); val gate=object: ApprovalGate {
                override suspend fun requestApproval(runId:String,toolCall:ToolCall,definition:ToolDefinition):ApprovalDecision {
                    if(drift) f.tools.definitions=f.tools.definitions.map { it.copy(description="漂移") }
                    return ApprovalDecision(drift,"拒绝或漂移")
                }
            }
            assertTrue(runCatching { f.run(g,gate) }.isFailure); assertEquals(0,f.tools.calls)
            assertEquals(AgentRunStatus.FAILED, f.ledger.snapshot(f.ledger.lastRunId!!).run.status)
        }
    }
    @Test fun realForegroundRevisionAndLeaseRevocationCancelApprovalWait() = runBlocking {
        for (pause in listOf(true,false)) {
            val f=Fixture(); val waiting=CompletableDeferred<Unit>(); val gate=object:ApprovalGate {
                override suspend fun requestApproval(runId:String,toolCall:ToolCall,definition:ToolDefinition):ApprovalDecision { waiting.complete(Unit); awaitCancellation() }
            }
            val job=async { runCatching { f.run(f.gateway(),gate) } }; waiting.await()
            if(pause) { f.foreground.paused(f.owner); f.foreground.resumed(f.owner) } else f.issuer.revokeAll()
            assertTrue(withTimeout(2_000){job.await()}.exceptionOrNull() is CancellationException)
            assertEquals(0,f.tools.calls); assertEquals(AgentRunStatus.CANCELLED, f.ledger.snapshot(f.ledger.lastRunId!!).run.status)
        }
    }
    @Test fun directExecutorBypassAndCrossConversationContextAreRejected() = runBlocking {
        val f=Fixture(); val g=f.gateway()
        assertTrue(runCatching { g.bindRunContext(AgentToolExecutionContext("other","u","r","goal",memoryRecallEnabled=false)) }.isFailure)
        g.bindRunContext(AgentToolExecutionContext("c","u","r","goal",memoryRecallEnabled=false))
        assertTrue(runCatching { g.execute(pluginTimeCall()) }.isFailure); assertEquals(0,f.tools.calls)
    }
    @Test fun damagedMarkerStillRejectsRecoveryRetryAndReplay() = runBlocking {
        val ledger=GenUiTestLedger(); val run=ledger.createRun("c","m","old",null)
        ledger.appendEvent(run.id,GenUiExecutionPolicy.SELECTION_EVENT,"损坏来源",null); ledger.updateRunStatus(run.id,AgentRunStatus.CANCELLED)
        val detail=AgentRunDetailRecord(ledger.snapshot(run.id),emptyList())
        assertEquals(AgentRunRestartDispositionCode.GENUI_SELECTION_EXPIRED,AgentRunResumePolicy.assess(detail).restartDisposition!!.code)
        assertEquals(AgentTaskRetryEligibility.NotRetryable,AgentTaskRetryPolicy.evaluate(detail))
        assertTrue(AgentNotCommittedReplayQualificationPolicy.assess(detail,null){null} is AgentNotCommittedReplayQualificationAssessment.Ineligible)
        assertTrue(runCatching { GenUiExecutionPolicy.requireFreshSelection(detail) }.isFailure)
        val metadata=RunEventMetadata.Reason(JSONObject().put("messageSha256","hash").toString())
        assertEquals(metadata,RunEventMetadataCodec.decode(GenUiExecutionPolicy.SELECTION_EVENT,RunEventMetadataCodec.encode(metadata)))
    }
}
