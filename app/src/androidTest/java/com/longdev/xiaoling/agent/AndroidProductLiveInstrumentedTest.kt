package com.longdev.xiaoling.agent

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.data.*
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** long: 正式产品链只在无用户业务内容的测试手机执行；临时 Provider 仅保存 Keystore 密文，只恢复本轮选择键。 */
class AndroidProductLiveInstrumentedTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun actualViewModelSendsChatsAndApprovesRealModelGenUiCard() = runBlocking {
        val config = androidLiveProvider()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = XiaoLingDatabase.getInstance(context)
        // long: 正式 VM 启动会读取会话与恢复账本，先以数量门禁拒绝有用户业务内容的设备，不能只切换空会话后声称完全隔离。
        val emptyTables = listOf("messages", "agent_memories", "agent_notes", "knowledge_documents", "workflows", "scheduled_tasks")
        emptyTables.forEach { table ->
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { cursor ->
                check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "正式产品验收要求无用户业务内容：$table 非空" }
            }
        }
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM conversations WHERE title NOT IN ('新会话', '') OR summary != '' OR summaryUntilMessageId IS NOT NULL").use { cursor ->
            check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "已有会话含用户内容，拒绝正式产品验收" }
        }
        androidLiveStatus("STAGE292_EVIDENCE PRODUCT_SCOPE businessContentEmpty=true originalBlankConversationsRetained=true")
        val providers = db.providerDao().getAll()
        val preferences = listOf("xiaoling_room_state", "xiaoling_ui").associateWith { name ->
            val preference = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            Triple(preference, preference.all.toMap(), File(context.applicationInfo.dataDir, "shared_prefs/$name.xml").takeIf { it.exists() }?.readBytes())
        }
        val id = UUID.randomUUID().toString()
        val providerId = "stage292-provider-$id"
        val profileId = "stage292-profile-$id"
        val conversationId = "conversation-stage292-product-$id"
        val ownedSelection = mapOf<String, Any>("providers_migrated" to true, "conversations_migrated" to true,
            "selected_provider_id" to providerId, "selected_agent_profile_id" to profileId, "selected_conversation_id" to conversationId)
        val encrypted = ApiKeyCipher().encrypt(config.apiKey)
        val now = System.currentTimeMillis()
        try {
            db.providerDao().insertAll(listOf(ProviderEntity(providerId, "公开真机验收", config.baseUrl,
                encrypted.iv, encrypted.ciphertext, config.model, "[\"${config.model}\"]", "[\"${config.model}\"]", "")))
            RoomAgentProfileStore(context).upsert(AgentProfileRecord(profileId, "公开只读验收", "P", providerId,
                config.model, ApiMode.CHAT_COMPLETIONS, "只读取当前时间，完成后结束。", AgentContextPolicy.CURRENT_CONVERSATION,
                listOf("app.current_time"), emptyList(), false, now, now))
            db.conversationDao().insertConversations(listOf(ConversationEntity(conversationId, "公开验收", "", null, null, null, now, now)))
            check(context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE).edit()
                .putBoolean("providers_migrated", true).putBoolean("conversations_migrated", true)
                .putString("selected_provider_id", providerId).putString("selected_agent_profile_id", profileId)
                .putString("selected_conversation_id", conversationId).commit())
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                scenario.awaitState { !it.loadingConversationMessages && it.selectedConversationId == conversationId && it.selectedAgentProfileId == profileId }
                for ((mode, streaming) in listOf(ApiMode.CHAT_COMPLETIONS to false, ApiMode.RESPONSES to true)) {
                    val marker = "product-${UUID.randomUUID()}"
                    scenario.onActivity { activity -> ViewModelProvider(activity)[XiaoLingViewModel::class.java].apply {
                        updateApiMode(mode); updateStreamingEnabled(streaming)
                        updatePrompt("这是公开产品连通验收。只原样回复 $marker，不添加其他文字。")
                    } }
                    rule.onNodeWithContentDescription("发送").performClick()
                    val state = scenario.awaitState(90_000) { !it.sendingMessage && it.chatMessages.any { msg -> msg.role == "assistant" && msg.text.contains(marker) } }
                    val message = state.chatMessages.single { it.role == "assistant" && it.text.contains(marker) }
                    assertTrue(db.conversationDao().getMessagesByConversationId(conversationId).any { it.id == message.id && it.text.contains(marker) })
                    androidLiveStatus("STAGE292_EVIDENCE PRODUCT_CHAT mode=$mode streaming=$streaming visibleSend=true actualViewModel=true marker=true roomMessage=true")
                }
                val title = "公开产品卡片-${id.take(8)}"
                scenario.onActivity { activity -> ViewModelProvider(activity)[XiaoLingViewModel::class.java].apply {
                    updateApiMode(ApiMode.CHAT_COMPLETIONS); updateStreamingEnabled(false)
                    updatePrompt("生成一段 xiaoling-ui fenced JSON，根 type=card、version=2、id=product、revision=1、title=$title。items 只有一个 action：type=action、version=1、id=read、label=申请读取时间，action 对象 type=host_read、version=1、tool=app.current_time、arguments={}、fields={}。不添加其他字段或说明。")
                } }
                rule.onNodeWithContentDescription("发送").performClick()
                val card = scenario.awaitState(90_000) { !it.sendingMessage && it.genUiActionLeases.isNotEmpty() }
                val sourceId = card.genUiActionLeases.keys.single()
                val source = card.chatMessages.single { it.id == sourceId }
                rule.onNodeWithTag("genui-action-read").performScrollTo().assertIsEnabled().performClick()
                val waiting = scenario.awaitState(20_000) { it.pendingAgentApproval?.toolName == "app.current_time" && it.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL }
                val runId = requireNotNull(waiting.activeAgentRun).run.id
                val repo = RoomAgentRunRepository(context)
                assertTrue(repo.toolLedger(runId).results.isEmpty())
                rule.onNodeWithText("批准执行").performScrollTo().performClick()
                scenario.awaitState(30_000) { !it.sendingMessage && it.pendingAgentApproval == null && it.activeAgentRun?.run?.status == AgentRunStatus.COMPLETED }
                val detail = requireNotNull(repo.runDetail(runId))
                assertEquals("app.current_time", detail.toolLedger.calls.single().toolName)
                assertEquals(ToolVerificationStatus.PASSED, detail.toolLedger.results.single().verificationStatus)
                assertEquals(ApprovalRequestStatus.APPROVED, detail.approvals.single().status)
                val binding = org.json.JSONObject((detail.snapshot.events.single { it.type == GenUiExecutionPolicy.SELECTION_EVENT }.metadata as RunEventMetadata.Reason).reason)
                assertEquals(sourceId, binding.getString("messageId"))
                assertEquals(com.longdev.xiaoling.genui.GenUiActionPolicy.hash(source.text), binding.getString("messageSha256"))
                androidLiveStatus("STAGE292_EVIDENCE PRODUCT_GENUI runId=$runId actualViewModel=true realModelCard=true visibleCardClick=true visibleApproval=true zeroBeforeApproval=true roomPassed=true sourceBound=true")
            }
        } finally {
            // long: 只移除本轮稳定 ID 的会话/配置，Run 与失败审计保留；不重写原 Provider，避免并发配置被整表恢复覆盖。
            val cleanupErrors = mutableListOf<Throwable>()
            runCatching { db.withTransaction {
                val ids = db.conversationDao().getMessagesByConversationId(conversationId).map { it.id }
                if (ids.isNotEmpty()) db.conversationDao().deleteMessageParts(ids)
                db.conversationDao().deleteMessagesByConversationIds(listOf(conversationId))
                db.conversationDao().deleteConversations(listOf(conversationId))
                db.agentProfileDao().delete(profileId)
                db.openHelper.writableDatabase.execSQL("DELETE FROM providers WHERE id = ?", arrayOf(providerId))
            } }.exceptionOrNull()?.let(cleanupErrors::add)
            for ((name, snapshot) in preferences) {
                runCatching {
                    val (prefs, original, originalBytes) = snapshot
                    if (name == "xiaoling_room_state") ownedSelection.forEach { (key, testValue) ->
                        // long: 只回滚仍属于本轮的值；同期选择冲突保留现场并报错，不能用旧快照覆盖用户修改。
                        runCatching {
                            val current = prefs.all[key]
                            check(current == testValue || current == original[key]) { "正式选择键已被其他操作修改：$key" }
                            val editor = prefs.edit()
                            when (val value = original[key]) {
                                null -> editor.remove(key)
                                is String -> editor.putString(key, value)
                                is Boolean -> editor.putBoolean(key, value)
                                else -> error("未知选择键类型")
                            }
                            check(editor.commit())
                        }.exceptionOrNull()?.let(cleanupErrors::add)
                    }
                    check(prefs.all == original) { "正式偏好发生本轮范围外的变化：$name" }
                    if (originalBytes == null && prefs.all.isEmpty()) check(context.deleteSharedPreferences(name))
                    // long: SharedPreferences 重新序列化可能改变 XML 字节顺序；同时记录稳定值摘要与文件摘要，不把值恢复冒充字节未变。
                    val file = File(context.applicationInfo.dataDir, "shared_prefs/$name.xml")
                    androidLiveStatus("STAGE292_EVIDENCE PRODUCT_PREF_VALUES name=$name beforeValues=${androidLivePreferenceDigest(original)} afterValues=${androidLivePreferenceDigest(prefs.all)} valuesEqual=true beforeXml=${originalBytes?.sha256() ?: "absent"} afterXml=${file.takeIf { it.exists() }?.readBytes()?.sha256() ?: "absent"}")
                }.exceptionOrNull()?.let(cleanupErrors::add)
            }
            runCatching { check(providers.toSet() == db.providerDao().getAll().toSet()) { "原 Provider 集合发生变化" } }
                .exceptionOrNull()?.let(cleanupErrors::add)
            if (cleanupErrors.isNotEmpty()) throw IllegalStateException("产品清理存在 ${cleanupErrors.size} 项失败，已独立尝试其余恢复")
                .also { error -> cleanupErrors.forEach(error::addSuppressed) }
            androidLiveStatus("STAGE292_EVIDENCE PRODUCT_RESTORE originalProviderCiphertext=true originalPreferences=true fixtureContentRemoved=true runAuditRetained=true")
        }
    }

    private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this)
        .joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun ActivityScenario<MainActivity>.awaitState(timeoutMs: Long = 20_000, predicate: (XiaoLingUiState) -> Boolean): XiaoLingUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest: XiaoLingUiState? = null
        while (System.currentTimeMillis() < deadline) {
            // long: Compose v2 由测试时钟推进主线程协程；只阻塞轮询会让真实 VM 初始化停在待调度状态。
            rule.waitForIdle()
            var current: XiaoLingUiState? = null
            onActivity { current = ViewModelProvider(it)[XiaoLingViewModel::class.java].uiState }
            latest = current
            if (predicate(requireNotNull(current))) return current
            Thread.sleep(50)
        }
        androidLiveStatus("STAGE292_PRODUCT_STATE profiles=${latest?.agentProfiles?.size} selectedProfile=${latest?.selectedAgentProfileId} selectedConversation=${latest?.selectedConversationId} loading=${latest?.loadingConversationMessages} sending=${latest?.sendingMessage}")
        error("正式 ViewModel 状态超时，失败记录保留")
    }
}
