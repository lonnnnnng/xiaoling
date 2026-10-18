package com.longdev.xiaoling.shared.browser

import com.longdev.xiaoling.shared.agent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlin.test.*

/** long: 这些门禁验证模型目录和当前调用绑定，不把注入reader当作iOS网络或HTML解析验收。 */
class SharedReadOnlyBrowserTest {
    private class Reader : SharedReadOnlyBrowser {
        var reads = 0
        var lists = 0
        var returnedId = "guide"
        var onRead: suspend () -> Unit = {}
        override val pageIds: List<String> get() { lists++; return listOf("guide") }
        override suspend fun readPage(pageId: String, maxChars: Int): SharedBrowserPage {
            reads++; onRead()
            return SharedBrowserPage(returnedId, "公开指南", "正文😀", false, "a".repeat(32), "b".repeat(64), "https://example.com/guide")
        }
        override fun close() = Unit
    }
    private val call = SharedToolCall("read-1", "browser.read_page", mapOf("page_id" to "guide"))

    @Test fun registryCopiesTargetsAndRejectsArbitraryOrSensitiveUrls() {
        val source = mutableMapOf("guide" to "https://example.com/guide")
        val registry = SharedBrowserPageRegistry.https(source)
        source["guide"] = "https://other.example.com/changed"
        assertEquals("https://example.com/guide", registry.urlFor("guide"))
        assertEquals(SharedBrowserFailure.PAGE_NOT_REGISTERED, assertFailsWith<SharedBrowserException> { registry.urlFor("missing") }.failure)
        for (url in listOf("http://example.com/", "https://127.0.0.1/", "https://localhost/", "https://a.local/", "https://a.internal/", "https://a.test/", "https://example.com:8443/", "https://example.com/?token=fixture", "https://user@example.com/", "https://example.com/#x"))
            assertFailsWith<IllegalArgumentException> { SharedBrowserPageRegistry.https(mapOf("guide" to url)) }
        assertFailsWith<IllegalArgumentException> { SharedBrowserPageRegistry.https(mapOf("../guide" to "https://example.com/")) }
        assertFailsWith<IllegalArgumentException> { SharedBrowserPageRegistry.https((1..9).associate { "p$it" to "https://example.com/$it" }) }
        assertFalse(registry.toString().contains("example.com"))
    }
    @Test fun catalogHasOnlyForegroundReadonlyTools() {
        val executor = SharedReadOnlyBrowserExecutor(Reader()) { true }
        assertEquals(setOf("browser.list_pages", "browser.read_page"), executor.catalog.entries.map { it.definition.name }.toSet())
        assertTrue(executor.catalog.entries.all { !it.definition.requiresApproval && !it.definition.supportsBackground })
    }
    @Test fun invalidArgumentsAndActionToolsHaveZeroReaderAccess() = runTest {
        val reader = Reader(); val executor = SharedReadOnlyBrowserExecutor(reader) { true }
        val invalid = listOf(call.copy(arguments = mapOf("url" to "https://example.com/private")), call.copy(arguments = mapOf("page_id" to "../guide")),
            call.copy(arguments = call.arguments + ("headers" to "fixture-secret")), call.copy(arguments = call.arguments + ("max_chars" to "01")),
            call.copy(arguments = call.arguments + ("max_chars" to "50001")), call.copy(name = "browser.click"), call.copy(name = "browser.type"),
            call.copy(name = "browser.list_pages"), call.copy(id = " "))
        for (item in invalid) { val result = executor.execute(item); assertFalse(result.success); assertFalse(result.content.contains("fixture-secret")) }
        assertEquals(0, reader.reads); assertEquals(0, reader.lists)
    }
    @Test fun inactiveForegroundOwnerHasZeroReaderAccess() = runTest {
        val reader = Reader(); val result = SharedReadOnlyBrowserExecutor(reader) { false }.execute(call)
        assertFalse(result.success); assertTrue(result.content.contains("FOREGROUND_OWNER_REQUIRED")); assertEquals(0, reader.reads)
    }
    @Test fun foregroundRevocationDiscardsLateBodyAndEvidence() = runTest {
        val reader = Reader(); var active = true
        reader.onRead = { active = false }
        val result = SharedReadOnlyBrowserExecutor(reader) { active }.execute(call)
        assertFalse(result.success); assertFalse(result.content.contains("正文")); assertNull(result.readableEvidence)
        assertTrue(result.content.contains("FOREGROUND_OWNER_REVOKED"))
    }
    @Test fun precancelledCallerDoesNotRead() = runTest {
        val reader = Reader(); val executor = SharedReadOnlyBrowserExecutor(reader) { true }
        val job = async { currentCoroutineContext().cancel(); executor.execute(call) }
        assertFailsWith<CancellationException> { job.await() }; assertEquals(0, reader.reads)
    }
    @Test fun foregroundCallbackCancellationDoesNotRead() = runTest {
        val reader = Reader()
        val job = async {
            val context = currentCoroutineContext()
            SharedReadOnlyBrowserExecutor(reader) { context.cancel(); true }.execute(call)
        }
        assertFailsWith<CancellationException> { job.await() }; assertEquals(0, reader.reads)
    }
    @Test fun cancellationDuringReadNeverBecomesOrdinaryFailure() = runTest {
        val reader = Reader(); val entered = CompletableDeferred<Unit>()
        reader.onRead = { entered.complete(Unit); awaitCancellation() }
        val job = async { SharedReadOnlyBrowserExecutor(reader) { true }.execute(call) }
        entered.await(); job.cancel()
        assertFailsWith<CancellationException> { job.await() }; assertEquals(1, reader.reads)
    }
    @Test fun finalForegroundCallbackCancellationPreventsSuccessfulPublication() = runTest {
        val reader = Reader()
        val job = async {
            var checks = 0
            val context = currentCoroutineContext()
            SharedReadOnlyBrowserExecutor(reader) { if (++checks == 2) context.cancel(); true }.execute(call)
        }
        assertFailsWith<CancellationException> { job.await() }; assertEquals(1, reader.reads)
    }
    @Test fun evidenceBindsCurrentCallAndRoundTripsWithoutBusinessVerification() = runTest {
        val result = SharedReadOnlyBrowserExecutor(Reader()) { true }.execute(call)
        assertTrue(result.success); assertEquals(call.id, result.readableEvidence!!.toolCallId)
        assertNull(result.verified); assertNull(result.verification); assertNull(result.executionReceipt); assertNull(result.readableEvidence.browserAction)
        val frame = SharedToolResultFrame(call.id, result)
        assertEquals(frame, SharedToolExecutionResultCodec.decode(SharedToolExecutionResultCodec.encode(frame), call.id))
        assertFalse(result.content.contains("https://"))
    }
    @Test fun readerCannotReturnAnotherPageIdentity() = runTest {
        val reader = Reader().apply { returnedId = "another" }
        val result = SharedReadOnlyBrowserExecutor(reader) { true }.execute(call)
        assertFalse(result.success); assertNull(result.readableEvidence); assertFalse(result.content.contains("正文"))
    }
}
