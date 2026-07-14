package com.longdev.endpointtester.network

object HeaderParser {
    private val validName = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

    fun parse(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()

        return raw.lineSequence()
            .mapIndexedNotNull { index, original ->
                val line = original.trim()
                if (line.isBlank()) return@mapIndexedNotNull null

                val separator = line.indexOf(':')
                require(separator > 0) { "自定义 Header 第 ${index + 1} 行缺少冒号" }
                val name = line.substring(0, separator).trim()
                val value = line.substring(separator + 1).trim()
                require(validName.matches(name)) { "自定义 Header 第 ${index + 1} 行名称无效" }
                require(value.isNotBlank()) { "自定义 Header 第 ${index + 1} 行值为空" }
                name to value
            }
            .toMap()
    }
}
