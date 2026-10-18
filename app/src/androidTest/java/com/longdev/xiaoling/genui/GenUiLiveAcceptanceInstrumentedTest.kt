package com.longdev.xiaoling.genui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.XiaoLingApplication
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.network.RequestMessage
import com.longdev.xiaoling.storage.RoomAgentSkillStore
import com.longdev.xiaoling.ui.conversation.GenUiMessageContent
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** long: 同一轮贯穿真实模型、生产卡片组件、可见审批点击与生产 UseCase；使用隔离 Room，不冒充完整 ViewModel 流程。 */
class GenUiLiveAcceptanceInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun realModelCardClickWaitsForVisibleApprovalAndPersistsNativeResult() = runBlocking {
        val config = androidLiveProvider()
        AndroidLiveFixture().use { f ->
            val title = "公开卡片-${UUID.randomUUID().toString().take(8)}"
            val source = withTimeout(90_000) { OpenAiCompatibleClient().sendMessage(config, listOf(RequestMessage("user",
                "生成一段 xiaoling-ui fenced JSON，根 type=card、version=2、id=live、revision=1、title=$title。items 只有一个 action 组件：type=action、version=1、id=read、label=申请读取时间，action 对象 type=host_read、version=1、tool=app.current_time、arguments={}、fields={}。不添加其他字段或说明。"))) }
            assertTrue(source.actionCompletionConfirmed)
            val document = requireNotNull(GenUiPolicy.projectAssistantText(source.responseText)).document
            assertEquals(title, document.title)
            assertEquals("app.current_time", document.items.filterIsInstance<GenUiItem.Action>().single().tool)
            val conversation = "conversation-${UUID.randomUUID()}"
            val message = "message-${UUID.randomUUID()}"
            val leases = GenUiActionLeases()
            val lease = requireNotNull(leases.issue(conversation, message, source.responseText))
            val selected = CompletableDeferred<GenUiActionSelection>()
            val clickedApproval = CompletableDeferred<Unit>()
            val pending = CompletableDeferred<ApprovalRequestRecord>()
            val decision = CompletableDeferred<ApprovalDecision>()
            var consumed by mutableStateOf(false)
            var showApproval by mutableStateOf(false)
            var finished by mutableStateOf(false)
            ActivityScenario.launch<MainActivity>(Intent(f.context, MainActivity::class.java)
                .putExtra("com.longdev.xiaoling.DEBUG_PLUGIN_LIFECYCLE_PROBE", true)).use { scenario ->
                scenario.moveToState(Lifecycle.State.RESUMED)
                val foreground = (f.context.applicationContext as XiaoLingApplication).agentPluginForeground
                val revision = foreground.capture()
                scenario.onActivity { activity -> activity.setContent { MaterialTheme { Column {
                    GenUiMessageContent(document, MaterialTheme.colorScheme.onSurface, {}, "$conversation:$message",
                        lease.takeUnless { consumed }, onHostAction = { id, drafts ->
                            selected.complete(leases.select(lease, conversation, message, source.responseText, id, drafts))
                            consumed = true
                        })
                    // long: 验收宿主显示当前真实 Room 审批等待，只由可见按钮提交决定；不是自动审批，也不模拟正式 ViewModel 的投影。
                    if (showApproval && !finished) {
                        Text("等待确认：app.current_time")
                        Button(onClick = { clickedApproval.complete(Unit) }) { Text("批准执行") }
                    }
                    if (finished) Text("已验证：原生时间读取完成")
                } } } }
                rule.onNodeWithText(title).assertExists()
                rule.onNodeWithTag("genui-action-read").assertIsEnabled().performClick()
                val selection = withTimeout(5000) { selected.await() }
                rule.onNodeWithTag("genui-action-read").assertIsNotEnabled()
                assertTrue(runCatching { leases.select(lease, conversation, message, source.responseText, "read", emptyMap()) }.isFailure)
                val repo = f.repo
                val sessions = McpSessionRegistry(object : McpServerStore {
                    override fun list() = emptyList<McpServerConfig>()
                    override fun get(id: String): McpServerConfig? = null
                    override fun upsert(config: McpServerConfig): McpServerConfig = error("不得写正式 MCP")
                    override fun delete(id: String): Boolean = error("不得写正式 MCP")
                })
                val useCase = AgentRunUseCase(f.context, OpenAiCompatibleClient(), sessions, repo,
                    pluginForeground = foreground, skillStore = RoomAgentSkillStore(f.context, f.db))
                val profile = AgentProfileSnapshot("stage292-genui", "公开卡片", "G", "stage292-live", config.model,
                    ApiMode.CHAT_COMPLETIONS, "只读时间", AgentContextPolicy.CURRENT_CONVERSATION, listOf("app.current_time"), emptyList(), false)
                val gate = object : ApprovalGate {
                    override suspend fun requestApproval(runId: String, toolCall: ToolCall, definition: ToolDefinition): ApprovalDecision {
                        pending.complete(repo.createApprovalRequest(conversation, runId, toolCall, definition))
                        return decision.await()
                    }
                }
                try {
                    val job = async(Dispatchers.IO) { useCase.runGenUiAction(conversation, "message-${UUID.randomUUID()}", selection, revision, profile, gate) }
                    try {
                        val request = withTimeout(10_000) { pending.await() }
                        assertEquals(AgentRunStatus.WAITING_APPROVAL, repo.snapshot(request.runId).run.status)
                        assertTrue(repo.toolLedger(request.runId).results.isEmpty())
                        rule.runOnIdle { showApproval = true }
                        rule.onNodeWithText("等待确认：app.current_time").assertExists()
                        rule.onNodeWithText("批准执行").performClick()
                        withTimeout(5000) { clickedApproval.await() }
                        assertNotNull(repo.decideApprovalRequest(request.id, ApprovalRequestStatus.APPROVED, "可见审批按钮"))
                        decision.complete(ApprovalDecision(true, "可见审批按钮"))
                        val result = withTimeout(15_000) { job.await() }
                        val detail = requireNotNull(repo.runDetail(result.runId))
                        assertEquals(AgentRunStatus.COMPLETED, result.status)
                        assertEquals("app.current_time", detail.toolLedger.calls.single().toolName)
                        assertEquals(ToolVerificationStatus.PASSED, detail.toolLedger.results.single().verificationStatus)
                        assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single().status)
                        val event = detail.snapshot.events.single { it.type == GenUiExecutionPolicy.SELECTION_EVENT }
                        val binding = JSONObject((event.metadata as RunEventMetadata.Reason).reason)
                        assertEquals(message, binding.getString("messageId"))
                        assertEquals(GenUiActionPolicy.hash(source.responseText), binding.getString("messageSha256"))
                        assertTrue(repo.contextTranscript(result.runId).isEmpty())
                        assertTrue(foreground.isCurrent(revision))
                        rule.runOnIdle { finished = true }
                        rule.onNodeWithText("已验证：原生时间读取完成").assertExists()
                        androidLiveStatus("STAGE292_EVIDENCE GENUI runId=${result.runId} modelCard=true productionComponentClick=true visibleApproval=true zeroBeforeApproval=true nativeTimePassed=true boundSource=true singleUse=true actualForeground=true")
                    } finally { job.cancelAndJoin() }
                } finally { useCase.closeMcpOwners(); sessions.close() }
            }
        }
    }
}
