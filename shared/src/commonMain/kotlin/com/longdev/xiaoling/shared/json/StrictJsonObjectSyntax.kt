package com.longdev.xiaoling.shared.json

/** long: 在各端 JSON 树覆盖重复键之前验证完整标准 JSON，避免同一份证据在不同解析器上产生不同身份。 */
object StrictJsonObjectSyntax {
    fun validate(raw: String, maxChars: Int = 1_000_000) {
        require(raw.length in 1..maxChars)
        Scanner(raw).validate()
    }
    private class Scanner(private val raw: String) {
        private var index = 0
        fun validate() { whitespace(); require(peek() == '{'); value(0); whitespace(); require(index == raw.length) }
        private fun peek() = raw.getOrNull(index)
        private fun whitespace() { while (peek() in listOf(' ', '\t', '\r', '\n')) index++ }
        private fun take(char: Char) { whitespace(); require(peek() == char); index++ }
        private fun value(depth: Int) {
            require(depth <= 32); whitespace()
            when (peek()) {
                '{' -> {
                    index++; whitespace(); val keys = mutableSetOf<String>()
                    if (peek() != '}') while (true) {
                        whitespace(); require(peek() == '"'); require(keys.add(string()))
                        take(':'); value(depth + 1); whitespace()
                        if (peek() != ',') break
                        index++
                    }
                    take('}')
                }
                '[' -> {
                    index++; whitespace()
                    if (peek() != ']') while (true) {
                        value(depth + 1); whitespace(); if (peek() != ',') break; index++
                    }
                    take(']')
                }
                '"' -> string()
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }
        private fun literal(text: String) { require(raw.startsWith(text, index)); index += text.length }
        private fun number() {
            if (peek() == '-') index++
            if (peek() == '0') index++ else { require(peek() in '1'..'9'); digits() }
            if (peek() == '.') { index++; require(peek() in '0'..'9'); digits() }
            if (peek() == 'e' || peek() == 'E') {
                index++; if (peek() == '+' || peek() == '-') index++
                require(peek() in '0'..'9'); digits()
            }
        }
        private fun digits() { while (peek() in '0'..'9') index++ }
        private fun validateUnicode(value: String) {
            // long: 孤立代理字符会在 UTF-8 持久化时被替换，拒绝它们才能让两端保留同一调用与来源文本。
            var offset = 0
            while (offset < value.length) {
                val char = value[offset++]
                if (char.isHighSurrogate()) {
                    require(offset < value.length && value[offset].isLowSurrogate())
                    offset++
                } else require(!char.isLowSurrogate())
            }
        }
        private fun string(): String {
            take('"'); val result = StringBuilder()
            while (true) {
                val char = requireNotNull(peek()); index++
                if (char == '"') return result.toString().also(::validateUnicode)
                require(char.code >= 0x20)
                if (char != '\\') { result.append(char); continue }
                val escape = requireNotNull(peek()); index++
                result.append(when (escape) {
                    '"', '\\', '/' -> escape
                    'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                    'u' -> {
                        require(index + 4 <= raw.length)
                        val hex = raw.substring(index, index + 4); require(hex.all { it in "0123456789abcdefABCDEF" })
                        index += 4; hex.toInt(16).toChar()
                    }
                    else -> error("JSON 转义无效")
                })
            }
        }
    }
}
