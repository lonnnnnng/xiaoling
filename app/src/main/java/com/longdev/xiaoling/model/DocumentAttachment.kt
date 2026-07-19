package com.longdev.xiaoling.model

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Base64

class DocumentAttachmentException(message: String) : IllegalArgumentException(message)

enum class MessageDocumentDetail(val apiValue: String) {
    AUTO("auto"),
}

class DocumentAttachment internal constructor(
    val fileName: String,
    val mimeType: String,
    data: ByteArray,
    val extractedText: String?,
    val pageCount: Int?,
    val detail: MessageDocumentDetail,
) {
    private val bytes = data.copyOf()

    val byteSize: Int
        get() = bytes.size

    val characterCount: Int?
        get() = extractedText?.length

    fun copyData(): ByteArray = bytes.copyOf()

    internal fun encodedBase64(): String = Base64.getEncoder().encodeToString(bytes)

    override fun equals(other: Any?): Boolean {
        return other is DocumentAttachment &&
            fileName == other.fileName &&
            mimeType == other.mimeType &&
            extractedText == other.extractedText &&
            pageCount == other.pageCount &&
            detail == other.detail &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = fileName.hashCode()
        result = 31 * result + mimeType.hashCode()
        result = 31 * result + (extractedText?.hashCode() ?: 0)
        result = 31 * result + (pageCount ?: 0)
        result = 31 * result + detail.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

object DocumentAttachmentPolicy {
    const val MAX_DOCUMENT_BYTES = 8 * 1024 * 1024
    const val MAX_PDF_PAGES = 50
    const val MAX_TEXT_CHARACTERS = 200_000

    fun create(
        fileName: String,
        mimeType: String,
        data: ByteArray,
        pageCount: Int? = null,
        extractedText: String? = null,
        detail: MessageDocumentDetail = MessageDocumentDetail.AUTO,
    ): DocumentAttachment {
        val normalizedMimeType = normalizeMimeType(mimeType)
        val extension = SUPPORTED_MIME_TYPES[normalizedMimeType]
            ?: throw DocumentAttachmentException("不支持的文档格式，仅支持 PDF、TXT、Markdown、JSON 和 CSV")
        if (data.isEmpty()) throw DocumentAttachmentException("文档内容为空")
        if (data.size > MAX_DOCUMENT_BYTES) {
            throw DocumentAttachmentException("文档不能超过 ${MAX_DOCUMENT_BYTES / 1024 / 1024} MB")
        }

        val canonicalText: String?
        val canonicalPageCount: Int?
        if (normalizedMimeType == PDF_MIME_TYPE) {
            if (!data.startsWith(PDF_SIGNATURE)) {
                throw DocumentAttachmentException("文档内容与 PDF 格式不一致")
            }
            canonicalPageCount = pageCount
                ?.takeIf { it in 1..MAX_PDF_PAGES }
                ?: throw DocumentAttachmentException("PDF 页数必须在 1 到 $MAX_PDF_PAGES 页之间")
            canonicalText = null
            if (extractedText != null) throw DocumentAttachmentException("PDF 不接受本地伪造的提取文本")
        } else {
            if (data.startsWith(PDF_SIGNATURE)) {
                throw DocumentAttachmentException("文档内容与声明格式不一致，PDF 必须按 PDF 解析")
            }
            if (pageCount != null) throw DocumentAttachmentException("文本类文档不应包含 PDF 页数")
            canonicalText = decodeUtf8Text(data)
            if (canonicalText.length > MAX_TEXT_CHARACTERS) {
                throw DocumentAttachmentException("文本类文档不能超过 $MAX_TEXT_CHARACTERS 个字符")
            }
            if (canonicalText.indexOf('\u0000') >= 0) {
                throw DocumentAttachmentException("文本类文档包含二进制空字符")
            }
            if (extractedText != null && extractedText != canonicalText) {
                throw DocumentAttachmentException("文档提取文本与原始字节不一致")
            }
            canonicalPageCount = null
        }

        val normalizedFileName = fileName
            .replace('\\', '/')
            .substringAfterLast('/')
            .trim()
            .take(MAX_FILE_NAME_LENGTH)
            .ifBlank { "document.$extension" }
        // long: 系统文件授权可能在发送前失效；原始字节与受预算约束的本地文本一起进入消息模型，后续请求、展示和 Room 备份不再依赖外部 URI。
        return DocumentAttachment(
            fileName = normalizedFileName,
            mimeType = normalizedMimeType,
            data = data,
            extractedText = canonicalText,
            pageCount = canonicalPageCount,
            detail = detail,
        )
    }

    private fun decodeUtf8Text(data: ByteArray): String {
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data))
                .toString()
                .removePrefix("\uFEFF")
        } catch (_: Exception) {
            throw DocumentAttachmentException("文本类文档必须是有效 UTF-8 编码")
        }
    }

    internal fun resolveMimeType(
        fileName: String,
        declaredMimeType: String?,
        data: ByteArray,
    ): String {
        val declared = declaredMimeType
            ?.let(::normalizeMimeType)
            ?.takeIf(SUPPORTED_MIME_TYPES::containsKey)
        val inferred = inferMimeType(fileName)
        val hasPdfSignature = data.startsWith(PDF_SIGNATURE)
        if (hasPdfSignature) {
            if (inferred != null && inferred != PDF_MIME_TYPE) {
                throw DocumentAttachmentException("文档扩展名与 PDF 内容不一致")
            }
            // long: 部分 DocumentsProvider 会把 PDF 错报为 text/plain；PDF 签名必须提升为平台解析路径，不能借错误 MIME 绕过页数预算。
            return PDF_MIME_TYPE
        }
        if (declared == PDF_MIME_TYPE || inferred == PDF_MIME_TYPE) {
            throw DocumentAttachmentException("文档内容与 PDF 格式不一致")
        }
        return inferred
            ?: declared
            ?: throw DocumentAttachmentException("无法识别文档格式，仅支持 PDF、TXT、Markdown、JSON 和 CSV")
    }

    internal fun normalizeMimeType(mimeType: String): String = mimeType.trim().lowercase().let { value ->
        MIME_TYPE_ALIASES[value] ?: value
    }

    internal fun inferMimeType(fileName: String): String? = when (fileName.substringAfterLast('.', "").lowercase()) {
        "pdf" -> PDF_MIME_TYPE
        "txt", "text" -> "text/plain"
        "md", "markdown" -> "text/markdown"
        "json" -> "application/json"
        "csv" -> "text/csv"
        else -> null
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { index -> this[index] == prefix[index] }
    }

    private val SUPPORTED_MIME_TYPES = mapOf(
        PDF_MIME_TYPE to "pdf",
        "text/plain" to "txt",
        "text/markdown" to "md",
        "application/json" to "json",
        "text/csv" to "csv",
    )
    private val MIME_TYPE_ALIASES = mapOf(
        "application/x-pdf" to PDF_MIME_TYPE,
        "text/x-markdown" to "text/markdown",
        "application/markdown" to "text/markdown",
        "application/csv" to "text/csv",
    )
    private val PDF_SIGNATURE = "%PDF-".toByteArray(Charsets.US_ASCII)
    private const val PDF_MIME_TYPE = "application/pdf"
    private const val MAX_FILE_NAME_LENGTH = 120
}
