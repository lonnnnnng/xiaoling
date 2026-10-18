package com.longdev.xiaoling.shared.workspace

import com.longdev.xiaoling.shared.agent.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** long: 宿主必须把前台检查绑定当前 Run/会话；静态目录声明不能替代真实前台生命周期。 */
class SharedReadOnlyWorkspaceExecutor(
    private val files: SharedWorkspaceFiles,
    private val isForegroundOwnerActive: () -> Boolean,
) : SharedToolExecutor {
    val catalog: SharedToolCatalog = SharedToolCatalog.from(listOf(
        SharedToolDefinition("workspace.list", "列出本地工作区一级文件", false, false),
        SharedToolDefinition("workspace.read_file", "读取本地工作区 UTF-8 文件预览", false, false),
    ))

    override suspend fun execute(toolCall: SharedToolCall): SharedToolExecutionResult = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        if (!isForegroundOwnerActive()) return@withContext failure("FOREGROUND_OWNER_REQUIRED")
        try {
            require(toolCall.id.isNotBlank())
            val content = when (toolCall.name) {
                "workspace.list" -> {
                    require(toolCall.arguments.keys.all { it == "path" })
                    val path = toolCall.arguments["path"] ?: "."
                    SharedWorkspaceFilePolicy.pathSegments(path, allowRoot = true)
                    currentCoroutineContext().ensureActive()
                    val entries = files.list(path)
                    buildJsonObject { put("entries", JsonArray(entries.map { entry -> buildJsonObject {
                        put("path", entry.path); put("directory", entry.directory); put("sizeBytes", entry.sizeBytes)
                    } })) }.toString()
                }
                "workspace.read_file" -> {
                    require(toolCall.arguments.keys.all { it in setOf("path", "max_chars") })
                    val path = requireNotNull(toolCall.arguments["path"])
                    val maxChars = toolCall.arguments["max_chars"]?.let {
                        require(it.matches(Regex("[1-9][0-9]*")))
                        it.toInt()
                    } ?: SharedWorkspaceFilePolicy.DEFAULT_MAX_CHARS
                    SharedWorkspaceFilePolicy.pathSegments(path, allowRoot = false)
                    require(maxChars in 1..SharedWorkspaceFilePolicy.MAX_MAX_CHARS)
                    // long: 宿主前台检查本身可能触发取消；再次核查后才开始阻塞文件读取。
                    currentCoroutineContext().ensureActive()
                    val result = files.readText(path, maxChars)
                    buildJsonObject {
                        put("path", result.path); put("text", result.text)
                        put("sizeBytes", result.sizeBytes); put("truncated", result.truncated)
                    }.toString()
                }
                else -> return@withContext failure("UNSUPPORTED_WORKSPACE_TOOL")
            }
            currentCoroutineContext().ensureActive()
            // long: 前台撤销或 Run 切换后，迟到读取不能再向当前执行账本发布文件正文。
            if (!isForegroundOwnerActive()) failure("FOREGROUND_OWNER_REVOKED")
            else {
                currentCoroutineContext().ensureActive()
                SharedToolExecutionResult(success = true, content = content)
            }
        } catch (error: SharedWorkspaceAccessException) {
            failure(error.code)
        } catch (_: IllegalArgumentException) {
            failure("INVALID_WORKSPACE_ARGUMENTS")
        }
    }

    private fun failure(code: String) = SharedToolExecutionResult(false, "工作区工具拒绝：$code")
}
