package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import java.security.MessageDigest
import java.util.UUID

/** long: 多进程只共享外部指定的 UUID 数据库，不写正式 Provider、MCP 或 Memory undo。 */
internal class AgentContextRecoveryFixture(val context: Context, val name: String) : AutoCloseable {
    init { require(name.matches(Regex("stage287-recovery-[0-9a-f-]{36}\\.db"))); UUID.fromString(name.removePrefix("stage287-recovery-").removeSuffix(".db")) }
    val db = Room.databaseBuilder(context, XiaoLingDatabase::class.java, name).addMigrations(*XiaoLingDatabase.migrations()).build()
    val repo = RoomAgentRunRepository(context, db)
    val oldText = "首部完整原文" + "中😀".repeat(12_000) + "原文中间事实" + "尾部".repeat(1_000)

    suspend fun seed(halfPlan: Boolean = false): String {
        val definition = ToolDefinition("fixture.read", "读取隔离事实", ToolRisk.SAFE, inputSchema = listOf(ToolInputField("value", "索引", required = true)))
        val registry = object : ToolRegistry {
            override fun availableTools() = listOf(definition)
            override fun definition(name: String) = definition.takeIf { name == it.name }
            override suspend fun execute(call: ToolCall) = ToolExecutionResult(true, if (call.arguments["value"] == "0") oldText else "完整事实-${call.arguments["value"]}")
        }
        val server = MockWebServer()
        repeat(3) { index ->
            val action = """{"action":"tool","tool":"fixture.read","arguments":{"value":"$index"}}"""
            val body = JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", action))))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()))
        }
        server.start()
        try {
            val adapter = OpenAiAgentLlm(OpenAiCompatibleClient(), ProviderRequestConfig(server.url("/v1").toString(), "fixture-key", "fixture", httpDebugLoggingEnabled = false), "样式")
            val llm = object : AgentLlm by adapter {
                override suspend fun proposeNextActionWithCompression(goal: String, tools: List<ToolDefinition>, completedTools: List<AgentToolExecution>,
                    capture: suspend (AgentContextInput, AgentContextCompressionCandidate?) -> Unit): AgentLlmCallResult<AgentPlanDecision> =
                    adapter.proposeNextActionWithCompression(goal, tools, completedTools) { input, candidate ->
                        capture(input, candidate)
                        // long: 来源已提交但尚未发送第三次模型请求；重启不能把输入快照当作模型结果或工具授权。
                        if (halfPlan && candidate != null) throw AgentProcessTerminationSimulation()
                    }
            }
            var verified = 0
            val runtime = MinimalAgentRuntime(repo, registry, llm, faultInjector = object : AgentRuntimeFaultInjector {
                override fun afterToolVerificationPersisted(runId: String, call: ToolCall, result: ToolExecutionResult) {
                    if (++verified == 3) throw AgentProcessTerminationSimulation()
                }
            })
            assertTrue(runCatching { runtime.run("fixture-conversation", "fixture-message", "读取三个隔离事实") }.exceptionOrNull() is AgentProcessTerminationSimulation)
            assertEquals(if (halfPlan) 2 else 3, server.requestCount)
            val runId = db.agentRunDao().getRecentRuns(1).single().id
            assertEquals(3, repo.contextTranscript(runId).size)
            assertEquals(1, repo.contextCompressions(runId).size)
            val detail = requireNotNull(repo.runDetail(runId))
            assertEquals(AgentContextRecoveryStatus.VALIDATED, detail.contextRecoveryStatus)
            assertEquals(if (halfPlan) AgentRunResumeKind.RESTART_REQUIRED else AgentRunResumeKind.VERIFIED_TOOL_COMPLETION, AgentRunResumePolicy.assess(detail).kind)
            return runId
        } finally { server.shutdown() }
    }

    suspend fun seedApproval(): Pair<String, ApprovalRequestRecord> {
        val run = repo.createRun("fixture-conversation", "fixture-message", "恢复待审批边界")
        repo.updateRunStatus(run.id, AgentRunStatus.THINKING)
        val plan = repo.appendStep(run.id, AgentStepTypes.LLM_PLAN, "规划", "隔离规划", AgentStepStatus.RUNNING)
        repo.appendContextSnapshot(run.id, plan.id, AgentContextInput(AgentLlmPhase.PLAN, "fixture", null, "CHAT_COMPLETIONS",
            listOf(AgentContextMessage("system", "约束"), AgentContextMessage("user", "目标与空历史")), emptyList()))
        repo.updateStep(plan.id, AgentStepStatus.COMPLETED)
        repo.updateRunStatus(run.id, AgentRunStatus.WAITING_APPROVAL)
        repo.appendStep(run.id, "approval", "审批", "等待显式确认", AgentStepStatus.RUNNING)
        val definition = ToolDefinition("fixture.approval", "隔离审批", ToolRisk.REQUIRES_APPROVAL)
        val call = ToolCall(name = definition.name, arguments = mapOf("value" to "fixture"), risk = definition.risk)
        val metadata = RunEventMetadata.ToolCall(call.id, call.name, call.risk, call.arguments)
        repo.appendEvent(run.id, "tool.call.proposed", "候选", metadata)
        repo.appendEvent(run.id, "tool.call.validated", "已校验", metadata)
        val approval = repo.createApprovalRequest(run.conversationId, run.id, call, definition)
        assertEquals(AgentRunResumeKind.APPROVAL_WAIT, AgentRunResumePolicy.assess(requireNotNull(repo.runDetail(run.id))).kind)
        return run.id to approval
    }

    suspend fun manifest(runId: String): JSONObject = JSONObject().put("runId", runId)
        .put("ledgerSha256", hash(repo.toolLedger(runId).toString()))
        .put("frames", JSONArray(repo.contextTranscript(runId).map { it.recordSha256 }))
        .put("sources", JSONArray(repo.contextCompressions(runId).map { it.recordSha256 }))
        .put("results", JSONArray(repo.toolLedger(runId).results.map { result -> JSONObject().put("id", result.toolCallId).put("contentSha256", hash(result.content)) }))

    fun useCase(ledger: RoomAgentRunRepository = repo) = AgentRunUseCase(context, OpenAiCompatibleClient(), McpSessionRegistry(object : McpServerStore {
        override fun list() = emptyList<McpServerConfig>()
        override fun get(id: String): McpServerConfig? = null
        override fun upsert(config: McpServerConfig): McpServerConfig = error("恢复夹具不写 MCP")
        override fun delete(id: String): Boolean = error("恢复夹具不写 MCP")
    }), ledger)

    override fun close() { db.close() }
    fun delete() { close(); check(context.deleteDatabase(name)) }
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
