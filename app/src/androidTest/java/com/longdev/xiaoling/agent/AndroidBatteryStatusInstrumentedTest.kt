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
 * long: 该探针只读取 Redmi 当前电池广播，验证 app.get_battery 只投影电量和供电状态，
 * 不携带设备标识、应用列表或 Provider 配置，也不需要网络和额外权限。
 */
@RunWith(AndroidJUnit4::class)
class AndroidBatteryStatusInstrumentedTest {
    @Test
    fun foregroundRegistryReadsCurrentBatteryFactsOnly() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context),
            noteStore = RoomAgentNoteStore(context),
            memoryStore = RoomAgentMemoryStore(context),
            knowledgeStore = RoomKnowledgeDocumentStore(context),
            batteryStatusReader = AndroidBatteryStatusReader(context),
        )

        val result = registry.execute(
            ToolCall(name = "app.get_battery", arguments = emptyMap(), risk = ToolRisk.SAFE),
        )

        assertTrue(result.success)
        val lines = result.content.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].matches(Regex("电量：(?:100|[0-9]{1,2})%")))
        assertTrue(lines[1].startsWith("充电状态："))
        assertTrue(lines[2].startsWith("供电方式："))
        assertFalse(result.content.contains("Provider"))
        assertFalse(result.content.contains("API Key"))
        assertFalse(result.content.contains("wsvwypiz7xwslvl7"))
        assertFalse(result.content.contains("com.longdev"))
        println("STAGE215_BATTERY package=com.longdev.xiaoling fields=3 privacySafe=true")
    }
}
