package com.longdev.xiaoling.ui.remote

import android.app.Application
import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.*
import com.longdev.xiaoling.storage.KeystoreRemoteChannelInboxStateStore
import com.longdev.xiaoling.ui.XiaoLingViewModel
import com.longdev.xiaoling.ui.conversation.SharedDraftPendingNotice
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** long: 使用 UUID 偏好文件和 Keystore 别名，正文/处理记录不进入用户正式收件箱。 */
class RemoteChannelInboxAuditInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun encryptedTerminalAndExpiryAuditSurvivesRecreationWithoutChangingOutbox() {
        val fixture = AuditDeviceFixture(context)
        try {
            val opened = fixture.receive("opened")
            val ignored = fixture.receive("ignored")
            fixture.receive("expired")
            val batch = fixture.seedLocalOutbox()
            assertTrue(fixture.gateway.discard(opened, RemoteChannelInboxDisposition.OPENED))
            assertTrue(fixture.gateway.discard(ignored))
            fixture.now += RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS
            val status = fixture.ready()
            assertTrue(status.pending.isEmpty())
            assertEquals(listOf(RemoteChannelInboxDisposition.OPENED, RemoteChannelInboxDisposition.IGNORED, RemoteChannelInboxDisposition.EXPIRED),
                status.auditEntries.map { it.disposition })
            val restored = fixture.newStore().loadSnapshot()!!
            assertEquals(status.auditEntries, restored.auditEntries)
            assertEquals(listOf(batch), restored.batchStates)
            assertEquals(6, restored.dedupeKeys.size)
            val raw = fixture.raw()
            listOf("审计测试正文", "opened", "IGNORED", status.auditEntries.first().messageIdentitySha256).forEach { assertFalse(raw.contains(it)) }
            val envelope = JSONObject(raw)
            val cipher = envelope.getString("ciphertext")
            envelope.put("ciphertext", (if (cipher.first() == 'A') "B" else "A") + cipher.drop(1))
            val corrupted = envelope.toString()
            assertTrue(fixture.preferences.edit().putString("snapshot", corrupted).commit())
            assertEquals(RemoteChannelInboxStatus.Unavailable, fixture.gateway.status())
            assertEquals(corrupted, fixture.raw())
        } finally { fixture.close() }
    }

    @Test fun oldEncryptedV1AndV2UpgradeWithSameKeyAndNoInventedHistory() {
        for (version in listOf(1, 2)) {
            val fixture = AuditDeviceFixture(context)
            try {
                val draft = fixture.receive("legacy-$version")
                val batch = fixture.seedLocalOutbox()
                fixture.rewriteAsLegacy(version)
                assertTrue(fixture.ready().auditEntries.isEmpty())
                assertEquals(draft, fixture.ready().pending.single().draft)
                assertTrue(fixture.gateway.discard(draft))
                val restored = fixture.newStore().loadSnapshot()!!
                assertEquals(RemoteChannelInboxDisposition.IGNORED, restored.auditEntries.single().disposition)
                assertTrue(restored.pendingDrafts.isEmpty())
                assertEquals(2, restored.dedupeKeys.size)
                assertEquals(if (version == 2) listOf(batch) else emptyList<RemoteChannelBatchState>(), restored.batchStates)
                assertEquals(3, fixture.plaintext().getInt("schemaVersion"))
            } finally { fixture.close() }
        }
    }

    @Test fun realConfirmationHandlersOnlyAuditFinalDecisionAndFailureKeepsCard() = runBlocking {
        val fixture = AuditDeviceFixture(context)
        val viewModels = ViewModelStore()
        val vm = withContext(Dispatchers.Main) {
            XiaoLingViewModel(context.applicationContext as Application, fixture.gateway).also {
                viewModels.put("audit-fixture", it)
                // long: 只验证前台就绪后的确认决定，不运行用户 Room/Agent 恢复；完整启动由既有 Activity 回归另验。
                XiaoLingViewModel::class.java.getDeclaredField("initializationComplete").apply { isAccessible = true }.setBoolean(it, true)
            }
        }
        try {
            assertNotNull(XiaoLingViewModel::class.java.getConstructor(Application::class.java))
            val opened = fixture.receive("ui-open")
            composeRule.setContent { MaterialTheme { Column {
                vm.uiState.pendingSharedDraft?.let { payload -> SharedDraftPendingNotice(payload, isRemote = true, enabled = true,
                    onOpen = vm::openPendingSharedDraft, onDiscard = vm::discardPendingSharedDraft) }
                RemoteChannelPairingPage(fixture.pairings, {}, inboxGateway = fixture.gateway,
                    onOpenPending = vm::stagePersistedRemoteChannelDraft, onIgnorePending = vm::ignorePersistedRemoteChannelDraft)
            } } }
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("remote-inbox-item-channel-sender-ui-open").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("remote-inbox-open-channel-sender-ui-open").performScrollTo().performClick()
            composeRule.waitUntil(5_000) { vm.uiState.pendingSharedDraft != null }
            assertTrue(fixture.ready().auditEntries.isEmpty())
            assertEquals(1, fixture.ready().pending.size)
            fixture.failWrites = true
            composeRule.onNodeWithText("打开远程草稿").performClick()
            composeRule.waitUntil(5_000) { vm.uiState.result?.message == "远程收件箱未能保存处理结果，请重试" }
            assertNotNull(vm.uiState.pendingSharedDraft)
            assertTrue(fixture.ready().auditEntries.isEmpty())
            fixture.failWrites = false
            composeRule.onNodeWithText("打开远程草稿").performClick()
            composeRule.waitUntil(5_000) { vm.uiState.pendingSharedDraft == null && vm.uiState.prompt == opened.payload.text }
            assertEquals(RemoteChannelInboxDisposition.OPENED, fixture.ready().auditEntries.single().disposition)
            assertFalse(vm.uiState.sendingMessage)
            assertNull(vm.uiState.activeAgentRun)
            assertFalse(vm.uiState.chatMessages.any { it.role == "user" && it.text == opened.payload.text })

            val ignored = fixture.receive("ui-ignore")
            assertTrue(withContext(Dispatchers.Main) { vm.stagePersistedRemoteChannelDraft(ignored) })
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("忽略远程草稿").performClick()
            composeRule.waitUntil(5_000) { vm.uiState.pendingSharedDraft == null }
            assertEquals(RemoteChannelInboxDisposition.IGNORED, fixture.ready().auditEntries.last().disposition)
            val listed = fixture.receive("list-ignore")
            assertTrue(withContext(Dispatchers.Main) { vm.ignorePersistedRemoteChannelDraft(listed) })
            assertEquals(RemoteChannelInboxDisposition.IGNORED, fixture.ready().auditEntries.last().disposition)

            val expired = fixture.receive("ui-expire")
            assertTrue(withContext(Dispatchers.Main) { vm.stagePersistedRemoteChannelDraft(expired) })
            fixture.now += RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS
            composeRule.waitForIdle()
            composeRule.onNodeWithText("打开远程草稿").performClick()
            composeRule.waitUntil(5_000) { vm.uiState.pendingSharedDraft == null }
            assertEquals(opened.payload.text, vm.uiState.prompt)
            assertEquals(RemoteChannelInboxDisposition.EXPIRED, fixture.ready().auditEntries.last().disposition)
            val expiredFromList = fixture.receive("list-expire")
            assertTrue(withContext(Dispatchers.Main) { vm.stagePersistedRemoteChannelDraft(expiredFromList) })
            fixture.now += RemoteChannelPolicy.MAX_PENDING_AGE_MILLIS
            assertFalse(withContext(Dispatchers.Main) { vm.ignorePersistedRemoteChannelDraft(expiredFromList) })
            assertNull(vm.uiState.pendingSharedDraft)
            assertEquals(RemoteChannelInboxDisposition.EXPIRED, fixture.ready().auditEntries.last().disposition)
            composeRule.onNodeWithTag("remote-inbox-refresh").performScrollTo().performClick()
            composeRule.waitUntil(5_000) { composeRule.onAllNodesWithTag("remote-inbox-empty").fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("remote-inbox-audit-toggle").performScrollTo().performClick()
            composeRule.onNodeWithTag("remote-inbox-audit-OPENED").performScrollTo()
            assertEquals(2, composeRule.onAllNodesWithTag("remote-inbox-audit-EXPIRED").fetchSemanticsNodes().size)
            assertEquals(5, fixture.ready().auditEntries.size)
        } finally {
            withContext(Dispatchers.Main) { viewModels.clear() }
            fixture.close()
        }
    }
}

private class AuditDeviceFixture(val context: Context) {
    val prefix = "remote_inbox_audit_test_${UUID.randomUUID()}"
    val alias = prefix + "_aes"
    val preferences = context.getSharedPreferences(prefix, Context.MODE_PRIVATE)
    var now = System.currentTimeMillis()
    var failWrites = false
    private val keys = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    val pairings = RemoteChannelPairingRegistry(object : RemoteChannelPairingStateStore {
        var raw: String? = null
        override fun loadState() = raw
        override fun saveState(state: String) { raw = state }
    })
    private val peer = RemoteChannelPairingPolicy.prepare("channel", "sender", "key-1", "审计夹具", java.util.Base64.getEncoder().encodeToString(keys.public.encoded))
    init { pairings.confirm(peer, peer.fingerprintSha256); pairings.setEnabled("channel", "sender", "key-1", true) }
    fun newStore() = KeystoreRemoteChannelInboxStateStore(context, prefix, alias)
    private val store = object : RemoteChannelInboxStateStore {
        override fun loadSnapshot() = newStore().loadSnapshot()
        override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { check(!failWrites) { "injected write failure" }; newStore().saveSnapshot(snapshot) }
    }
    val gateway = RemoteChannelInboxGateway(store, pairings) { now }
    fun ready() = RemoteChannelInboxGateway(store, pairings) { now }.status() as RemoteChannelInboxStatus.Ready
    fun raw() = preferences.getString("snapshot", null)!!
    fun receive(id: String): RemoteChannelDraft {
        val message = RemoteChannelEnvelope("channel", id, "sender", "conversation", "审计测试正文-$id", now,
            nonce = "nonce-$id-1234567890123456", keyId = "key-1")
        val signature = Signature.getInstance("SHA256withECDSA").run { initSign(keys.private); update(RemoteChannelPolicy.canonicalBytes(message)); sign() }
        return (gateway.receive(message.copy(signature = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(signature))) as RemoteChannelReceiveResult.Accepted).draft
    }
    fun seedLocalOutbox(): RemoteChannelBatchState {
        val receipt = RemoteChannelBatchReceipt("local-fixture-batch", "a".repeat(64), "cursor-1", listOf("local-message"))
        val state = RemoteChannelBatchState(RemoteChannelPullReliabilityPolicy.sourceIdentity(peer), "b".repeat(64), "c".repeat(32), "cursor-1", receipt)
        newStore().saveSnapshot(newStore().loadSnapshot()!!.copy(batchStates = listOf(state)))
        return state
    }
    fun plaintext(): JSONObject {
        val envelope = JSONObject(raw())
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)))
        return JSONObject(String(cipher.doFinal(Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)), Charsets.UTF_8))
    }
    fun rewriteAsLegacy(version: Int) {
        val value = plaintext().put("schemaVersion", version).apply { remove("auditEntries"); if (version == 1) remove("batchStates") }
        val key = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey(alias, null) as SecretKey
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val envelope = JSONObject(raw()).put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("ciphertext", Base64.encodeToString(cipher.doFinal(value.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        assertTrue(preferences.edit().putString("snapshot", envelope.toString()).commit())
    }
    fun close() {
        require(prefix.startsWith("remote_inbox_audit_test_"))
        context.deleteSharedPreferences(prefix)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }
}
