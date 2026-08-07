package com.longdev.xiaoling.ui

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.AgentMemorySource
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class XiaoLingViewModelMemoryNavigationInstrumentedTest {
    @Test
    fun refreshesCurrentRoomBeforeSelectingOrRejectingAnswerMemoryNavigation() {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val store = RoomAgentMemoryStore(application)
        val memory = runBlocking {
            store.remember(
                content = "第180阶段答案导航临时记忆",
                tags = "stage180 navigation",
                type = "ProjectFact",
                source = AgentMemorySource(
                    conversationId = "conversation-stage180-navigation",
                    runId = null,
                    summary = "Redmi instrumentation 临时夹具",
                ),
                confidence = 0.9,
            )
        }
        val viewModel = XiaoLingViewModel(application)

        try {
            val resolved = CountDownLatch(1)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.refreshMemoriesAndResolveNavigation(memory.id) { resolved.countDown() }
            }

            assertTrue("当前 Room 记录应允许导航", resolved.await(5, TimeUnit.SECONDS))
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertEquals(memory.id, viewModel.uiState.selectedMemoryId)
                assertEquals(memory.id, viewModel.uiState.memories.firstOrNull()?.id)
            }

            runBlocking { store.delete(memory.id) }
            val staleNavigation = AtomicBoolean(false)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                viewModel.refreshMemoriesAndResolveNavigation(memory.id) { staleNavigation.set(true) }
            }
            waitUntil(timeoutMillis = 5_000) { !viewModel.uiState.loadingMemories }

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertFalse("已删除记录不能继续导航", staleNavigation.get())
                assertTrue(viewModel.uiState.memories.none { record -> record.id == memory.id })
                assertTrue(viewModel.uiState.memoryError?.contains("已不存在") == true)
            }
        } finally {
            runBlocking { store.delete(memory.id) }
        }
    }

    private fun waitUntil(timeoutMillis: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition() && System.currentTimeMillis() < deadline) {
            Thread.sleep(25)
        }
        assertTrue("等待 ViewModel 完成 Room 刷新超时", condition())
    }
}
