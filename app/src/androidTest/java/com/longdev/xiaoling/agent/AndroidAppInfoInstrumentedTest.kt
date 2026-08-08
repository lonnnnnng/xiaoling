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
 * long: 该探针只读取 Redmi 当前安装包的公开版本信息，验证 app.get_info 的生产 Registry
 * 投影不携带 Provider、凭据、设备标识或安装来源，也不需要网络和后台执行。
 */
@RunWith(AndroidJUnit4::class)
class AndroidAppInfoInstrumentedTest {
    @Test
    fun foregroundRegistryReadsCurrentPackageMetadataOnly() = kotlinx.coroutines.runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = XiaoLingToolRegistry(
            clock = SystemAgentClock(),
            conversationStore = RoomAgentConversationStore(context),
            noteStore = RoomAgentNoteStore(context),
            memoryStore = RoomAgentMemoryStore(context),
            knowledgeStore = RoomKnowledgeDocumentStore(context),
            appInfoReader = AndroidAppInfoReader(context),
        )
        val result = registry.execute(
            ToolCall(
                name = "app.get_info",
                arguments = emptyMap(),
                risk = ToolRisk.SAFE,
            ),
        )

        assertTrue(result.success)
        val lines = result.content.lines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].startsWith("应用名称："))
        assertEquals("包名：com.longdev.xiaoling", lines[1])
        assertTrue(lines[2].startsWith("版本名："))
        assertTrue(lines[3].startsWith("版本号："))
        assertFalse(result.content.contains("Provider"))
        assertFalse(result.content.contains("API Key"))
        assertFalse(result.content.contains("wsvwypiz7xwslvl7"))
        assertFalse(result.content.contains("安装来源"))
        println("STAGE213_APP_INFO package=com.longdev.xiaoling fields=4 privacySafe=true")
    }
}
