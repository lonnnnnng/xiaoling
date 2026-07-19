package com.longdev.xiaoling.storage

import android.content.ContentResolver
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.longdev.xiaoling.model.ImageAttachment
import com.longdev.xiaoling.model.ImageAttachmentException
import com.longdev.xiaoling.model.ImageAttachmentPolicy
import java.io.ByteArrayOutputStream
import java.io.InputStream

class ImageAttachmentReader(
    private val contentResolver: ContentResolver,
) {
    fun read(uri: Uri): ImageAttachment {
        return try {
            val metadata = readMetadata(uri)
            if (metadata.size != null && metadata.size > ImageAttachmentPolicy.MAX_IMAGE_BYTES) {
                throw ImageAttachmentException("图片不能超过 ${ImageAttachmentPolicy.MAX_IMAGE_BYTES / 1024 / 1024} MB")
            }
            val fileName = metadata.fileName.ifBlank { uri.lastPathSegment.orEmpty() }
            val mimeType = contentResolver.getType(uri)
                ?.normalizeImageMimeType()
                ?: fileName.inferImageMimeType()
                ?: throw ImageAttachmentException("无法识别图片格式，仅支持 PNG、JPEG 和 WEBP")
            val data = contentResolver.openInputStream(uri)?.use(::readBounded)
                ?: throw ImageAttachmentException("无法读取所选图片")
            val attachment = ImageAttachmentPolicy.create(
                fileName = fileName,
                mimeType = mimeType,
                data = data,
            )
            validateDecodableImage(data, attachment.mimeType)
            attachment
        } catch (error: ImageAttachmentException) {
            throw error
        } catch (error: SecurityException) {
            throw ImageAttachmentException("没有权限读取所选图片")
        } catch (error: Exception) {
            throw ImageAttachmentException(error.message ?: "无法读取所选图片")
        }
    }

    private fun readMetadata(uri: Uri): ImageMetadata {
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
                ImageMetadata(
                    fileName = nameIndex.takeIf { it >= 0 }?.let(cursor::getString).orEmpty(),
                    size = sizeIndex.takeIf { it >= 0 && !cursor.isNull(it) }?.let(cursor::getLong),
                )
            }
        }.getOrNull() ?: ImageMetadata(fileName = "", size = null)
    }

    private fun readBounded(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        // long: 部分 DocumentsProvider 不提供可靠长度；每个分块写入前再次核对总量，确保未知长度流也不会短暂越过 8 MB 内存边界。
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (total + count > ImageAttachmentPolicy.MAX_IMAGE_BYTES) {
                throw ImageAttachmentException("图片不能超过 ${ImageAttachmentPolicy.MAX_IMAGE_BYTES / 1024 / 1024} MB")
            }
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }

    private fun validateDecodableImage(data: ByteArray, mimeType: String) {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, options)
        val decodedMimeType = options.outMimeType?.normalizeImageMimeType()
        if (options.outWidth <= 0 || options.outHeight <= 0 || decodedMimeType != mimeType) {
            // long: 文件签名只能证明头部格式；再用平台解码器核对尺寸和 MIME，避免损坏内容进入 Room 后在每次消息渲染时反复失败。
            throw ImageAttachmentException("图片文件已损坏或内容与格式不一致")
        }
        var sampleSize = 1
        while (options.outWidth / sampleSize > VALIDATION_MAX_DIMENSION ||
            options.outHeight / sampleSize > VALIDATION_MAX_DIMENSION
        ) {
            sampleSize *= 2
        }
        // long: 只读 bounds 可能接受像素区已截断的图片；再实际解码一份小尺寸像素，既验证完整可读性，也限制校验阶段的堆占用。
        val decoded = BitmapFactory.decodeByteArray(
            data,
            0,
            data.size,
            BitmapFactory.Options().apply { inSampleSize = sampleSize },
        ) ?: throw ImageAttachmentException("图片文件已损坏或无法解码")
        decoded.recycle()
    }

    private fun String.normalizeImageMimeType(): String = trim().lowercase().let { value ->
        if (value == "image/jpg") "image/jpeg" else value
    }

    private fun String.inferImageMimeType(): String? = when (substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        else -> null
    }

    private data class ImageMetadata(
        val fileName: String,
        val size: Long?,
    )

    companion object {
        private const val VALIDATION_MAX_DIMENSION = 256
    }
}
