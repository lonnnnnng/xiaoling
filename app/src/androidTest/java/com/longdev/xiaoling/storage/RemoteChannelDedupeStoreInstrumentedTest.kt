package com.longdev.xiaoling.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.agent.InMemoryRemoteChannelInbox
import com.longdev.xiaoling.agent.RemoteChannelAllowlist
import com.longdev.xiaoling.agent.RemoteChannelEnvelope
import com.longdev.xiaoling.agent.RemoteChannelReceiveResult
import com.longdev.xiaoling.agent.RemoteChannelRejectionReason
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class RemoteChannelDedupeStoreInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val cleanup = object : TestWatcher() {
        override fun starting(description: Description) {
            context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        }

        override fun finished(description: Description) {
            context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun orderedDedupeKeysSurviveStoreRecreationWithoutMessageBody() {
        val first = SharedPreferencesRemoteChannelDedupeStore(context, TEST_PREFERENCES)
        first.saveKeys(listOf("telegram\u0000user-1\u0000message-1", "telegram\u0000user-1\u0000message-2"))

        val recreated = SharedPreferencesRemoteChannelDedupeStore(context, TEST_PREFERENCES)
        assertEquals(
            listOf("telegram\u0000user-1\u0000message-1", "telegram\u0000user-1\u0000message-2"),
            recreated.loadKeys(),
        )
    }

    @Test
    fun corruptedDedupeLedgerFreezesIngress() {
        context.getSharedPreferences(TEST_PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString("dedupe_keys", "{broken")
            .commit()
        val inbox = InMemoryRemoteChannelInbox(
            allowlist = RemoteChannelAllowlist("channel", setOf("sender")),
            dedupeStore = SharedPreferencesRemoteChannelDedupeStore(context, TEST_PREFERENCES),
        )
        val result = inbox.receive(RemoteChannelEnvelope("channel", "message", "sender", "chat", "text", 1L))
        assertEquals(
            RemoteChannelRejectionReason.DEDUPE_PERSISTENCE_FAILURE,
            (result as RemoteChannelReceiveResult.Rejected).reason,
        )
    }

    private companion object {
        const val TEST_PREFERENCES = "xiaoling_remote_channel_dedupe_instrumentation"
    }
}
