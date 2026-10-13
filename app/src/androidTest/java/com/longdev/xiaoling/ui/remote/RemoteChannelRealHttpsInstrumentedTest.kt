package com.longdev.xiaoling.ui.remote

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.RemoteChannelInboxGateway
import com.longdev.xiaoling.agent.RemoteChannelInboxStatus
import com.longdev.xiaoling.agent.RemoteChannelPairingPolicy
import com.longdev.xiaoling.agent.RemoteChannelPairingRegistry
import com.longdev.xiaoling.agent.RemoteChannelPullService
import com.longdev.xiaoling.agent.RemoteChannelPullSourceRegistry
import com.longdev.xiaoling.agent.RemoteChannelReceiveResult
import com.longdev.xiaoling.agent.RemoteChannelRejectionReason
import com.longdev.xiaoling.storage.KeystoreRemoteChannelInboxStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPairingStateStore
import com.longdev.xiaoling.storage.SharedPreferencesRemoteChannelPullSourceStateStore
import java.security.KeyStore
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/**
 * long: 通过外部 HTTPS 夹具验收手机自身的 DNS、TLS、签名入箱和前台列表；
 * 配对、来源与加密收件箱都使用本次测试专属状态，避免碰到用户正式消息。
 */
class RemoteChannelRealHttpsInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun realTlsPullKeepsSignedMessagePendingAndRejectsUnsafeResponses() {
        val arguments = InstrumentationRegistry.getArguments()
        val baseUrl = arguments.getString("remotePullBaseUrl").orEmpty().trimEnd('/')
        val publicKey = arguments.getString("remotePullPublicKey").orEmpty()
        assumeTrue("仅在传入 HTTPS 夹具和公钥时执行真实网络验收", baseUrl.isNotBlank() && publicKey.isNotBlank())

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val suffix = UUID.randomUUID().toString()
        val pairingPreferences = "xiaoling_remote_https_pairings_test_$suffix"
        val sourcePreferences = "xiaoling_remote_https_sources_test_$suffix"
        val inboxPreferences = "xiaoling_remote_https_inbox_test_$suffix"
        val keyAlias = "xiaoling_remote_https_inbox_aes_test_$suffix"
        try {
            val pairings = RemoteChannelPairingRegistry(
                SharedPreferencesRemoteChannelPairingStateStore(context, pairingPreferences),
            )
            val peer = RemoteChannelPairingPolicy.prepare(
                "real-device-test", "fixture-sender", "fixture-key-1", "真机 HTTPS 合成来源", publicKey,
            )
            pairings.confirm(peer, peer.fingerprintSha256)
            pairings.setEnabled(peer.channelId, peer.senderId, peer.keyId, true)
            val sources = RemoteChannelPullSourceRegistry(
                SharedPreferencesRemoteChannelPullSourceStateStore(context, sourcePreferences), pairings,
            )
            fun gateway() = RemoteChannelInboxGateway(
                KeystoreRemoteChannelInboxStateStore(context, inboxPreferences, keyAlias), pairings,
            )
            val inbox = gateway()
            val service = RemoteChannelPullService(pairings, sources, inbox)
            composeRule.setContent {
                MaterialTheme {
                    RemoteChannelPairingPage(
                        registry = pairings, onBack = {}, inboxGateway = inbox,
                        pullSources = sources, pullService = service,
                        onIgnorePending = { inbox.discard(it) },
                    )
                }
            }
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("remote-inbox-empty").fetchSemanticsNodes().isNotEmpty()
            }

            val peerTag = "real-device-test-fixture-sender-fixture-key-1"
            val messageTag = "real-device-test-fixture-sender-real-https-20261014-1"
            composeRule.onNodeWithTag("remote-source-url-$peerTag").performScrollTo()
                .performTextInput("$baseUrl/next")
            composeRule.onNodeWithTag("remote-source-save-$peerTag").performScrollTo().performClick()
            composeRule.onNodeWithTag("remote-source-pull-$peerTag").performScrollTo().performClick()
            composeRule.waitUntil(30_000) {
                composeRule.onAllNodesWithTag("remote-inbox-item-$messageTag")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            val pending = (gateway().status() as RemoteChannelInboxStatus.Ready).pending
            assertEquals("real-https-20261014-1", pending.single().draft.messageId)
            assertEquals("真实 HTTPS 真机验收合成正文", pending.single().draft.payload.text)
            val encrypted = context.getSharedPreferences(inboxPreferences, Context.MODE_PRIVATE)
                .getString("snapshot", null).orEmpty()
            assertTrue(encrypted.isNotBlank())
            assertFalse(encrypted.contains("真实 HTTPS 真机验收合成正文"))
            assertFalse(encrypted.contains("real-https-20261014-1"))

            // long: 同一个外部服务再次交付相同身份时，只能得到持久去重拒绝，旧未读正文仍在。
            assertEquals(RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
                (runBlocking { service.pull(peer) } as RemoteChannelReceiveResult.Rejected).reason)
            assertEquals(1, (gateway().status() as RemoteChannelInboxStatus.Ready).pending.size)

            sources.save(peer, "$baseUrl/redirect")
            assertTrue(runCatching { runBlocking { service.pull(peer) } }.exceptionOrNull()
                ?.message.orEmpty().contains("HTTP 302"))
            sources.save(peer, "$baseUrl/oversized")
            assertTrue(runCatching { runBlocking { service.pull(peer) } }.exceptionOrNull()
                ?.message.orEmpty().contains("响应过大"))
            sources.save(peer, "$baseUrl/bad-signature")
            assertEquals(RemoteChannelRejectionReason.INVALID_SIGNATURE,
                (runBlocking { service.pull(peer) } as RemoteChannelReceiveResult.Rejected).reason)
            assertEquals(1, (gateway().status() as RemoteChannelInboxStatus.Ready).pending.size)

            composeRule.onNodeWithTag("remote-inbox-ignore-$messageTag").performScrollTo().performClick()
            composeRule.waitUntil(5_000) {
                composeRule.onAllNodesWithTag("remote-inbox-empty").fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue((gateway().status() as RemoteChannelInboxStatus.Ready).pending.isEmpty())
        } finally {
            // long: 只回收本轮 UUID 命名的测试状态和密钥；正式配对、来源与收件箱保持原样。
            context.deleteSharedPreferences(pairingPreferences)
            context.deleteSharedPreferences(sourcePreferences)
            context.deleteSharedPreferences(inboxPreferences)
            KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(keyAlias)
        }
    }
}
