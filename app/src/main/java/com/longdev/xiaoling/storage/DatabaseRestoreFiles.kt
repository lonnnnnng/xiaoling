package com.longdev.xiaoling.storage

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

/** long: 已验证备份只在下次数据库打开前激活，避免在线连接与恢复互相覆盖。 */
internal class DatabaseRestoreFiles(
    private val current: File,
    private val move: (File, File) -> Unit = { source, target ->
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        Unit
    },
    private val syncDirectory: (File) -> Unit = { directory ->
        java.nio.channels.FileChannel.open(directory.toPath(), java.nio.file.StandardOpenOption.READ).use { it.force(true) }
    },
) {
    private val ready = File(current.path + ".restore-ready")
    private val safety = File(current.path + ".pre-restore")
    private val installing = File(current.path + ".restore-installing")
    val hasUnfinishedRestore: Boolean get() = installing.exists()

    fun stage(validated: File) {
        require(!installing.exists()) { "上次恢复尚未完成，请先重新打开应用" }
        val staged = File(current.path + ".restore-staging")
        try {
            copySynced(validated, staged)
            move(staged, ready)
            syncParent()
        } catch (error: Exception) {
            ready.delete()
            try { syncParent() } catch (cleanupError: Exception) { error.addSuppressed(cleanupError) }
            throw error
        } finally { staged.delete() }
    }

    fun activate(
        prepareCurrent: () -> Unit,
        validate: (File) -> Unit,
        verifyInstalled: () -> Unit,
    ): Boolean {
        if (installing.exists()) {
            // long: 替换后尚未确认首次打开便退出的进程，下一次启动必须先恢复旧库，不能把半次恢复当成功。
            rollback()
            ready.delete()
            syncParent()
            return false
        }
        if (!ready.isFile) return false
        try { validate(ready) } catch (error: Exception) {
            ready.delete()
            syncParent()
            throw error
        }
        prepareCurrent()
        require(current.isFile) { "恢复前的数据库不存在" }
        val safetyStaging = File(safety.path + ".tmp")
        try {
            copySynced(current, safetyStaging)
            move(safetyStaging, safety)
            syncParent()
        } finally { safetyStaging.delete() }
        try {
            FileOutputStream(installing).use { output -> output.write(1); output.fd.sync() }
            syncParent()
            // long: ready 与正式库在同一目录；原子 rename 让断电只留下完整旧库或完整新库，绝不覆盖一半正式文件。
            move(ready, current)
            syncParent()
            verifyInstalled()
            check(installing.delete()) { "无法确认数据库恢复已完成" }
            syncParent()
            return true
        } catch (error: Exception) {
            try { rollback() } catch (rollbackError: Exception) { error.addSuppressed(rollbackError) }
            ready.delete()
            throw XiaoLingBackupException("备份激活失败，已尝试恢复原数据库", error)
        }
    }

    private fun rollback() {
        check(safety.isFile) { "恢复安全副本不存在" }
        val rollback = File(current.path + ".restore-rollback")
        try {
            copySynced(safety, rollback)
            // long: 首次打开失败的候选库可能留下自己的日志；关闭连接后清除这些旁文件，禁止把新库 WAL 重放到安全副本。
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                val sidecar = File(current.path + suffix)
                check(!sidecar.exists() || sidecar.delete()) { "无法清除恢复候选日志" }
            }
            move(rollback, current)
            syncParent()
            check(installing.delete() || !installing.exists()) { "无法清除恢复中标记" }
            syncParent()
        } finally { rollback.delete() }
    }

    private fun copySynced(source: File, destination: File) {
        FileOutputStream(destination).use { output ->
            source.inputStream().use { it.copyTo(output) }
            output.fd.sync()
        }
    }

    private fun syncParent() = syncDirectory(requireNotNull(current.parentFile))
}
