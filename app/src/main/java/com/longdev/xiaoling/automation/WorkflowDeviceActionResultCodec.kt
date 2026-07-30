package com.longdev.xiaoling.automation

import com.longdev.xiaoling.device.DeviceSnapshot
import org.json.JSONObject

data class WorkflowDeviceActionResultEvidence(
    val action: String,
    val beforePackageName: String,
    val afterPackageName: String,
    val afterNodeCount: Int,
    val afterRedactedNodeCount: Int,
    val afterTruncated: Boolean,
    val afterObservedAt: Long,
    val verified: Boolean,
    val ruleVersion: String = WorkflowDeviceActionResultCodec.RULE_VERSION,
    val safetyRuleVersion: String = WorkflowDeviceActionSafetyPolicy.RULE_VERSION,
)

object WorkflowDeviceActionResultCodec {
    const val RULE_VERSION = "workflow-device-action-result-v1"

    fun encode(
        action: String,
        beforeSnapshot: DeviceSnapshot,
        afterSnapshot: DeviceSnapshot,
        verified: Boolean,
    ): String {
        return JSONObject()
            .put("ruleVersion", RULE_VERSION)
            .put("safetyRuleVersion", WorkflowDeviceActionSafetyPolicy.RULE_VERSION)
            .put("action", action)
            .put("beforePackageName", beforeSnapshot.packageName)
            .put("afterPackageName", afterSnapshot.packageName)
            .put("afterNodeCount", afterSnapshot.nodes.size)
            .put("afterRedactedNodeCount", afterSnapshot.redactedNodeCount)
            .put("afterTruncated", afterSnapshot.truncated)
            .put("afterObservedAt", afterSnapshot.capturedAt)
            .put("verified", verified)
            .toString()
    }

    fun decode(value: String): WorkflowDeviceActionResultEvidence? = runCatching {
        val json = JSONObject(value)
        val keys = buildSet {
            val iterator = json.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        require(keys == ALLOWED_KEYS) { "Workflow 设备动作结果包含未授权字段" }
        val ruleVersion = json.getString("ruleVersion")
        val safetyRuleVersion = json.getString("safetyRuleVersion")
        val action = json.getString("action").trim()
        val beforePackageName = json.getString("beforePackageName").trim()
        val afterPackageName = json.getString("afterPackageName").trim()
        val afterNodeCount = json.getInt("afterNodeCount")
        val afterRedactedNodeCount = json.getInt("afterRedactedNodeCount")
        val afterObservedAt = json.getLong("afterObservedAt")
        require(ruleVersion == RULE_VERSION) { "未知 Workflow 设备动作结果规则：$ruleVersion" }
        require(safetyRuleVersion == WorkflowDeviceActionSafetyPolicy.RULE_VERSION) {
            "未知 Workflow 设备动作安全规则：$safetyRuleVersion"
        }
        require(action == "tap_ref") { "当前阶段只接受 tap_ref 结果" }
        require(beforePackageName.isNotEmpty() && afterPackageName.isNotEmpty()) { "设备动作包名不能为空" }
        require(afterNodeCount >= 0) { "设备动作后节点数无效" }
        require(afterRedactedNodeCount in 0..afterNodeCount) { "设备动作后脱敏节点数无效" }
        require(afterObservedAt >= 0L) { "设备动作后观察时间无效" }
        WorkflowDeviceActionResultEvidence(
            action = action,
            beforePackageName = beforePackageName,
            afterPackageName = afterPackageName,
            afterNodeCount = afterNodeCount,
            afterRedactedNodeCount = afterRedactedNodeCount,
            afterTruncated = json.getBoolean("afterTruncated"),
            afterObservedAt = afterObservedAt,
            verified = json.getBoolean("verified"),
            ruleVersion = ruleVersion,
            safetyRuleVersion = safetyRuleVersion,
        )
    }.getOrNull()

    private val ALLOWED_KEYS = setOf(
        "ruleVersion",
        "safetyRuleVersion",
        "action",
        "beforePackageName",
        "afterPackageName",
        "afterNodeCount",
        "afterRedactedNodeCount",
        "afterTruncated",
        "afterObservedAt",
        "verified",
    )
}
