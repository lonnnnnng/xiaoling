package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentSkillDefinition
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.agent.ToolRisk
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentSkillStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val store = RoomAgentSkillStore(context, database)

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun builtInDefinitionRefreshPreservesUserEnabledState() = runBlocking {
        val original = builtInSkill(version = 1, instructions = "读取设备时间。")
        store.synchronizeBuiltIns(listOf(original))
        store.setEnabled(original.id, false)

        store.synchronizeBuiltIns(
            listOf(builtInSkill(version = 2, instructions = "读取设备时间并返回时区。")),
        )

        val refreshed = store.list().single()
        // long: 内置规则可以随应用升级，但停用代表用户撤回能力；同步新定义时必须保留这个决定。
        assertEquals(2, refreshed.definition.version)
        assertEquals("读取设备时间并返回时区。", refreshed.definition.instructions)
        assertEquals(false, refreshed.enabled)
    }

    private fun builtInSkill(version: Int, instructions: String) = AgentSkillDefinition(
        id = "device-time-test",
        version = version,
        name = "设备时间测试",
        description = "读取设备时间。",
        instructions = instructions,
        toolNames = setOf("app.current_time"),
        keywords = setOf("时间"),
        triggerExamples = listOf("现在几点"),
        declaredRisk = ToolRisk.SAFE,
        failureRecovery = "失败时停止。",
        completionCriteria = "时间可读。",
        source = AgentSkillSource.BUILT_IN,
    )
}
