package com.longdev.xiaoling.model

import java.util.Base64

class ImageAttachmentException(message: String) : IllegalArgumentException(message)

enum class MessageImageDetail(val apiValue: String) {
    AUTO("auto"),
}

class ImageAttachment internal constructor(
    val fileName: String,
    val mimeType: String,
    data: ByteArray,
    val detail: MessageImageDetail,
) {
    private val bytes = data.copyOf()

    val byteSize: Int
        get() = bytes.size

    fun copyData(): ByteArray = bytes.copyOf()

    internal fun encodedBase64(): String = Base64.getEncoder().encodeToString(bytes)

    override fun equals(other: Any?): Boolean {
        return other is ImageAttachment &&
            fileName == other.fileName &&
            mimeType == other.mimeType &&
            detail == other.detail &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + detail.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

object ImageAttachmentPolicy {
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

    fun create(
        fileName: String,
        mimeType: String,
        data: ByteArray,
        detail: MessageImageDetail = MessageImageDetail.AUTO,
    ): ImageAttachment {
        val normalizedMimeType = mimeType.trim().lowercase()
        if (normalizedMimeType !in SUPPORTED_MIME_TYPES) {
            throw ImageAttachmentException("不支持的图片格式，仅支持 PNG、JPEG 和 WEBP")
        }
        if (data.isEmpty()) throw ImageAttachmentException("图片内容为空")
        if (data.size > MAX_IMAGE_BYTES) {
            throw ImageAttachmentException("图片不能超过 ${MAX_IMAGE_BYTES / 1024 / 1024} MB")
        }
        if (!matchesSignature(normalizedMimeType, data)) {
            throw ImageAttachmentException("图片内容与格式不一致")
        }
        val normalizedFileName = fileName
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .take(MAX_FILE_NAME_LENGTH)
            .ifBlank { "image.${SUPPORTED_MIME_TYPES.getValue(normalizedMimeType)}" }
        // long: 系统文档 URI 可能在用户发送前失效；进入消息模型时复制字节，后续请求、Room 备份和删除都不再依赖外部授权。
        return ImageAttachment(
            fileName = normalizedFileName,
            mimeType = normalizedMimeType,
            data = data,
            detail = detail,
        )
    }

    private fun matchesSignature(mimeType: String, data: ByteArray): Boolean = when (mimeType) {
        "image/png" -> data.startsWith(PNG_SIGNATURE)
        "image/jpeg" -> data.startsWith(JPEG_SIGNATURE)
        "image/webp" -> data.startsWith(WEBP_RIFF) && data.hasBytesAt(8, WEBP_SIGNATURE)
        else -> false
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean = hasBytesAt(0, prefix)

    private fun ByteArray.hasBytesAt(offset: Int, expected: ByteArray): Boolean {
        if (offset < 0 || size < offset + expected.size) return false
        return expected.indices.all { index -> this[offset + index] == expected[index] }
    }

    private val SUPPORTED_MIME_TYPES = mapOf(
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/webp" to "webp",
    )
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
    private val JPEG_SIGNATURE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val WEBP_RIFF = byteArrayOf(0x52, 0x49, 0x46, 0x46)
    private val WEBP_SIGNATURE = byteArrayOf(0x57, 0x45, 0x42, 0x50)
    private const val MAX_FILE_NAME_LENGTH = 120
}
