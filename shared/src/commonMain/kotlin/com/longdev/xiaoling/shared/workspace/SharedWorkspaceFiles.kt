package com.longdev.xiaoling.shared.workspace

object SharedWorkspaceFilePolicy {
    const val VERSION = 1
    const val MAX_FILE_BYTES = 512 * 1024
    const val DEFAULT_MAX_CHARS = 20_000
    const val MAX_MAX_CHARS = 100_000
    const val MAX_PATH_CHARS = 512
    const val MAX_ENTRIES = 256
    const val MAX_LIST_PATH_BYTES = 64 * 1024
    const val STAGING_PREFIX = ".xiaoling-stage-"

    fun pathSegments(path: String, allowRoot: Boolean): List<String> {
        require(path.length <= MAX_PATH_CHARS) { "工作区路径超过限额" }
        require(path.none { it.code < 32 || it.code == 127 }) { "工作区路径包含控制字符" }
        val normalized = path.ifBlank { "." }
        if (normalized == ".") {
            require(allowRoot) { "文件路径不能指向工作区根目录" }
            return emptyList()
        }
        require(!normalized.startsWith('/') && '\\' !in normalized) { "工作区只接受相对路径" }
        val parts = normalized.split('/')
        // long: 不折叠父目录、重复分隔符或点段，避免不同平台把同一工具参数解析为不同目标。
        require(parts.all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith(STAGING_PREFIX) }) {
            "工作区路径包含不允许的目录段"
        }
        val sizes = try { parts.map { it.encodeToByteArray(throwOnInvalidSequence = true).size } }
            catch (_: CharacterCodingException) { throw IllegalArgumentException("工作区路径包含无效 Unicode") }
        require(sizes.all { it <= 255 }) { "工作区文件名超过限额" }
        return parts
    }

    fun preview(text: String, maxChars: Int): String {
        require(maxChars in 1..MAX_MAX_CHARS) { "工作区预览长度无效" }
        var end = minOf(maxChars, text.length)
        // long: 预览不得切断 emoji 的 UTF-16 代理对；截断事实单独返回，不能用替换字符冒充原文。
        if (end < text.length && end > 0 && text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        return text.substring(0, end)
    }
}

data class SharedWorkspaceFileEntry(val path: String, val directory: Boolean, val sizeBytes: Long)
data class SharedWorkspaceText(val path: String, val text: String, val sizeBytes: Long, val truncated: Boolean)

/** long: 文件端口与终端命令分开，平台提供根目录能力；模型参数只允许选择根内相对路径。 */
interface SharedWorkspaceFiles {
    fun list(path: String = "."): List<SharedWorkspaceFileEntry>
    fun readText(path: String, maxChars: Int = SharedWorkspaceFilePolicy.DEFAULT_MAX_CHARS): SharedWorkspaceText
    fun close()
}

/** long: 平台失败只暴露固定分类，不能把宿主绝对路径、文件正文或系统错误原文带入工具结果。 */
class SharedWorkspaceAccessException(val code: String) : IllegalStateException("工作区访问失败：$code")
