package com.longdev.xiaoling.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.longdev.xiaoling.storage.SharedPreferencesAgentPluginStateStore
import java.util.UUID
import org.junit.After

class AgentPluginSigningInstrumentedTest : AgentPluginSigningContractTests() {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val preferences = mutableListOf<String>()

    override fun freshStore(): AgentPluginStateStore {
        val name = "stage288-signing-test-${UUID.randomUUID()}"
        preferences += name
        return SharedPreferencesAgentPluginStateStore(context, name)
    }

    @After
    fun clearOwnFixtures() {
        // long: 仅清空本轮 UUID 隔离账本，不触碰正式插件、Provider、MCP 或记忆数据。
        preferences.forEach { context.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
    }
}
