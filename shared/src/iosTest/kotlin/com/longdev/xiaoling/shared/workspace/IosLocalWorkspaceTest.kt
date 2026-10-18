@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.longdev.xiaoling.shared.workspace

import com.longdev.xiaoling.shared.agent.*
import kotlinx.cinterop.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import platform.Foundation.*
import platform.posix.*
import kotlin.test.*

class IosLocalWorkspaceTest {
    // long: 每项原生测试只创建并清理当前 UUID 的临时目录，不能接触宿主正式 workspace 或应用偏好。
    private fun <T> workspace(block: (String, IosLocalWorkspace) -> T): T {
        val root = NSTemporaryDirectory() + "xiaoling-ios-workspace-test-" + NSUUID().UUIDString
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null))
        val files = IosLocalWorkspace(root)
        try { return block(root, files) }
        finally {
            files.close()
            require(root.startsWith(NSTemporaryDirectory() + "xiaoling-ios-workspace-test-"))
            assertTrue(NSFileManager.defaultManager.removeItemAtPath(root, null))
        }
    }
    private fun rawFile(path: String, bytes: ByteArray) {
        val fd = open(path, O_WRONLY or O_CREAT or O_EXCL, 384)
        assertTrue(fd >= 0)
        try {
            if (bytes.isNotEmpty()) bytes.usePinned { assertEquals(bytes.size.toLong(), write(fd, it.addressOf(0), bytes.size.convert())) }
        } finally { close(fd) }
    }

    @Test fun nativeCreateReadRepeatedListAndReopenKeepRealFiles(): Unit = workspace { root, files ->
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath("$root/笔记", false, null, null))
        files.createText("笔记/内容😀.txt", "原生文件😀\n第二行")
        files.createText("z.txt", "")
        val expected = listOf(SharedWorkspaceFileEntry("笔记", true, 0), SharedWorkspaceFileEntry("z.txt", false, 0))
        assertEquals(expected, files.list("."))
        assertEquals(expected, files.list("."))
        assertEquals("原生文件😀\n第二行", files.readText("笔记/内容😀.txt", 100).text)
        assertEquals("", files.readText("z.txt", 1).text)
        val reopened = IosLocalWorkspace(root)
        try { assertEquals(files.readText("笔记/内容😀.txt", 100), reopened.readText("笔记/内容😀.txt", 100)) }
        finally { reopened.close() }
    }

    @Test fun hostCreateNeverOverwritesExistingTargetOrFollowsLink(): Unit = workspace { root, files ->
        files.createText("a", "旧正文")
        assertFailsWith<SharedWorkspaceAccessException> { files.createText("a", "新正文") }
        assertEquals("旧正文", files.readText("a", 100).text)
        assertEquals(0, symlink("$root/a", "$root/link"))
        assertFailsWith<SharedWorkspaceAccessException> { files.createText("link", "不可覆盖") }
        assertEquals("旧正文", files.readText("a", 100).text)
        assertFalse(NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, null)!!.any { (it as String).startsWith(SharedWorkspaceFilePolicy.STAGING_PREFIX) })
    }

    @Test fun relativeTraversalAndAbsoluteTargetsCannotReadOrCreate(): Unit = workspace { root, files ->
        for (path in listOf("../outside", "$root/a", "dir/../a", "dir//a", "\na")) {
            assertFailsWith<IllegalArgumentException> { files.readText(path, 1) }
            assertFailsWith<IllegalArgumentException> { files.createText(path, "x") }
        }
        assertTrue(files.list(".").isEmpty())
        assertFailsWith<SharedWorkspaceAccessException> { files.list("missing") }
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$root/missing"))
    }

    @Test fun symlinkInParentAndLeafAndRootIsRejected(): Unit = workspace { root, files ->
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath("$root/real", false, null, null))
        files.createText("real/a", "内容")
        assertEquals(0, symlink("$root/real", "$root/alias"))
        assertEquals(0, symlink("$root/real/a", "$root/leaf"))
        for (path in listOf("alias/a", "leaf")) assertFailsWith<SharedWorkspaceAccessException> { files.readText(path, 10) }
        assertFailsWith<SharedWorkspaceAccessException> { files.createText("alias/new", "x") }
        assertFailsWith<SharedWorkspaceAccessException> { files.list(".") }
        assertFailsWith<SharedWorkspaceAccessException> { IosLocalWorkspace("$root/alias") }
        Unit
    }

    @Test fun hardLinksDirectoriesAndFifoAreNotReadableRegularFiles(): Unit = workspace { root, files ->
        files.createText("a", "原文")
        assertEquals(0, link("$root/a", "$root/hard"))
        assertFailsWith<SharedWorkspaceAccessException> { files.readText("hard", 100) }
        assertFailsWith<SharedWorkspaceAccessException> { files.list(".") }
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath("$root/dir", false, null, null))
        assertFailsWith<SharedWorkspaceAccessException> { files.readText("dir", 100) }
        assertEquals(0, mkfifo("$root/pipe", 384u))
        assertFailsWith<SharedWorkspaceAccessException> { files.readText("pipe", 100) }
    }

    @Test fun malformedUtf8IsRejectedAndEmojiPreviewStaysWhole(): Unit = workspace { root, files ->
        rawFile("$root/invalid", byteArrayOf(0xc3.toByte(), 0x28))
        val error = assertFailsWith<SharedWorkspaceAccessException> { files.readText("invalid", 100) }
        assertEquals("INVALID_UTF8", error.code)
        files.createText("emoji", "a😀b")
        val preview = files.readText("emoji", 2)
        assertEquals("a", preview.text); assertTrue(preview.truncated); assertEquals(6L, preview.sizeBytes)
        assertFailsWith<CharacterCodingException> { files.createText("bad", "\uD800") }
        assertFalse(NSFileManager.defaultManager.fileExistsAtPath("$root/bad"))
    }

    @Test fun exactByteLimitAndOversizedHostFilesFailClosed(): Unit = workspace { root, files ->
        val bytes = SharedWorkspaceFilePolicy.MAX_FILE_BYTES
        val text = "中".repeat(bytes / 3) + "a".repeat(bytes % 3)
        files.createText("bounded", text)
        assertEquals(bytes.toLong(), files.readText("bounded", 100_000).sizeBytes)
        assertTrue(files.readText("bounded", 100_000).truncated)
        assertFailsWith<IllegalArgumentException> { files.createText("too-large", text + "a") }
        rawFile("$root/oversized", ByteArray(bytes + 1))
        assertEquals("FILE_LIMIT_EXCEEDED", assertFailsWith<SharedWorkspaceAccessException> { files.readText("oversized", 1) }.code)
    }

    @Test fun listingHasExactCountLimitIncludingInterruptedStagingFiles(): Unit = workspace { root, files ->
        repeat(256) { files.createText("f$it", "") }
        assertEquals(256, files.list(".").size)
        rawFile("$root/${SharedWorkspaceFilePolicy.STAGING_PREFIX}interrupted", byteArrayOf())
        assertEquals("LIST_LIMIT_EXCEEDED", assertFailsWith<SharedWorkspaceAccessException> { files.list(".") }.code)
    }

    @Test fun listingPathBudgetRejectsLargeDirectoryWithoutPartialFacts(): Unit = workspace { root, files ->
        val directory = "d".repeat(255)
        assertTrue(NSFileManager.defaultManager.createDirectoryAtPath("$root/$directory", false, null, null))
        repeat(130) { files.createText("$directory/" + it.toString().padStart(255, 'f'), "") }
        assertEquals("LIST_LIMIT_EXCEEDED", assertFailsWith<SharedWorkspaceAccessException> { files.list(directory) }.code)
    }

    @Test fun closeIsIdempotentAndRevokesAllNativeOperations(): Unit = workspace { _, files ->
        files.createText("a", "原文")
        files.close(); files.close()
        for (operation in listOf<() -> Unit>({ files.list(".") }, { files.readText("a", 100) }, { files.createText("b", "x") })) {
            assertEquals("WORKSPACE_CLOSED", assertFailsWith<SharedWorkspaceAccessException> { operation() }.code)
        }
    }

    @Test fun nativeWorkspaceRunsThroughSharedAgentLoopAndResultCodec(): Unit = workspace { _, files -> runBlocking {
        files.createText("readme.txt", "Simulator 原生正文😀")
        val calls = listOf(SharedToolCall("list-call", "workspace.list", emptyMap()),
            SharedToolCall("read-call", "workspace.read_file", mapOf("path" to "readme.txt")))
        val executor = SharedReadOnlyWorkspaceExecutor(files) { true }
        val llm = object : SharedAgentLlm {
            override suspend fun proposeNextAction(goal: String, tools: List<SharedToolDefinition>, completedCalls: List<SharedToolCall>) =
                calls.getOrNull(completedCalls.size)?.let { SharedAgentPlanDecision.CallTool(it) } ?: SharedAgentPlanDecision.Complete
            override suspend fun summarize(goal: String, completedCalls: List<SharedToolCall>) = "只读 smoke 已结束"
        }
        val gate = object : SharedApprovalGate {
            override suspend fun requestApproval(runId: String, toolCall: SharedToolCall, definition: SharedToolDefinition): Boolean = error("只读工具不应请求写入审批")
        }
        val result = SharedAgentRuntime(llm, gate, executor).run("native-run", "读隔离文件", executor.catalog)
        assertEquals(SharedAgentRunStatus.COMPLETED, result.status)
        assertEquals(calls, result.executions.map { it.toolCall })
        val readResult = result.executions.last().result
        assertEquals("Simulator 原生正文😀", Json.parseToJsonElement(readResult.content).jsonObject.getValue("text").jsonPrimitive.content)
        assertNull(readResult.verified); assertNull(readResult.executionReceipt)
        assertEquals(readResult, SharedToolExecutionResultCodec.decode(SharedToolExecutionResultCodec.encode(SharedToolResultFrame("read-call", readResult)), "read-call").result)
    } }
}
