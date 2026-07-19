package com.longdev.xiaoling.storage

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.longdev.xiaoling.knowledge.KnowledgeDocumentException
import com.longdev.xiaoling.knowledge.KnowledgeDocumentImport
import com.longdev.xiaoling.knowledge.KnowledgeTextPolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream

class KnowledgeDocumentReader(
    context: Context,
) {
    private val contentResolver = context.contentResolver

    fun read(uri: Uri): KnowledgeDocumentImport {
        return try {
            val metadata = readMetadata(uri)
            if (metadata.size != null && metadata.size > KnowledgeTextPolicy.MAX_IMPORT_BYTES) {
                throw KnowledgeDocumentException("知识文档不能超过 64 MB")
            }
            val bytes = contentResolver.openInputStream(uri)?.use(::readBounded)
                ?: throw KnowledgeDocumentException("无法读取所选知识文档")
            KnowledgeDocumentImport(
                fileName = metadata.fileName.ifBlank { uri.lastPathSegment.orEmpty() },
                declaredMimeType = contentResolver.getType(uri).orEmpty(),
                bytes = bytes,
            )
        } catch (error: KnowledgeDocumentException) {
            throw error
        } catch (error: SecurityException) {
            throw KnowledgeDocumentException("没有权限读取所选知识文档", error)
        } catch (error: Exception) {
            throw KnowledgeDocumentException(error.message ?: "无法读取所选知识文档", error)
        }
    }

    private fun readMetadata(uri: Uri): KnowledgeMetadata {
        return runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                KnowledgeMetadata(
                    fileName = nameIndex.takeIf { it >= 0 }?.let(cursor::getString).orEmpty(),
                    size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
                )
            }
        }.getOrNull() ?: KnowledgeMetadata(fileName = "", size = null)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        // long: DocumentsProvider 可以隐瞒文件大小；逐块读取并在写入前检查 64 MB，避免未知长度资料先占满应用堆。
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (total.toLong() + count > KnowledgeTextPolicy.MAX_IMPORT_BYTES) {
                throw KnowledgeDocumentException("知识文档不能超过 64 MB")
            }
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private data class KnowledgeMetadata(
        val fileName: String,
        val size: Long?,
    )
}
