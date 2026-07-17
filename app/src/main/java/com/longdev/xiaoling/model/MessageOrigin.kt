package com.longdev.xiaoling.model

enum class MessageOrigin {
    USER,
    ORDINARY_ASSISTANT,
    AGENT_RESULT,
    ERROR,
    ;

    companion object {
        fun fromStored(value: String?, role: String): MessageOrigin {
            val storedOrigin = value
                ?.takeUnless { it == LEGACY_VALUE }
                ?.let { raw -> entries.firstOrNull { it.name == raw } }
            if (storedOrigin != null) return storedOrigin

            // long: 老版本没有记录消息来源，迁移时只能按角色保守恢复；历史 assistant 不自动升级为 Agent 结果，避免把普通回复误当成工具执行证据。
            return when (role) {
                "user" -> USER
                "assistant" -> ORDINARY_ASSISTANT
                else -> ERROR
            }
        }

        const val LEGACY_VALUE = "LEGACY"
    }
}
