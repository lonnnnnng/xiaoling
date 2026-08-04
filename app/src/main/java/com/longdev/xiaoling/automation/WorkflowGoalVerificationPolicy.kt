package com.longdev.xiaoling.automation

import com.longdev.xiaoling.device.DeviceActionPolicy
import org.json.JSONArray
import org.json.JSONObject

data class WorkflowGoalVerificationSpec(
    val requiredToolNames: List<String>,
    val expectedFinalPackageName: String? = null,
) {
    init {
        require(requiredToolNames.isNotEmpty()) { "任务完成标准至少需要一个工具事实" }
        require(requiredToolNames.none(String::isBlank)) { "任务完成标准不能包含空工具名" }
        require(
            expectedFinalPackageName == null || expectedFinalPackageName in DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES,
        ) { "任务完成标准的最终应用不在允许列表" }
    }
}

data class WorkflowGoalVerificationContract(
    val sourceGoal: String,
    val spec: WorkflowGoalVerificationSpec,
) {
    init {
        require(sourceGoal.isNotBlank()) { "任务完成标准缺少用户原始目标" }
    }
}

object WorkflowGoalVerificationContractCodec {
    const val SCHEMA = "workflow-goal-verification-contract-v1"

    fun encode(contract: WorkflowGoalVerificationContract): String = JSONObject()
        .put("schema", SCHEMA)
        .put("sourceGoal", contract.sourceGoal.trim())
        .put("requiredToolNames", JSONArray(contract.spec.requiredToolNames))
        .put("expectedFinalPackageName", contract.spec.expectedFinalPackageName.orEmpty())
        .toString()

    fun decode(raw: String?): WorkflowGoalVerificationContract? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            require(json.keys().asSequence().toSet() == CONTRACT_KEYS) { "任务完成标准字段不符合约定" }
            require(json.getString("schema") == SCHEMA) { "未知任务完成标准版本" }
            val toolsJson = json.getJSONArray("requiredToolNames")
            val requiredToolNames = buildList {
                repeat(toolsJson.length()) { index -> add(toolsJson.getString(index).trim()) }
            }
            WorkflowGoalVerificationContract(
                sourceGoal = json.getString("sourceGoal").trim(),
                spec = WorkflowGoalVerificationSpec(
                    requiredToolNames = requiredToolNames,
                    expectedFinalPackageName = json.getString("expectedFinalPackageName").trim().ifBlank { null },
                ),
            )
        }.getOrNull()
    }

    private val CONTRACT_KEYS = setOf(
        "schema",
        "sourceGoal",
        "requiredToolNames",
        "expectedFinalPackageName",
    )
}

object WorkflowGoalVerificationDecisionCodec {
    fun encode(decision: WorkflowGoalVerificationDecision): String = JSONObject()
        .put("schema", decision.ruleVersion)
        .put("sourceGoal", decision.sourceGoal)
        .put("status", decision.status.name)
        .put("reason", decision.reason.name)
        .put("requiredToolNames", JSONArray(decision.requiredToolNames))
        .put("matchedRequiredToolNames", JSONArray(decision.matchedRequiredToolNames))
        .put("expectedFinalPackageName", decision.expectedFinalPackageName.orEmpty())
        .put("actualFinalPackageName", decision.actualFinalPackageName.orEmpty())
        .put("completedStepCount", decision.completedStepCount)
        .put("totalStepCount", decision.totalStepCount)
        .toString()

    fun decode(raw: String?): WorkflowGoalVerificationDecision? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(raw)
            require(json.keys().asSequence().toSet() == DECISION_KEYS) { "目标级判定字段不符合约定" }
            require(json.getString("schema") == WorkflowGoalVerificationPolicy.RULE_VERSION) {
                "未知目标级判定版本"
            }
            val requiredToolNames = json.getJSONArray("requiredToolNames").toStringList()
            val matchedRequiredToolNames = json.getJSONArray("matchedRequiredToolNames").toStringList()
            require(requiredToolNames.take(matchedRequiredToolNames.size) == matchedRequiredToolNames) {
                "目标级判定的工具匹配前缀无效"
            }
            val completedStepCount = json.getInt("completedStepCount")
            val totalStepCount = json.getInt("totalStepCount")
            require(totalStepCount > 0 && completedStepCount in 0..totalStepCount) { "目标级判定步骤计数无效" }
            val decision = WorkflowGoalVerificationDecision(
                sourceGoal = json.getString("sourceGoal").trim().also { require(it.isNotEmpty()) },
                status = WorkflowGoalVerificationStatus.valueOf(json.getString("status")),
                reason = WorkflowGoalVerificationReason.valueOf(json.getString("reason")),
                requiredToolNames = requiredToolNames,
                matchedRequiredToolNames = matchedRequiredToolNames,
                expectedFinalPackageName = json.getString("expectedFinalPackageName").trim().ifBlank { null },
                actualFinalPackageName = json.getString("actualFinalPackageName").trim().ifBlank { null },
                completedStepCount = completedStepCount,
                totalStepCount = totalStepCount,
            )
            require(decision.hasConsistentConclusion()) { "目标级判定结论与证据摘要不一致" }
            decision
        }.getOrNull()
    }

    private fun WorkflowGoalVerificationDecision.hasConsistentConclusion(): Boolean {
        // long: Room 中的最终文案只能由这组摘要字段一致推导；损坏记录即使把 status 改成 VERIFIED，也不能绕过工具、步骤和最终应用三项约束。
        if (requiredToolNames.isEmpty()) return false
        val allStepsCompleted = completedStepCount == totalStepCount
        val allRequiredToolsMatched = matchedRequiredToolNames.size == requiredToolNames.size
        val finalPackageMatches = expectedFinalPackageName == null ||
            actualFinalPackageName == expectedFinalPackageName
        val hasProgress = completedStepCount > 0 ||
            matchedRequiredToolNames.isNotEmpty() ||
            actualFinalPackageName != null
        val expectedReason = when {
            !hasProgress -> WorkflowGoalVerificationReason.NO_VERIFIED_PROGRESS
            !allStepsCompleted -> WorkflowGoalVerificationReason.STEP_INCOMPLETE
            !allRequiredToolsMatched -> WorkflowGoalVerificationReason.REQUIRED_TOOL_MISSING_OR_OUT_OF_ORDER
            !finalPackageMatches -> WorkflowGoalVerificationReason.FINAL_PACKAGE_MISMATCH
            else -> WorkflowGoalVerificationReason.ALL_CRITERIA_VERIFIED
        }
        val expectedStatus = when (expectedReason) {
            WorkflowGoalVerificationReason.ALL_CRITERIA_VERIFIED -> WorkflowGoalVerificationStatus.VERIFIED
            WorkflowGoalVerificationReason.NO_VERIFIED_PROGRESS -> WorkflowGoalVerificationStatus.INCOMPLETE
            else -> WorkflowGoalVerificationStatus.PARTIAL
        }
        return reason == expectedReason && status == expectedStatus
    }

    private fun JSONArray.toStringList(): List<String> = buildList {
        repeat(length()) { index -> add(getString(index).trim().also { require(it.isNotEmpty()) }) }
    }

    private val DECISION_KEYS = setOf(
        "schema",
        "sourceGoal",
        "status",
        "reason",
        "requiredToolNames",
        "matchedRequiredToolNames",
        "expectedFinalPackageName",
        "actualFinalPackageName",
        "completedStepCount",
        "totalStepCount",
    )
}

data class WorkflowGoalVerificationStepEvidence(
    val status: WorkflowStepStatus,
    val verifiedToolNames: List<String>,
    val deviceObservationDecisions: List<WorkflowDeviceObservationDecision>,
    val deviceActionDecisions: List<WorkflowDeviceActionDecision>,
)

enum class WorkflowGoalVerificationStatus {
    VERIFIED,
    PARTIAL,
    INCOMPLETE,
}

enum class WorkflowGoalVerificationReason {
    ALL_CRITERIA_VERIFIED,
    STEP_INCOMPLETE,
    REQUIRED_TOOL_MISSING_OR_OUT_OF_ORDER,
    FINAL_PACKAGE_MISMATCH,
    NO_VERIFIED_PROGRESS,
}

data class WorkflowGoalVerificationDecision(
    val sourceGoal: String,
    val status: WorkflowGoalVerificationStatus,
    val reason: WorkflowGoalVerificationReason,
    val requiredToolNames: List<String>,
    val matchedRequiredToolNames: List<String>,
    val expectedFinalPackageName: String?,
    val actualFinalPackageName: String?,
    val completedStepCount: Int,
    val totalStepCount: Int,
    val ruleVersion: String = WorkflowGoalVerificationPolicy.RULE_VERSION,
) {
    fun renderForUser(): String = buildString {
        appendLine(
            when (status) {
                WorkflowGoalVerificationStatus.VERIFIED -> "任务目标已验证完成"
                WorkflowGoalVerificationStatus.PARTIAL -> "任务目标仅部分完成"
                WorkflowGoalVerificationStatus.INCOMPLETE -> "任务目标尚未完成"
            },
        )
        appendLine()
        appendLine("目标：$sourceGoal")
        appendLine("已验证步骤：$completedStepCount/$totalStepCount")
        appendLine("完成标准工具：${requiredToolNames.joinToString(" -> ")}")
        appendLine("已匹配工具：${matchedRequiredToolNames.joinToString(" -> ").ifBlank { "无" }}")
        expectedFinalPackageName?.let { expected ->
            appendLine("期望最终应用：$expected")
            appendLine("实际最终应用：${actualFinalPackageName ?: "无可用观察"}")
        }
        append("结论依据：${reason.toUserLabel()}")
    }

    private fun WorkflowGoalVerificationReason.toUserLabel(): String = when (this) {
        WorkflowGoalVerificationReason.ALL_CRITERIA_VERIFIED -> "全部确认标准均由已验证步骤和最终观察满足"
        WorkflowGoalVerificationReason.STEP_INCOMPLETE -> "仍有计划步骤没有完成"
        WorkflowGoalVerificationReason.REQUIRED_TOOL_MISSING_OR_OUT_OF_ORDER -> "必需工具事实缺失或顺序不符合确认标准"
        WorkflowGoalVerificationReason.FINAL_PACKAGE_MISMATCH -> "最终应用与确认标准不一致"
        WorkflowGoalVerificationReason.NO_VERIFIED_PROGRESS -> "没有可用于确认目标进度的已验证事实"
    }
}

object WorkflowGoalVerificationPolicy {
    const val RULE_VERSION = "workflow-goal-verification-v1"

    fun evaluate(
        sourceGoal: String,
        spec: WorkflowGoalVerificationSpec,
        steps: List<WorkflowGoalVerificationStepEvidence>,
    ): WorkflowGoalVerificationDecision {
        val normalizedGoal = sourceGoal.trim()
        require(normalizedGoal.isNotEmpty()) { "目标级验证缺少用户原始目标" }
        require(steps.isNotEmpty()) { "目标级验证缺少 Workflow 步骤" }

        // long: 关联重试的 SKIPPED 表示复用来源 Run 的成功前缀，但仍必须携带该步骤冻结的已验证工具；空壳跳过不能冒充已验证步骤。
        val completedStepCount = steps.count { step ->
            step.status in setOf(WorkflowStepStatus.COMPLETED, WorkflowStepStatus.SKIPPED) &&
                step.verifiedToolNames.isNotEmpty()
        }
        val verifiedToolNames = steps.flatMap(WorkflowGoalVerificationStepEvidence::verifiedToolNames)
        val matchedTools = matchRequiredTools(spec.requiredToolNames, verifiedToolNames)
        val actualFinalPackageName = latestObservedPackage(steps)
        val allStepsCompleted = completedStepCount == steps.size
        val allRequiredToolsMatched = matchedTools.size == spec.requiredToolNames.size
        val finalPackageMatches = spec.expectedFinalPackageName == null ||
            actualFinalPackageName == spec.expectedFinalPackageName

        val reason = when {
            !allStepsCompleted -> WorkflowGoalVerificationReason.STEP_INCOMPLETE
            !allRequiredToolsMatched -> WorkflowGoalVerificationReason.REQUIRED_TOOL_MISSING_OR_OUT_OF_ORDER
            !finalPackageMatches -> WorkflowGoalVerificationReason.FINAL_PACKAGE_MISMATCH
            else -> WorkflowGoalVerificationReason.ALL_CRITERIA_VERIFIED
        }
        val status = when {
            reason == WorkflowGoalVerificationReason.ALL_CRITERIA_VERIFIED -> WorkflowGoalVerificationStatus.VERIFIED
            completedStepCount > 0 || matchedTools.isNotEmpty() || actualFinalPackageName != null -> {
                WorkflowGoalVerificationStatus.PARTIAL
            }
            else -> WorkflowGoalVerificationStatus.INCOMPLETE
        }

        return WorkflowGoalVerificationDecision(
            sourceGoal = normalizedGoal,
            status = status,
            reason = reason.takeUnless {
                status == WorkflowGoalVerificationStatus.INCOMPLETE &&
                    completedStepCount == 0 &&
                    matchedTools.isEmpty() &&
                    actualFinalPackageName == null
            } ?: WorkflowGoalVerificationReason.NO_VERIFIED_PROGRESS,
            requiredToolNames = spec.requiredToolNames,
            matchedRequiredToolNames = matchedTools,
            expectedFinalPackageName = spec.expectedFinalPackageName,
            actualFinalPackageName = actualFinalPackageName,
            completedStepCount = completedStepCount,
            totalStepCount = steps.size,
        )
    }

    private fun matchRequiredTools(required: List<String>, actual: List<String>): List<String> {
        // long: 完成标准只约束必需工具的先后顺序，允许 snapshot 等辅助工具夹在中间；因此按子序列匹配而不是要求完整列表相等。
        var requiredIndex = 0
        actual.forEach { toolName ->
            if (requiredIndex < required.size && toolName == required[requiredIndex]) {
                requiredIndex += 1
            }
        }
        return required.take(requiredIndex)
    }

    private fun latestObservedPackage(steps: List<WorkflowGoalVerificationStepEvidence>): String? {
        data class PackageObservation(val packageName: String, val observedAt: Long)

        // long: 最终应用以时间最新的 snapshot 或动作后观察为准，不能按步骤列表位置猜测，也不能让旧观察覆盖新动作结果。
        return steps.flatMap { step ->
            step.deviceObservationDecisions.map { decision ->
                PackageObservation(decision.packageName, decision.capturedAt)
            } + step.deviceActionDecisions.map { decision ->
                PackageObservation(decision.afterPackageName, decision.afterObservedAt)
            }
        }.maxByOrNull(PackageObservation::observedAt)?.packageName
    }
}
