package com.longdev.xiaoling.storage

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import com.longdev.xiaoling.model.DocumentAttachment
import com.longdev.xiaoling.model.DocumentAttachmentException
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class DocumentAttachmentReader(
    private val context: Context,
) {
    private val contentResolver = context.contentResolver

    fun read(uri: Uri): DocumentAttachment {
        return try {
            val metadata = readMetadata(uri)
            if (metadata.size != null && metadata.size > DocumentAttachmentPolicy.MAX_DOCUMENT_BYTES) {
                throw DocumentAttachmentException(
                    "文档不能超过 ${DocumentAttachmentPolicy.MAX_DOCUMENT_BYTES / 1024 / 1024} MB",
                )
            }
            val fileName = metadata.fileName.ifBlank { uri.lastPathSegment.orEmpty() }
            val data = contentResolver.openInputStream(uri)?.use(::readBounded)
                ?: throw DocumentAttachmentException("无法读取所选文档")
            val mimeType = DocumentAttachmentPolicy.resolveMimeType(
                fileName = fileName,
                declaredMimeType = contentResolver.getType(uri),
                data = data,
            )
            val pageCount = if (mimeType == PDF_MIME_TYPE) validatePdfAndGetPageCount(data) else null
            DocumentAttachmentPolicy.create(
                fileName = fileName,
                mimeType = mimeType,
                data = data,
                pageCount = pageCount,
            )
        } catch (error: DocumentAttachmentException) {
            throw error
        } catch (error: SecurityException) {
            throw DocumentAttachmentException("没有权限读取所选文档")
        } catch (error: Exception) {
            throw DocumentAttachmentException(error.message ?: "无法读取所选文档")
        }
    }

    private fun readMetadata(uri: Uri): DocumentMetadata {
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
                DocumentMetadata(
                    fileName = nameIndex.takeIf { it >= 0 }?.let(cursor::getString).orEmpty(),
                    size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
                )
            }
        }.getOrNull() ?: DocumentMetadata(fileName = "", size = null)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        // long: DocumentsProvider 可能不返回大小；分块读取仍以 8 MB 为硬上限，避免未知长度文件在进入策略校验前占满应用堆。
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (total + count > DocumentAttachmentPolicy.MAX_DOCUMENT_BYTES) {
                throw DocumentAttachmentException(
                    "文档不能超过 ${DocumentAttachmentPolicy.MAX_DOCUMENT_BYTES / 1024 / 1024} MB",
                )
            }
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private fun validatePdfAndGetPageCount(data: ByteArray): Int {
        val tempFile = File.createTempFile("xiaoling-document-", ".pdf", context.cacheDir)
        return try {
            tempFile.outputStream().use { it.write(data) }
            ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    // long: PDF 头签名不足以证明文件可读；平台渲染器打开成功并给出页数后，才能执行移动端页数预算并写入 Room。
                    renderer.pageCount
                }
            }
        } catch (_: Exception) {
            throw DocumentAttachmentException("PDF 文件已损坏或无法解析")
        } finally {
            tempFile.delete()
        }
    }

    private data class DocumentMetadata(
        val fileName: String,
        val size: Long?,
    )

    companion object {
        private const val PDF_MIME_TYPE = "application/pdf"
    }
}
