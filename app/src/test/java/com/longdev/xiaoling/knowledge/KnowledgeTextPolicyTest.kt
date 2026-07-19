package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class KnowledgeTextPolicyTest {
    @Test
    fun strictUtf8ImportNormalizesLineEndingsAndHashesCanonicalText() {
        val source = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) +
            "标题\r\n\r\n第一段\r第二段".toByteArray(Charsets.UTF_8)

        val imported = KnowledgeTextPolicy.decodeUtf8(source)

        assertEquals("标题\n\n第一段\n第二段", imported.normalizedText)
        assertEquals(source.size.toLong(), imported.byteSize)
        assertEquals(11, imported.characterCount)
        assertEquals("38b89c1286f0234bb0a9f8f1bf50e137767aea43ab46e29a797bbf746c217cb2", imported.contentHash)
    }

    @Test
    fun malformedUtf8AndBlankDocumentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeTextPolicy.decodeUtf8(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeTextPolicy.decodeUtf8(" \r\n\t".toByteArray(Charsets.UTF_8))
        }
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeTextPolicy.decodeUtf8("text\u0000binary".toByteArray(Charsets.UTF_8))
        }
    }

    @Test
    fun chunkingPrefersParagraphBoundariesAndKeepsBoundedOverlap() {
        val text = "alpha block\n\nbeta block\n\ngamma block\n\ndelta block"

        val chunks = KnowledgeTextPolicy.chunk(
            normalizedText = text,
            maxCharacters = 24,
            overlapCharacters = 5,
        )

        assertTrue(chunks.size > 1)
        assertTrue(chunks.first().text.endsWith("\n\n"))
        chunks.forEach { chunk ->
            assertTrue(chunk.text.length <= 24)
            assertEquals(text.substring(chunk.startOffset, chunk.endOffset), chunk.text)
        }
        chunks.zipWithNext().forEach { (current, next) ->
            assertEquals(current.endOffset - next.startOffset, 5)
            assertTrue(next.startOffset < current.endOffset)
            assertTrue(next.endOffset > current.endOffset)
        }
        assertEquals(chunks, KnowledgeTextPolicy.chunk(text, 24, 5))
    }

    @Test
    fun oversizedParagraphUsesHardBoundariesWithoutLosingText() {
        val text = "0123456789abcdefghij"

        val chunks = KnowledgeTextPolicy.chunk(text, maxCharacters = 8, overlapCharacters = 2)

        assertEquals(listOf(0, 6, 12), chunks.map { it.startOffset })
        assertEquals(listOf(8, 14, 20), chunks.map { it.endOffset })
        assertEquals(listOf("01234567", "6789abcd", "cdefghij"), chunks.map { it.text })
    }

    @Test
    fun chunkBoundariesNeverSplitUtf16SurrogatePairs() {
        val text = "abcdef😀ghijklmnop"

        val chunks = KnowledgeTextPolicy.chunk(text, maxCharacters = 7, overlapCharacters = 2)

        chunks.forEach { chunk ->
            assertFalse(chunk.text.first().isLowSurrogate())
            assertFalse(chunk.text.last().isHighSurrogate())
            assertEquals(text.substring(chunk.startOffset, chunk.endOffset), chunk.text)
        }
        assertTrue(chunks.any { "😀" in it.text })
    }
}
