package com.longdev.xiaoling.shared.workspace

import com.longdev.xiaoling.shared.agent.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.*

class SharedWorkspaceFilesTest {
    @Test fun pathPolicyKeepsUnicodeAndRootButRejectsAliasesAndControlNames() {
        assertEquals(emptyList(), SharedWorkspaceFilePolicy.pathSegments(" ", true))
        assertEquals(listOf("笔记", "内容😀.txt"), SharedWorkspaceFilePolicy.pathSegments("笔记/内容😀.txt", false))
        assertEquals(listOf(" file "), SharedWorkspaceFilePolicy.pathSegments(" file ", false))
        for (path in listOf("/a", "../a", "a/../b", "a/./b", "a//b", "a/", "a\\b", "\nfile", "a\u0000b", "a/" + SharedWorkspaceFilePolicy.STAGING_PREFIX + "id")) {
            assertFailsWith<IllegalArgumentException>(path) { SharedWorkspaceFilePolicy.pathSegments(path, true) }
        }
        assertFailsWith<IllegalArgumentException> { SharedWorkspaceFilePolicy.pathSegments(".", false) }
    }

    @Test fun filenameAndWholePathBoundsUseUtf8AndUtf16Separately() {
        assertEquals(listOf("a".repeat(255)), SharedWorkspaceFilePolicy.pathSegments("a".repeat(255), false))
        assertFailsWith<IllegalArgumentException> { SharedWorkspaceFilePolicy.pathSegments("a".repeat(256), false) }
        assertFailsWith<IllegalArgumentException> { SharedWorkspaceFilePolicy.pathSegments("中".repeat(86), false) }
        assertFailsWith<IllegalArgumentException> { SharedWorkspaceFilePolicy.pathSegments("a/".repeat(256)+"a", false) }
        assertFailsWith<IllegalArgumentException> { SharedWorkspaceFilePolicy.pathSegments("\uD800", false) }
    }

    @Test fun previewDoesNotSplitSurrogatePairAndReportsExactPrefix() {
        assertEquals("a", SharedWorkspaceFilePolicy.preview("a😀b", 2))
        assertEquals("a😀", SharedWorkspaceFilePolicy.preview("a😀b", 3))
        assertEquals("", SharedWorkspaceFilePolicy.preview("😀b", 1))
        assertEquals("", SharedWorkspaceFilePolicy.preview("", 1))
        assertFailsWith<IllegalArgumentException> { SharedWorkspaceFilePolicy.preview("x", 0) }
        assertFailsWith<IllegalArgumentException> { SharedWorkspaceFilePolicy.preview("x", 100_001) }
    }

    private class FakeFiles : SharedWorkspaceFiles {
        var accesses = 0
        var onRead: () -> Unit = {}
        override fun list(path: String): List<SharedWorkspaceFileEntry> {
            accesses++; return listOf(SharedWorkspaceFileEntry("a.txt", false, 3))
        }
        override fun readText(path: String, maxChars: Int): SharedWorkspaceText {
            accesses++; onRead(); return SharedWorkspaceText(path, "中", 3, false)
        }
        override fun close() = Unit
    }

    @Test fun catalogHasOnlyForegroundReadToolsAndNoModelWriteOrShell() = runTest {
        val files = FakeFiles()
        val executor = SharedReadOnlyWorkspaceExecutor(files) { true }
        assertEquals(listOf("workspace.list", "workspace.read_file"), executor.catalog.definitions.map { it.name })
        assertTrue(executor.catalog.definitions.none { it.requiresApproval || it.supportsBackground })
        for (name in listOf("workspace.write_file", "terminal.execute", "workspace.create_file")) {
            assertFalse(executor.execute(SharedToolCall("c", name, emptyMap())).success)
        }
        assertEquals(0, files.accesses)
    }

    @Test fun inactiveOwnerAndInvalidArgumentsNeverTouchFiles() = runTest {
        val files = FakeFiles()
        val call = SharedToolCall("c", "workspace.read_file", mapOf("path" to "a.txt"))
        assertFalse(SharedReadOnlyWorkspaceExecutor(files) { false }.execute(call).success)
        val executor = SharedReadOnlyWorkspaceExecutor(files) { true }
        for (args in listOf(emptyMap(), mapOf("path" to "a", "max_chars" to "1.0"), mapOf("path" to "a", "max_chars" to "100001"),
            mapOf("path" to "../a"), mapOf("path" to "\uD800"), mapOf("path" to "a", "root" to "/private"))) {
            assertFalse(executor.execute(call.copy(arguments = args)).success)
        }
        assertEquals(0, files.accesses)
    }

    @Test fun revokedOwnerDiscardsLateReadWithoutLeakingText() = runTest {
        val files = FakeFiles()
        var active = true
        files.onRead = { active = false }
        val result = SharedReadOnlyWorkspaceExecutor(files) { active }.execute(SharedToolCall("c", "workspace.read_file", mapOf("path" to "a.txt")))
        assertFalse(result.success)
        assertFalse(result.content.contains("中"))
        assertNull(result.verified)
        assertNull(result.executionReceipt)
    }

    @Test fun fileFactsStayUnverifiedAndPortableResultRoundTrips() = runTest {
        val files = FakeFiles()
        val executor = SharedReadOnlyWorkspaceExecutor(files) { true }
        val result = executor.execute(SharedToolCall("c", "workspace.read_file", mapOf("path" to "a.txt")))
        assertTrue(result.success)
        assertEquals("中", Json.parseToJsonElement(result.content).jsonObject.getValue("text").jsonPrimitive.content)
        assertNull(result.verified); assertNull(result.verification); assertNull(result.executionReceipt)
        assertEquals(result, SharedToolExecutionResultCodec.decode(SharedToolExecutionResultCodec.encode(SharedToolResultFrame("c", result)), "c").result)
    }

    @Test fun cancelledCoroutineDoesNotReadOrPublishSuccessfulResult() = runTest {
        val files = FakeFiles()
        val executor = SharedReadOnlyWorkspaceExecutor(files) { true }
        val task = launch(start = CoroutineStart.UNDISPATCHED) {
            currentCoroutineContext().cancel()
            executor.execute(SharedToolCall("c", "workspace.list", emptyMap()))
        }
        task.join()
        assertTrue(task.isCancelled)
        assertEquals(0, files.accesses)
    }

    @Test fun ownerCallbackCancellationPreventsFileAccess() = runTest {
        val ownerJob = Job()
        val files = FakeFiles()
        val executor = SharedReadOnlyWorkspaceExecutor(files) { ownerJob.cancel(); true }
        val task = async(ownerJob) { executor.execute(SharedToolCall("c", "workspace.read_file", mapOf("path" to "a.txt"))) }
        assertFailsWith<CancellationException> { task.await() }
        assertEquals(0, files.accesses)
        ownerJob.cancel()
    }

    @Test fun cancellationDuringReadCannotPublishSuccessfulFacts() = runTest {
        val ownerJob = Job()
        val files = FakeFiles().apply { onRead = { ownerJob.cancel() } }
        val executor = SharedReadOnlyWorkspaceExecutor(files) { true }
        val task = async(ownerJob) { executor.execute(SharedToolCall("c", "workspace.read_file", mapOf("path" to "a.txt"))) }
        assertFailsWith<CancellationException> { task.await() }
        assertEquals(1, files.accesses)
        ownerJob.cancel()
    }
}
