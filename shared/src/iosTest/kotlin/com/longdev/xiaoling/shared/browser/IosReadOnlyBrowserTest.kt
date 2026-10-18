@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.longdev.xiaoling.shared.browser

import com.longdev.xiaoling.shared.agent.*
import com.longdev.xiaoling.shared.http.*
import com.longdev.xiaoling.shared.http.IosHttpClientTest.Reply
import com.longdev.xiaoling.shared.http.IosHttpClientTest.Server
import kotlinx.coroutines.*
import com.longdev.xiaoling.shared.workspace.IosLocalWorkspace
import platform.Foundation.*
import kotlin.test.*

/** long: 使用系统NSURLSession、真实loopback和系统HTML解析器联合；不接公网、真实模型或UIKit生命周期。 */
class IosReadOnlyBrowserTest {
    private suspend fun fixture(replies: Map<String, Reply>, block: suspend (Server, IosReadOnlyBrowser) -> Unit) {
        val server = Server(replies)
        val pages = replies.keys.associate { it.removePrefix("/") to server.url + it }
        val browser = IosReadOnlyBrowser(SharedBrowserPageRegistry.loopbackTest(pages), IosHttpClient(SharedHttpTargetPolicy.loopbackTest(server.url)))
        try { withTimeout(10_000) { block(server, browser) } } finally { browser.close(); server.close() }
    }
    private fun html(body: String, headers: String = "") = Reply(body = body, headers = headers, contentType = "text/html; charset=utf-8")

    @Test fun htmlExtractionExcludesHiddenScriptInputsAndDoesNotLoadResources(): Unit = runBlocking {
        val source = """<!doctype html><html><head><title>指南 &amp; 😀</title><style>private-style</style></head><body><h1>公开标题</h1><p>你好 &amp; 世界😀</p><a href="http://127.0.0.1/private">链接文本</a><img src="/asset"><script src="/script">private-script</script><iframe src="/frame">private-frame</iframe><input value="private-input"><textarea>private-textarea</textarea><div hidden>private-hidden</div><div style="display:none !important">private-css</div><div aria-hidden="true"><p>private-child</p></div></body></html>"""
        fixture(mapOf("/guide" to html(source))) { server, browser ->
            val page = browser.readPage("guide")
            assertEquals("指南 & 😀", page.title); assertEquals("公开标题 你好 & 世界😀 链接文本", page.text)
            assertFalse(page.content().contains("private")); assertFalse(page.truncated)
            val next = browser.readPage("guide")
            assertEquals(page.contentHash, next.contentHash); assertNotEquals(page.snapshotId, next.snapshotId)
            assertEquals(sha256(page.content().encodeToByteArray()), page.contentHash)
            assertFalse(page.toString().contains("公开"))
            assertEquals(listOf("/guide", "/guide"), server.received().map { it.path })
            assertTrue(server.received().all { it.headers.startsWith("GET ") && it.body.isEmpty() && !Regex("(?im)^(Cookie|Authorization):").containsMatchIn(it.headers) })
        }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha256("abc".encodeToByteArray()))
    }
    @Test fun mimeDownloadCharsetAndStatusAreRejectedWithoutPartialContent(): Unit = runBlocking {
        fixture(mapOf("/mime" to Reply(body = "private", contentType = "application/json"), "/download" to html("private", "Content-Disposition: attachment\r\n"),
            "/charset" to Reply(body = "private", contentType = "text/html; charset=iso-8859-1"), "/status" to Reply(503, "private", contentType = "text/html"))) { server, browser ->
            for (id in browser.pageIds) {
                val error = assertFailsWith<SharedBrowserException> { browser.readPage(id) }
                assertEquals(SharedBrowserFailure.CONTENT_REJECTED, error.failure); assertNull(error.cause); assertFalse(error.message!!.contains("private"))
            }
            assertEquals(4, server.received().size)
        }
    }
    @Test fun badUtf8AndNullTextAreRejected(): Unit = runBlocking {
        fixture(mapOf("/utf" to Reply(rawBody = byteArrayOf(0xff.toByte()), contentType = "text/html"), "/null" to Reply(body = "public\u0000private", contentType = "text/plain"))) { _, browser ->
            for (id in browser.pageIds) assertEquals(SharedBrowserFailure.INVALID_TEXT, assertFailsWith<SharedBrowserException> { browser.readPage(id) }.failure)
        }
    }
    @Test fun plainTextAndHtmlPreviewDoNotSplitEmojiOrInventDom(): Unit = runBlocking {
        fixture(mapOf("/plain" to Reply(body = "A😀B", contentType = "text/plain"), "/html" to html("<p>A😀B</p>"))) { _, browser ->
            for (id in browser.pageIds) {
                val page = browser.readPage(id, 2)
                assertEquals("A", page.text); assertTrue(page.truncated); assertFalse(page.content().contains("ref"))
            }
        }
    }
    @Test fun parserDepthAndScanLimitsRejectTheWholePage(): Unit = runBlocking {
        fixture(mapOf("/depth" to html("<div>".repeat(40) + "private" + "</div>".repeat(40)), "/nodes" to html("<p>x</p>".repeat(5_100)))) { _, browser ->
            for (id in browser.pageIds) assertEquals(SharedBrowserFailure.PARSE_LIMIT, assertFailsWith<SharedBrowserException> { browser.readPage(id) }.failure)
        }
    }
    @Test fun unregisteredPagesAndInvalidInputDoNotConnect(): Unit = runBlocking {
        fixture(mapOf("/guide" to html("public"))) { server, browser ->
            assertEquals(SharedBrowserFailure.PAGE_NOT_REGISTERED, assertFailsWith<SharedBrowserException> { browser.readPage("missing") }.failure)
            assertFailsWith<IllegalArgumentException> { browser.readPage("../guide") }
            assertFailsWith<IllegalArgumentException> { browser.readPage("guide", 0) }
            assertEquals(0, server.acceptedCount())
        }
    }
    @Test fun redirectsNeverChooseAnUnregisteredPath(): Unit = runBlocking {
        fixture(mapOf("/redirect" to Reply(302, "private", "Location: /target\r\n", contentType = "text/html"))) { server, browser ->
            assertEquals(SharedBrowserFailure.CONTENT_REJECTED, assertFailsWith<SharedBrowserException> { browser.readPage("redirect") }.failure)
            assertEquals(listOf("/redirect"), server.received().map { it.path })
        }
    }
    @Test fun cancellationClosesSocketAndCloseRevokesReuse(): Unit = runBlocking {
        fixture(mapOf("/stall" to Reply(stall = true))) { server, browser ->
            val job = async { browser.readPage("stall") }; withTimeout(3_000) { server.ready.await() }
            job.cancel(); assertFailsWith<CancellationException> { job.await() }; withTimeout(3_000) { server.peerClosed.await() }
            browser.close(); browser.close()
            assertEquals(SharedBrowserFailure.READER_CLOSED, assertFailsWith<SharedBrowserException> { browser.readPage("stall") }.failure)
        }
    }
    @Test fun browserCloseTerminatesPendingSocketWithoutPublishingBody(): Unit = runBlocking {
        fixture(mapOf("/stall" to Reply(stall = true))) { server, browser -> supervisorScope {
            val job = async { browser.readPage("stall") }; withTimeout(3_000) { server.ready.await() }
            browser.close(); browser.close()
            assertEquals(SharedBrowserFailure.READER_CLOSED, assertFailsWith<SharedBrowserException> { job.await() }.failure)
            withTimeout(3_000) { server.peerClosed.await() }
            assertEquals(1, server.received().size)
        } }
    }
    @Test fun nativeForegroundRevocationDiscardsParsedBodyAndEvidence(): Unit = runBlocking {
        fixture(mapOf("/guide" to html("<p>late-private-body</p>"))) { server, browser ->
            var checks = 0
            val executor = SharedReadOnlyBrowserExecutor(browser) { ++checks == 1 }
            val result = executor.execute(SharedToolCall("late", "browser.read_page", mapOf("page_id" to "guide")))
            assertFalse(result.success); assertNull(result.readableEvidence); assertFalse(result.content.contains("late-private-body"))
            assertTrue(result.content.contains("FOREGROUND_OWNER_REVOKED")); assertEquals(1, server.received().size)
        }
    }
    @Test fun nativeReaderRunsThroughSharedLoopAndFullResultCodec(): Unit = runBlocking {
        fixture(mapOf("/guide" to html("<title>指南</title><p>loop正文😀</p>"))) { _, browser ->
            val calls = listOf(SharedToolCall("list", "browser.list_pages", emptyMap()), SharedToolCall("page", "browser.read_page", mapOf("page_id" to "guide")))
            val executor = SharedReadOnlyBrowserExecutor(browser) { true }
            val llm = object : SharedAgentLlm {
                override suspend fun proposeNextAction(goal: String, tools: List<SharedToolDefinition>, completedCalls: List<SharedToolCall>) =
                    calls.getOrNull(completedCalls.size)?.let(SharedAgentPlanDecision::CallTool) ?: SharedAgentPlanDecision.Complete
                override suspend fun summarize(goal: String, completedCalls: List<SharedToolCall>) = "完成"
            }
            val gate = object : SharedApprovalGate {
                override suspend fun requestApproval(runId: String, toolCall: SharedToolCall, definition: SharedToolDefinition): Boolean = error("只读页面不申请动作审批")
            }
            val loop = SharedAgentRuntime(llm, gate, executor).run("native-browser-run", "读取登记指南", executor.catalog)
            assertEquals(SharedAgentRunStatus.COMPLETED, loop.status); assertEquals(calls, loop.executions.map { it.toolCall })
            val result = loop.executions.last().result
            assertTrue(result.content.contains("loop正文😀")); assertNull(result.verified); assertNull(result.executionReceipt)
            assertEquals("page", result.readableEvidence!!.toolCallId); assertNull(result.readableEvidence!!.browserAction)
            val frame = SharedToolResultFrame("page", result)
            assertEquals(frame, SharedToolExecutionResultCodec.decode(SharedToolExecutionResultCodec.encode(frame), "page"))
        }
    }
    @Test fun responseByteBudgetStopsChunkedBody(): Unit = runBlocking {
        fixture(mapOf("/large" to Reply(body = "x".repeat(SharedBrowserContract.MAX_BODY_BYTES + 1), chunked = true, contentType = "text/html"))) { _, browser ->
            assertEquals(SharedBrowserFailure.HTTP_REJECTED, assertFailsWith<SharedBrowserException> { browser.readPage("large") }.failure)
        }
    }
    @Test fun externalDoctypeAndFileEntityCannotLoadExtraContent(): Unit = runBlocking {
        val root = NSTemporaryDirectory() + "xiaoling-ios-browser-test-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, false, null, null))
        val files = IosLocalWorkspace(root)
        files.createText("private.txt", "external-private-secret")
        try {
            val other = Server(emptyMap())
            try {
                val body = """<!DOCTYPE html SYSTEM "${other.url}/external.dtd" [<!ENTITY leak SYSTEM "file://$root/private.txt">]><html><body><p>公开 &leak; 正文</p></body></html>"""
                fixture(mapOf("/guide" to html(body))) { _, browser ->
                    val page = browser.readPage("guide")
                    assertFalse(page.content().contains("external-private-secret")); assertEquals(0, other.acceptedCount())
                    assertTrue(page.text.contains("公开") && page.text.contains("正文"))
                }
            } finally { other.close() }
        } finally {
            files.close()
            require(root.startsWith(NSTemporaryDirectory() + "xiaoling-ios-browser-test-"))
            assertTrue(NSFileManager.defaultManager.removeItemAtPath(root, null))
        }
    }
}
