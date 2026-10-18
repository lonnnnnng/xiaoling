package com.longdev.xiaoling.agent

import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class BrowserDomActionTest {
    private val owner = BrowserSessionOwner("dom-run", "dom-chat", "dom-message", "dom-runtime")
    private val html = """<title>DOM</title><h1>Heading</h1><form><label for="q">查询</label><input id="q" type="search" value="server-value" />
        <label for="p">密码</label><input id="p" type="password" value="never-read" /><button>提交</button></form>
        <label for="comment">评论</label><textarea id="comment">initial-private-value</textarea><a href="/next">Next</a>
        <div hidden><a href="/hidden">Hidden</a><input aria-label="hidden-input"></div><script>bad()</script>"""
    private fun reader(server: MockWebServer): OkHttpBrowserPageReader {
        // long: 仅隔离 transport 将公开源地址导向 loopback；生产构造默认的 BrowserPublicDns 不被这份夹具替代。
        val client = OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/fixture")).build()) }.build()
        return OkHttpBrowserPageReader(client, connectionDns = Dns.SYSTEM)
    }

    @Test fun domNamesActionsAndSensitiveFieldsAreConservative() {
        val extracted = HtmlTextExtractor.extract(html, "https://8.8.8.8/start", 2_000)
        val dom = requireNotNull(extracted.dom)
        assertEquals("SERVER_HTML", dom.source); assertEquals(1, dom.schemaVersion)
        val comment = dom.nodes.single { it.name == "评论" }
        assertEquals(setOf(BrowserDomAction.TYPE_DRAFT), comment.actions)
        assertTrue(dom.nodes.filter { it.role == "textbox" && it.name != "评论" }.all { it.actions.isEmpty() })
        assertTrue(dom.nodes.single { it.role == "button" }.actions.isEmpty())
        assertEquals(listOf("https://8.8.8.8/next"), extracted.links)
        for (secret in listOf("server-value", "never-read", "initial-private-value", "bad()")) assertFalse(extracted.toString().contains(secret))
    }

    @Test fun hiddenDescendantsAndLinkOverflowCannotReenterEvidenceOrActions() {
        val hidden = HtmlTextExtractor.extract("""<p>Visible<span hidden>hidden-secret</span><span style="display:none">style-secret</span></p>
            <label for='q'>Query<span aria-hidden='true'>label-secret</span></label><input id='q'><input>
            <form><input type='password' hidden><input aria-label='Login form'></form>""", "https://8.8.8.8/", 2_000)
        for (secret in listOf("hidden-secret", "style-secret", "label-secret")) assertFalse(hidden.toString().contains(secret))
        assertEquals(1, hidden.dom!!.nodes.count { BrowserDomAction.TYPE_DRAFT in it.actions })
        val overflow = HtmlTextExtractor.extract((0..110).joinToString("") { "<a href='/$it'>Link $it</a>" }, "https://8.8.8.8/", 2_000)
        assertEquals(BrowserUrlPolicy.MAX_LINKS, overflow.links.size)
        assertEquals(BrowserUrlPolicy.MAX_LINKS, overflow.dom!!.nodes.count { BrowserDomAction.NAVIGATE_LINK in it.actions })
        assertTrue(overflow.dom!!.truncated)
    }

    @Test fun draftOnlyMutatesLocalSnapshotAndOldRefsCannotBeReused() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(html)); server.start()
            val reader = reader(server); val session = reader.openSession("https://8.8.8.8/start", owner = owner)
            val node = session.page.dom!!.nodes.single { it.name == "评论" }
            val hashBefore = session.page.toReadableEvidence("before", session.snapshotId).contentHash
            val next = reader.typeDraft(session.id, session.snapshotId, node.ref, "private-draft", owner, "type-1")
            assertNotEquals(session.snapshotId, next.snapshotId)
            assertEquals(owner, next.owner); assertEquals(BrowserDomAction.TYPE_DRAFT, next.lastAction!!.action)
            assertEquals("type-1", next.lastAction!!.toolCallId)
            val updated = next.page.dom!!.nodes.single { it.nodeId == node.nodeId }
            assertEquals(13, updated.draftLength); assertEquals(BrowserTypeTextAuditPolicy.fingerprint("private-draft"), updated.draftSha256)
            assertFalse(next.toString().contains("private-draft"))
            assertNotEquals(hashBefore, next.page.toReadableEvidence("after", next.snapshotId).contentHash)
            assertEquals(1, server.requestCount)
            assertTrue(runCatching { reader.typeDraft(session.id, session.snapshotId, node.ref, "replay", owner, "type-2") }.isFailure)
            assertTrue(runCatching { reader.typeDraft(next.id, next.snapshotId, updated.ref, "replay", owner, "type-1") }.isFailure)
            assertTrue(runCatching { reader.readSession(next.id, owner.copy(runId = "foreign")) }.isFailure)
            assertTrue(runCatching { reader.closeSession(next.id, owner.copy(processSessionId = "foreign")) }.isFailure)
        }
    }

    @Test fun failedClickConsumesRefAndCallWithoutReplacingSnapshot() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<a href='/next'>Next</a>")); server.enqueue(MockResponse().setResponseCode(503)); server.start()
            val reader = reader(server); val session = reader.openSession("https://8.8.8.8/start", owner = owner)
            val ref = session.page.linkRefs.single().ref
            assertTrue(runCatching { reader.clickLink(session.id, session.snapshotId, ref, owner, "click") }.isFailure)
            assertEquals(session.snapshotId, reader.readSession(session.id, owner).snapshotId)
            assertTrue(runCatching { reader.clickLink(session.id, session.snapshotId, ref, owner, "retry") }.isFailure)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun concurrentClickAndCloseCannotPublishLateSnapshot() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<a href='/next'>Next</a>")); server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
            val reader = reader(server); val session = reader.openSession("https://8.8.8.8/start", owner = owner)
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            val operation = async { runCatching { reader.clickLink(session.id, session.snapshotId, session.page.linkRefs.single().ref, owner, "click") } }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            assertTrue(runCatching { reader.navigateSession(session.id, "https://8.8.8.8/other", owner = owner) }.isFailure)
            assertTrue(reader.closeSession(session.id, owner))
            assertTrue(withTimeout(2_000) { operation.await() }.isFailure)
            assertTrue(runCatching { reader.readSession(session.id, owner) }.isFailure)
        }
    }

    @Test fun runClearCancelsPendingOpenAndNoSessionLeaks() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
            val reader = reader(server)
            val operation = async { runCatching { reader.openSession("https://8.8.8.8/start", owner = owner) } }
            assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(2, TimeUnit.SECONDS) })
            reader.clearSessions()
            assertTrue(withTimeout(2_000) { operation.await() }.isFailure)
            server.enqueue(MockResponse().setBody("<p>new</p>"))
            assertEquals("new", reader.openSession("https://8.8.8.8/start", owner = owner.copy(runId = "new")).page.text)
        }
    }

    @Test fun domNodeAndActionBudgetsRejectOverflowAndUnknownInputs() = runBlocking {
        val page = HtmlTextExtractor.extract("<p>bounded</p>".repeat(200), "https://8.8.8.8/", 2_000)
        assertEquals(BrowserUrlPolicy.MAX_DOM_NODES, page.dom!!.nodes.size); assertTrue(page.dom!!.truncated)
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<label for='q'>Query</label><input id='q'>")); server.start()
            val reader = reader(server); var session = reader.openSession("https://8.8.8.8/start", owner = owner)
            assertTrue(runCatching { reader.typeDraft(session.id, session.snapshotId, "unknown", "text", owner, "bad") }.isFailure)
            repeat(BrowserUrlPolicy.MAX_ACTIONS_PER_SESSION) { index ->
                session = reader.typeDraft(session.id, session.snapshotId, session.page.dom!!.nodes.single().ref, "draft-$index", owner, "type-$index")
            }
            assertTrue(runCatching { reader.typeDraft(session.id, session.snapshotId, session.page.dom!!.nodes.single().ref, "overflow", owner, "overflow") }.isFailure)
        }
    }

    @Test fun connectionDnsRejectsPrivateReservedAndMixedAnswers() {
        for (address in listOf("127.0.0.1", "10.0.0.1", "100.64.0.1", "192.0.2.1", "2001:db8::1")) {
            val dns = BrowserPublicDns(object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByName(address)) })
            assertThrows(java.net.UnknownHostException::class.java) { dns.lookup("example.com") }
        }
        assertThrows(IllegalArgumentException::class.java) { BrowserUrlPolicy.publicAddresses(listOf(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("127.0.0.1"))) }
    }

    @Test fun typeAuditProjectionAndRecoveryNeverPersistRawText() {
        val call = ToolCall(name = "browser.type", arguments = mapOf("session_id" to "s", "snapshot_id" to "p", "ref" to "r", "text" to "private-draft"), risk = ToolRisk.REQUIRES_APPROVAL)
        val audited = DeviceTypeTextAuditPolicy.toolCallForPersistence(call)
        assertFalse(audited.arguments.containsKey("text")); assertFalse(audited.toString().contains("private-draft"))
        assertEquals(BrowserTypeTextAuditPolicy.fingerprint("private-draft"), audited.arguments["text_sha256"])
        assertEquals(audited, DeviceTypeTextAuditPolicy.toolCallForPersistence(audited))
    }

    @Test fun actionCodecPreservesBindingsAndRejectsMalformedEvidence() {
        val action = BrowserActionReceipt("a".repeat(32), "call", BrowserDomAction.TYPE_DRAFT, "b".repeat(16), "c".repeat(16), "input-ref", "d".repeat(16), "e".repeat(64))
        val evidence = ToolReadableEvidence(ToolReadableEvidenceKind.BROWSER_PAGE, "call", "c".repeat(16), "f".repeat(64), "https://8.8.8.8/", action)
        val encoded = ToolReadableEvidenceCodec.encodeToString(evidence)
        assertEquals(evidence, ToolReadableEvidenceCodec.decode(encoded))
        for (field in listOf("schemaVersion", "outputSnapshotId", "inputContentHash", "action")) {
            val json = org.json.JSONObject(encoded)
            json.getJSONObject("browserAction").put(field, "invalid")
            assertNull(ToolReadableEvidenceCodec.decode(json))
        }
        for (change in listOf("missing", "extra", "null")) {
            val json = org.json.JSONObject(encoded)
            when (change) {
                "missing" -> json.getJSONObject("browserAction").remove("sessionId")
                "extra" -> json.getJSONObject("browserAction").put("unexpected", true)
                else -> json.put("browserAction", org.json.JSONObject.NULL)
            }
            assertNull(ToolReadableEvidenceCodec.decode(json))
        }
        assertEquals(evidence.copy(browserAction = null), ToolReadableEvidenceCodec.decode(ToolReadableEvidenceCodec.encodeToString(evidence.copy(browserAction = null))))
    }

    @Test fun runtimeRejectsActionMismatchesAndReadableEvidenceUpgradeBeforeLedgerResult() = runBlocking {
        val call = ToolCall(id = "bound-call", name = "browser.click", risk = ToolRisk.REQUIRES_APPROVAL,
            arguments = mapOf("session_id" to "a".repeat(16), "snapshot_id" to "b".repeat(16), "ref" to "link-ref"))
        val action = BrowserActionReceipt("d".repeat(32), call.id, BrowserDomAction.NAVIGATE_LINK, "b".repeat(16), "c".repeat(16), "link-ref", "a".repeat(16), "e".repeat(64))
        val evidence = ToolReadableEvidence(ToolReadableEvidenceKind.BROWSER_PAGE, call.id, action.outputSnapshotId, "f".repeat(64), "https://8.8.8.8/", action)
        val invalid = listOf(null, action.copy(sessionId = "9".repeat(16)), action.copy(inputSnapshotId = "9".repeat(16)),
            action.copy(ref = "other-ref"), action.copy(action = BrowserDomAction.TYPE_DRAFT))
        val committed = ToolExecutionReceipt(call.id, "forged-business", "forged-key", ToolExecutionReceiptStatus.COMMITTED)
        val results = invalid.map { ToolExecutionResult(true, "动作候选", true, readableEvidence = evidence.copy(browserAction = it)) } +
            listOf(ToolExecutionResult(true, "夹带提交", true, executionReceipt = committed, readableEvidence = evidence),
                ToolExecutionResult(true, "只读夹带提交", executionReceipt = committed, readableEvidence = evidence.copy(browserAction = null)))
        for (result in results) {
            val definition = ToolDefinition(call.name, "隔离证据验证", call.risk, inputSchema = call.arguments.keys.map { ToolInputField(it, "目标", required = true) },
                verificationPolicy = ToolVerificationPolicy.EXECUTOR_VERIFIED)
            val registry = object : ToolRegistry {
                override fun availableTools() = listOf(definition)
                override fun definition(name: String) = definition.takeIf { it.name == name }
                override suspend fun execute(call: ToolCall) = result
            }
            val model = object : AgentLlm {
                override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>) = call
                override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult) = error("拒绝结果不得总结")
            }
            val ledger = InMemoryAgentRunLedger()
            assertTrue(runCatching { MinimalAgentRuntime(ledger, registry, model).run("chat", "message", "验证绑定", memoryRecallEnabled = false) }.isFailure)
            assertFalse(ledger.snapshot(ledger.lastRunId!!).events.any { it.type == "tool.result" })
        }
    }
}
