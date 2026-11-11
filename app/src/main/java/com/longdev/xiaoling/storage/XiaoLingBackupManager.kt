package com.longdev.xiaoling.storage

import android.content.Context
import com.longdev.xiaoling.BuildConfig
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile

data class XiaoLingRestoreResult(
    val manifest: XiaoLingBackupManifest,
    val restartRequired: Boolean,
)

class XiaoLingBackupManager(
    private val context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
    private val databaseName: String = XiaoLingDatabase.DATABASE_NAME,
) {
    suspend fun export(uri: android.net.Uri): XiaoLingBackupManifest = withContext(Dispatchers.IO) {
        val localArchive = File.createTempFile("xiaoling-export-", ".zip", context.cacheDir)
        var snapshot: File? = null
        try {
            val snapshotFile = File.createTempFile("xiaoling-export-snapshot-", ".db", context.cacheDir).also { snapshot = it }
            // long: checkpoint 后复制主文件仍可能漏掉并发写入或未刷回的 WAL；由 SQLite 生成事务快照，原库保持在线且不被替换。
            DatabaseSnapshotExporter.write(database, snapshotFile)
            RandomAccessFile(snapshotFile, "rw").use { it.fd.sync() }
            BackupDatabaseValidator.checkIntegrityAndVersion(snapshotFile, XiaoLingDatabase.CURRENT_VERSION)
            XiaoLingBackupArchive.create(
                output = localArchive,
                database = snapshotFile,
                schemaVersion = XiaoLingDatabase.CURRENT_VERSION,
                appVersion = BuildConfig.VERSION_NAME,
            )
            context.contentResolver.openOutputStream(uri)?.use { output ->
                localArchive.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw XiaoLingBackupException("无法写入用户选择的备份位置")
            XiaoLingBackupArchive.readManifest(localArchive)
        } finally {
            localArchive.delete()
            snapshot?.let { file ->
                file.delete()
                listOf("-wal", "-shm", "-journal").forEach { File(file.path + it).delete() }
            }
        }
    }

    suspend fun restore(uri: android.net.Uri): XiaoLingRestoreResult = withContext(Dispatchers.IO) {
        val localArchive = File.createTempFile("xiaoling-import-", ".zip", context.cacheDir)
        val current = context.getDatabasePath(databaseName)
        val extracted = File.createTempFile("xiaoling-restore-", ".db", current.parentFile)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                localArchive.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: throw XiaoLingBackupException("无法读取用户选择的备份文件")
            val manifest = XiaoLingBackupArchive.extractDatabase(
                archive = localArchive,
                destination = extracted,
                expectedSchemaVersion = XiaoLingDatabase.CURRENT_VERSION,
            )
            BackupDatabaseValidator.prepare(context, extracted, manifest.schemaVersion)
            // long: 在线 Room 和 Worker 继续使用当前数据；只有下次进程首次打开数据库时才原子激活，防止旧连接覆盖恢复结果。
            androidDatabaseRestoreFiles(current).stage(extracted)
            XiaoLingRestoreResult(manifest = manifest, restartRequired = true)
        } finally {
            localArchive.delete()
            extracted.delete()
            listOf("-wal", "-shm", "-journal").forEach { File(extracted.path + it).delete() }
        }
    }

}
