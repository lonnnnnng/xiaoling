package com.longdev.xiaoling.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class AgentRunRestartDispositionGuidanceInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun restartRequiredGuidanceShowsReasonEvidenceBoundaryAndNextAction() {
        composeRule.setContent {
            MaterialTheme {
                AgentRunRestartDispositionGuidance(
                    disposition = AgentRunRestartDispositionPresentation(
                        kind = "RESTART_REQUIRED",
                        code = "RECOVERY_EVIDENCE_INVALID",
                        reason = "工具账本与事件不一致",
                        evidenceBoundary = "不能证明历史副作用边界",
                        suggestedAction = "保留旧 Run 并创建关联新 Run",
                    ),
                )
            }
        }

        composeRule.onNodeWithText("恢复处置 · RESTART_REQUIRED").assertExists()
        composeRule.onNodeWithText("RECOVERY_EVIDENCE_INVALID").assertExists()
        composeRule.onNodeWithText("工具账本与事件不一致").assertExists()
        composeRule.onNodeWithText("证据边界：不能证明历史副作用边界").assertExists()
        composeRule.onNodeWithText("建议：保留旧 Run 并创建关联新 Run").assertExists()
    }
}
