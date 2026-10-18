package com.longdev.xiaoling.agent

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.storage.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** long: 生产 Registry/Runtime 共用隔离 Room 与真实本机 socket；正式 Provider、MCP 和 Memory 撤销文件不参与夹具。 */
class BrowserDomActionInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val owner = BrowserSessionOwner("browser-device-run", "browser-chat", "browser-message", "browser-runtime")
    private fun directory() = File(context.cacheDir, "stage290-browser-${UUID.randomUUID()}").apply {
        assertEquals("begonia", android.os.Build.DEVICE); mkdirs()
    }
    private fun reader(server: MockWebServer): OkHttpBrowserPageReader {
        // long: 固定公网 IP 避免手机 DNS 夹具漂移，只有测试拦截器会导向本机，生产公网 DNS 门禁仍使用默认实现。
        val client = OkHttpClient.Builder().cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
            override fun loadForRequest(url: HttpUrl) = listOf(Cookie.Builder().name("fixture-cookie").value("private").domain(url.host).build())
        }).addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/fixture")).build()) }.build()
        return OkHttpBrowserPageReader(client, connectionDns = Dns.SYSTEM)
    }
    private fun registry(db: XiaoLingDatabase, reader: BrowserPageReader, root: File) = XiaoLingToolRegistry(
        SystemAgentClock(), RoomAgentConversationStore(context, db), noteStore = RoomAgentNoteStore(context, db),
        memoryStore = RoomAgentMemoryStore(context, db, File(root, "undo.json")), knowledgeStore = RoomKnowledgeDocumentStore(context, db),
        browserPageReader = reader, workspaceSandbox = AndroidWorkspaceSandbox(File(root, "workspace")))
    private fun session(content: String) = Regex("(?:浏览器会话已打开：|^会话：)([a-f0-9]{16})", RegexOption.MULTILINE).find(content)!!.groupValues[1]
    private fun snapshot(content: String) = Regex("^快照：([a-f0-9]{16})", RegexOption.MULTILINE).find(content)!!.groupValues[1]
    private fun ref(content: String, type: Boolean) = Regex(if (type) "ref=(input-[a-f0-9]{16}-n[a-z0-9]+)" else "ref=(link-[a-f0-9]{16}-[a-z0-9]+)").find(content)!!.groupValues[1]

    @Test fun publicHtmlDomAndLinkNavigationUseDefaultDnsAndHttps() = runBlocking {
        assertEquals("begonia", android.os.Build.DEVICE)
        val reader = OkHttpBrowserPageReader()
        try {
            // long: 独立公开网页只读门禁使用生产 DNS/TLS，不复用会改正式 MCP 偏好的旧能力测试类。
            val opened = reader.openSession("https://www.iana.org/help/example-domains", owner = owner)
            assertEquals("SERVER_HTML", opened.page.dom!!.source)
            assertTrue(opened.page.text.contains("Example", ignoreCase = true))
            val link = opened.page.linkRefs.first { it.href.startsWith("https://www.iana.org/") }
            val next = reader.clickLink(opened.id, opened.snapshotId, link.ref, owner, "public-click")
            assertNotEquals(opened.snapshotId, next.snapshotId)
            assertEquals(BrowserDomAction.NAVIGATE_LINK, next.lastAction!!.action)
            assertTrue(next.page.url.startsWith("https://www.iana.org/"))
            println("DEVICE_BROWSER_PUBLIC production_dns=true https=true server_html=true link_navigation=true")
        } finally { reader.clearSessions() }
    }

    @Test fun runtimeApprovalLedgerAndRoomReopenPreserveActionEvidenceWithoutDraftText() = runBlocking {
        val root = directory(); val name = "stage290-browser-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).build()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<title>DOM</title><label for='q'>Query</label><input id='q'><a href='/next'>Next</a>").setHeader("Set-Cookie", "server-cookie=private"))
            server.enqueue(MockResponse().setBody("<title>Next</title><p>clicked</p>")); server.start()
            val reader = reader(server)
            try {
                val native = registry(db, reader, root); val repo = RoomAgentRunRepository(context, db)
                val draft = "browser-local-private-draft"; var approvals = 0
                val model = object : AgentLlm {
                    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>): ToolCall = error("顺序动作")
                    override suspend fun proposeNextAction(goal: String, tools: List<ToolDefinition>, completedTools: List<AgentToolExecution>): AgentPlanDecision {
                        if (completedTools.isEmpty()) return AgentPlanDecision.CallTool(ToolCall(name = "browser.open", arguments = mapOf("url" to "https://8.8.8.8/start"), risk = ToolRisk.SAFE))
                        if (completedTools.size == 3) return AgentPlanDecision.Complete
                        val content = completedTools.last().toolResult.content; val type = completedTools.size == 1
                        return AgentPlanDecision.CallTool(ToolCall(name = if (type) "browser.type" else "browser.click", risk = ToolRisk.REQUIRES_APPROVAL,
                            arguments = mapOf("session_id" to session(content), "snapshot_id" to snapshot(content), "ref" to ref(content, type)) + if (type) mapOf("text" to draft) else emptyMap()))
                    }
                    override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult) = """{"style":"compact","tone":"neutral"}"""
                }
                val gate = object : ApprovalGate {
                    override suspend fun requestApproval(runId: String, toolCall: ToolCall, definition: ToolDefinition): ApprovalDecision {
                        approvals++
                        // long: 直接向 Repository 传 Executor 原参数，证明最后持久化边界也能独立去掉原文。
                        val approval = repo.createApprovalRequest("browser-chat", runId, toolCall, definition)
                        assertFalse(approval.toString().contains(draft))
                        assertNotNull(repo.decideApprovalRequest(approval.id, ApprovalRequestStatus.APPROVED, "独立夹具逐次确认"))
                        return ApprovalDecision(true, "独立夹具逐次确认")
                    }
                }
                val summary = MinimalAgentRuntime(repo, native, model, gate, processSessionId = owner.processSessionId)
                    .run(owner.conversationId, owner.userMessageId, "浏览器本机动作验收", memoryRecallEnabled = false)
                assertEquals(AgentRunStatus.COMPLETED, summary.status); assertEquals(2, approvals)
                assertFalse(VerifiedAgentContextCodec.encode(summary.verifiedContext).contains(draft))
                val ledger = repo.toolLedger(summary.runId)
                assertEquals(listOf("browser.open", "browser.type", "browser.click"), ledger.calls.map { it.toolName })
                val typeCall = ledger.calls[1]
                assertFalse(typeCall.arguments.containsKey("text")); assertEquals(BrowserTypeTextAuditPolicy.fingerprint(draft), typeCall.arguments["text_sha256"])
                assertFalse(repo.runDetail(summary.runId).toString().contains(draft)); assertFalse(ledger.toString().contains(draft))
                ledger.results.drop(1).forEach { result ->
                    assertTrue(result.success); assertEquals(true, result.executorVerified); assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
                    assertNull(result.executionReceipt)
                    val evidence = requireNotNull(result.readableEvidence); val action = requireNotNull(evidence.browserAction)
                    assertEquals(result.toolCallId, action.toolCallId); assertEquals(evidence.snapshotId, action.outputSnapshotId)
                    assertNotEquals(action.inputSnapshotId, action.outputSnapshotId)
                }
                val inputEvidence = requireNotNull(ledger.results[0].readableEvidence)
                val typeEvidence = requireNotNull(ledger.results[1].readableEvidence)
                assertEquals(inputEvidence.contentHash, typeEvidence.browserAction!!.inputContentHash)
                assertNotEquals(inputEvidence.contentHash, typeEvidence.contentHash)
                assertEquals(typeEvidence.snapshotId, ledger.results[2].readableEvidence!!.browserAction!!.inputSnapshotId)
                assertEquals(typeEvidence.contentHash, ledger.results[2].readableEvidence!!.browserAction!!.inputContentHash)
                assertEquals(2, server.requestCount)
                repeat(2) { val request = server.takeRequest(2, TimeUnit.SECONDS)!!; assertEquals("GET", request.method); assertNull(request.getHeader("Cookie")); assertEquals(0L, request.bodySize) }
                val oldSession = session(ledger.results.first().content)
                assertTrue(runCatching { reader.readSession(oldSession, owner.copy(runId = summary.runId)) }.isFailure)
                db.close(); db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).build()
                val reopened = RoomAgentRunRepository(context, db)
                assertEquals(ledger, reopened.toolLedger(summary.runId))
                assertFalse(reopened.runDetail(summary.runId).toString().contains(draft))
                println("DEVICE_BROWSER_CHAIN approval=2 ledger=3 action_binding=true room_reopen=true draft_redacted=true cookie_absent=true requests=2")
            } finally { reader.clearSessions(); db.close(); context.deleteDatabase(name); root.deleteRecursively() }
        }
    }

    @Test fun ownerStaleReplayAndSensitiveTargetsFailClosedWithoutFormSubmission() = runBlocking {
        val root = directory()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<label for='q'>Query</label><input id='q'><input aria-label='密码'><p>Visible<span style='display:none'>hidden-secret</span></p><a href='/next'>Next</a>"))
            server.enqueue(MockResponse().setResponseCode(503)); server.start()
            val reader = reader(server)
            try {
                val opened = reader.openSession("https://8.8.8.8/start", owner = owner)
                assertFalse(opened.toString().contains("hidden-secret"))
                val sensitive = opened.page.dom!!.nodes.single { it.name == "敏感输入（不可操作）" }
                assertTrue(sensitive.actions.isEmpty()); assertEquals("", sensitive.ref)
                assertTrue(runCatching { reader.typeDraft(opened.id, opened.snapshotId, sensitive.ref, "blocked", owner, "blocked") }.isFailure)
                val input = opened.page.dom!!.nodes.single { BrowserDomAction.TYPE_DRAFT in it.actions }
                val next = reader.typeDraft(opened.id, opened.snapshotId, input.ref, "local-draft", owner, "type")
                assertTrue(runCatching { reader.typeDraft(opened.id, opened.snapshotId, input.ref, "stale", owner, "stale") }.isFailure)
                assertTrue(runCatching { reader.typeDraft(next.id, next.snapshotId, next.page.dom!!.nodes.single { it.nodeId == input.nodeId }.ref, "replay", owner, "type") }.isFailure)
                assertTrue(runCatching { reader.readSession(next.id, owner.copy(runId = "foreign")) }.isFailure)
                val link = next.page.linkRefs.single().ref
                assertTrue(runCatching { reader.clickLink(next.id, next.snapshotId, link, owner, "click") }.isFailure)
                assertTrue(runCatching { reader.clickLink(next.id, next.snapshotId, link, owner, "retry") }.isFailure)
                assertEquals(next.snapshotId, reader.readSession(next.id, owner).snapshotId)
                assertEquals(2, server.requestCount); assertTrue(reader.closeSession(next.id, owner))
                assertTrue(runCatching { reader.readSession(next.id, owner) }.isFailure)
                assertTrue(runCatching { BrowserPublicDns(object : Dns { override fun lookup(hostname: String) = listOf(java.net.InetAddress.getByName("127.0.0.1")) }).lookup("fixture") }.isFailure)
                println("DEVICE_BROWSER_GUARDS owner=true stale=true replay=true sensitive=true failed_ref_consumed=true private_dns_rejected=true")
            } finally { reader.clearSessions(); root.deleteRecursively() }
        }
    }

    @Test fun runtimeCancellationStopsPendingSocketAndSettlesRoomCancelled() = runBlocking {
        val root = directory(); val db = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java).build()
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start()
            val reader = reader(server)
            try {
                val repo = RoomAgentRunRepository(context, db); val native = registry(db, reader, root)
                val model = object : AgentLlm {
                    override suspend fun proposeToolCall(goal: String, tools: List<ToolDefinition>) = ToolCall(name = "browser.open", arguments = mapOf("url" to "https://8.8.8.8/start"), risk = ToolRisk.SAFE)
                    override suspend fun summarize(goal: String, toolCall: ToolCall, toolResult: ToolExecutionResult) = error("取消不得总结")
                }
                val job = launch { MinimalAgentRuntime(repo, native, model).run("cancel-chat", "cancel-message", "取消页面", memoryRecallEnabled = false) }
                assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
                withTimeout(2_000) { job.cancelAndJoin() }
                val detail = repo.recentRunDetails(1).single()
                assertEquals(AgentRunStatus.CANCELLED, detail.snapshot.run.status)
                assertFalse(detail.snapshot.events.any { it.type == "tool.result" }); assertTrue(repo.toolLedger(detail.snapshot.run.id).results.isEmpty())
                println("DEVICE_BROWSER_CANCEL socket_arrived=true cancelled_under_2s=true room_cancelled=true no_success_result=true")
            } finally { reader.clearSessions(); db.close(); root.deleteRecursively() }
        }
    }

    @Test fun draftApprovalAfterRoomReopenRequiresNewSessionAndNewConfirmation() = runBlocking {
        val root = directory(); val name = "stage290-browser-${UUID.randomUUID()}.db"
        var db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).build()
        try {
            val repo = RoomAgentRunRepository(context, db)
            val run = repo.createRun("old-chat", "old-message", "浏览器待确认草稿")
            val definition = registry(db, DisabledBrowserPageReader, root).registeredTools().single { it.name == "browser.type" }
            val call = ToolCall(id = "old-browser-type", name = definition.name, risk = definition.risk,
                arguments = mapOf("session_id" to "old-session", "snapshot_id" to "old-snapshot", "ref" to "old-ref", "text" to "private-recovery-draft"))
            val audited = DeviceTypeTextAuditPolicy.toolCallForPersistence(call)
            val metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, audited.arguments,
                recoveryContract = ToolDefinitionRecoveryContract.snapshot(definition))
            repo.appendEvent(run.id, "tool.call.proposed", "浏览器草稿", metadata)
            repo.appendEvent(run.id, "tool.call.validated", "草稿目标已校验", metadata)
            repo.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
            repo.appendStep(run.id, "approval", "应用侧审批", "等待浏览器草稿确认", AgentStepStatus.RUNNING)
            repo.createApprovalRequest("old-chat", run.id, call, definition)
            db.close(); db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).build()
            val reopened = RoomAgentRunRepository(context, db)
            assertFalse(reopened.runDetail(run.id).toString().contains("private-recovery-draft"))
            assertTrue(reopened.recoverPendingApprovalRuns(setOf(run.id)).isEmpty())
            assertEquals(1, reopened.closeInterruptedRuns(runIds = setOf(run.id)))
            val detail = reopened.runDetail(run.id)!!
            assertEquals(AgentRunStatus.CANCELLED, detail.snapshot.run.status); assertEquals(ApprovalRequestStatus.CANCELLED, detail.approvals.single().status)
            val recovery = detail.snapshot.events.single { it.type == "run.recovered" }.metadata as RunEventMetadata.Recovery
            assertEquals(AgentRunRestartDispositionCode.EPHEMERAL_TOOL_INPUT_UNAVAILABLE, recovery.restartDisposition?.code)
            println("DEVICE_BROWSER_RECOVERY room_reopen=true raw_absent=true old_approval_rejected=true restart_required=true")
        } finally { db.close(); context.deleteDatabase(name); root.deleteRecursively() }
    }
}
