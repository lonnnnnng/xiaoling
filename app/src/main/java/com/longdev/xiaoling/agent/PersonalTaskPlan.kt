package com.longdev.xiaoling.agent

import com.longdev.xiaoling.automation.WorkflowDefinitionPolicy
import com.longdev.xiaoling.automation.WorkflowStepDefinitionInput
import com.longdev.xiaoling.device.DeviceActionPolicy
import com.longdev.xiaoling.network.LlmStructuredOutputFormat
import com.longdev.xiaoling.network.RequestMessage
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

data class PersonalTaskPlanStep(
    val goal: String,
)

data class PersonalTaskPlan(
    val name: String,
    val targetAppPackage: String?,
    val steps: List<PersonalTaskPlanStep>,
)

object PersonalTaskPlanPolicy {
    val outputFormat: LlmStructuredOutputFormat by lazy {
        LlmStructuredOutputFormat(
            name = "personal_task_plan",
            schema = JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("name", JSONObject().put("type", "string"))
                        .put(
                            "target_app_package",
                            JSONObject()
                                .put("type", "string")
                                .put(
                                    "enum",
                                    JSONArray(
                                        listOf("") + DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES.sorted(),
                                    ),
                                ),
                        )
                        .put(
                            "steps",
                            JSONObject()
                                .put("type", "array")
                                .put("minItems", 1)
                                .put("maxItems", WorkflowDefinitionPolicy.MAX_STEPS)
                                .put(
                                    "items",
                                    JSONObject()
                                        .put("type", "object")
                                        .put(
                                            "properties",
                                            JSONObject().put("goal", JSONObject().put("type", "string")),
                                        )
                                        .put("required", JSONArray().put("goal"))
                                        .put("additionalProperties", false),
                                ),
                        ),
                )
                .put("required", JSONArray().put("name").put("target_app_package").put("steps"))
                .put("additionalProperties", false),
        )
    }

    fun requestMessages(
        goal: String,
        allowedToolNames: List<String>,
        allowedAppPackages: List<String> = DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES.sorted(),
    ): List<RequestMessage> {
        val normalizedGoal = goal.trim()
        require(normalizedGoal.isNotEmpty()) { "个人任务目标不能为空" }
        val toolBoundary = allowedToolNames
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .joinToString()
            .ifBlank { "无" }
        val appBoundary = allowedAppPackages
            .map(String::trim)
            .filter { packageName -> packageName in DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES }
            .distinct()
            .sorted()
            .joinToString()
            .ifBlank { "无" }
        return listOf(
            RequestMessage(
                role = "system",
                content = """
                    你负责把用户目标拆成可确认的临时计划。你不能执行工具、不能声称任务已完成，也不能扩大给定工具边界。
                    只返回符合 JSON Schema 的对象。name 是简短任务名；target_app_package 是整份任务唯一允许操作的应用包名，不需要设备操作时必须返回空字符串；steps 是按执行顺序排列的 1 至 ${WorkflowDefinitionPolicy.MAX_STEPS} 个独立 Agent 目标。
                    每一步只描述可验证的业务目标，不写工具调用 JSON，不包含审批已通过或结果已产生等虚假事实。
                """.trimIndent(),
            ),
            RequestMessage(
                role = "user",
                content = buildString {
                    appendLine("用户目标：$normalizedGoal")
                    appendLine("当前 Agent 允许的工具：$toolBoundary")
                    append("当前任务可选择的目标应用：$appBoundary")
                },
            ),
        )
    }

    fun parse(raw: String): PersonalTaskPlan = try {
        parseStrict(raw)
    } catch (error: IllegalArgumentException) {
        throw error
    } catch (error: Throwable) {
        throw IllegalArgumentException("任务计划 JSON 不符合约定", error)
    }

    private fun parseStrict(raw: String): PersonalTaskPlan {
        val tokener = JSONTokener(raw.trim())
        val root = runCatching { tokener.nextValue() as? JSONObject }
            .getOrNull()
            ?: throw IllegalArgumentException("任务计划不是 JSON 对象")
        require(tokener.nextClean() == 0.toChar()) { "任务计划包含 JSON 之外的内容" }
        require(root.keys().asSequence().toSet() == ROOT_KEYS) { "任务计划字段不符合约定" }

        val name = root.getString("name").trim()
        val targetAppPackage = root.getString("target_app_package").trim().ifBlank { null }
        require(targetAppPackage == null || targetAppPackage in DeviceActionPolicy.DEFAULT_ALLOWED_PACKAGES) {
            "任务计划目标应用不在允许列表"
        }
        val stepArray = root.getJSONArray("steps")
        val steps = buildList {
            repeat(stepArray.length()) { index ->
                val step = stepArray.getJSONObject(index)
                require(step.keys().asSequence().toSet() == STEP_KEYS) {
                    "任务计划第 ${index + 1} 步字段不符合约定"
                }
                add(PersonalTaskPlanStep(goal = step.getString("goal").trim()))
            }
        }
        // long: 模型输出仍是不可信输入；复用 Workflow 定义门禁可保证确认弹层展示的内容与确认后真正写入 Room 的内容完全同构。
        WorkflowDefinitionPolicy.validate(
            name = name,
            steps = steps.map { step -> WorkflowStepDefinitionInput(step.goal) },
        )
        return PersonalTaskPlan(name = name, targetAppPackage = targetAppPackage, steps = steps)
    }

    private val ROOT_KEYS = setOf("name", "target_app_package", "steps")
    private val STEP_KEYS = setOf("goal")
}
