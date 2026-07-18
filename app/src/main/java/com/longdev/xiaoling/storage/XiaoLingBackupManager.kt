package com.longdev.xiaoling.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.longdev.xiaoling.BuildConfig
import com.longdev.xiaoling.data.XiaoLingDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class XiaoLingRestoreResult(
    val manifest: XiaoLingBackupManifest,
    val restartRequired: Boolean,
)

class XiaoLingBackupManager(
    private val context: Context,
    private val database: XiaoLingDatabase = XiaoLingDatabase.getInstance(context),
) {
    suspend fun export(uri: android.net.Uri): XiaoLingBackupManifest = withContext(Dispatchers.IO) {
        val dbFile = context.getDatabasePath(XiaoLingDatabase.DATABASE_NAME)
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { }
        val localArchive = File.createTempFile("xiaoling-export-", ".zip", context.cacheDir)
        try {
            XiaoLingBackupArchive.create(
                output = localArchive,
                database = dbFile,
                schemaVersion = XiaoLingDatabase.CURRENT_VERSION,
                appVersion = BuildConfig.VERSION_NAME,
            )
            context.contentResolver.openOutputStream(uri)?.use { output ->
                localArchive.inputStream().buffered().use { input -> input.copyTo(output) }
            } ?: throw XiaoLingBackupException("无法写入用户选择的备份位置")
            XiaoLingBackupArchive.readManifest(localArchive)
        } finally {
            localArchive.delete()
        }
    }

    suspend fun restore(uri: android.net.Uri): XiaoLingRestoreResult = withContext(Dispatchers.IO) {
        val localArchive = File.createTempFile("xiaoling-import-", ".zip", context.cacheDir)
        val extracted = File.createTempFile("xiaoling-restore-", ".db", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                localArchive.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: throw XiaoLingBackupException("无法读取用户选择的备份文件")
            val manifest = XiaoLingBackupArchive.extractDatabase(
                archive = localArchive,
                destination = extracted,
                expectedSchemaVersion = XiaoLingDatabase.CURRENT_VERSION,
            )
            validateDatabaseVersion(extracted)
            val current = context.getDatabasePath(XiaoLingDatabase.DATABASE_NAME)
            val safetyCopy = File(current.parentFile, "${current.name}.pre-restore")
            XiaoLingDatabase.resetInstanceForRestore()
            if (current.isFile) current.copyTo(safetyCopy, overwrite = true)
            extracted.copyTo(current, overwrite = true)
            File(current.path + "-wal").delete()
            File(current.path + "-shm").delete()
            XiaoLingRestoreResult(manifest = manifest, restartRequired = true)
        } finally {
            localArchive.delete()
            extracted.delete()
        }
    }

    private fun validateDatabaseVersion(databaseFile: File) {
        val opened = SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            opened.rawQuery("PRAGMA user_version", null).use { cursor ->
                if (!cursor.moveToFirst()) throw XiaoLingBackupException("备份数据库缺少 user_version")
                val version = cursor.getInt(0)
                if (version <= 0) throw XiaoLingBackupException("备份数据库版本无效：$version")
                if (version > XiaoLingDatabase.CURRENT_VERSION) {
                    throw XiaoLingBackupException("备份数据库版本 $version 高于当前支持版本 ${XiaoLingDatabase.CURRENT_VERSION}")
                }
            }
        } finally {
            opened.close()
        }
    }
}
