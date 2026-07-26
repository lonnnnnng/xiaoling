package com.longdev.xiaoling.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderRequestConfigTest {
    @Test
    fun toStringRedactsCredentials() {
        val description = ProviderRequestConfig(
            baseUrl = "https://private-user:private-password@example.com/v1?api_key=private-query-key",
            apiKey = "private-api-key",
            model = "gpt-test",
            customHeaders = mapOf("X-Api-Key" to "private-header-value"),
        ).toString()

        assertFalse(description.contains("private-api-key"))
        assertFalse(description.contains("private-header-value"))
        assertFalse(description.contains("private-user"))
        assertFalse(description.contains("private-password"))
        assertFalse(description.contains("private-query-key"))
        assertTrue(description.contains("baseUrl=<redacted>"))
        assertTrue(description.contains("apiKey=<redacted>"))
        assertTrue(description.contains("customHeaders=<redacted>"))
    }
}
