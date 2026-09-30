package com.longdev.xiaoling.storage

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
            context.getSharedPreferences("xiaoling_remote_channel", Context.MODE_PRIVATE).edit().clear().commit()
        }

        override fun finished(description: Description) {
            context.getSharedPreferences("xiaoling_remote_channel", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

    @Test
    fun orderedDedupeKeysSurviveStoreRecreationWithoutMessageBody() {
        val first = SharedPreferencesRemoteChannelDedupeStore(context)
        first.saveKeys(listOf("telegram\u0000user-1\u0000message-1", "telegram\u0000user-1\u0000message-2"))

        val recreated = SharedPreferencesRemoteChannelDedupeStore(context)
        assertEquals(
            listOf("telegram\u0000user-1\u0000message-1", "telegram\u0000user-1\u0000message-2"),
            recreated.loadKeys(),
        )
    }
}
