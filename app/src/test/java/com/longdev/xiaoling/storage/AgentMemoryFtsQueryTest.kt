package com.longdev.xiaoling.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class AgentMemoryFtsQueryTest {
    @Test
    fun searchTermsBecomeEscapedPrefixQuery() {
        assertEquals(
            "\"compact\"* AND \"user\"*",
            buildAgentMemoryFtsQuery(" compact   user "),
        )
        assertEquals(
            "\"he\"\"llo\"*",
            buildAgentMemoryFtsQuery("he\"llo"),
        )
    }

    @Test
    fun likeFallbackSplitsTermsAndEscapesWildcardCharacters() {
        assertEquals(
            listOf("%紧凑%", "%界面%"),
            buildAgentMemoryLikePatterns("紧凑  界面"),
        )
        assertEquals(
            listOf("%100\\%\\_\\\\ready%"),
            buildAgentMemoryLikePatterns("100%_\\ready"),
        )
    }
}
