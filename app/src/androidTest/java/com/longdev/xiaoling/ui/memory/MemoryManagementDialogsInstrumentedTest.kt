package com.longdev.xiaoling.ui.memory

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.longdev.xiaoling.agent.AgentMemoryExpiryOption
import com.longdev.xiaoling.agent.AgentMemoryFilter
import com.longdev.xiaoling.agent.AgentMemoryRecord
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class MemoryManagementDialogsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun editDialogRoutesDraftChangesSaveAndCancelThroughFeatureActions() {
        var state by mutableStateOf(
            MemoryManagementUiState(
                editingMemory = AgentMemoryEditUiState(
                    id = "memory-1",
                    content = "偏好中文回答",
                    tags = "语言",
                    type = "Preference",
                    confidence = 0.8,
                ),
            ),
        )
        val actions = FakeMemoryManagementActions(
            onContentUpdated = { value ->
                state = state.copy(editingMemory = state.editingMemory?.copy(content = value))
            },
            onTagsUpdated = { value ->
                state = state.copy(editingMemory = state.editingMemory?.copy(tags = value))
            },
            onTypeUpdated = { value ->
                state = state.copy(editingMemory = state.editingMemory?.copy(type = value))
            },
            onSaved = { state = state.copy(savingMemoryEdit = true) },
            onEditCancelled = { state = state.copy(editingMemory = null) },
        )
        composeRule.setContent {
            MaterialTheme {
                MemoryManagementDialogs(
                    state = state,
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithTag("memory-edit-content").performTextReplacement("偏好简洁中文回答")
        composeRule.onNodeWithTag("memory-edit-tags").performTextReplacement("语言,风格")
        composeRule.onNodeWithTag("memory-edit-type-ProfileFact").performClick()
        composeRule.onNodeWithTag("memory-edit-save").performClick()
        composeRule.onNodeWithTag("memory-edit-cancel").assertIsNotEnabled()

        composeRule.runOnIdle {
            state = state.copy(savingMemoryEdit = false)
        }
        composeRule.onNodeWithTag("memory-edit-cancel").performClick()
        composeRule.onNodeWithTag("memory-edit-cancel").assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(listOf("偏好简洁中文回答"), actions.contents)
            assertEquals(listOf("语言,风格"), actions.tags)
            assertEquals(listOf("ProfileFact"), actions.types)
            assertEquals(null, state.editingMemory)
            assertEquals(1, actions.saveCount)
            assertEquals(1, actions.cancelEditCount)
        }
    }

    @Test
    fun savingEditDisablesSaveCancelAndTypeChange() {
        composeRule.setContent {
            MaterialTheme {
                MemoryManagementDialogs(
                    state = MemoryManagementUiState(
                        editingMemory = AgentMemoryEditUiState(
                            id = "memory-1",
                            content = "偏好中文回答",
                            tags = "语言",
                            type = "Preference",
                            confidence = 0.8,
                        ),
                        savingMemoryEdit = true,
                    ),
                    actions = FakeMemoryManagementActions(),
                )
            }
        }

        composeRule.onNodeWithTag("memory-edit-save").assertIsNotEnabled()
        composeRule.onNodeWithTag("memory-edit-cancel").assertIsNotEnabled()
        composeRule.onNodeWithTag("memory-edit-type-ProfileFact").assertIsNotEnabled()
    }

    @Test
    fun deleteDialogRoutesDecisionAndBusyStateDisablesActions() {
        var state by mutableStateOf(MemoryManagementUiState(pendingMemoryDelete = memory()))
        val actions = FakeMemoryManagementActions(
            onDeleteConfirmed = { state = state.copy(deletingMemory = true) },
            onDeleteCancelled = { state = state.copy(pendingMemoryDelete = null) },
        )
        composeRule.setContent {
            MaterialTheme {
                MemoryManagementDialogs(
                    state = state,
                    actions = actions,
                )
            }
        }

        composeRule.onNodeWithText("偏好中文回答").assertExists()
        composeRule.onNodeWithTag("memory-delete-confirm").performClick()
        composeRule.onNodeWithTag("memory-delete-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("memory-delete-cancel").assertIsNotEnabled()

        composeRule.runOnIdle {
            state = MemoryManagementUiState(pendingMemoryDelete = memory())
        }
        composeRule.onNodeWithTag("memory-delete-cancel").performClick()
        composeRule.onNodeWithTag("memory-delete-cancel").assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(1, actions.confirmDeleteCount)
            assertEquals(1, actions.cancelDeleteCount)
        }
    }

    private fun memory() = AgentMemoryRecord(
        id = "memory-1",
        content = "偏好中文回答",
        tags = "语言",
        type = "Preference",
        sourceConversationId = "conversation-1",
        sourceRunId = "run-1",
        sourceSummary = "用户明确要求",
        confidence = 0.8,
        enabled = true,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private class FakeMemoryManagementActions(
        private val onContentUpdated: (String) -> Unit = {},
        private val onTagsUpdated: (String) -> Unit = {},
        private val onTypeUpdated: (String) -> Unit = {},
        private val onSaved: () -> Unit = {},
        private val onEditCancelled: () -> Unit = {},
        private val onDeleteConfirmed: () -> Unit = {},
        private val onDeleteCancelled: () -> Unit = {},
    ) : MemoryManagementActions {
        val contents = mutableListOf<String>()
        val tags = mutableListOf<String>()
        val types = mutableListOf<String>()
        var saveCount = 0
        var cancelEditCount = 0
        var confirmDeleteCount = 0
        var cancelDeleteCount = 0

        override fun refreshMemories() = Unit

        override fun updateMemoryCandidatesEnabled(enabled: Boolean) = Unit

        override fun updateMemorySearchQuery(query: String) = Unit

        override fun updateMemoryFilter(filter: AgentMemoryFilter) = Unit

        override fun acceptMemoryCandidate(candidateId: String) = Unit

        override fun rejectMemoryCandidate(candidateId: String) = Unit

        override fun undoMemoryDelete() = Unit

        override fun selectMemory(memoryId: String) = Unit

        override fun setMemoryPinned(memoryId: String, pinned: Boolean) = Unit

        override fun setMemoryEnabled(memoryId: String, enabled: Boolean) = Unit

        override fun setMemoryExpiry(memoryId: String, option: AgentMemoryExpiryOption) = Unit

        override fun openMemoryEdit(memoryId: String) = Unit

        override fun updateMemoryEditContent(value: String) {
            contents += value
            onContentUpdated(value)
        }

        override fun updateMemoryEditTags(value: String) {
            tags += value
            onTagsUpdated(value)
        }

        override fun updateMemoryEditType(value: String) {
            types += value
            onTypeUpdated(value)
        }

        override fun updateMemoryEditConfidence(value: Double) = Unit

        override fun saveMemoryEdit() {
            saveCount += 1
            onSaved()
        }

        override fun cancelMemoryEdit() {
            cancelEditCount += 1
            onEditCancelled()
        }

        override fun requestMemoryDelete(memoryId: String) = Unit

        override fun confirmMemoryDelete() {
            confirmDeleteCount += 1
            onDeleteConfirmed()
        }

        override fun cancelMemoryDelete() {
            cancelDeleteCount += 1
            onDeleteCancelled()
        }

        override fun openMemorySourceConversation(memoryId: String) = Unit

        override fun openMemorySourceRun(memoryId: String) = Unit
    }
}
