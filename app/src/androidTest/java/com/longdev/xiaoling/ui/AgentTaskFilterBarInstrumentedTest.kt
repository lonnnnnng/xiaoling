package com.longdev.xiaoling.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class AgentTaskFilterBarInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun confirmationQueueIsVisibleAndSelectableWithoutRemovingExistingFilters() {
        val selected = AtomicReference<AgentTaskFilter?>(null)
        composeRule.setContent {
            MaterialTheme {
                AgentTaskFilterBar(
                    selected = AgentTaskFilter.ALL,
                    onSelected = selected::set,
                )
            }
        }

        listOf("全部", "需确认", "处理中", "可重试", "已完成").forEach { label ->
            composeRule.onNodeWithText(label).assertExists()
        }
        composeRule.onNodeWithText("需确认").performClick()
        composeRule.runOnIdle {
            assertEquals(AgentTaskFilter.NEEDS_CONFIRMATION, selected.get())
        }
    }
}
