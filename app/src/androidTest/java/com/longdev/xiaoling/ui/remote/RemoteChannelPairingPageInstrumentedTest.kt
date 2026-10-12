package com.longdev.xiaoling.ui.remote

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.longdev.xiaoling.agent.RemoteChannelPairingPolicy
import com.longdev.xiaoling.agent.RemoteChannelPairingRegistry
import com.longdev.xiaoling.agent.RemoteChannelPairingStateStore
import com.longdev.xiaoling.agent.RemoteChannelEnvelope
import com.longdev.xiaoling.agent.RemoteChannelInboxGateway
import com.longdev.xiaoling.agent.RemoteChannelInboxSnapshot
import com.longdev.xiaoling.agent.RemoteChannelInboxStateStore
import com.longdev.xiaoling.agent.RemoteChannelPolicy
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class RemoteChannelPairingPageInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun previewConfirmEnableAndRevokeKeepPublicKeyBoundToOneSender() {
        val publicKey = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair().public
        val encoded = Base64.getEncoder().encodeToString(publicKey.encoded)
        val candidate = RemoteChannelPairingPolicy.prepare("channel", "sender", "key-1", "测试来源", encoded)
        val registry = RemoteChannelPairingRegistry(TestStore())
        composeRule.setContent { MaterialTheme { RemoteChannelPairingPage(registry, onBack = {}) } }

        composeRule.onNodeWithTag("remote-pairing-channel").performTextInput("channel")
        composeRule.onNodeWithTag("remote-pairing-sender").performTextInput("sender")
        composeRule.onNodeWithTag("remote-pairing-key-id").performTextInput("key-1")
        composeRule.onNodeWithTag("remote-pairing-source").performTextInput("测试来源")
        composeRule.onNodeWithTag("remote-pairing-public-key").performTextInput(encoded)
        composeRule.onNodeWithTag("remote-pairing-preview").performScrollTo().performClick()
        composeRule.onNodeWithTag("remote-pairing-fingerprint").performScrollTo().assertExists()
        composeRule.onNodeWithTag("remote-pairing-confirm").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("remote-pairing-confirm-fingerprint")
            .performScrollTo().performTextInput(candidate.fingerprintSha256)
        composeRule.onNodeWithTag("remote-pairing-confirm").performScrollTo().performClick()
        composeRule.runOnIdle { assertEquals(false, registry.list().single().enabled) }

        composeRule.onNodeWithTag("remote-peer-enabled-channel-sender-key-1").performScrollTo().performClick()
        composeRule.runOnIdle { assertTrue(registry.list().single().enabled) }
        composeRule.onNodeWithTag("remote-peer-revoke-channel-sender-key-1").performScrollTo().performClick()
        composeRule.onNodeWithTag("remote-pairing-confirm-revoke").performClick()
        composeRule.runOnIdle { assertTrue(registry.list().isEmpty()) }
    }

    @Test
    fun signedImportListsPendingAndOpenOrIgnoreRemovesDurableBody() {
        val now = System.currentTimeMillis()
        val keyPair = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val registry = RemoteChannelPairingRegistry(TestStore())
        val candidate = RemoteChannelPairingPolicy.prepare(
            "channel", "sender", "paired", "测试来源",
            Base64.getEncoder().encodeToString(keyPair.public.encoded),
        )
        registry.confirm(candidate, candidate.fingerprintSha256)
        registry.setEnabled("channel", "sender", "paired", true)
        val store = TestInboxStore()
        val gateway = RemoteChannelInboxGateway(store, registry) { now }
        var stagedMessageId: String? = null
        composeRule.setContent {
            MaterialTheme {
                RemoteChannelPairingPage(
                    registry = registry,
                    onBack = {},
                    inboxGateway = gateway,
                    onOpenPending = { draft -> stagedMessageId = draft.messageId; true },
                    onIgnorePending = { draft -> gateway.discard(draft) },
                )
            }
        }
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("remote-inbox-empty").fetchSemanticsNodes().isNotEmpty()
        }

        fun import(id: String) {
            val unsigned = RemoteChannelEnvelope(
                channelId = "channel", messageId = id, senderId = "sender",
                conversationKey = "conversation", text = "待确认正文-$id", receivedAtMillis = now,
                nonce = "nonce-$id-1234567890123456", keyId = "paired",
            )
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(RemoteChannelPolicy.canonicalBytes(unsigned))
                Base64.getUrlEncoder().withoutPadding().encodeToString(sign())
            }
            val message = unsigned.copy(signature = signature)
            val raw = JSONObject()
                .put("channelId", message.channelId).put("messageId", message.messageId)
                .put("senderId", message.senderId).put("conversationKey", message.conversationKey)
                .put("text", message.text).put("receivedAtMillis", message.receivedAtMillis)
                .put("nonce", message.nonce).put("keyId", message.keyId)
                .put("signature", message.signature).toString()
            composeRule.onNodeWithTag("remote-inbox-import-json").performScrollTo().performTextInput(raw)
            composeRule.onNodeWithTag("remote-inbox-import").performScrollTo().performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("remote-inbox-item-channel-sender-$id")
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }

        import("message-1")
        composeRule.onNodeWithTag("remote-inbox-open-channel-sender-message-1").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { stagedMessageId == "message-1" }
        assertEquals(1, (gateway.status() as com.longdev.xiaoling.agent.RemoteChannelInboxStatus.Ready).pending.size)
        val staged = (gateway.status() as com.longdev.xiaoling.agent.RemoteChannelInboxStatus.Ready).pending.single().draft
        assertTrue(gateway.discard(staged))
        composeRule.onNodeWithTag("remote-inbox-refresh").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("remote-inbox-empty").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, (gateway.status() as com.longdev.xiaoling.agent.RemoteChannelInboxStatus.Ready).pending.size)

        import("message-2")
        composeRule.onNodeWithTag("remote-inbox-ignore-channel-sender-message-2").performScrollTo().performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithTag("remote-inbox-empty").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, (RemoteChannelInboxGateway(store, registry) { now }.status()
            as com.longdev.xiaoling.agent.RemoteChannelInboxStatus.Ready).pending.size)
    }

    private class TestStore(var state: String? = null) : RemoteChannelPairingStateStore {
        override fun loadState(): String? = state
        override fun saveState(state: String) { this.state = state }
    }

    private class TestInboxStore : RemoteChannelInboxStateStore {
        private var snapshot: RemoteChannelInboxSnapshot? = null
        override fun loadSnapshot(): RemoteChannelInboxSnapshot? = snapshot
        override fun saveSnapshot(snapshot: RemoteChannelInboxSnapshot) { this.snapshot = snapshot }
    }
}
