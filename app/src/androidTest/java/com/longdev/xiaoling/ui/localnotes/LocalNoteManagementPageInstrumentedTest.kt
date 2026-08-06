package com.longdev.xiaoling.ui.localnotes

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.longdev.xiaoling.agent.AgentNoteRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class LocalNoteManagementPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun listSearchRefreshAndBackDelegateActions() {
        val actions = FakeLocalNoteManagementActions()
        var backCount = 0
        composeRule.setContent {
            MaterialTheme {
                LocalNoteManagementContent(
                    state = LocalNoteManagementUiState(
                        notes = listOf(note()),
                        searchQuery = "初始",
                    ),
                    actions = actions,
                    onBack = { backCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("本地笔记").assertIsDisplayed()
        composeRule.onNodeWithText("标题 A").performClick()
        composeRule.onNodeWithText("搜索笔记").performTextInput("关键词")
        composeRule.onNodeWithContentDescription("搜索本地笔记").performClick()
        composeRule.onNodeWithContentDescription("清空搜索").performClick()
        composeRule.onNodeWithContentDescription("刷新本地笔记").performClick()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle {
            assertEquals("note-a", actions.selectedNoteId)
            assertTrue(actions.latestQuery.contains("关键词"))
            assertEquals(1, actions.searchCount)
            assertEquals(1, actions.clearCount)
            assertEquals(1, actions.refreshCount)
            assertEquals(1, backCount)
        }
    }

    @Test
    fun selectedNoteRequiresDeleteConfirmationAndCanStillClose() {
        val actions = FakeLocalNoteManagementActions()
        var state by mutableStateOf(
            LocalNoteManagementUiState(
                selectedNoteId = "note-a",
                loadingDetail = true,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                LocalNoteManagementContent(
                    state = state,
                    actions = actions,
                    onBack = {},
                )
            }
        }

        composeRule.onNodeWithText("正在读取完整正文...").assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(selectedNote = note(), loadingDetail = false)
        }
        composeRule.onNodeWithText("这是完整正文，不是列表摘要。").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("删除笔记").performClick()
        composeRule.runOnIdle {
            assertEquals("note-a", actions.requestedDeleteNoteId)
            state = state.copy(pendingDeleteNote = note())
        }
        composeRule.onNodeWithText("删除本地笔记").assertIsDisplayed()
        composeRule.runOnIdle {
            state = state.copy(deleting = true)
        }
        composeRule.onNodeWithText("删除中").assertIsNotEnabled()
        composeRule.onNodeWithText("取消").assertIsNotEnabled()
        composeRule.runOnIdle {
            state = state.copy(deleting = false)
        }
        composeRule.onNodeWithText("确认删除").performClick()
        composeRule.runOnIdle {
            assertEquals(1, actions.confirmDeleteCount)
            state = state.copy(pendingDeleteNote = null)
        }
        composeRule.onNodeWithText("关闭").performClick()

        composeRule.runOnIdle {
            assertEquals(1, actions.closeCount)
        }
    }

    private class FakeLocalNoteManagementActions : LocalNoteManagementActions {
        var refreshCount = 0
        var latestQuery = ""
        var searchCount = 0
        var clearCount = 0
        var selectedNoteId: String? = null
        var closeCount = 0
        var requestedDeleteNoteId: String? = null
        var cancelDeleteCount = 0
        var confirmDeleteCount = 0

        override fun refresh() {
            refreshCount += 1
        }

        override fun updateSearchQuery(value: String) {
            latestQuery = value
        }

        override fun search() {
            searchCount += 1
        }

        override fun clearSearch() {
            clearCount += 1
        }

        override fun selectNote(noteId: String) {
            selectedNoteId = noteId
        }

        override fun closeDetail() {
            closeCount += 1
        }

        override fun requestDelete(noteId: String) {
            requestedDeleteNoteId = noteId
        }

        override fun cancelDelete() {
            cancelDeleteCount += 1
        }

        override fun confirmDelete() {
            confirmDeleteCount += 1
        }
    }

    private companion object {
        fun note() = AgentNoteRecord(
            id = "note-a",
            title = "标题 A",
            content = "这是完整正文，不是列表摘要。",
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_100_000L,
        )
    }
}
