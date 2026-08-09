package com.longdev.xiaoling.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.storage.RoomAgentConversationStore
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import com.longdev.xiaoling.storage.RoomAgentNoteStore
import com.longdev.xiaoling.storage.RoomKnowledgeDocumentStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 该探针只读取 Redmi 当前活动网络的脱敏状态，验证 app.get_connectivity 不投影网络名称、地址、Provider 或凭据。
 */
@RunWith(AndroidJUnit4::class)
class AndroidConnectivityStatusInstrumentedTest {
    @Test
    fun foregroundRegistryReadsCurrentConnectivityFactsOnly() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context),
            noteStore = RoomAgentNoteStore(context),
            memoryStore = RoomAgentMemoryStore(context),
            knowledgeStore = RoomKnowledgeDocumentStore(context),
            connectivityStatusReader = AndroidConnectivityStatusReader(context),
        )

        val result = registry.execute(
            ToolCall(name = "app.get_connectivity", arguments = emptyMap(), risk = ToolRisk.SAFE),
        )

        assertTrue(result.success)
        val lines = result.content.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].matches(Regex("网络状态：(已连接|未连接)")))
        assertTrue(lines[1].startsWith("网络类型："))
        assertTrue(lines[2].matches(Regex("互联网可达：(是|否)")))
        assertFalse(result.content.contains("Provider"))
        assertFalse(result.content.contains("API Key"))
        assertFalse(result.content.contains("wsvwypiz7xwslvl7"))
        assertFalse(result.content.contains("com.longdev"))
        println("STAGE216_CONNECTIVITY package=com.longdev.xiaoling fields=3 privacySafe=true")
    }
}
