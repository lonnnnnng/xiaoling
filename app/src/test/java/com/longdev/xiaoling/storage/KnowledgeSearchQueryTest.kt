package com.longdev.xiaoling.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class KnowledgeSearchQueryTest {
    @Test
    fun searchTermsBecomeEscapedFtsPrefixQuery() {
        assertEquals(
            "\"local\"* AND \"index\"*",
            buildKnowledgeFtsQuery(" local   index "),
        )
        assertEquals("\"he\"\"llo\"*", buildKnowledgeFtsQuery("he\"llo"))
    }

    @Test
    fun likeFallbackEscapesWildcardCharactersAsLiterals() {
        assertEquals(
            listOf("%中文%", "%检索%"),
            buildKnowledgeLikePatterns("中文  检索"),
        )
        assertEquals(
            listOf("%100\\%\\_\\\\ready%"),
            buildKnowledgeLikePatterns("100%_\\ready"),
        )
    }
}
