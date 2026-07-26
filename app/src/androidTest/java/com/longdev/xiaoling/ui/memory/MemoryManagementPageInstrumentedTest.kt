package com.longdev.xiaoling.ui.memory

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.longdev.xiaoling.agent.AgentMemoryExpiryOption
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemoryRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MemoryManagementPageInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun pageRoutesMemoryActionsWithoutConcreteViewModel() {
        val selectedMemoryId = mutableStateOf<String?>(null)
        val actions = FakeMemoryManagementActions(
            onMemorySelected = { memoryId -> selectedMemoryId.value = memoryId },
        )
        var backCount = 0
        composeRule.setContent {
            MaterialTheme {
                MemoryManagementPage(
                    state = memoryManagementState(selectedMemoryId = selectedMemoryId.value),
                    actions = actions,
                    onBack = { backCount += 1 },
                )
            }
        }

        composeRule.onNodeWithContentDescription("刷新长期记忆").performClick()
        composeRule.onNodeWithText("搜索内容、标签、类型或来源").performTextInput("偏好")
        composeRule.onNodeWithText("已禁用").performClick()
        composeRule.onNodeWithTag("memory-management-item-memory-1").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("编辑记忆").performClick()
        composeRule.onNodeWithText("来源会话").performScrollTo().performClick()
        composeRule.onNodeWithText("来源 Run").performScrollTo().performClick()
        composeRule.onNodeWithContentDescription("返回设置").performClick()

        composeRule.runOnIdle {
            assertEquals(1, actions.refreshCount)
            assertEquals(listOf("偏好"), actions.searchQueries)
            assertEquals(listOf(AgentMemoryFilter.DISABLED), actions.filters)
            assertEquals(listOf("memory-1"), actions.selectedMemoryIds)
            assertEquals(listOf("memory-1"), actions.editedMemoryIds)
            assertEquals(listOf("memory-1"), actions.sourceConversationMemoryIds)
            assertEquals(listOf("memory-1"), actions.sourceRunMemoryIds)
            assertEquals(1, backCount)
        }
    }

    @Test
    fun emptyPageRefreshesMemoriesOnlyOnceAcrossRecomposition() {
        val state = mutableStateOf(MemoryManagementUiState())
        val actions = FakeMemoryManagementActions()
        composeRule.setContent {
            MaterialTheme {
                MemoryManagementPage(
                    state = state.value,
                    actions = actions,
                    onBack = {},
                )
            }
        }

        composeRule.runOnIdle {
            assertEquals(1, actions.refreshCount)
            state.value = state.value.copy(searchQuery = "偏好")
        }
        composeRule.runOnIdle {
            assertEquals(1, actions.refreshCount)
        }
    }

    private fun memoryManagementState(selectedMemoryId: String?): MemoryManagementUiState {
        val memory = AgentMemoryRecord(
            id = "memory-1",
            content = "偏好使用中文回答",
            tags = "语言,偏好",
            type = "Preference",
            sourceConversationId = "conversation-1",
            sourceRunId = "run-1",
            sourceSummary = "用户明确要求使用中文",
            confidence = 0.95,
            enabled = true,
            createdAt = 1L,
            updatedAt = 2L,
        )
        return MemoryManagementUiState(
            memories = listOf(
                MemoryManagementItemUiState(
                    record = memory,
                    selected = memory.id == selectedMemoryId,
                    mutating = false,
                ),
            ),
        )
    }

    private class FakeMemoryManagementActions(
        private val onMemorySelected: (String) -> Unit = {},
    ) : MemoryManagementActions {
        var refreshCount = 0
        val searchQueries = mutableListOf<String>()
        val filters = mutableListOf<AgentMemoryFilter>()
        val selectedMemoryIds = mutableListOf<String>()
        val editedMemoryIds = mutableListOf<String>()
        val sourceConversationMemoryIds = mutableListOf<String>()
        val sourceRunMemoryIds = mutableListOf<String>()

        override fun refreshMemories() {
            refreshCount += 1
        }

        override fun updateMemoryCandidatesEnabled(enabled: Boolean) = Unit

        override fun updateMemorySearchQuery(query: String) {
            searchQueries += query
        }

        override fun updateMemoryFilter(filter: AgentMemoryFilter) {
            filters += filter
        }

        override fun acceptMemoryCandidate(candidateId: String) = Unit

        override fun rejectMemoryCandidate(candidateId: String) = Unit

        override fun undoMemoryDelete() = Unit

        override fun selectMemory(memoryId: String) {
            selectedMemoryIds += memoryId
            onMemorySelected(memoryId)
        }

        override fun setMemoryPinned(memoryId: String, pinned: Boolean) = Unit

        override fun setMemoryEnabled(memoryId: String, enabled: Boolean) = Unit

        override fun setMemoryExpiry(memoryId: String, option: AgentMemoryExpiryOption) = Unit

        override fun openMemoryEdit(memoryId: String) {
            editedMemoryIds += memoryId
        }

        override fun requestMemoryDelete(memoryId: String) = Unit

        override fun openMemorySourceConversation(memoryId: String) {
            sourceConversationMemoryIds += memoryId
        }

        override fun openMemorySourceRun(memoryId: String) {
            sourceRunMemoryIds += memoryId
        }
    }
}
