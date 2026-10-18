package com.longdev.xiaoling.shared.http

import kotlin.test.*

class SharedHttpContractTest {
    @Test fun originNormalizesCaseAndDefaultPortWithoutChangingRequestUrl() {
        val url = "https://API.example.com/v1?q=%E4%B8%AD"
        assertEquals("https://api.example.com:443", SharedHttpContract.origin(url))
        assertEquals(url, SharedHttpRequest(url).url)
        assertEquals("https://api.example.com:443", SharedHttpContract.origin("https://api.example.com:443/x"))
        assertEquals("http://127.0.0.1:1234", SharedHttpContract.origin("http://127.0.0.1:1234/x"))
    }
    @Test fun ambiguousOrUnsafeUrlsAreRejectedWithoutRepeatingTheirText() {
        for (url in listOf("file:///x", "HTTPS://example.com", "https://user:secret@example.com/", "https://example.com/#secret", "https://example.com/\r\nheader", "https://example.com\\x", " https://example.com", "https://example.com/%", "https://example.com/%xy", "https://example.com:0", "https://example.com:65536", "https://example.com:0443", "https://example.com.", "https://-example.com", "https://[::1]", "https://example.com/中文")) {
            val error = assertFailsWith<IllegalArgumentException> { SharedHttpRequest(url) }
            assertEquals("HTTP 契约输入无效", error.message)
        }
    }
    @Test fun targetPolicyIsBoundToExactSchemeHostAndPort() {
        val targets = SharedHttpTargetPolicy.https(setOf("https://api.example.com/v1"))
        assertTrue(targets.accepts("https://API.example.com:443/other"))
        for (url in listOf("http://api.example.com/x", "https://api.example.com:444/x", "https://other.example.com/x")) assertFalse(targets.accepts(url))
        assertFailsWith<IllegalArgumentException> { SharedHttpTargetPolicy.https(setOf("http://127.0.0.1:1234")) }
        assertFailsWith<IllegalArgumentException> { SharedHttpTargetPolicy.https(emptySet()) }
        assertFailsWith<IllegalArgumentException> { SharedHttpTargetPolicy.https((1..9).map { "https://h$it.example.com" }.toSet()) }
        assertFailsWith<IllegalArgumentException> { SharedHttpTargetPolicy.loopbackTest("http://localhost:1234") }
    }
    @Test fun headerInjectionDuplicatesAndManagedHeadersAreRejected() {
        for (values in listOf(mapOf("Authorization" to "a\nb"), mapOf("X-Key\r" to "a"), mapOf("x" to "a", "X" to "b"), mapOf("Cookie" to "session=private"), mapOf("Host" to "other"), mapOf("Content-Length" to "1"), mapOf("Connection" to "close"), mapOf("Transfer-Encoding" to "chunked"), mapOf("Proxy-Authorization" to "fixture"))) {
            assertFailsWith<IllegalArgumentException> { SharedHttpRequest("https://api.example.com", headers = values) }
        }
        assertFailsWith<IllegalArgumentException> { SharedHttpRequest("https://api.example.com", headers = (1..33).associate { "X-$it" to "a" }) }
        assertFailsWith<IllegalArgumentException> { SharedHttpRequest("https://api.example.com", headers = mapOf("X" to "a".repeat(4097))) }
        assertFailsWith<IllegalArgumentException> { SharedHttpRequest("https://api.example.com", headers = (1..5).associate { "X-$it" to "a".repeat(4096) }) }
    }
    @Test fun requestHasBoundedBytesTimeoutAndResponseBudget() {
        val url = "https://api.example.com"
        SharedHttpRequest(url, SharedHttpMethod.POST, body = ByteArray(SharedHttpContract.MAX_REQUEST_BYTES), timeoutMillis = 100, maxResponseBytes = SharedHttpContract.MAX_RESPONSE_BYTES)
        assertFailsWith<IllegalArgumentException> { SharedHttpRequest(url, body = byteArrayOf(1)) }
        assertFailsWith<IllegalArgumentException> { SharedHttpRequest(url, SharedHttpMethod.POST, body = ByteArray(SharedHttpContract.MAX_REQUEST_BYTES + 1)) }
        for (timeout in listOf(99L, 60_001L)) assertFailsWith<IllegalArgumentException> { SharedHttpRequest(url, timeoutMillis = timeout) }
        for (limit in listOf(0, SharedHttpContract.MAX_RESPONSE_BYTES + 1)) assertFailsWith<IllegalArgumentException> { SharedHttpRequest(url, maxResponseBytes = limit) }
    }
    @Test fun requestAndResponseSnapshotMutableInputsAndRedactDiagnostics() {
        val headers = mutableMapOf("Authorization" to "fixture-secret")
        val bytes = "private-body".encodeToByteArray()
        val request = SharedHttpRequest("https://api.example.com/private?key=secret", SharedHttpMethod.POST, headers, bytes)
        headers.clear(); bytes.fill(0)
        assertEquals("fixture-secret", request.headers["Authorization"])
        assertEquals("private-body", request.body.decodeToString())
        request.body.fill(0)
        assertEquals("private-body", request.body.decodeToString())
        assertFalse(request.toString().contains("secret")); assertFalse(request.toString().contains("api.example")); assertFalse(request.toString().contains("private"))
        val response = SharedHttpResponse(401, mapOf("set-cookie" to "fixture-secret"), "private-body".encodeToByteArray())
        assertFalse(response.toString().contains("secret")); assertFalse(response.toString().contains("private"))
        response.body.fill(0); assertEquals("private-body", response.body.decodeToString())
        assertNull(SharedHttpException(SharedHttpFailure.NETWORK).cause)
    }
    @Test fun responsesPreserveStatusAndBytesButRejectInvalidMetadata() {
        val response = SharedHttpResponse(503, mapOf("retry-after" to "0"), byteArrayOf(0xff.toByte()))
        assertEquals(503, response.statusCode); assertEquals(0xff.toByte(), response.body.single())
        for (status in listOf(99, 600)) assertFailsWith<IllegalArgumentException> { SharedHttpResponse(status, emptyMap(), byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { SharedHttpResponse(200, mapOf("x" to "\r\n"), byteArrayOf()) }
        assertFailsWith<IllegalArgumentException> { SharedHttpResponse(200, emptyMap(), ByteArray(SharedHttpContract.MAX_RESPONSE_BYTES + 1)) }
    }
}
