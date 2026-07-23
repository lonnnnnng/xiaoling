package com.longdev.xiaoling.knowledge

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class KnowledgeEmbeddingTest {
    @Test
    fun vectorCodecUsesStableLittleEndianFloat32AndRejectsDimensionDrift() {
        val vector = floatArrayOf(1.5f, -2.25f, 0.125f)

        val encoded = KnowledgeEmbeddingVectorCodec.encode(vector)

        assertArrayEquals(vector, KnowledgeEmbeddingVectorCodec.decode(encoded, 3), 0.0f)
        assertThrows(IllegalArgumentException::class.java) {
            KnowledgeEmbeddingVectorCodec.decode(encoded, 2)
        }
    }

    @Test
    fun cosineRejectsZeroOrMismatchedVectors() {
        assertEquals(1.0, KnowledgeEmbeddingSimilarity.cosine(floatArrayOf(1f, 0f), floatArrayOf(1f, 0f))!!, 0.000001)
        assertEquals(0.0, KnowledgeEmbeddingSimilarity.cosine(floatArrayOf(1f, 0f), floatArrayOf(0f, 2f))!!, 0.000001)
        assertNull(KnowledgeEmbeddingSimilarity.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)))
        assertNull(KnowledgeEmbeddingSimilarity.cosine(floatArrayOf(1f), floatArrayOf(1f, 0f)))
    }

    @Test
    fun fusionKeepsLegacyOrderWithoutSemanticAndUsesStableRrfWithSemantic() {
        assertEquals(
            listOf("fts-a", "shared", "like-b"),
            KnowledgeSearchFusionPolicy.fuse(
                ftsIds = listOf("fts-a", "shared"),
                likeIds = listOf("shared", "like-b"),
                semanticIds = emptyList(),
                limit = 3,
            ),
        )
        assertEquals(
            listOf("shared", "fts-a", "semantic-c"),
            KnowledgeSearchFusionPolicy.fuse(
                ftsIds = listOf("fts-a", "shared"),
                likeIds = listOf("shared", "like-b"),
                semanticIds = listOf("semantic-c", "shared"),
                limit = 3,
            ),
        )
    }
}
