package com.longdev.xiaoling.storage

import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.json.JSONObject

data class XiaoLingBackupManifest(
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Long,
    val providerCiphertextRequiresKeystore: Boolean,
)

class XiaoLingBackupException(message: String, cause: Throwable? = null) : IOException(message, cause)

object XiaoLingBackupArchive {
    private const val MANIFEST_ENTRY = "manifest.json"
    private const val DATABASE_ENTRY = "xiaoling.db"

    fun create(
        output: File,
        database: File,
        schemaVersion: Int,
        appVersion: String,
        createdAt: Long = System.currentTimeMillis(),
    ) {
        if (!database.isFile || database.length() == 0L) {
            throw XiaoLingBackupException("Room 数据库文件不存在或为空")
        }
        val manifest = JSONObject()
            .put("schemaVersion", schemaVersion)
            .put("appVersion", appVersion)
            .put("createdAt", createdAt)
            // long: Provider 的 API Key 仍是 Android Keystore 密文；manifest 明确记录恢复边界，避免用户误以为备份可跨设备解密凭据。
            .put("providerCiphertextRequiresKeystore", true)
        try {
            ZipOutputStream(output.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
                zip.write(manifest.toString().toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(DATABASE_ENTRY))
                database.inputStream().buffered().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        } catch (error: IOException) {
            throw XiaoLingBackupException("创建小灵备份失败", error)
        }
    }

    fun readManifest(archive: File): XiaoLingBackupManifest {
        return try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(MANIFEST_ENTRY)
                    ?: throw XiaoLingBackupException("备份缺少 manifest.json")
                val json = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { it.readText() }
                XiaoLingBackupManifest(
                    schemaVersion = json.toJsonObject().getInt("schemaVersion"),
                    appVersion = json.toJsonObject().getString("appVersion"),
                    createdAt = json.toJsonObject().getLong("createdAt"),
                    providerCiphertextRequiresKeystore = json.toJsonObject()
                        .optBoolean("providerCiphertextRequiresKeystore", false),
                )
            }
        } catch (error: XiaoLingBackupException) {
            throw error
        } catch (error: Exception) {
            throw XiaoLingBackupException("备份格式无效", error)
        }
    }

    fun extractDatabase(archive: File, destination: File, expectedSchemaVersion: Int): XiaoLingBackupManifest {
        val manifest = readManifest(archive)
        if (manifest.schemaVersion > expectedSchemaVersion) {
            throw XiaoLingBackupException(
                "备份数据库版本 ${manifest.schemaVersion} 高于当前支持版本 $expectedSchemaVersion",
            )
        }
        try {
            ZipFile(archive).use { zip ->
                val entry = zip.getEntry(DATABASE_ENTRY)
                    ?: throw XiaoLingBackupException("备份缺少 xiaoling.db")
                destination.parentFile?.mkdirs()
                zip.getInputStream(entry).buffered().use { input ->
                    destination.outputStream().buffered().use { output -> input.copyTo(output) }
                }
            }
        } catch (error: XiaoLingBackupException) {
            throw error
        } catch (error: Exception) {
            throw XiaoLingBackupException("读取备份数据库失败", error)
        }
        return manifest
    }

    private fun String.toJsonObject(): JSONObject = JSONObject(this)
}
