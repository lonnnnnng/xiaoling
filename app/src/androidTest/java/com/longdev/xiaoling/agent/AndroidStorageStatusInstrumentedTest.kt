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
 * long: 该探针只读取 Redmi 数据分区的容量摘要，验证 app.get_storage 不投影文件名、路径、应用数据或设备身份。
 */
@RunWith(AndroidJUnit4::class)
class AndroidStorageStatusInstrumentedTest {
    @Test
    fun foregroundRegistryReadsCurrentStorageFactsOnly() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context),
            noteStore = RoomAgentNoteStore(context),
            memoryStore = RoomAgentMemoryStore(context),
            knowledgeStore = RoomKnowledgeDocumentStore(context),
            storageStatusReader = AndroidStorageStatusReader(context),
        )

        val result = registry.execute(
            ToolCall(name = "app.get_storage", arguments = emptyMap(), risk = ToolRisk.SAFE),
        )

        assertTrue(result.success)
        val lines = result.content.lines()
        assertEquals(3, lines.size)
        assertTrue(lines[0].matches(Regex("存储总量：[0-9]+\\.[0-9] GB")))
        assertTrue(lines[1].matches(Regex("可用空间：[0-9]+\\.[0-9] GB")))
        assertTrue(lines[2].matches(Regex("已使用：[0-9]+\\.[0-9]%")))
        assertFalse(result.content.contains("/data/"))
        assertFalse(result.content.contains("Provider"))
        assertFalse(result.content.contains("API Key"))
        assertFalse(result.content.contains("wsvwypiz7xwslvl7"))
        assertFalse(result.content.contains("com.longdev"))
        println("STAGE218_STORAGE package=com.longdev.xiaoling fields=3 privacySafe=true")
    }
}
