package com.longdev.xiaoling.ui.workflow

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.longdev.xiaoling.automation.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorkflowEditorContractInstrumentedTest {
    @get:Rule val rule = createComposeRule()

    @Test fun goalTextDoesNotGrantScopeAndExplicitCriteriaReachSave() {
        var saved: WorkflowEditorInput? = null
        rule.setContent { MaterialTheme { WorkflowEditorDialog(null, { saved = it }, {}) } }
        rule.onNodeWithTag("workflow-editor-name").performTextInput("公开编辑器")
        rule.onNodeWithTag("workflow-editor-step-0").performScrollTo().performTextInput("device.open_app com.android.settings")
        rule.onNodeWithTag("workflow-editor-save").performClick()
        rule.runOnIdle { assertNull(saved!!.confirmation.targetAppPackage); assertNull(saved!!.confirmation.goalVerificationContract); saved = null }
        rule.onNodeWithTag("workflow-editor-app").performScrollTo().performClick()
        rule.onNodeWithText("系统设置").performClick()
        rule.onNodeWithTag("workflow-editor-verify").performScrollTo().performClick()
        rule.onNodeWithTag("workflow-editor-completion-goal").performScrollTo().performTextInput("打开并复查系统设置")
        rule.onNodeWithTag("workflow-editor-final-app").performScrollTo().performClick()
        rule.onNodeWithTag("workflow-editor-criterion-0").performScrollTo().performClick()
        rule.onNodeWithText("打开限定应用并复查页面").performClick()
        rule.onNodeWithTag("workflow-editor-save").assertIsNotEnabled()
        rule.onNodeWithTag("workflow-editor-confirm-boundary").performScrollTo().performClick()
        rule.onNodeWithTag("workflow-editor-save").assertIsEnabled().performClick()
        rule.runOnIdle {
            assertEquals("com.android.settings", saved!!.confirmation.targetAppPackage)
            val contract = saved!!.confirmation.goalVerificationContract!!
            assertEquals("打开并复查系统设置", contract.sourceGoal)
            assertEquals("com.android.settings", contract.spec.expectedFinalPackageName)
            assertEquals(listOf(listOf("device.snapshot", "device.open_app", "device.snapshot")), contract.spec.requiredToolNamesByStep)
        }
    }

    @Test fun reorderingMovesCriteriaAndEditingRevokesBoundaryConfirmation() {
        val groups = listOf(listOf("app.current_time"), listOf("app.get_info"))
        val original = WorkflowGoalVerificationContract("读取时间和版本", WorkflowGoalVerificationSpec(groups.flatten(), requiredToolNamesByStep = groups))
        val workflow = WorkflowItemUiState("test", "已保存", true, "时间", listOf("时间", "版本"),
            goalVerificationContract = original, latestRun = null, runs = emptyList(), scheduledTasks = emptyList(), schedule = null,
            scheduling = false, running = false, canEdit = true, canRun = true, canSchedule = true, canToggleEnabled = true)
        var saved: WorkflowEditorInput? = null
        rule.setContent { MaterialTheme { WorkflowEditorDialog(workflow, { saved = it }, {}) } }
        rule.onAllNodesWithContentDescription("步骤上移")[1].performScrollTo().performClick()
        rule.onNodeWithTag("workflow-editor-confirm-boundary").performScrollTo().performClick()
        rule.onNodeWithTag("workflow-editor-save").assertIsEnabled()
        rule.onNodeWithTag("workflow-editor-step-0").performScrollTo().performTextReplacement("新版本目标")
        rule.onNodeWithTag("workflow-editor-save").assertIsNotEnabled()
        rule.onNodeWithTag("workflow-editor-confirm-boundary").performScrollTo().performClick()
        rule.onNodeWithTag("workflow-editor-save").performClick()
        rule.runOnIdle {
            assertEquals(listOf("新版本目标", "时间"), saved!!.stepGoals)
            assertEquals(groups.reversed(), saved!!.confirmation.goalVerificationContract!!.spec.requiredToolNamesByStep)
            assertEquals(groups, original.spec.requiredToolNamesByStep)
        }
    }

    @Test fun unsupportedStoredCriteriaCannotSilentlyBecomeLegacyCompletion() {
        val original = WorkflowGoalVerificationContract("旧扩展标准", WorkflowGoalVerificationSpec(listOf("workspace.read")))
        val workflow = WorkflowItemUiState("test", "已保存", true, "读文件", listOf("读文件"),
            goalVerificationContract = original, latestRun = null, runs = emptyList(), scheduledTasks = emptyList(), schedule = null,
            scheduling = false, running = false, canEdit = true, canRun = true, canSchedule = true, canToggleEnabled = true)
        rule.setContent { MaterialTheme { WorkflowEditorDialog(workflow, {}, {}) } }
        rule.onNodeWithTag("workflow-editor-confirm-boundary").performScrollTo().performClick()
        rule.onNodeWithTag("workflow-editor-save").assertIsNotEnabled()
        rule.onNodeWithTag("workflow-editor-verify").performScrollTo().performClick()
        rule.onNodeWithTag("workflow-editor-save").assertIsNotEnabled()
        rule.onNodeWithTag("workflow-editor-confirm-boundary").performScrollTo().performClick()
        rule.onNodeWithTag("workflow-editor-save").assertIsEnabled()
    }
}
