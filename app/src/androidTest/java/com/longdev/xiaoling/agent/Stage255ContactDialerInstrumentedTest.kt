package com.longdev.xiaoling.agent

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import androidx.lifecycle.ViewModelProvider
import androidx.room.withTransaction
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.data.ConversationEntity
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageToolVerificationStatus
import com.longdev.xiaoling.storage.MessageRepository
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentProfileStore
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import com.longdev.xiaoling.storage.RoomStateStore
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 第 255 阶段冻结“查找唯一联系人并打开拨号页”的前台主链；测试只观察预填号码，绝不触发系统拨号按钮。
 */
@RunWith(AndroidJUnit4::class)
class Stage255ContactDialerInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun naturalLanguageUniqueContactOpensDialerAfterVisibleApprovalWithoutCalling() = runBlocking {
        requireStage255RedmiRun()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val database = XiaoLingDatabase.getInstance(context)
        val profileStore = RoomAgentProfileStore(context)
        val roomState = RoomStateStore(context)
        val runRepository = RoomAgentRunRepository(context)
        val state = fixtureState()
        cleanupPreviousFixture(state, database, profileStore, roomState)
        val providerRepository = ProviderRepository(context)
        val originalProviderSnapshot = providerRepository.load()
        restoreProviderFromRunnerArgsIfRequested()

        val providerSnapshot = providerRepository.load()
        val provider = providerSnapshot.profiles.firstOrNull { it.id == providerSnapshot.selectedProfileId }
        assertNotNull("Redmi 当前没有选中的 Provider", provider)
        requireNotNull(provider)
        assertTrue(
            "Redmi 当前 Provider 配置不完整",
            provider.baseUrl.isNotBlank() && provider.apiKey.isNotBlank() && provider.model.isNotBlank(),
        )
        assertTrue("当前模型没有在 Provider 中启用", provider.model in provider.enabledModels)

        assumeTrue(
            "请先授予小灵 READ_CONTACTS 后运行第255阶段联系人拨号页验收",
            context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED,
        )

        val originalProfileId = roomState.selectedAgentProfileId()
        val originalConversationId = roomState.selectedConversationId()
        // long: 启动恢复会合法收敛此前中断的 Run；旧事实保护只绑定启动前已经终态的历史 Run，避免把待恢复状态误判为漂移。
        val baselineRun = runRepository.recentRunDetails(30)
            .firstOrNull { it.snapshot.run.status.isTerminal }
        val now = System.currentTimeMillis()
        val suffix = now.toString().takeLast(10)
        val fixture = SyntheticContact(
            displayName = "stage255_contact_$suffix",
            phoneNumber = "+8613${suffix.takeLast(9)}",
        )
        val rawContactUri = createSyntheticContact(fixture)
        state.edit().putString(KEY_RAW_CONTACT_URI, rawContactUri.toString()).commit()
        val contactId = awaitAggregateContactId(rawContactUri)
        val stableContactId = "contact-$contactId"
        val expectedDialerPackage = resolveDialerPackage(fixture.phoneNumber)
        assertNotNull("Redmi 没有可解析 ACTION_DIAL 的系统应用", expectedDialerPackage)

        val profileId = "stage255-contact-dialer-$now"
        val conversationId = "conversation-stage255-contact-dialer-$now"
        val profile = AgentProfileRecord(
            id = profileId,
            name = "第255阶段联系人拨号页验收",
            avatar = "255",
            providerId = provider.id,
            model = provider.model,
            apiMode = ApiMode.RESPONSES,
            systemPrompt = "For the Stage255 request, select only contact-dialer. Call contacts.search exactly once with the exact synthetic display name from the user. If and only if the result is unique, copy its contact_id unchanged into contacts.get exactly once. Then copy the same contact_id and the single complete phone_number unchanged into contacts.open_dialer exactly once and wait for visible approval. Call no other tool and never claim that a phone call was placed.",
            contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
            allowedToolNames = listOf(CONTACT_SEARCH_TOOL, CONTACT_GET_TOOL, CONTACT_OPEN_DIALER_TOOL),
            allowedSkillIds = listOf(CONTACT_DIALER_SKILL),
            memoryEnabled = false,
            createdAt = now,
            updatedAt = now,
        )
        profileStore.upsert(profile)
        assertTrue("无法选择第255阶段临时 Profile", profileStore.select(profileId))
        database.conversationDao().insertConversations(
            listOf(
                ConversationEntity(
                    id = conversationId,
                    title = "新会话",
                    summary = "",
                    summaryUntilMessageId = null,
                    summaryUpdatedAt = null,
                    summaryModel = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            ),
        )
        roomState.saveSelectedConversationId(conversationId)
        persistSelectedState(profileId, conversationId)
        state.edit()
            .putString(KEY_ORIGINAL_PROFILE_ID, originalProfileId)
            .putString(KEY_ORIGINAL_CONVERSATION_ID, originalConversationId)
            .putString(KEY_PROFILE_ID, profileId)
            .putString(KEY_CONVERSATION_ID, conversationId)
            .putString(KEY_BASELINE_RUN_ID, baselineRun?.snapshot?.run?.id)
            .putString(KEY_BASELINE_RUN_DIGEST, baselineRun?.stableDigest())
            .commit()

        val prompt = "/agent 查找 ${fixture.displayName}；唯一命中后读取详情，经我批准打开拨号页预填号码，不要直接拨号。"
        var completedRunId: String? = null
        var observedDialerPackage: String? = null
        try {
            val scenario = ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java))
            try {
                val ready = scenario.awaitState { current ->
                    !current.loadingConversationMessages &&
                        current.selectedConversationId == conversationId &&
                        current.selectedAgentProfileId == profileId
                }
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].updatePrompt(prompt)
                }
                val previousRunId = ready.activeAgentRun?.run?.id
                clickVisibleNode(description = "发送", timeoutMs = 15_000L)
                val waiting = scenario.awaitState(timeoutMs = 180_000L) { current ->
                    current.activeAgentRun?.run?.id != previousRunId &&
                        current.activeAgentRun?.run?.status == AgentRunStatus.WAITING_APPROVAL &&
                        current.pendingAgentApproval?.conversationId == conversationId &&
                        current.pendingAgentApproval?.toolName == CONTACT_OPEN_DIALER_TOOL
                }
                val pending = requireNotNull(waiting.pendingAgentApproval)
                assertEquals(stableContactId, pending.arguments["contact_id"]?.trim())
                assertEquals(fixture.phoneNumber, pending.arguments["phone_number"]?.trim())
                awaitForegroundPackage(context.packageName, timeoutMs = 10_000L)
                scenario.onActivity { activity ->
                    // long: Redmi 发送后会保留 IME 并把会话时间线压缩到不可见高度；先收起键盘，用户才能看到并核对审批卡。
                    activity.getSystemService(InputMethodManager::class.java)
                        .hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
                    activity.currentFocus?.clearFocus()
                }
                instrumentation.waitForIdleSync()
                clickVisibleNode(
                    text = "批准执行",
                    alternateText = "批准并继续",
                    timeoutMs = 20_000L,
                    scrollForward = true,
                )

                // long: 系统拨号页只做只读观察；确认号码已交付后立即返回小灵，测试没有任何拨号按钮查找、节点点击或坐标注入。
                observedDialerPackage = awaitDialerUi(fixture.phoneNumber, timeoutMs = 15_000L)
                assertEquals(expectedDialerPackage, observedDialerPackage)
                assertNotEquals(context.packageName, observedDialerPackage)
                assertTrue(
                    "无法从系统拨号页返回小灵",
                    instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK),
                )
                instrumentation.waitForIdleSync()
                // long: ACTION_DIAL 使用独立系统任务，Redmi 返回后可能落到桌面；显式恢复小灵任务，再以 Room 审计等待原 Run 收敛。
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                instrumentation.waitForIdleSync()
                awaitForegroundPackage(context.packageName, timeoutMs = 10_000L)
                awaitCompletedRun(runRepository, conversationId, timeoutMs = 180_000L)
            } finally {
                returnToXiaoLingIfNeeded()
                // long: Redmi 从独立拨号任务返回桌面后会重建 MainActivity，ActivityScenario 可能不再拥有当前实例；
                // 此时关闭失败只是测试宿主状态丢失，不能覆盖 Provider、审批或 Run 收敛的原始失败证据。
                runCatching { scenario.close() }
            }

            val detail = runRepository.recentRunDetails(30)
                .firstOrNull { it.snapshot.run.conversationId == conversationId }
            assertNotNull("没有找到第255阶段真实联系人拨号页 Run", detail)
            requireNotNull(detail)
            completedRunId = detail.snapshot.run.id
            state.edit().putString(KEY_RUN_ID, completedRunId).commit()
            assertEquals(AgentRunStatus.COMPLETED, detail.snapshot.run.status)
            val selectedProfile = detail.snapshot.events
                .singleOrNull { it.type == AgentEventTypes.PROFILE_SELECTED }
                ?.metadata
                .let { it as? RunEventMetadata.AgentProfileSelection }
                ?.profile
            assertEquals(profileId, selectedProfile?.id)
            assertEquals(listOf(CONTACT_DIALER_SKILL), selectedProfile?.allowedSkillIds)

            val calls = detail.toolLedger.calls
            assertEquals(
                listOf(CONTACT_SEARCH_TOOL, CONTACT_GET_TOOL, CONTACT_OPEN_DIALER_TOOL),
                calls.map { it.toolName },
            )
            assertEquals(fixture.displayName, calls[0].arguments["query"]?.trim())
            assertEquals(stableContactId, calls[1].arguments["contact_id"]?.trim())
            assertEquals(stableContactId, calls[2].arguments["contact_id"]?.trim())
            assertEquals(fixture.phoneNumber, calls[2].arguments["phone_number"]?.trim())

            val resultsByCallId = detail.toolLedger.results.associateBy { it.toolCallId }
            val searchResult = requireNotNull(resultsByCallId[calls[0].id])
            val getResult = requireNotNull(resultsByCallId[calls[1].id])
            val dialerResult = requireNotNull(resultsByCallId[calls[2].id])
            listOf(searchResult, getResult, dialerResult).forEach { result ->
                assertTrue(result.success)
                assertEquals(ToolVerificationStatus.PASSED, result.verificationStatus)
                assertNull("联系人拨号页不应伪造持久副作用回执", result.executionReceipt)
            }
            assertEquals(true, dialerResult.executorVerified)
            assertTrue(searchResult.content.contains(stableContactId))
            assertFalse("contacts.search 不应暴露号码", searchResult.content.contains(fixture.phoneNumber))
            assertTrue(getResult.content.contains(fixture.phoneNumber))
            assertTrue(dialerResult.content.contains("已打开系统拨号页"))
            assertTrue(dialerResult.content.contains("电话尚未拨出"))
            val approval = detail.approvals.single()
            assertEquals(calls[2].id, approval.toolCallId)
            assertEquals(CONTACT_OPEN_DIALER_TOOL, approval.toolName)
            assertEquals(ApprovalRequestStatus.APPROVED, approval.status)

            val messageTools = MessageRepository(database).loadConversation(conversationId)
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool>()
            assertEquals(calls.map { it.toolName }, messageTools.map { it.toolName })
            assertEquals(MessageToolVerificationStatus.READABLE_ONLY, messageTools[0].verificationStatus)
            assertEquals(MessageToolVerificationStatus.READABLE_ONLY, messageTools[1].verificationStatus)
            assertEquals(MessageToolVerificationStatus.VERIFIED, messageTools[2].verificationStatus)
            assertTrue(messageTools[2].result.contains("电话尚未拨出"))

            val baselineRunId = state.getString(KEY_BASELINE_RUN_ID, null)
            val baselineDigest = state.getString(KEY_BASELINE_RUN_DIGEST, null)
            if (!baselineRunId.isNullOrBlank() && !baselineDigest.isNullOrBlank()) {
                assertEquals(baselineDigest, requireNotNull(runRepository.runDetail(baselineRunId)).stableDigest())
            }
            println(
                "STAGE255_CONTACT_DIALER runId=$completedRunId tools=contacts.search,contacts.get,contacts.open_dialer " +
                    "approval=APPROVED verification=PASSED dialerPackage=$observedDialerPackage " +
                    "numberPrefilled=true callPlaced=false receiptAbsent=true oldRunUnchanged=true",
            )
        } finally {
            cleanupPreviousFixture(state, database, profileStore, roomState)
            if (InstrumentationRegistry.getArguments().getString(ARG_RESTORE_PROVIDER_AFTER_RUN) == "true") {
                // long: 本机反向代理只服务本次 Redmi 验收，结束后恢复用户原 Provider，避免临时 localhost 配置污染日常使用。
                providerRepository.save(originalProviderSnapshot.profiles, originalProviderSnapshot.selectedProfileId)
            }
        }

        assertNotNull("第255阶段 Run 审计必须保留", completedRunId?.let { runRepository.runDetail(it) })
    }

    private fun requireStage255RedmiRun() {
        assumeTrue(
            "第255阶段真实模型验收只在显式 stage255RealRun=true 下运行",
            InstrumentationRegistry.getArguments().getString(ARG_REAL_RUN) == "true",
        )
        assertEquals("第255阶段 Android 验收只允许 Redmi Note 8 Pro", "begonia", Build.DEVICE)
    }

    private fun fixtureState() = context.getSharedPreferences(STATE_PREFERENCES, Context.MODE_PRIVATE)

    private suspend fun restoreProviderFromRunnerArgsIfRequested() {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString(ARG_RESTORE_PROVIDER) != "true") return
        val baseUrl = requireNotNull(arguments.getString(ARG_FALLBACK_BASE_URL)?.takeIf { it.isNotBlank() })
        val apiKey = requireNotNull(arguments.getString(ARG_FALLBACK_API_KEY)?.takeIf { it.isNotBlank() })
        val model = requireNotNull(arguments.getString(ARG_FALLBACK_MODEL)?.takeIf { it.isNotBlank() })
        val repository = ProviderRepository(context)
        val current = repository.load()
        val existing = current.profiles.firstOrNull() ?: com.longdev.xiaoling.model.ProviderProfile.blank()
        val restored = existing.copy(
            name = existing.name.ifBlank { "兜底 Provider" },
            baseUrl = baseUrl,
            apiKey = apiKey,
            model = model,
            availableModels = listOf(model),
            enabledModels = listOf(model),
        )
        // long: 兜底凭据只经显式 instrumentation 参数进入设备 Keystore，仓库、测试报告和日志均不保存密钥原文。
        repository.save(listOf(restored), restored.id)
        val loaded = repository.load().profiles.single()
        assertEquals(baseUrl, loaded.baseUrl)
        assertEquals(model, loaded.model)
        assertTrue(loaded.apiKey.isNotBlank())
    }

    private suspend fun cleanupPreviousFixture(
        state: android.content.SharedPreferences,
        database: XiaoLingDatabase,
        profileStore: RoomAgentProfileStore,
        roomState: RoomStateStore,
    ) {
        state.getString(KEY_RAW_CONTACT_URI, null)?.let { value ->
            runCatching { Uri.parse(value) }.getOrNull()?.let(::deleteSyntheticContact)
        }
        val profileId = state.getString(KEY_PROFILE_ID, null)
        val conversationId = state.getString(KEY_CONVERSATION_ID, null)
        val originalProfileId = state.getString(KEY_ORIGINAL_PROFILE_ID, null)
        val originalConversationId = state.getString(KEY_ORIGINAL_CONVERSATION_ID, null)
        originalProfileId?.let { profileStore.select(it) }
        profileId?.let { profileStore.delete(it) }
        if (!conversationId.isNullOrBlank()) {
            database.withTransaction {
                MessageRepository(database).deleteByConversationIds(listOf(conversationId))
                database.conversationDao().deleteConversations(listOf(conversationId))
            }
        }
        originalConversationId?.let { roomState.saveSelectedConversationId(it) }
        persistSelectedState(originalProfileId, originalConversationId)
        state.edit().clear().commit()
    }

    private fun createSyntheticContact(fixture: SyntheticContact): Uri {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = context.contentResolver
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        )
        return try {
            val rawContactUri = requireNotNull(
                resolver.insert(
                    ContactsContract.RawContacts.CONTENT_URI,
                    ContentValues().apply {
                        putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
                        putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    },
                ),
            )
            val rawContactId = ContentUris.parseId(rawContactUri)
            resolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, fixture.displayName)
                },
            )
            resolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, fixture.phoneNumber)
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                },
            )
            rawContactUri
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun deleteSyntheticContact(rawContactUri: Uri) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        )
        try {
            context.contentResolver.delete(rawContactUri, null, null)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun awaitAggregateContactId(rawContactUri: Uri): Long {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rawContactId = ContentUris.parseId(rawContactUri)
        val deadline = SystemClock.uptimeMillis() + 5_000L
        do {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_CONTACTS)
            val contactId = try {
                context.contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                    "${ContactsContract.RawContacts._ID} = ?",
                    arrayOf(rawContactId.toString()),
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
                }
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
            if (contactId != null) return contactId
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("系统 Contacts Provider 未及时生成第255阶段聚合联系人 ID")
    }

    private fun resolveDialerPackage(phoneNumber: String): String? {
        val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phoneNumber, null))
        return context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    }

    private fun awaitDialerUi(phoneNumber: String, timeoutMs: Long): String {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val expectedDigits = phoneNumber.filter(Char::isDigit)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            val root = automation.rootInActiveWindow
            val packageName = root?.packageName?.toString().orEmpty()
            if (
                packageName.isNotBlank() &&
                packageName != context.packageName &&
                root?.containsPhoneDigits(expectedDigits) == true
            ) {
                return packageName
            }
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("系统拨号页未在时限内显示合成联系人号码")
    }

    private fun awaitForegroundPackage(expectedPackage: String, timeoutMs: Long) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (automation.rootInActiveWindow?.packageName?.toString() == expectedPackage) return
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("返回后前台页面不是小灵")
    }

    private suspend fun awaitCompletedRun(
        runRepository: RoomAgentRunRepository,
        conversationId: String,
        timeoutMs: Long,
    ): AgentRunDetailRecord {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var latest: AgentRunDetailRecord? = null
        do {
            latest = runRepository.recentRunDetails(30)
                .firstOrNull { it.snapshot.run.conversationId == conversationId }
            if (latest?.snapshot?.run?.status == AgentRunStatus.COMPLETED) return latest
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("第255阶段 Run 未在时限内完成：${latest?.snapshot?.run?.status}")
    }

    private fun returnToXiaoLingIfNeeded() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val foregroundPackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
        if (!foregroundPackage.isNullOrBlank() && foregroundPackage != context.packageName) {
            instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            instrumentation.waitForIdleSync()
        }
    }

    private fun AccessibilityNodeInfo.containsPhoneDigits(expectedDigits: String): Boolean {
        val visibleValues = listOfNotNull(text?.toString(), contentDescription?.toString())
        if (visibleValues.any { value -> value.filter(Char::isDigit).contains(expectedDigits) }) return true
        repeat(childCount) { index ->
            if (getChild(index)?.containsPhoneDigits(expectedDigits) == true) return true
        }
        return false
    }

    private fun persistSelectedState(profileId: String?, conversationId: String?) {
        context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE).edit().apply {
            if (profileId == null) remove("selected_agent_profile_id") else putString("selected_agent_profile_id", profileId)
            if (conversationId == null) remove("selected_conversation_id") else putString("selected_conversation_id", conversationId)
        }.commit()
    }

    private fun Any.stableDigest(): String = MessageDigest.getInstance("SHA-256")
        .digest(toString().toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun ActivityScenario<MainActivity>.awaitState(
        timeoutMs: Long = STATE_TIMEOUT_MS,
        predicate: (XiaoLingUiState) -> Boolean,
    ): XiaoLingUiState {
        val deadline = System.currentTimeMillis() + timeoutMs
        var latest = XiaoLingUiState()
        while (System.currentTimeMillis() < deadline) {
            onActivity { latest = ViewModelProvider(it)[XiaoLingViewModel::class.java].uiState }
            if (predicate(latest)) return latest
            val run = latest.activeAgentRun?.run
            if (run?.status?.isTerminal == true && run.status != AgentRunStatus.COMPLETED) {
                throw AssertionError(
                    "Stage255 Run 在等待目标状态前终止：status=${run.status}, error=${run.errorMessage}",
                )
            }
            Thread.sleep(STATE_POLL_MS)
        }
        throw AssertionError(
            "Timed out waiting for Stage255 state: selectedConversation=${latest.selectedConversationId}, " +
                "approval=${latest.pendingAgentApproval?.toolName}, runStatus=${latest.activeAgentRun?.run?.status}",
        )
    }

    private fun clickVisibleNode(
        text: String? = null,
        alternateText: String? = null,
        description: String? = null,
        timeoutMs: Long,
        scrollForward: Boolean = false,
    ) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var nextGestureAt = 0L
        do {
            val root = automation.rootInActiveWindow
            root?.refresh()
            val node = root?.findNode(text, alternateText, description)
            if (node != null && node.clickSelfOrAncestor()) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                return
            }
            if (scrollForward && root?.scrollBackward() == true) {
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            }
            val now = SystemClock.uptimeMillis()
            if (scrollForward && now >= nextGestureAt) {
                // long: 审批卡位于会话时间线当前视口上方；向历史方向回滚，直到用户可见的批准按钮进入语义树。
                swipeConversationBackward()
                nextGestureAt = now + 600L
            }
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        val root = automation.rootInActiveWindow
        throw AssertionError(
            "没有找到可点击节点：${text ?: description}；foreground=${root?.packageName}；" +
                "visible=${root?.visibleTextSummary()}",
        )
    }

    private fun AccessibilityNodeInfo.findNode(
        expectedText: String?,
        alternateText: String?,
        expectedDescription: String?,
    ): AccessibilityNodeInfo? {
        if (
            isVisibleToUser &&
            (
                (expectedText != null && text?.toString() == expectedText) ||
                    (alternateText != null && text?.toString() == alternateText) ||
                    (expectedDescription != null && contentDescription?.toString() == expectedDescription)
                )
        ) {
            return this
        }
        repeat(childCount) { index ->
            getChild(index)?.findNode(expectedText, alternateText, expectedDescription)?.let { return it }
        }
        return null
    }

    private fun AccessibilityNodeInfo.clickSelfOrAncestor(): Boolean {
        var current: AccessibilityNodeInfo? = this
        repeat(5) {
            val candidate = current ?: return false
            if (candidate.isClickable && candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            current = candidate.parent
        }
        return false
    }

    private fun AccessibilityNodeInfo.visibleTextSummary(limit: Int = 30): String {
        val values = mutableListOf<String>()
        fun collect(node: AccessibilityNodeInfo) {
            if (values.size >= limit) return
            if (node.isVisibleToUser) {
                listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .forEach { value -> if (values.size < limit) values += value.take(80) }
            }
            repeat(node.childCount) { index -> node.getChild(index)?.let(::collect) }
        }
        collect(this)
        return values.distinct().joinToString(" | ")
    }

    private fun AccessibilityNodeInfo.scrollBackward(): Boolean {
        if (isScrollable && performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) return true
        repeat(childCount) { index ->
            if (getChild(index)?.scrollBackward() == true) return true
        }
        return false
    }

    private fun swipeConversationBackward() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val metrics = context.resources.displayMetrics
        val x = metrics.widthPixels * 0.5f
        val startY = metrics.heightPixels * 0.32f
        val endY = metrics.heightPixels * 0.78f
        val downTime = SystemClock.uptimeMillis()
        automation.injectInputEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, startY, 0), true)
        automation.injectInputEvent(MotionEvent.obtain(downTime, downTime + 120L, MotionEvent.ACTION_MOVE, x, endY, 0), true)
        automation.injectInputEvent(MotionEvent.obtain(downTime, downTime + 160L, MotionEvent.ACTION_UP, x, endY, 0), true)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private data class SyntheticContact(
        val displayName: String,
        val phoneNumber: String,
    )

    private companion object {
        const val ARG_REAL_RUN = "stage255RealRun"
        const val ARG_RESTORE_PROVIDER = "stage255RestoreProvider"
        const val ARG_RESTORE_PROVIDER_AFTER_RUN = "stage255RestoreProviderAfterRun"
        const val ARG_FALLBACK_BASE_URL = "stage255FallbackBaseUrl"
        const val ARG_FALLBACK_API_KEY = "stage255FallbackApiKey"
        const val ARG_FALLBACK_MODEL = "stage255FallbackModel"
        const val CONTACT_SEARCH_TOOL = "contacts.search"
        const val CONTACT_GET_TOOL = "contacts.get"
        const val CONTACT_OPEN_DIALER_TOOL = "contacts.open_dialer"
        const val CONTACT_DIALER_SKILL = "contact-dialer"
        const val STATE_PREFERENCES = "stage255_contact_dialer"
        const val KEY_ORIGINAL_PROFILE_ID = "original_profile_id"
        const val KEY_ORIGINAL_CONVERSATION_ID = "original_conversation_id"
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_CONVERSATION_ID = "conversation_id"
        const val KEY_RAW_CONTACT_URI = "raw_contact_uri"
        const val KEY_BASELINE_RUN_ID = "baseline_run_id"
        const val KEY_BASELINE_RUN_DIGEST = "baseline_run_digest"
        const val KEY_RUN_ID = "run_id"
        const val STATE_TIMEOUT_MS = 20_000L
        const val STATE_POLL_MS = 100L
    }
}
