package com.longdev.xiaoling.knowledge

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

data class ImportedKnowledgeText(
    val normalizedText: String,
    val contentHash: String,
    val byteSize: Long,
    val characterCount: Int,
)

data class KnowledgeTextChunk(
    val sequence: Int,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
)

object KnowledgeTextPolicy {
    const val PARSER_VERSION = 1
    const val DEFAULT_MAX_CHUNK_CHARACTERS = 1_600
    const val DEFAULT_OVERLAP_CHARACTERS = 200
    const val MAX_IMPORT_BYTES = 64 * 1024 * 1024
    const val MAX_IMPORT_CHARACTERS = 16_000_000

    fun decodeUtf8(bytes: ByteArray): ImportedKnowledgeText {
        require(bytes.isNotEmpty()) { "知识文档不能为空" }
        require(bytes.size <= MAX_IMPORT_BYTES) { "知识文档不能超过 64 MB" }
        // long: 文档 hash 和后续 chunk offset 都以同一份规范全文为身份依据；严格解码并统一换行可避免平台换行差异产生两套引用。
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val decoded = try {
            decoder.decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            throw IllegalArgumentException("知识文档必须是严格 UTF-8 文本")
        }
        val normalized = decoded
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        require(normalized.isNotBlank()) { "知识文档不能只包含空白字符" }
        require('\u0000' !in normalized) { "知识文档不能包含二进制空字符" }
        require(normalized.length <= MAX_IMPORT_CHARACTERS) { "知识文档文本不能超过 1600 万字符" }
        return ImportedKnowledgeText(
            normalizedText = normalized,
            contentHash = sha256(normalized.toByteArray(Charsets.UTF_8)),
            byteSize = bytes.size.toLong(),
            characterCount = normalized.length,
        )
    }

    fun chunk(
        normalizedText: String,
        maxCharacters: Int = DEFAULT_MAX_CHUNK_CHARACTERS,
        overlapCharacters: Int = DEFAULT_OVERLAP_CHARACTERS,
    ): List<KnowledgeTextChunk> {
        require(normalizedText.isNotBlank()) { "知识文档不能只包含空白字符" }
        require(maxCharacters >= 2) { "分块字符上限不能小于 2" }
        require(overlapCharacters in 0..(maxCharacters / 2)) { "分块重叠不能超过字符上限的一半" }
        val chunks = mutableListOf<KnowledgeTextChunk>()
        var start = 0
        while (start < normalizedText.length) {
            val hardEnd = safeEndOffset(
                text = normalizedText,
                start = start,
                candidateEnd = (start + maxCharacters).coerceAtMost(normalizedText.length),
            )
            val end = if (hardEnd == normalizedText.length) {
                hardEnd
            } else {
                paragraphBoundary(
                    text = normalizedText,
                    minimumEnd = start + maxCharacters / 2,
                    hardEnd = hardEnd,
                ) ?: hardEnd
            }
            chunks += KnowledgeTextChunk(
                sequence = chunks.size,
                startOffset = start,
                endOffset = end,
                text = normalizedText.substring(start, end),
            )
            if (end == normalizedText.length) break
            // long: 相邻块保留固定、有限的正文重叠，检索命中跨边界语句时仍可还原上下文，同时 offset 始终指向规范全文。
            val overlapStart = end - overlapCharacters
            start = if (
                overlapStart in 1 until normalizedText.length &&
                normalizedText[overlapStart].isLowSurrogate() &&
                normalizedText[overlapStart - 1].isHighSurrogate()
            ) {
                overlapStart + 1
            } else {
                overlapStart
            }
        }
        return chunks
    }

    private fun paragraphBoundary(text: String, minimumEnd: Int, hardEnd: Int): Int? {
        for (index in hardEnd - 2 downTo (minimumEnd - 2).coerceAtLeast(0)) {
            if (text[index] == '\n' && text[index + 1] == '\n') return index + 2
        }
        return null
    }

    private fun safeEndOffset(text: String, start: Int, candidateEnd: Int): Int {
        if (
            candidateEnd in (start + 1) until text.length &&
            text[candidateEnd - 1].isHighSurrogate() &&
            text[candidateEnd].isLowSurrogate()
        ) {
            return candidateEnd - 1
        }
        return candidateEnd
    }

    private fun sha256(bytes: ByteArray): String {
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
