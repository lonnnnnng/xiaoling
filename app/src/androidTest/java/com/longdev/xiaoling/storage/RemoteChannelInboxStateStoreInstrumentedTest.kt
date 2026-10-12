package com.longdev.xiaoling.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.HmacRemoteChannelAuthenticator
import com.longdev.xiaoling.agent.InMemoryRemoteChannelInbox
import com.longdev.xiaoling.agent.RemoteChannelAllowlist
import com.longdev.xiaoling.agent.RemoteChannelEnvelope
import com.longdev.xiaoling.agent.RemoteChannelPolicy
import com.longdev.xiaoling.agent.RemoteChannelReceiveResult
import com.longdev.xiaoling.agent.RemoteChannelRejectionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import org.json.JSONObject

class RemoteChannelInboxStateStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val now = System.currentTimeMillis()
    private val authenticator = HmacRemoteChannelAuthenticator(
        mapOf("test-key" to ByteArray(RemoteChannelPolicy.MIN_HMAC_KEY_BYTES) { 11 }),
    )

    @get:Rule
    val cleanup = object : TestWatcher() {
        override fun starting(description: Description) {
            // long: 真机收件箱回归只清理专属测试偏好文件，不能抹掉用户正式远程去重或待确认消息。
            if (phase() != "restore") {
                context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }

        override fun finished(description: Description) {
            // long: seed 单项退出后保留专属测试快照，下一次独立 instrumentation 进程才能验证真实跨进程恢复。
            if (phase() != "seed") {
                context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }

    @Test
    fun seedPendingForProcessRestart() {
        assumeTrue(phase() == "seed")
        assertTrue(inbox().receive(signed("restart-message")) is RemoteChannelReceiveResult.Accepted)
    }

    @Test
    fun restorePendingAfterProcessRestart() {
        assumeTrue(phase() == "restore")
        val recreated = inbox()
        assertEquals("restart-message", recreated.pendingDrafts().single().draft.messageId)
        assertEquals("仅测试的远程正文", recreated.pendingDrafts().single().draft.payload.text)
        assertEquals(
            RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (recreated.receive(signed("restart-message")) as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    @Test
    fun encryptedPendingTextAndReplayIdentitySurviveStoreRecreation() {
        val first = inbox()
        val message = signed("message-1")
        assertTrue(first.receive(message) is RemoteChannelReceiveResult.Accepted)
        val raw = context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE)
            .getString("snapshot", null).orEmpty()
        assertTrue(raw.isNotBlank())
        assertFalse(raw.contains("仅测试的远程正文"))
        assertFalse(raw.contains("message-1"))

        val recreated = inbox()
        assertEquals("仅测试的远程正文", recreated.pendingDrafts().single().draft.payload.text)
        assertEquals(
            RemoteChannelRejectionReason.DUPLICATE_MESSAGE,
            (recreated.receive(message) as RemoteChannelReceiveResult.Rejected).reason,
        )
        assertTrue(recreated.discardPending("channel", "sender", "message-1"))
        assertTrue(inbox().pendingDrafts().isEmpty())
    }

    @Test
    fun corruptedCiphertextFreezesIngressWithoutResettingReplayLedger() {
        assertTrue(inbox().receive(signed("message-2")) is RemoteChannelReceiveResult.Accepted)
        val preferences = context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE)
        val envelope = JSONObject(preferences.getString("snapshot", null)!!)
        val ciphertext = envelope.getString("ciphertext")
        envelope.put("ciphertext", (if (ciphertext.first() == 'A') "B" else "A") + ciphertext.drop(1))
        preferences.edit()
            .putString("snapshot", envelope.toString())
            .commit()

        val recreated = inbox()
        assertTrue(recreated.pendingDrafts().isEmpty())
        assertEquals(
            RemoteChannelRejectionReason.DEDUPE_PERSISTENCE_FAILURE,
            (recreated.receive(signed("message-3")) as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    private fun inbox() = InMemoryRemoteChannelInbox(
        allowlist = RemoteChannelAllowlist("channel", setOf("sender")),
        authenticator = authenticator,
        inboxStateStore = KeystoreRemoteChannelInboxStateStore(context, TEST_PREFERENCES, TEST_KEY_ALIAS),
        clock = { now },
    )

    private fun signed(messageId: String): RemoteChannelEnvelope {
        val unsigned = RemoteChannelEnvelope(
            channelId = "channel",
            messageId = messageId,
            senderId = "sender",
            conversationKey = "conversation",
            text = "仅测试的远程正文",
            receivedAtMillis = now,
            nonce = "nonce-$messageId".padEnd(RemoteChannelPolicy.MIN_NONCE_CHARS, 'x'),
            keyId = "test-key",
        )
        return unsigned.copy(signature = authenticator.sign(unsigned, "test-key"))
    }

    private fun phase(): String? = InstrumentationRegistry.getArguments().getString("remoteInboxPhase")

    private companion object {
        const val TEST_PREFERENCES = "xiaoling_remote_channel_inbox_instrumentation"
        const val TEST_KEY_ALIAS = "xiaoling_remote_channel_inbox_instrumentation_aes"
    }
}
