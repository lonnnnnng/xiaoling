package com.longdev.xiaoling.network

import com.longdev.xiaoling.model.ProviderRequestConfig
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class OpenAiCompatibleClientTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun modelRequestUsesConfiguredUserAgent() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"gpt-test"}]}"""))

        OpenAiCompatibleClient().fetchModels(config(userAgent = "Custom Client/1.0"))

        assertEquals("Custom Client/1.0", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun blankUserAgentFallsBackToCodexDesktopDefault() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[{"id":"gpt-test"}]}"""))

        OpenAiCompatibleClient().fetchModels(config(userAgent = "  "))

        assertEquals(ProviderRequestConfig.DEFAULT_USER_AGENT, server.takeRequest().getHeader("User-Agent"))
        assertEquals(
            "Codex Desktop/0.145.0-alpha.18 (Mac OS 14.7.4; arm64) unknown (Codex Desktop; 26.715.31251)",
            ProviderRequestConfig.DEFAULT_USER_AGENT,
        )
    }

    private fun config(userAgent: String) = ProviderRequestConfig(
        baseUrl = server.url("/v1").toString(),
        apiKey = "test-key",
        model = "",
        userAgent = userAgent,
    )
}
