package com.longdev.xiaoling.network

import org.junit.Assert.assertEquals
import org.junit.Test

class HeaderParserTest {
    @Test
    fun `multiple custom headers are parsed`() {
        assertEquals(
            mapOf("api-key" to "secret", "X-Tenant" to "demo"),
            HeaderParser.parse("api-key: secret\nX-Tenant: demo"),
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `header without colon is rejected`() {
        HeaderParser.parse("broken header")
    }
}
