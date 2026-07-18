package com.longdev.xiaoling.agent

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

object AgentSkillDocumentCodec {
    private const val SCHEMA_VERSION = 1
    const val MAX_DOCUMENT_BYTES = 65_536
    private val validId = Regex("[a-z0-9][a-z0-9._-]{2,63}")

    fun decode(raw: String, registeredTools: List<ToolDefinition>): AgentSkillDefinition {
        require(raw.isNotBlank()) { "Skill 文件不能为空" }
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= MAX_DOCUMENT_BYTES) { "Skill 文件不能超过 64 KiB" }
        val json = runCatching { JSONObject(raw) }
            .getOrElse { throw IllegalArgumentException("Skill 文件不是有效 JSON object", it) }
        json.requireOnlyKeys(
            scope = "根对象",
            allowed = setOf(
                "schemaVersion",
                "id",
                "version",
                "name",
                "description",
                "source",
                "trigger",
                "tools",
                "requirements",
                "instructions",
                "failureRecovery",
                "completionCriteria",
            ),
        )
        require(json.getInt("schemaVersion") == SCHEMA_VERSION) { "仅支持 schemaVersion=1" }
        require(json.getString("source") == "local") { "本地导入 Skill 的 source 必须是 local" }

        val id = json.getString("id").trim()
        require(validId.matches(id)) { "Skill id 必须为 3-64 位小写字母、数字、点、下划线或连字符" }
        val version = json.getInt("version")
        require(version > 0) { "Skill version 必须大于 0" }
        val trigger = json.getJSONObject("trigger")
        trigger.requireOnlyKeys("trigger", setOf("keywords", "examples"))
        val requirements = json.getJSONObject("requirements")
        requirements.requireOnlyKeys("requirements", setOf("androidPermissions", "risk"))
        val toolNames = json.getJSONArray("tools").toStringSet("tools", maxItems = 20, maxItemLength = 120)
        require(toolNames.isNotEmpty()) { "Skill 至少声明一个工具" }
        val keywords = trigger.getJSONArray("keywords")
            .toStringSet("trigger.keywords", maxItems = 30, maxItemLength = 100)
        require(keywords.isNotEmpty()) { "Skill 至少声明一个触发关键词" }
        val triggerExamples = trigger.getJSONArray("examples")
            .toStringList("trigger.examples", maxItems = 20, maxItemLength = 300)
        require(triggerExamples.isNotEmpty()) { "Skill 至少声明一个触发示例" }

        val toolsByName = registeredTools.associateBy { it.name }
        val selectedTools = toolNames.map { name ->
            toolsByName[name] ?: throw IllegalArgumentException("Skill 引用了未注册工具：$name")
        }
        val declaredRisk = runCatching { ToolRisk.valueOf(requirements.getString("risk")) }
            .getOrElse { throw IllegalArgumentException("Skill risk 不是有效枚举", it) }
        val actualRisk = selectedTools.maxBy { it.risk.ordinal }.risk
        require(declaredRisk == actualRisk) {
            "Skill 声明风险 $declaredRisk 与工具实际最高风险 $actualRisk 不一致"
        }
        val declaredPermissions = requirements.getJSONArray("androidPermissions")
            .toStringSet("requirements.androidPermissions", maxItems = 30, maxItemLength = 200)
        val actualPermissions = selectedTools.flatMapTo(linkedSetOf()) {
            it.permissionPolicy.requiredAndroidPermissions
        }
        require(declaredPermissions == actualPermissions) {
            "Skill 声明 Android 权限与工具实际权限不一致"
        }

        // long: 本地 Skill 只能组合应用已经注册的工具；风险和权限从 ToolDefinition 反算并核对，文本文件不能自行扩大能力或降低审批边界。
        return AgentSkillDefinition(
            id = id,
            version = version,
            name = json.requiredText("name", 80),
            description = json.requiredText("description", 500),
            instructions = json.requiredText("instructions", 8_000),
            toolNames = toolNames,
            keywords = keywords,
            triggerExamples = triggerExamples,
            requiredAndroidPermissions = actualPermissions,
            declaredRisk = actualRisk,
            failureRecovery = json.requiredText("failureRecovery", 2_000),
            completionCriteria = json.requiredText("completionCriteria", 2_000),
            source = AgentSkillSource.LOCAL,
        )
    }

    private fun JSONObject.requiredText(name: String, maxLength: Int): String {
        val value = getString(name).trim()
        require(value.isNotBlank()) { "Skill 字段 $name 不能为空" }
        require(value.length <= maxLength) { "Skill 字段 $name 超过长度限制" }
        return value
    }

    private fun JSONObject.requireOnlyKeys(scope: String, allowed: Set<String>) {
        val unknown = buildSet { keys().forEach { key -> if (key !in allowed) add(key) } }.sorted()
        require(unknown.isEmpty()) { "Skill ${scope}包含未知字段：${unknown.joinToString()}" }
    }

    private fun JSONArray.toStringSet(field: String, maxItems: Int, maxItemLength: Int): Set<String> {
        return toStringList(field, maxItems, maxItemLength).toCollection(linkedSetOf())
    }

    private fun JSONArray.toStringList(field: String, maxItems: Int, maxItemLength: Int): List<String> {
        require(length() <= maxItems) { "Skill 字段 $field 最多包含 $maxItems 项" }
        val values = buildList<String> {
            for (index in 0 until this@toStringList.length()) {
                val rawValue = this@toStringList.get(index)
                require(rawValue is String) { "Skill 字段 $field 只能包含字符串" }
                val value = rawValue.trim()
                require(value.isNotBlank()) { "Skill 字段 $field 不能包含空字符串" }
                require(value.length <= maxItemLength) { "Skill 字段 $field 的单项超过长度限制" }
                add(value)
            }
        }
        require(values.distinct().size == values.size) { "Skill 字段 $field 不能包含重复项" }
        return values
    }
}
