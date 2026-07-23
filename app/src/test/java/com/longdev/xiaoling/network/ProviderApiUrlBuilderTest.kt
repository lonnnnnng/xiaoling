package com.longdev.xiaoling.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderApiUrlBuilderTest {
    @Test
    fun `api root keeps existing version path`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            ProviderApiUrlBuilder.chatCompletionsUrl("https://api.example.com/v1"),
        )
    }

    @Test
    fun `full chat requestUrl is not duplicated`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            ProviderApiUrlBuilder.chatCompletionsUrl("https://api.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `models requestUrl can be replaced with chat requestUrl`() {
        assertEquals(
            "http://192.168.1.2:11434/v1/chat/completions",
            ProviderApiUrlBuilder.chatCompletionsUrl("http://192.168.1.2:11434/v1/models"),
        )
    }

    @Test
    fun `responses requestUrl can be built from api root`() {
        assertEquals(
            "https://api.example.com/v1/responses",
            ProviderApiUrlBuilder.responsesUrl("https://api.example.com/v1"),
        )
    }

    @Test
    fun `full responses requestUrl is not duplicated`() {
        assertEquals(
            "https://api.example.com/v1/responses",
            ProviderApiUrlBuilder.responsesUrl("https://api.example.com/v1/responses"),
        )
    }

    @Test
    fun `embeddings requestUrl normalizes any known endpoint suffix`() {
        assertEquals(
            "https://api.example.com/v1/embeddings",
            ProviderApiUrlBuilder.embeddingsUrl("https://api.example.com/v1/responses"),
        )
        assertEquals(
            "https://api.example.com/v1/embeddings",
            ProviderApiUrlBuilder.embeddingsUrl("https://api.example.com/v1/embeddings"),
        )
    }

    @Test
    fun `http and https inputs are accepted`() {
        assertNull(ProviderApiUrlBuilder.validate("http://192.168.1.2:11434/v1"))
        assertNull(ProviderApiUrlBuilder.validate("https://api.example.com/v1"))
    }
}
