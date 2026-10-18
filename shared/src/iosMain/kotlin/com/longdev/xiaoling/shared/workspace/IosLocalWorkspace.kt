@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.longdev.xiaoling.shared.workspace

import kotlinx.cinterop.*
import platform.Foundation.NSLock
import platform.Foundation.NSUUID
import platform.posix.*
import platform.posix.close as closeFd
import platform.posix.read as readFd
import platform.posix.write as writeFd

/** long: 宿主提供已创建的 App 私有目录；固定目录句柄与逐段 NOFOLLOW 使工具无法借符号链接逃出该能力根。 */
class IosLocalWorkspace(rootDirectoryPath: String) : SharedWorkspaceFiles {
    private val lock = NSLock()
    private var rootFd: Int

    init {
        require(rootDirectoryPath.startsWith('/') && '\u0000' !in rootDirectoryPath)
        rootDirectoryPath.encodeToByteArray(throwOnInvalidSequence = true)
        rootFd = open(rootDirectoryPath, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
        if (rootFd < 0) throw SharedWorkspaceAccessException("ROOT_UNAVAILABLE")
    }

    override fun list(path: String): List<SharedWorkspaceFileEntry> = locked {
        val parts = SharedWorkspaceFilePolicy.pathSegments(path, allowRoot = true)
        val directoryFd = directory(parts)
        // long: fdopendir 接管当前独立句柄；closedir 必须关闭它，不能复用根句柄的目录游标。
        val stream = fdopendir(directoryFd)
        if (stream == null) { closeFd(directoryFd); fail("DIRECTORY_UNAVAILABLE") }
        try {
            val entries = mutableListOf<SharedWorkspaceFileEntry>()
            var pathBytes = 0
            var scanned = 0
            while (true) {
                __error()!!.pointed.value = 0
                val entry = readdir(stream)
                if (entry == null) {
                    if (errno != 0) fail("DIRECTORY_READ_FAILED")
                    break
                }
                val name = strictText(entry.pointed.d_name.readBytes(entry.pointed.d_namlen.toInt()))
                if (name == "." || name == "..") continue
                // long: 中断暂存文件也消耗扫描预算，不能靠隐藏名称绕过单次工作量上限。
                if (++scanned > SharedWorkspaceFilePolicy.MAX_ENTRIES) fail("LIST_LIMIT_EXCEEDED")
                if (name.startsWith(SharedWorkspaceFilePolicy.STAGING_PREFIX)) continue
                val childParts = parts + name
                val relative = childParts.joinToString("/")
                SharedWorkspaceFilePolicy.pathSegments(relative, allowRoot = false)
                val info = memScoped {
                    val value = alloc<stat>()
                    if (fstatat(directoryFd, name, value.ptr, AT_SYMLINK_NOFOLLOW) != 0) fail("ENTRY_UNAVAILABLE")
                    val kind = value.st_mode.toInt() and S_IFMT
                    if (kind != S_IFDIR && kind != S_IFREG) fail("UNSAFE_ENTRY")
                    if (kind == S_IFREG && value.st_nlink.toInt() != 1) fail("HARD_LINK_REJECTED")
                    SharedWorkspaceFileEntry(relative, kind == S_IFDIR, if (kind == S_IFDIR) 0L else value.st_size)
                }
                entries += info
                pathBytes += relative.encodeToByteArray(throwOnInvalidSequence = true).size
                if (entries.size > SharedWorkspaceFilePolicy.MAX_ENTRIES || pathBytes > SharedWorkspaceFilePolicy.MAX_LIST_PATH_BYTES) fail("LIST_LIMIT_EXCEEDED")
            }
            entries.sortedWith(compareBy<SharedWorkspaceFileEntry> { !it.directory }.thenBy { it.path })
        } finally { closedir(stream) }
    }

    override fun readText(path: String, maxChars: Int): SharedWorkspaceText = locked {
        require(maxChars in 1..SharedWorkspaceFilePolicy.MAX_MAX_CHARS)
        val parts = SharedWorkspaceFilePolicy.pathSegments(path, allowRoot = false)
        val parent = directory(parts.dropLast(1))
        val fd = try { openat(parent, parts.last(), O_RDONLY or O_NOFOLLOW or O_NONBLOCK or O_CLOEXEC) }
            finally { closeFd(parent) }
        if (fd < 0) fail("FILE_UNAVAILABLE")
        try {
            val before = fileInfo(fd)
            val bytes = ByteArray(before.size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val count = bytes.usePinned { readFd(fd, it.addressOf(offset), (bytes.size - offset).convert()).toInt() }
                if (count < 0 && errno == EINTR) continue
                if (count <= 0) fail("FILE_CHANGED")
                offset += count
            }
            // long: 长度相同也可能被原地改写；读前后 inode/尺寸/修改时间复核，增长则用额外一字节探测拒绝。
            val extra = ByteArray(1)
            val more = extra.usePinned { readFd(fd, it.addressOf(0), 1u).toInt() }
            if (more != 0 || before != fileInfo(fd)) fail("FILE_CHANGED")
            val text = strictText(bytes)
            val preview = SharedWorkspaceFilePolicy.preview(text, maxChars)
            SharedWorkspaceText(parts.joinToString("/"), preview, bytes.size.toLong(), preview.length < text.length)
        } finally { closeFd(fd) }
    }

    /** long: 仅宿主可调用的创建入口，不进入只读工具目录；完整暂存文件以 linkat 发布，已有目标永不覆盖。 */
    fun createText(path: String, content: String) = locked {
        val parts = SharedWorkspaceFilePolicy.pathSegments(path, allowRoot = false)
        val bytes = content.encodeToByteArray(throwOnInvalidSequence = true)
        require(bytes.size <= SharedWorkspaceFilePolicy.MAX_FILE_BYTES) { "工作区文件超过限额" }
        val parent = directory(parts.dropLast(1))
        val temporary = SharedWorkspaceFilePolicy.STAGING_PREFIX + NSUUID().UUIDString
        var fd = -1
        var created = false
        try {
            fd = openat(parent, temporary, O_WRONLY or O_CREAT or O_EXCL or O_NOFOLLOW or O_CLOEXEC, 384)
            if (fd < 0) fail("CREATE_FAILED")
            created = true
            var offset = 0
            while (offset < bytes.size) {
                val count = bytes.usePinned { writeFd(fd, it.addressOf(offset), (bytes.size - offset).convert()).toInt() }
                if (count < 0 && errno == EINTR) continue
                if (count <= 0) fail("WRITE_FAILED")
                offset += count
            }
            if (fsync(fd) != 0) fail("SYNC_FAILED")
            if (linkat(parent, temporary, parent, parts.last(), 0) != 0) fail("TARGET_EXISTS_OR_PUBLISH_FAILED")
            if (unlinkat(parent, temporary, 0) != 0) fail("STAGING_CLEANUP_FAILED")
            created = false
        } finally {
            if (fd >= 0) closeFd(fd)
            if (created) unlinkat(parent, temporary, 0)
            closeFd(parent)
        }
    }

    override fun close() {
        lock.lock()
        try { if (rootFd >= 0) { closeFd(rootFd); rootFd = -1 } }
        finally { lock.unlock() }
    }

    private fun directory(parts: List<String>): Int {
        // long: openat(".") 得到独立打开描述，避免 dup 与目录枚举共享游标导致后续 list 遗漏条目。
        var fd = openat(rootFd, ".", O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
        if (fd < 0) fail("DIRECTORY_UNAVAILABLE")
        try {
            parts.forEach { name ->
                val next = openat(fd, name, O_RDONLY or O_DIRECTORY or O_NOFOLLOW or O_CLOEXEC)
                if (next < 0) fail("DIRECTORY_UNAVAILABLE")
                closeFd(fd); fd = next
            }
            return fd
        } catch (error: Throwable) { closeFd(fd); throw error }
    }

    private data class FileInfo(val inode: ULong, val size: Long, val modifiedSeconds: Long, val modifiedNanos: Long)
    private fun fileInfo(fd: Int): FileInfo = memScoped {
        val value = alloc<stat>()
        if (fstat(fd, value.ptr) != 0) fail("FILE_UNAVAILABLE")
        if ((value.st_mode.toInt() and S_IFMT) != S_IFREG) fail("NOT_REGULAR_FILE")
        if (value.st_nlink.toInt() != 1) fail("HARD_LINK_REJECTED")
        if (value.st_size !in 0..SharedWorkspaceFilePolicy.MAX_FILE_BYTES.toLong()) fail("FILE_LIMIT_EXCEEDED")
        FileInfo(value.st_ino, value.st_size, value.st_mtimespec.tv_sec, value.st_mtimespec.tv_nsec)
    }

    private fun strictText(bytes: ByteArray): String = try { bytes.decodeToString(throwOnInvalidSequence = true) }
        catch (_: CharacterCodingException) { fail("INVALID_UTF8") }

    private fun <T> locked(block: () -> T): T {
        lock.lock()
        try { if (rootFd < 0) fail("WORKSPACE_CLOSED"); return block() }
        finally { lock.unlock() }
    }
    private fun fail(code: String): Nothing = throw SharedWorkspaceAccessException(code)
}
