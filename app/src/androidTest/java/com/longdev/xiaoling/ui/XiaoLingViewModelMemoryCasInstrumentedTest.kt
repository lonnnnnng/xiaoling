package com.longdev.xiaoling.ui

import android.app.Application
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.agent.AgentMemorySource
import com.longdev.xiaoling.agent.AgentMemoryUpdate
import com.longdev.xiaoling.data.XiaoLingDatabase
import com.longdev.xiaoling.storage.RoomAgentMemoryStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** long: 使用生产 ViewModel 验证旧草稿冲突；只写入并清理 UUID 记忆，不触碰正式撤销文件。 */
@RunWith(AndroidJUnit4::class)
class XiaoLingViewModelMemoryCasInstrumentedTest {
    @Test fun conflictKeepsDraftUntilExplicitReloadAndFreshSave() {
        assertEquals("begonia", android.os.Build.DEVICE)
        val application = ApplicationProvider.getApplicationContext<Application>()
        val database = XiaoLingDatabase.getInstance(application)
        val fixture = "stage287-${UUID.randomUUID()}"
        val store = RoomAgentMemoryStore(application, database, File(application.cacheDir, "$fixture.undo"))
        val original = runBlocking { store.remember("$fixture 原始事实", fixture, "Episode", AgentMemorySource(null, null, "隔离编辑验收"), 0.8) }
        val owner = ViewModelStore()
        lateinit var viewModel: XiaoLingViewModel
        try {
            onMain { viewModel = XiaoLingViewModel(application); owner.put(fixture, viewModel) }
            val loaded = CountDownLatch(1)
            onMain { viewModel.refreshMemoriesAndResolveNavigation(original.id) { loaded.countDown() } }
            assertTrue(loaded.await(10, TimeUnit.SECONDS))
            val draft = "$fixture 用户未保存的草稿"
            onMain { viewModel.openMemoryEdit(original.id); viewModel.updateMemoryEditContent(draft) }
            val current = runBlocking { requireNotNull(store.update(original.id, AgentMemoryUpdate("$fixture 最新事实", fixture, "Episode", 0.8), original.version)) }
            onMain { viewModel.saveMemoryEdit() }
            await { original.id !in viewModel.uiState.mutatingMemoryIds }
            onMain {
                assertEquals(draft, viewModel.uiState.editingMemory?.content)
                assertEquals(original.version, viewModel.uiState.editingMemory?.expectedVersion)
                assertEquals(current, viewModel.uiState.editingMemory?.conflict)
                assertEquals(current, viewModel.uiState.memories.single { it.id == original.id })
                assertNotNull(viewModel.uiState.memoryError)
            }
            assertEquals(current, runBlocking { store.get(original.id) })
            // long: 只有用户明确重新载入，草稿才能取得新版本；旧保存失败不自动重试。
            onMain {
                viewModel.openMemoryEdit(original.id)
                assertEquals(current.content, viewModel.uiState.editingMemory?.content)
                assertEquals(current.version, viewModel.uiState.editingMemory?.expectedVersion)
                assertNull(viewModel.uiState.editingMemory?.conflict)
                assertNull(viewModel.uiState.memoryError)
                viewModel.updateMemoryEditContent(draft)
                viewModel.saveMemoryEdit()
            }
            await { original.id !in viewModel.uiState.mutatingMemoryIds }
            val saved = runBlocking { requireNotNull(store.get(original.id)) }
            assertEquals(3L, saved.version)
            assertEquals(draft, saved.content)
            onMain { assertNull(viewModel.uiState.editingMemory) }
            // long: 删除确认同样冻结版本；外部编辑后旧确认不能删除当前事实。
            await { !viewModel.uiState.loadingMemories }
            onMain { viewModel.requestMemoryDelete(original.id) }
            val winner = runBlocking { requireNotNull(store.setPinned(original.id, true, saved.version)) }
            onMain { viewModel.confirmMemoryDelete() }
            await { original.id !in viewModel.uiState.mutatingMemoryIds }
            assertEquals(winner, runBlocking { store.get(original.id) })
            onMain { assertEquals(winner, viewModel.uiState.memories.single { it.id == original.id }); assertNotNull(viewModel.uiState.memoryError) }
        } finally {
            onMain { owner.clear() }
            runBlocking {
                database.withTransaction {
                    // long: 精确清理本轮身份，不调用带正式撤销副作用的管理删除，也不移除其他用户记录。
                    val dao = database.agentMemoryDao()
                    dao.getMemory(original.id)?.let { current ->
                        dao.deleteMemoryIndex(original.id)
                        check(dao.deleteMemoryIfVersionMatches(original.id, current.version) == 1)
                    }
                }
            }
        }
    }

    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun await(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        var done = false
        while (!done && System.currentTimeMillis() < deadline) {
            onMain { done = condition() }
            if (!done) Thread.sleep(25)
        }
        assertTrue("等待 Memory CAS 操作超时", done)
    }
}
