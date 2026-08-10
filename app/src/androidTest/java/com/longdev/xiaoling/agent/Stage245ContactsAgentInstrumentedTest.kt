package com.longdev.xiaoling.agent

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ProviderRequestConfig
import com.longdev.xiaoling.network.OpenAiCompatibleClient
import com.longdev.xiaoling.storage.ProviderRepository
import com.longdev.xiaoling.storage.RoomAgentRunRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * long: 该探针只创建并清理一条纯合成联系人，验证正式 Agent 的 search -> stable ID -> get 链；
 * 手机中的真实联系人既不进入模型，也不进入测试日志。
 */
@RunWith(AndroidJUnit4::class)
class Stage245ContactsAgentInstrumentedTest {
    @Test
    fun foregroundAgentSearchesSyntheticContactThenReadsAuthoritativeDetail() = kotlinx.coroutines.runBlocking {
        assumeTrue("第 245 阶段 Android 验收只允许 Redmi begonia", Build.DEVICE == "begonia")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val stored = ProviderRepository(targetContext).load()
        val provider = stored.profiles.firstOrNull { it.id == stored.selectedProfileId }
        val baseUrl = provider?.baseUrl?.trim().orEmpty()
        val apiKey = provider?.apiKey?.trim().orEmpty()
        val model = provider?.model?.trim().orEmpty()
        assumeTrue("Redmi 当前 Provider Base URL 不可用", baseUrl.isNotBlank())
        assumeTrue("Redmi 当前 Provider API Key 不可用", apiKey.isNotBlank())
        assumeTrue("Redmi 当前 Provider 模型不可用", model.isNotBlank())

        val suffix = System.currentTimeMillis().toString().takeLast(10)
        val fixture = SyntheticContact(
            displayName = "stage245_contact_$suffix",
            phone = "+8613${suffix.takeLast(9)}",
            email = "stage245_$suffix@example.invalid",
        )
        val rawContactUri = createSyntheticContact(fixture)
        try {
            val providerId = "stage245-contacts-provider"
            val profile = AgentProfileSnapshot(
                id = "stage245-contacts-agent",
                name = "第245阶段联系人查询 Agent",
                avatar = "C",
                providerId = providerId,
                model = model,
                apiMode = ApiMode.RESPONSES,
                systemPrompt = "只按用户给出的合成姓名搜索联系人，再按返回的稳定 ID 读取详情；不得枚举通讯录、猜测 ID、写入联系人或调用其他工具。",
                contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
                allowedToolNames = listOf("contacts.search", "contacts.get"),
                allowedSkillIds = listOf("contacts-lookup"),
                memoryEnabled = false,
            )
            val config = ProviderRequestConfig(
                baseUrl = baseUrl,
                apiKey = apiKey,
                model = model,
                providerId = providerId,
                apiMode = ApiMode.RESPONSES,
                streamingEnabled = false,
                reasoningSummaryEnabled = false,
                userAgent = ProviderRequestConfig.DEFAULT_USER_AGENT,
                maxTokens = 4_000,
            )
            val run = AgentRunUseCase(
                context = targetContext,
                client = OpenAiCompatibleClient(),
            ).run(
                conversationId = "conversation-stage245-${UUID.randomUUID()}",
                userMessageId = "message-stage245-${UUID.randomUUID()}",
                goal = "请从系统通讯录查找 ${fixture.displayName}，先搜索，再按返回的稳定 ID 读取当前电话号码和邮箱；只使用 contacts.search 与 contacts.get。",
                config = config,
                summarySystemPrompt = "只根据联系人工具结果回答合成联系人的姓名、电话和邮箱，不补充其他通讯录事实。",
                agentProfile = profile,
                memoryRecallEnabled = false,
            )

            assertEquals(AgentRunStatus.COMPLETED, run.status)
            val detail = requireNotNull(RoomAgentRunRepository(targetContext).runDetail(run.runId))
            val results = detail.toolLedger.results
            assertEquals(listOf("contacts.search", "contacts.get"), results.map { it.toolName })
            assertTrue(results.all { result -> result.success && result.verificationStatus == ToolVerificationStatus.PASSED })
            assertTrue(detail.approvals.isEmpty())
            assertFalse(results.first().content.contains(fixture.phone))
            assertFalse(results.first().content.contains(fixture.email))
            assertTrue(results.last().content.contains(fixture.displayName))
            assertTrue(results.last().content.contains(fixture.phone))
            assertTrue(results.last().content.contains(fixture.email))
            assertFalse(run.responseText.contains(baseUrl))
            assertFalse(run.responseText.contains(apiKey))
            println(
                "STAGE245_REAL_CONTACTS runId=${run.runId} status=${run.status} " +
                    "tools=${results.map { it.toolName }} approvals=0 syntheticFixture=true privacySafe=true",
            )
        } finally {
            deleteSyntheticContact(rawContactUri)
        }
    }

    private fun createSyntheticContact(fixture: SyntheticContact): Uri {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
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
                    put(ContactsContract.CommonDataKinds.Phone.NUMBER, fixture.phone)
                    put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                },
            )
            resolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.Email.ADDRESS, fixture.email)
                    put(ContactsContract.CommonDataKinds.Email.TYPE, ContactsContract.CommonDataKinds.Email.TYPE_WORK)
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
            instrumentation.targetContext.contentResolver.delete(rawContactUri, null, null)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private data class SyntheticContact(
        val displayName: String,
        val phone: String,
        val email: String,
    )
}
