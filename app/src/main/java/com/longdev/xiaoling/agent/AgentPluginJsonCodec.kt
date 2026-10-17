package com.longdev.xiaoling.agent

import org.json.JSONArray
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray

/**
 * 插件注册表的稳定 JSON 编解码器。
 *
 * long: 持久化内容只描述已安装的声明和启停状态；未知字段、未知枚举和损坏快照全部拒绝，
 * 这样旧版本或被篡改的 SharedPreferences 不会悄悄扩大 Agent 能力面。
 */
object AgentPluginJsonCodec {
    const val SCHEMA_VERSION = 2
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
        val root = runCatching { strictObject(raw) }
            .getOrElse { throw IllegalArgumentException("插件状态不是有效 JSON object", it) }
        root.requireOnlyKeys("根对象", setOf("schemaVersion", "plugins"))
        val schemaVersion = root.opt("schemaVersion")
        require(schemaVersion == 1 || schemaVersion == SCHEMA_VERSION) {
            "仅支持插件状态 schemaVersion=1 或 $SCHEMA_VERSION"
        }
        val jsonPlugins = root.getJSONArray("plugins")
        require(jsonPlugins.length() <= MAX_PLUGINS) { "插件数量不能超过 $MAX_PLUGINS" }
        val result = ArrayList<InstalledAgentPlugin>(jsonPlugins.length())
        val ids = linkedSetOf<String>()
        for (index in 0 until jsonPlugins.length()) {
            val plugin = decodePlugin(jsonPlugins.getJSONObject(index), signedSchema = schemaVersion == SCHEMA_VERSION)
            require(ids.add(plugin.manifest.id)) { "插件状态包含重复 ID：${plugin.manifest.id}" }
            result += plugin
        }
        return result
    }

    /**
     * long: 只解析用户手动粘贴的单个声明式 manifest，不读取 URL，也不加载仓库中的代码。
     */
    fun decodeManifest(raw: String): AgentPluginManifest {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= 16 * 1024) {
            "插件 manifest 不能超过 16 KiB"
        }
        val manifest = runCatching { strictObject(raw) }
            .getOrElse { throw IllegalArgumentException("插件 manifest 不是有效 JSON object", it) }
        return decodePlugin(JSONObject().put("manifest", manifest).put("enabled", false), false).manifest
    }

    fun encodeSignedManifest(signed: AgentPluginSignedManifest): String = encodeSigned(signed).toString().also {
        require(it.toByteArray(StandardCharsets.UTF_8).size <= 24 * 1024) { "插件签名 envelope 不能超过 24 KiB" }
    }

    fun decodeSignedManifest(raw: String): AgentPluginSignedManifest {
        require(raw.toByteArray(StandardCharsets.UTF_8).size <= 24 * 1024) { "插件签名 envelope 不能超过 24 KiB" }
        return decodeSigned(strictObject(raw))
    }

    private fun encodePlugin(plugin: InstalledAgentPlugin): JSONObject {
        validateLedger(plugin)
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
                .put("apiVersion", plugin.manifest.apiVersion)
                .put("name", plugin.manifest.name)
                .put("description", plugin.manifest.description)
                .put("publisher", plugin.manifest.publisher)
                .put("capabilities", JSONArray(plugin.manifest.capabilities.toList().sorted()))
                .put("permissions", JSONArray(plugin.manifest.permissions.map { it.name }.sorted()))
                .put("executionMode", plugin.manifest.executionMode.name)
                .put("source", plugin.manifest.source?.let(::encodeSource) ?: JSONObject.NULL))
            .put("enabled", plugin.enabled)
            .put("signedManifest", plugin.signedManifest?.let(::encodeSigned) ?: JSONObject.NULL)
            .put("highestSignedManifest", plugin.highestSignedManifest?.let(::encodeSigned) ?: JSONObject.NULL)
            .put("history", JSONArray().apply { plugin.history.forEach { put(encodeSigned(it)) } })
            .put("removed", plugin.removed)
    }

    private fun decodePlugin(json: JSONObject, signedSchema: Boolean): InstalledAgentPlugin {
        val keys = setOf("manifest", "enabled") + if (signedSchema) {
            setOf("signedManifest", "highestSignedManifest", "history", "removed")
        } else emptySet()
        json.requireOnlyKeys("插件", keys)
        require(json.keys().asSequence().toSet() == keys) { "插件状态字段缺失" }
        val manifestJson = json.getJSONObject("manifest")
        manifestJson.requireOnlyKeys(
            "插件 manifest",
            setOf("id", "version", "apiVersion", "name", "description", "publisher", "capabilities", "permissions", "executionMode", "source"),
        )
        val apiVersion = manifestJson.opt("apiVersion").let { value ->
            when (value) {
                null, JSONObject.NULL -> AgentPluginPolicy.SUPPORTED_API_VERSION
                is Number -> value.toInt().also { parsed ->
                    require(value.toDouble() == parsed.toDouble()) { "插件 API 版本必须是整数" }
                }
                else -> throw IllegalArgumentException("插件 API 版本必须是整数")
            }
        }
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
            apiVersion = apiVersion,
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
            signedManifest = if (signedSchema) json.optionalSigned("signedManifest") else null,
            highestSignedManifest = if (signedSchema) json.optionalSigned("highestSignedManifest") else null,
            history = if (signedSchema) json.getJSONArray("history").let { values ->
                require(values.length() <= AgentPluginSigning.MAX_HISTORY) { "插件历史版本过多" }
                (0 until values.length()).map { decodeSigned(values.getJSONObject(it)) }
            } else emptyList(),
            removed = signedSchema && json.requiredBoolean("removed"),
        ).also(::validateLedger)
    }

    private fun encodeSigned(signed: AgentPluginSignedManifest): JSONObject {
        AgentPluginSigning.verify(signed)
        return JSONObject().put("envelopeVersion", signed.envelopeVersion).put("algorithm", signed.algorithm)
            .put("manifest", encodePlugin(InstalledAgentPlugin(signed.manifest)).getJSONObject("manifest"))
            .put("publicKeySpkiBase64", signed.publicKeySpkiBase64).put("signatureBase64", signed.signatureBase64)
    }

    private fun decodeSigned(json: JSONObject): AgentPluginSignedManifest {
        val keys = setOf("envelopeVersion", "algorithm", "manifest", "publicKeySpkiBase64", "signatureBase64")
        json.requireOnlyKeys("签名 envelope", keys)
        require(json.keys().asSequence().toSet() == keys && json.opt("envelopeVersion") == 1) { "插件签名 envelope 字段或版本无效" }
        val manifest = decodePlugin(JSONObject().put("manifest", json.getJSONObject("manifest")).put("enabled", false), false).manifest
        return AgentPluginSignedManifest(manifest, json.requiredString("publicKeySpkiBase64", 512),
            json.requiredString("signatureBase64", 100), algorithm = json.requiredString("algorithm", 80))
            .also { AgentPluginSigning.verify(it) }
    }

    private fun JSONObject.optionalSigned(name: String): AgentPluginSignedManifest? =
        if (get(name) == JSONObject.NULL) null else decodeSigned(getJSONObject(name))

    private fun validateLedger(plugin: InstalledAgentPlugin) {
        val current = plugin.signedManifest
        if (current == null) {
            require(plugin.highestSignedManifest == null && plugin.history.isEmpty() && !plugin.removed) { "无签名插件不能含信任或回滚记录" }
            return
        }
        require(current.manifest == plugin.manifest && !(plugin.removed && plugin.enabled)) { "插件当前签名或移除状态不一致" }
        val highest = requireNotNull(plugin.highestSignedManifest) { "插件最高版本签名缺失" }
        require(plugin.history.size <= AgentPluginSigning.MAX_HISTORY &&
            plugin.history.map { it.manifest.version }.distinct().size == plugin.history.size &&
            plugin.history.none { it.manifest.version == plugin.manifest.version }) { "插件历史版本重复或过多" }
        (listOf(current, highest) + plugin.history).forEach { signed ->
            AgentPluginSigning.verify(signed)
            require(signed.manifest.id == current.manifest.id && signed.manifest.publisher == current.manifest.publisher &&
                signed.publicKeySpkiBase64 == current.publicKeySpkiBase64) { "插件历史身份与确认公钥不一致" }
            val compare = AgentPluginPolicy.compareVersions(signed.manifest.version, highest.manifest.version)
            require(compare <= 0 && (compare != 0 || signed.manifest == highest.manifest)) { "插件历史超过最高版本或同版本内容漂移" }
        }
    }

    private fun strictObject(raw: String): JSONObject {
        // long: Android JSONObject 接受重复 key 和宽松 JSON；先拒绝重复字段、过深结构和非标准语法，避免各平台验签对象不同。
        val stack = mutableListOf<MutableSet<String>?>()
        var index = 0
        while (index < raw.length) {
            when (raw[index]) {
                '{', '[' -> {
                    stack += if (raw[index] == '{') mutableSetOf<String>() else null
                    require(stack.size <= 32) { "插件 JSON 嵌套过深" }
                }
                '}', ']' -> { require(stack.isNotEmpty()) { "插件 JSON 结构无效" }; stack.removeAt(stack.lastIndex) }
                '"' -> {
                    val start = index++
                    while (index < raw.length && raw[index] != '"') {
                        if (raw[index] == '\\') index++
                        index++
                    }
                    require(index < raw.length) { "插件 JSON 字符串未结束" }
                    val token = raw.substring(start, index + 1)
                    var next = index + 1
                    while (next < raw.length && raw[next].isWhitespace()) next++
                    if (next < raw.length && raw[next] == ':') {
                        val key = (Json.parseToJsonElement(token) as JsonPrimitive).content
                        require(stack.lastOrNull()?.add(key) == true) { "插件 JSON 字段重复" }
                    }
                }
            }
            index++
        }
        require(stack.isEmpty()) { "插件 JSON 结构未结束" }
        fun validateTokens(element: JsonElement, field: String? = null) {
            when (element) {
                is JsonObject -> element.forEach { (key, value) -> validateTokens(value, key) }
                is JsonArray -> element.forEach { validateTokens(it) }
                is JsonPrimitive -> if (!element.isString && element.content !in setOf("true", "false", "null")) {
                    val token = element.content
                    require(Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?").matches(token)) { "插件 JSON 数字无效" }
                    // long: 版本字段只接受原始十进制整数 token，不让 Double 舍入把高精度小数冒充支持的 API。
                    if (field in setOf("apiVersion", "schemaVersion", "envelopeVersion")) {
                        require(Regex("0|[1-9][0-9]*").matches(token) && token.toIntOrNull() != null) { "插件版本字段必须是十进制整数" }
                    }
                }
            }
        }
        validateTokens(Json.parseToJsonElement(raw))
        return JSONObject(raw)
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
