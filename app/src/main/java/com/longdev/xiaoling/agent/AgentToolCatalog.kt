package com.longdev.xiaoling.agent

import java.security.MessageDigest

enum class AgentToolCatalogOrigin {
    NATIVE,
    SKILL,
    MCP,
    PLUGIN,
}

data class AgentToolCatalogCandidate(
    val definition: ToolDefinition,
    val origin: AgentToolCatalogOrigin,
)

data class AgentToolCatalogEntry(
    val definition: ToolDefinition,
    val origins: Set<AgentToolCatalogOrigin>,
) {
    init {
        require(origins.isNotEmpty()) { "工具目录来源不能为空" }
    }
}

/**
 * long: 目录只描述当前入口可见的工具和来源，不授予执行权限；执行仍必须回到当前 Registry 的动态 definition 门禁。
 */
class AgentToolCatalog private constructor(
    val version: Int,
    val entries: List<AgentToolCatalogEntry>,
    val fingerprint: String,
) {
    init {
        require(version > 0) { "工具目录版本必须大于 0" }
        require(entries.map { it.definition.name }.distinct().size == entries.size) {
            "工具目录不能包含重复工具名"
        }
        require(entries == entries.sortedBy { it.definition.name }) {
            "工具目录必须按工具名稳定排序"
        }
        require(fingerprint == fingerprintFor(version, entries)) {
            "工具目录指纹与内容不一致"
        }
    }

    val definitions: List<ToolDefinition>
        get() = entries.map { it.definition }

    fun definition(name: String): ToolDefinition? =
        entries.firstOrNull { it.definition.name == name }?.definition

    fun contains(name: String): Boolean = definition(name) != null

    fun toSharedCatalog(): com.longdev.xiaoling.shared.agent.SharedToolCatalog =
        com.longdev.xiaoling.shared.agent.SharedToolCatalog.fromEntries(
            entries.map { entry ->
                com.longdev.xiaoling.shared.agent.SharedToolCatalogEntry(
                    definition = entry.definition.toSharedAgentDefinition(),
                    sources = entry.origins.map { it.name.lowercase() }.toSet(),
                )
            },
            version = version,
        )

    companion object {
        const val CURRENT_VERSION: Int = 1

        fun fromDefinitions(
            definitions: List<ToolDefinition>,
            origin: AgentToolCatalogOrigin = AgentToolCatalogOrigin.NATIVE,
            version: Int = CURRENT_VERSION,
        ): AgentToolCatalog = fromCandidates(
            definitions.map { AgentToolCatalogCandidate(it, origin) },
            version,
        )

        fun fromCandidates(
            candidates: List<AgentToolCatalogCandidate>,
            version: Int = CURRENT_VERSION,
        ): AgentToolCatalog {
            val entries = candidates
                .groupBy { it.definition.name }
                .toSortedMap()
                .map { (name, grouped) ->
                    val fingerprints = grouped
                        .map { ToolDefinitionRecoveryContract.snapshot(it.definition).definitionFingerprint }
                        .distinct()
                    require(fingerprints.size == 1) {
                        "工具目录存在定义漂移：$name"
                    }
                    AgentToolCatalogEntry(
                        definition = grouped.first().definition,
                        origins = grouped.mapTo(sortedSetOf()) { it.origin },
                    )
                }
            return AgentToolCatalog(
                version = version,
                entries = entries,
                fingerprint = fingerprintFor(version, entries),
            )
        }

        private fun fingerprintFor(
            version: Int,
            entries: List<AgentToolCatalogEntry>,
        ): String {
            val fields = buildList {
                add(version.toString())
                entries.forEach { entry ->
                    add(entry.definition.name)
                    add(ToolDefinitionRecoveryContract.snapshot(entry.definition).definitionFingerprint)
                    entry.origins.sortedBy { it.name }.forEach { add("origin:${it.name}") }
                }
            }
            val canonical = buildString {
                fields.forEach { field -> append(field.length).append(':').append(field) }
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte) }
        }
    }
}

fun interface AgentToolExecutor {
    suspend fun execute(call: ToolCall): ToolExecutionResult
}
