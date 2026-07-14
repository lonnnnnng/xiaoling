package com.longdev.endpointtester.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointUrlBuilderTest {
    @Test
    fun `api root keeps existing version path`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            EndpointUrlBuilder.chatCompletionsUrl("https://api.example.com/v1"),
        )
    }

    @Test
    fun `full chat endpoint is not duplicated`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            EndpointUrlBuilder.chatCompletionsUrl("https://api.example.com/v1/chat/completions"),
        )
    }

    @Test
    fun `models endpoint can be replaced with chat endpoint`() {
        assertEquals(
            "http://192.168.1.2:11434/v1/chat/completions",
            EndpointUrlBuilder.chatCompletionsUrl("http://192.168.1.2:11434/v1/models"),
        )
    }

    @Test
    fun `responses endpoint can be built from api root`() {
        assertEquals(
            "https://api.example.com/v1/responses",
            EndpointUrlBuilder.responsesUrl("https://api.example.com/v1"),
        )
    }

    @Test
    fun `full responses endpoint is not duplicated`() {
        assertEquals(
            "https://api.example.com/v1/responses",
            EndpointUrlBuilder.responsesUrl("https://api.example.com/v1/responses"),
        )
    }

    @Test
    fun `http and https inputs are accepted`() {
        assertNull(EndpointUrlBuilder.validate("http://192.168.1.2:11434/v1"))
        assertNull(EndpointUrlBuilder.validate("https://api.example.com/v1"))
    }
}
