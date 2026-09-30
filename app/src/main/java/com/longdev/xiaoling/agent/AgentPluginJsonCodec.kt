package com.longdev.xiaoling.agent

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets

/**
 * 插件注册表的稳定 JSON 编解码器。
 *
 * long: 持久化内容只描述已安装的声明和启停状态；未知字段、未知枚举和损坏快照全部拒绝，
 * 这样旧版本或被篡改的 SharedPreferences 不会悄悄扩大 Agent 能力面。
 */
object AgentPluginJsonCodec {
    const val SCHEMA_VERSION = 1
    const val MAX_STATE_BYTES = 256 * 1024

    private const val MAX_PLUGINS = 128
    private const val MAX_CAPABILITIES = 64
    private const val MAX_CAPABILITY_LENGTH = 120
    private const val MAX_PUBLISHER_LENGTH = 200

    fun encode(plugins: Collection<InstalledAgentPlugin>): String {
        require(plugins.size <= MAX_PLUGINS) { "插件数量不能超过 $MAX_PLUGINS" }
        val sortedPlugins = plugins.sortedBy { it.manifest.id }
        require(sortedPlugins.map { it.manifest.id }.distinct().size == sortedPlugins.size) {
            "插件状态包含重复 ID"
        }
        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put("plugins", JSONArray().apply {
                sortedPlugins.forEach { put(encodePlugin(it)) }
            })
        val encoded = root.toString()
        require(encoded.toByteArray(StandardCharsets.UTF_8).size <= MAX_STATE_BYTES) {
            "插件状态不能超过 256 KiB"
        }
        return encoded
    }

    fun decode(raw: String): List<InstalledAgentPlugin> {
        require(raw.isNotBlank()) { "插件状态不能为空" }
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= MAX_STATE_BYTES) {
            "插件状态不能超过 256 KiB"
        }
        val root = runCatching { JSONObject(raw) }
            .getOrElse { throw IllegalArgumentException("插件状态不是有效 JSON object", it) }
        root.requireOnlyKeys("根对象", setOf("schemaVersion", "plugins"))
        require(root.opt("schemaVersion") == SCHEMA_VERSION) {
            "仅支持插件状态 schemaVersion=$SCHEMA_VERSION"
        }
        val jsonPlugins = root.getJSONArray("plugins")
        require(jsonPlugins.length() <= MAX_PLUGINS) { "插件数量不能超过 $MAX_PLUGINS" }
        val result = ArrayList<InstalledAgentPlugin>(jsonPlugins.length())
        val ids = linkedSetOf<String>()
        for (index in 0 until jsonPlugins.length()) {
            val plugin = decodePlugin(jsonPlugins.getJSONObject(index))
            require(ids.add(plugin.manifest.id)) { "插件状态包含重复 ID：${plugin.manifest.id}" }
            result += plugin
        }
        return result
    }

    private fun encodePlugin(plugin: InstalledAgentPlugin): JSONObject {
        AgentPluginPolicy.validate(plugin.manifest)
        require(plugin.manifest.capabilities.size <= MAX_CAPABILITIES) {
            "插件能力数量不能超过 $MAX_CAPABILITIES"
        }
        require(plugin.manifest.capabilities.all { it.length <= MAX_CAPABILITY_LENGTH }) {
            "插件能力 ID 过长"
        }
        require(plugin.manifest.publisher.length <= MAX_PUBLISHER_LENGTH) { "插件发布者过长" }
        return JSONObject()
            .put("manifest", JSONObject()
                .put("id", plugin.manifest.id)
                .put("version", plugin.manifest.version)
                .put("name", plugin.manifest.name)
                .put("description", plugin.manifest.description)
                .put("publisher", plugin.manifest.publisher)
                .put("capabilities", JSONArray(plugin.manifest.capabilities.toList().sorted()))
                .put("permissions", JSONArray(plugin.manifest.permissions.map { it.name }.sorted()))
                .put("executionMode", plugin.manifest.executionMode.name)
                .put("source", plugin.manifest.source?.let(::encodeSource) ?: JSONObject.NULL))
            .put("enabled", plugin.enabled)
    }

    private fun decodePlugin(json: JSONObject): InstalledAgentPlugin {
        json.requireOnlyKeys("插件", setOf("manifest", "enabled"))
        val manifestJson = json.getJSONObject("manifest")
        manifestJson.requireOnlyKeys(
            "插件 manifest",
            setOf("id", "version", "name", "description", "publisher", "capabilities", "permissions", "executionMode", "source"),
        )
        val capabilities = manifestJson.getJSONArray("capabilities")
            .toStringSet("插件能力", MAX_CAPABILITIES, MAX_CAPABILITY_LENGTH)
        val permissions = manifestJson.getJSONArray("permissions")
            .toStringList("插件权限", AgentPluginPermission.entries.size, 80)
            .map { value ->
                runCatching { AgentPluginPermission.valueOf(value) }
                    .getOrElse { throw IllegalArgumentException("插件权限不是有效枚举：$value", it) }
            }
            .toSet()
        val executionMode = runCatching {
            AgentPluginExecutionMode.valueOf(manifestJson.requiredString("executionMode", 80))
        }.getOrElse { throw IllegalArgumentException("插件执行模式不是有效枚举", it) }
        require(manifestJson.has("source")) { "插件来源字段缺失" }
        val source = manifestJson.opt("source")?.takeUnless { it == JSONObject.NULL }?.let {
            require(it is JSONObject) { "插件来源必须是 JSON object 或 null" }
            it.requireOnlyKeys("插件来源", setOf("url", "commit", "contentSha256"))
            AgentPluginSource(
                url = it.requiredString("url", 2_000),
                commit = it.requiredString("commit", 64),
                contentSha256 = it.requiredString("contentSha256", 64),
            )
        }
        val manifest = AgentPluginManifest(
            id = manifestJson.requiredString("id", 100),
            version = manifestJson.requiredString("version", 32),
            name = manifestJson.requiredString("name", 200),
            description = manifestJson.requiredString("description", 2_000),
            publisher = manifestJson.requiredString("publisher", MAX_PUBLISHER_LENGTH),
            capabilities = capabilities,
            permissions = permissions,
            executionMode = executionMode,
            source = source,
        )
        AgentPluginPolicy.validate(manifest)
        return InstalledAgentPlugin(
            manifest = manifest,
            enabled = json.requiredBoolean("enabled"),
        )
    }

    private fun encodeSource(source: AgentPluginSource): JSONObject = JSONObject()
        .put("url", source.url)
        .put("commit", source.commit)
        .put("contentSha256", source.contentSha256)

    private fun JSONObject.requiredString(name: String, maxLength: Int): String {
        val value = opt(name)
        require(value is String) { "插件字段 $name 必须是字符串" }
        require(value.isNotEmpty() && value.length <= maxLength) { "插件字段 $name 长度无效" }
        return value
    }

    private fun JSONObject.requiredBoolean(name: String): Boolean {
        val value = opt(name)
        require(value is Boolean) { "插件字段 $name 必须是布尔值" }
        return value
    }

    private fun JSONObject.requireOnlyKeys(scope: String, allowed: Set<String>) {
        val unknown = buildList {
            keys().forEach { if (it !in allowed) add(it) }
        }.sorted()
        require(unknown.isEmpty()) { "$scope 包含未知字段：${unknown.joinToString()}" }
    }

    private fun JSONArray.toStringSet(field: String, maxItems: Int, maxItemLength: Int): Set<String> =
        toStringList(field, maxItems, maxItemLength).toCollection(linkedSetOf())

    private fun JSONArray.toStringList(field: String, maxItems: Int, maxItemLength: Int): List<String> {
        require(length() <= maxItems) { "$field 最多包含 $maxItems 项" }
        val values = buildList {
            for (index in 0 until length()) {
                val value = opt(index)
                require(value is String) { "$field 只能包含字符串" }
                require(value.isNotEmpty() && value.length <= maxItemLength) { "$field 单项长度无效" }
                add(value)
            }
        }
        require(values.distinct().size == values.size) { "$field 不能包含重复项" }
        return values
    }
}
