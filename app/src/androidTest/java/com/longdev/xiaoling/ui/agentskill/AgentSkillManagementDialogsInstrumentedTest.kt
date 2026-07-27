package com.longdev.xiaoling.ui.agentskill

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.agent.AgentSkillDefinition
import com.longdev.xiaoling.agent.AgentSkillRecord
import com.longdev.xiaoling.agent.AgentSkillSource
import com.longdev.xiaoling.agent.ToolRisk
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AgentSkillManagementDialogsInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun localSkillDeleteRoutesConfirmAndCancelByProjectedIdentity() {
        var confirmCount = 0
        var cancelCount = 0
        var state by mutableStateOf(AgentSkillManagementUiState(pendingLocalSkillDelete = localSkill()))
        composeRule.setContent {
            MaterialTheme {
                AgentSkillManagementDialogs(
                    state = state,
                    onConfirmLocalSkillDelete = {
                        confirmCount += 1
                        state = state.copy(deletingLocalSkill = true)
                    },
                    onCancelLocalSkillDelete = {
                        cancelCount += 1
                        state = state.copy(pendingLocalSkillDelete = null)
                    },
                )
            }
        }

        composeRule.onNodeWithText("本地日报 Skill").assertExists()
        composeRule.onNodeWithTag("skill-delete-confirm").performClick()
        composeRule.onNodeWithTag("skill-delete-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("skill-delete-cancel").assertIsNotEnabled()

        composeRule.runOnIdle {
            state = AgentSkillManagementUiState(pendingLocalSkillDelete = localSkill())
        }
        composeRule.onNodeWithTag("skill-delete-cancel").performClick()
        composeRule.onNodeWithTag("skill-delete-cancel").assertDoesNotExist()

        composeRule.runOnIdle {
            assertEquals(1, confirmCount)
            assertEquals(1, cancelCount)
        }
    }

    @Test
    fun deletingLocalSkillDisablesBothDecisions() {
        composeRule.setContent {
            MaterialTheme {
                AgentSkillManagementDialogs(
                    state = AgentSkillManagementUiState(
                        pendingLocalSkillDelete = localSkill(),
                        deletingLocalSkill = true,
                    ),
                    onConfirmLocalSkillDelete = {},
                    onCancelLocalSkillDelete = {},
                )
            }
        }

        composeRule.onNodeWithTag("skill-delete-confirm").assertIsNotEnabled()
        composeRule.onNodeWithTag("skill-delete-cancel").assertIsNotEnabled()
        composeRule.onNodeWithText("删除中").assertExists()
    }

    private fun localSkill() = AgentSkillRecord(
        definition = AgentSkillDefinition(
            id = "local-daily",
            name = "本地日报 Skill",
            description = "生成本地日报",
            instructions = "整理当天执行记录",
            toolNames = emptySet(),
            keywords = setOf("日报"),
            declaredRisk = ToolRisk.SAFE,
            source = AgentSkillSource.LOCAL,
        ),
        enabled = true,
        importedAt = 1L,
        updatedAt = 2L,
    )
}
