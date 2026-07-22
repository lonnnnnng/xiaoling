package com.longdev.xiaoling.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.longdev.xiaoling.system.ProcessExitEvidenceKind
import com.longdev.xiaoling.system.ProcessExitObservation
import com.longdev.xiaoling.system.RawProcessExitObservation
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ProcessExitObservationContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun emptyLedgerExplainsEvidenceBoundaryAndAllowsReadOnlyRefresh() {
        var refreshCount = 0
        composeRule.setContent {
            MaterialTheme {
                ProcessExitObservationContent(
                    observations = emptyList(),
                    loading = false,
                    error = null,
                    onBack = {},
                    onRefresh = { refreshCount += 1 },
                )
            }
        }

        composeRule.onNodeWithText("暂无进程退出记录").assertExists()
        composeRule.onNodeWithText("记录仅用于系统诊断，不关联 Agent Run、工作流或任务。", substring = true)
            .assertExists()
        composeRule.onNodeWithContentDescription("刷新进程退出记录").performClick()
        composeRule.runOnIdle { assertEquals(1, refreshCount) }
    }

    @Test
    fun ledgerSeparatesDirectLowMemoryEvidenceFromControlledExit() {
        composeRule.setContent {
            MaterialTheme {
                ProcessExitObservationContent(
                    observations = listOf(
                        observation(
                            pid = 101,
                            reasonName = "LOW_MEMORY",
                            evidenceKind = ProcessExitEvidenceKind.DIRECT_LOW_MEMORY,
                        ),
                        observation(
                            pid = 202,
                            reasonName = "USER_REQUESTED",
                            evidenceKind = ProcessExitEvidenceKind.CONTROLLED_OR_MAINTENANCE,
                        ),
                        observation(
                            pid = 303,
                            reasonName = "SIGNALED",
                            evidenceKind = ProcessExitEvidenceKind.LOW_MEMORY_CANDIDATE,
                        ),
                    ),
                    loading = false,
                    error = null,
                    onBack = {},
                    onRefresh = {},
                )
            }
        }

        composeRule.onNodeWithText("直接低内存回收证据").assertExists()
        composeRule.onNodeWithText("受控退出或包维护").assertExists()
        composeRule.onNodeWithText("低内存回收候选").assertExists()
        composeRule.onNodeWithText("LOW_MEMORY · PID 101 · status 0").assertExists()
        composeRule.onNodeWithText("USER_REQUESTED · PID 202 · status 0").assertExists()
        composeRule.onAllNodesWithText("com.longdev.xiaoling", useUnmergedTree = true).assertCountEquals(3)
    }

    @Test
    fun everyEvidenceKindHasAnExplicitUserFacingLabel() {
        val current = mutableStateOf(
            observation(
                pid = 1,
                reasonName = "LOW_MEMORY",
                evidenceKind = ProcessExitEvidenceKind.DIRECT_LOW_MEMORY,
            ),
        )
        composeRule.setContent {
            MaterialTheme {
                ProcessExitObservationContent(
                    observations = listOf(current.value),
                    loading = false,
                    error = null,
                    onBack = {},
                    onRefresh = {},
                )
            }
        }

        val labels = linkedMapOf(
            ProcessExitEvidenceKind.DIRECT_LOW_MEMORY to "直接低内存回收证据",
            ProcessExitEvidenceKind.LOW_MEMORY_CANDIDATE to "低内存回收候选",
            ProcessExitEvidenceKind.APP_FAILURE to "应用故障",
            ProcessExitEvidenceKind.SYSTEM_RESOURCE to "系统资源限制",
            ProcessExitEvidenceKind.CONTROLLED_OR_MAINTENANCE to "受控退出或包维护",
            ProcessExitEvidenceKind.UNATTRIBUTED to "未归因退出",
        )
        labels.forEach { (kind, label) ->
            composeRule.runOnIdle {
                current.value = observation(pid = kind.ordinal + 1, reasonName = kind.name, evidenceKind = kind)
            }
            composeRule.onNodeWithText(label).assertExists()
        }
    }

    private fun observation(
        pid: Int,
        reasonName: String,
        evidenceKind: ProcessExitEvidenceKind,
    ) = ProcessExitObservation(
        raw = RawProcessExitObservation(
            timestamp = 1_753_161_600_000L + pid,
            processName = "com.longdev.xiaoling",
            pid = pid,
            reasonCode = 0,
            status = 0,
            importance = 400,
            pssKb = 12_345L,
            rssKb = 23_456L,
        ),
        reasonName = reasonName,
        evidenceKind = evidenceKind,
        lowMemoryReportSupported = true,
        observedAt = 1_753_161_700_000L + pid,
    )
}
