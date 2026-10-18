package com.longdev.xiaoling.automation

import com.longdev.xiaoling.device.DeviceActionPolicy

data class WorkflowDefinitionConfirmation(
    val targetAppPackage: String? = null,
    val goalVerificationContract: WorkflowGoalVerificationContract? = null,
) {
    fun validate(stepCount: Int) {
        require(targetAppPackage == null || targetAppPackage in DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES) {
            "工作流限定应用不在允许列表"
        }
        goalVerificationContract?.let { contract ->
            val groups = contract.spec.requiredToolNamesByStep
            // long: 保存时拒绝错位的每步标准，避免直到用户运行后才发现无法满足冻结契约。
            require(groups.isEmpty() || groups.size == stepCount) { "每步完成标准与工作流步骤数量不一致" }
            require(contract.sourceGoal.length <= WorkflowDefinitionPolicy.MAX_GOAL_LENGTH) { "工作流完成目标过长" }
            require(contract.spec.expectedFinalPackageName == null ||
                DeviceActionPolicy.areEquivalentAppPackages(targetAppPackage, contract.spec.expectedFinalPackageName)) {
                "完成时应用必须属于已确认的限定应用"
            }
            require(targetAppPackage != null || contract.spec.requiredToolNames.none {
                it.startsWith("device.") && it != "device.snapshot"
            }) { "设备动作完成标准需要先确认限定应用" }
        }
    }
}
