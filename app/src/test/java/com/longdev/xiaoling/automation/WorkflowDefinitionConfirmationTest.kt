package com.longdev.xiaoling.automation

import com.longdev.xiaoling.device.DeviceActionPolicy
import com.longdev.xiaoling.ui.workflow.workflowEditorApplications
import org.junit.Assert.*
import org.junit.Test

class WorkflowDefinitionConfirmationTest {
    private fun contract(groups: List<List<String>>, final: String? = null) = WorkflowGoalVerificationContract(
        "用户确认的完成目标", WorkflowGoalVerificationSpec(groups.flatten(), final, groups),
    )

    @Test fun actionCannotAcquireScopeFromGoalTextOrFinalPackage() {
        val action = contract(listOf(listOf("device.open_app")))
        assertTrue(runCatching { WorkflowDefinitionConfirmation(null, action).validate(1) }.isFailure)
        val final = contract(listOf(listOf("device.snapshot")), "com.android.settings")
        assertTrue(runCatching { WorkflowDefinitionConfirmation(null, final).validate(1) }.isFailure)
        WorkflowDefinitionConfirmation("com.android.settings", action).validate(1)
    }

    @Test fun wrongStepCountAndConflictingFinalApplicationAreRejected() {
        val twoSteps = contract(listOf(listOf("app.current_time"), listOf("app.get_info")))
        assertTrue(runCatching { WorkflowDefinitionConfirmation(null, twoSteps).validate(1) }.isFailure)
        val mismatch = contract(listOf(listOf("device.snapshot")), "com.android.deskclock")
        assertTrue(runCatching { WorkflowDefinitionConfirmation("com.android.settings", mismatch).validate(1) }.isFailure)
    }

    @Test fun readOnlyAndLegacyContractsRemainUsableAndEquivalentAppIsAccepted() {
        WorkflowDefinitionConfirmation(null, contract(listOf(listOf("app.current_time")))).validate(1)
        WorkflowDefinitionConfirmation(null, WorkflowGoalVerificationContract("旧只读标准", WorkflowGoalVerificationSpec(listOf("app.current_time")))).validate(2)
        WorkflowDefinitionConfirmation("com.android.calculator2", contract(listOf(listOf("device.snapshot")), "com.google.android.calculator")).validate(1)
    }

    @Test fun unsupportedPackageAndOversizedGoalFailBeforeSaving() {
        assertTrue(runCatching { WorkflowDefinitionConfirmation("not.allowed").validate(1) }.isFailure)
        val tooLong = WorkflowGoalVerificationContract("x".repeat(WorkflowDefinitionPolicy.MAX_GOAL_LENGTH + 1), WorkflowGoalVerificationSpec(listOf("app.current_time")))
        assertTrue(runCatching { WorkflowDefinitionConfirmation(null, tooLong).validate(1) }.isFailure)
        assertEquals(DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES, workflowEditorApplications.keys)
    }
}
