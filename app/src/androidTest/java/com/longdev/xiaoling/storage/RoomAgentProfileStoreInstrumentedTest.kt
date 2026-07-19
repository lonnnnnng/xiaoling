package com.longdev.xiaoling.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.agent.AgentContextPolicy
import com.longdev.xiaoling.agent.AgentProfileRecord
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.model.ApiMode
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomAgentProfileStoreInstrumentedTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var database: XiaoLingDatabase
    private lateinit var store: RoomAgentProfileStore

    @Before
    fun setUp() {
        context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE).edit().clear().commit()
        database = Room.inMemoryDatabaseBuilder(context, XiaoLingDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        store = RoomAgentProfileStore(context, database, RoomStateStore(context))
    }

    @After
    fun tearDown() {
        database.close()
        context.getSharedPreferences("xiaoling_room_state", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun defaultSelectionAndCapabilityListsRoundTripThroughRoom() = runBlocking {
        val default = profile("agent-default", "默认 Agent", "gpt-default")
        val initial = store.loadOrCreateDefault(default)

        assertEquals(listOf(default), initial.profiles)
        assertEquals(default.id, initial.selectedProfileId)

        val focused = profile("agent-focused", "专注 Agent", "gpt-focused").copy(
            allowedToolNames = listOf("notes.search"),
            allowedSkillIds = listOf("local-notes"),
            memoryEnabled = false,
        )
        store.upsert(focused)
        assertTrue(store.select(focused.id))

        val reopenedSelection = store.loadOrCreateDefault(default.copy(name = "不应覆盖"))
        assertEquals(focused.id, reopenedSelection.selectedProfileId)
        assertEquals(focused, reopenedSelection.profiles.first { it.id == focused.id })
        assertTrue(store.delete(default.id))
        assertEquals(listOf(focused), store.list())
    }

    private fun profile(id: String, name: String, model: String) = AgentProfileRecord(
        id = id,
        name = name,
        avatar = "A",
        providerId = "provider-test",
        model = model,
        apiMode = ApiMode.RESPONSES,
        systemPrompt = "保持简洁",
        contextPolicy = AgentContextPolicy.CURRENT_CONVERSATION,
        allowedToolNames = listOf("app.current_time", "notes.search"),
        allowedSkillIds = listOf("device-time"),
        memoryEnabled = true,
        createdAt = 1L,
        updatedAt = 2L,
    )
}
