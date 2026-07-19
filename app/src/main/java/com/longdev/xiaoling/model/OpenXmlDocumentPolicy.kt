package com.longdev.xiaoling.model

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

internal enum class OpenXmlDocumentType(
    val mimeType: String,
    val extension: String,
    val requiredRootEntry: String,
) {
    DOCX(
        mimeType = "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        extension = "docx",
        requiredRootEntry = "word/document.xml",
    ),
    PPTX(
        mimeType = "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        extension = "pptx",
        requiredRootEntry = "ppt/presentation.xml",
    ),
    XLSX(
        mimeType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        extension = "xlsx",
        requiredRootEntry = "xl/workbook.xml",
    ),
}

internal object OpenXmlDocumentPolicy {
    const val MAX_EXPANDED_BYTES = 64 * 1024 * 1024
    const val MAX_ENTRY_COUNT = 4_096

    fun typeForMimeType(mimeType: String?): OpenXmlDocumentType? {
        return OpenXmlDocumentType.entries.firstOrNull { it.mimeType == mimeType }
    }

    fun looksLikeZip(data: ByteArray): Boolean {
        return data.hasSignatureAt(0, LOCAL_FILE_HEADER_SIGNATURE)
    }

    fun isGenericZipMimeType(mimeType: String?): Boolean {
        return mimeType in GENERIC_ZIP_MIME_TYPES
    }

    fun validate(type: OpenXmlDocumentType, data: ByteArray) {
        val directory = readCentralDirectory(data)
        validateLocalHeaders(data, directory)
        validateExpandedEntries(data, directory.entries)
        val entries = directory.entries.associateBy(CentralDirectoryEntry::name)
        if (entries[CONTENT_TYPES_ENTRY]?.expandedSize?.takeIf { it > 0L } == null ||
            entries[type.requiredRootEntry]?.expandedSize?.takeIf { it > 0L } == null
        ) {
            throw DocumentAttachmentException("${type.extension.uppercase()} 文件结构不完整或格式不匹配")
        }
    }

    private fun readCentralDirectory(data: ByteArray): CentralDirectory {
        val endOffset = findEndOfCentralDirectory(data)
        if (endOffset >= ZIP64_LOCATOR_SIZE &&
            data.hasSignatureAt(endOffset - ZIP64_LOCATOR_SIZE, ZIP64_LOCATOR_SIGNATURE)
        ) {
            throw DocumentAttachmentException("富文档暂不支持 ZIP64")
        }
        val diskNumber = data.readUInt16(endOffset + 4)
        val centralDirectoryDisk = data.readUInt16(endOffset + 6)
        val entriesOnDisk = data.readUInt16(endOffset + 8)
        val totalEntries = data.readUInt16(endOffset + 10)
        if (diskNumber != 0 || centralDirectoryDisk != 0 || entriesOnDisk != totalEntries) {
            throw DocumentAttachmentException("富文档不支持分卷 ZIP")
        }
        if (totalEntries !in 1..MAX_ENTRY_COUNT) {
            throw DocumentAttachmentException("富文档 ZIP 条目数超出限制")
        }
        val centralDirectorySize = data.readUInt32(endOffset + 12)
        val centralDirectoryOffset = data.readUInt32(endOffset + 16)
        if (centralDirectorySize == ZIP64_SENTINEL || centralDirectoryOffset == ZIP64_SENTINEL) {
            throw DocumentAttachmentException("富文档暂不支持 ZIP64")
        }
        val directoryStart = centralDirectoryOffset.toInt()
        val directoryEnd = directoryStart.toLong() + centralDirectorySize
        if (directoryStart < 0 || directoryEnd != endOffset.toLong()) {
            throw DocumentAttachmentException("富文档 ZIP 中央目录已损坏")
        }

        var cursor = directoryStart
        var declaredExpandedBytes = 0L
        val entries = mutableListOf<CentralDirectoryEntry>()
        val names = mutableSetOf<String>()
        repeat(totalEntries) {
            if (!data.hasSignatureAt(cursor, CENTRAL_DIRECTORY_HEADER_SIGNATURE)) {
                throw DocumentAttachmentException("富文档 ZIP 中央目录已损坏")
            }
            val flags = data.readUInt16(cursor + 8)
            if (flags and ENCRYPTED_FLAG != 0) {
                throw DocumentAttachmentException("富文档暂不支持加密 ZIP")
            }
            val crc32 = data.readUInt32(cursor + 16)
            val compressedSize = data.readUInt32(cursor + 20)
            val expandedSize = data.readUInt32(cursor + 24)
            val diskStart = data.readUInt16(cursor + 34)
            val localHeaderOffset = data.readUInt32(cursor + 42)
            if (compressedSize == ZIP64_SENTINEL || expandedSize == ZIP64_SENTINEL ||
                localHeaderOffset == ZIP64_SENTINEL
            ) {
                throw DocumentAttachmentException("富文档暂不支持 ZIP64")
            }
            if (diskStart != 0) throw DocumentAttachmentException("富文档不支持分卷 ZIP")
            declaredExpandedBytes += expandedSize
            if (declaredExpandedBytes > MAX_EXPANDED_BYTES) {
                throw DocumentAttachmentException(
                    "富文档展开后不能超过 ${MAX_EXPANDED_BYTES / 1024 / 1024} MB",
                )
            }
            val nameLength = data.readUInt16(cursor + 28)
            val extraLength = data.readUInt16(cursor + 30)
            val commentLength = data.readUInt16(cursor + 32)
            val nameStart = cursor + CENTRAL_DIRECTORY_FIXED_SIZE
            val nameEnd = nameStart + nameLength
            val extraStart = nameEnd
            val nextCursor = extraStart.toLong() + extraLength + commentLength
            if (nameStart < 0 || nameEnd > data.size || nextCursor > directoryEnd) {
                throw DocumentAttachmentException("富文档 ZIP 条目已损坏")
            }
            rejectZip64Extra(data, extraStart, extraLength)
            val entryName = data.copyOfRange(nameStart, nameEnd)
                .toString(Charsets.UTF_8)
                .normalizeZipEntryName()
            if (!names.add(entryName)) throw DocumentAttachmentException("富文档 ZIP 包含重复条目")
            entries += CentralDirectoryEntry(
                name = entryName,
                flags = flags,
                crc32 = crc32,
                compressedSize = compressedSize,
                expandedSize = expandedSize,
                localHeaderOffset = localHeaderOffset,
            )
            cursor = nextCursor.toInt()
        }
        if (cursor.toLong() != directoryEnd) {
            throw DocumentAttachmentException("富文档 ZIP 中央目录已损坏")
        }
        return CentralDirectory(startOffset = directoryStart, entries = entries)
    }

    private fun validateLocalHeaders(data: ByteArray, directory: CentralDirectory) {
        directory.entries.forEach { entry ->
            val offset = entry.localHeaderOffset.toInt()
            if (offset < 0 || offset >= directory.startOffset ||
                !data.hasSignatureAt(offset, LOCAL_FILE_HEADER_SIGNATURE)
            ) {
                throw DocumentAttachmentException("富文档 ZIP 本地条目不存在")
            }
            val localFlags = data.readUInt16(offset + 6)
            if (localFlags and ENCRYPTED_FLAG != 0 || localFlags != entry.flags) {
                throw DocumentAttachmentException("富文档 ZIP 本地条目标志不一致或已加密")
            }
            val localCrc32 = data.readUInt32(offset + 14)
            val localCompressedSize = data.readUInt32(offset + 18)
            val localExpandedSize = data.readUInt32(offset + 22)
            val nameLength = data.readUInt16(offset + 26)
            val extraLength = data.readUInt16(offset + 28)
            val nameStart = offset + LOCAL_FILE_HEADER_FIXED_SIZE
            val nameEnd = nameStart + nameLength
            val extraStart = nameEnd
            val dataStart = extraStart.toLong() + extraLength
            val dataEnd = dataStart + entry.compressedSize
            if (nameStart < 0 || nameEnd > data.size || dataEnd > directory.startOffset.toLong()) {
                throw DocumentAttachmentException("富文档 ZIP 本地条目已损坏")
            }
            rejectZip64Extra(data, extraStart, extraLength)
            val localName = data.copyOfRange(nameStart, nameEnd)
                .toString(Charsets.UTF_8)
                .normalizeZipEntryName()
            if (localName != entry.name) {
                throw DocumentAttachmentException("富文档 ZIP 中央目录与本地条目名称不一致")
            }
            if (localFlags and DATA_DESCRIPTOR_FLAG == 0 &&
                (localCrc32 != entry.crc32 || localCompressedSize != entry.compressedSize ||
                    localExpandedSize != entry.expandedSize)
            ) {
                throw DocumentAttachmentException("富文档 ZIP 中央目录与本地条目大小不一致")
            }
        }
    }

    private fun validateExpandedEntries(data: ByteArray, expectedEntries: List<CentralDirectoryEntry>) {
        val expectedByName = expectedEntries.associateBy(CentralDirectoryEntry::name)
        val seenNames = mutableSetOf<String>()
        var actualExpandedBytes = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        try {
            ZipInputStream(ByteArrayInputStream(data)).use { zip ->
                while (true) {
                    val zipEntry = zip.nextEntry ?: break
                    val name = zipEntry.name.normalizeZipEntryName()
                    val expected = expectedByName[name]
                        ?: throw DocumentAttachmentException("富文档 ZIP 本地条目未出现在中央目录")
                    if (!seenNames.add(name)) throw DocumentAttachmentException("富文档 ZIP 包含重复条目")
                    var entryExpandedBytes = 0L
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        entryExpandedBytes += count
                        actualExpandedBytes += count
                        if (actualExpandedBytes > MAX_EXPANDED_BYTES) {
                            throw DocumentAttachmentException(
                                "富文档展开后不能超过 ${MAX_EXPANDED_BYTES / 1024 / 1024} MB",
                            )
                        }
                    }
                    if (entryExpandedBytes != expected.expandedSize) {
                        throw DocumentAttachmentException("富文档 ZIP 条目实际大小与中央目录不一致")
                    }
                    if (zipEntry.size != expected.expandedSize ||
                        zipEntry.compressedSize != expected.compressedSize ||
                        zipEntry.crc != expected.crc32
                    ) {
                        throw DocumentAttachmentException("富文档 ZIP 实际 CRC 或压缩大小与中央目录不一致")
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: DocumentAttachmentException) {
            throw error
        } catch (_: Exception) {
            throw DocumentAttachmentException("富文档 ZIP 正文已损坏或无法解压")
        }
        if (seenNames != expectedByName.keys) {
            throw DocumentAttachmentException("富文档 ZIP 中央目录包含不存在的本地条目")
        }
        // long: 中央目录预算用于提前拒绝，流式解压再核对真实字节、CRC 和条目集合；全程只保留固定缓冲区，压缩炸弹超过 64 MB 时立即停止。
    }

    private fun rejectZip64Extra(data: ByteArray, extraStart: Int, extraLength: Int) {
        val extraEnd = extraStart.toLong() + extraLength
        if (extraStart < 0 || extraEnd > data.size) throw DocumentAttachmentException("富文档 ZIP Extra 已损坏")
        var cursor = extraStart
        while (cursor.toLong() < extraEnd) {
            if (cursor + EXTRA_FIELD_HEADER_SIZE > extraEnd) {
                throw DocumentAttachmentException("富文档 ZIP Extra 已损坏")
            }
            val fieldId = data.readUInt16(cursor)
            val fieldSize = data.readUInt16(cursor + 2)
            val nextCursor = cursor.toLong() + EXTRA_FIELD_HEADER_SIZE + fieldSize
            if (nextCursor > extraEnd) throw DocumentAttachmentException("富文档 ZIP Extra 已损坏")
            if (fieldId == ZIP64_EXTRA_FIELD_ID) throw DocumentAttachmentException("富文档暂不支持 ZIP64")
            cursor = nextCursor.toInt()
        }
    }

    private fun findEndOfCentralDirectory(data: ByteArray): Int {
        val minimumOffset = (data.size - MAX_EOCD_SEARCH_BYTES).coerceAtLeast(0)
        for (offset in data.size - MIN_EOCD_SIZE downTo minimumOffset) {
            if (data.hasSignatureAt(offset, END_OF_CENTRAL_DIRECTORY_SIGNATURE)) {
                val commentLength = data.readUInt16(offset + 20)
                if (offset + MIN_EOCD_SIZE + commentLength == data.size) return offset
            }
        }
        throw DocumentAttachmentException("富文档 ZIP 中央目录不存在")
    }

    private fun String.normalizeZipEntryName(): String {
        val normalized = replace('\\', '/')
        if (normalized.isBlank() || normalized.startsWith('/') || normalized.split('/').any { it == ".." }) {
            throw DocumentAttachmentException("富文档 ZIP 包含不安全路径")
        }
        return normalized
    }

    private fun ByteArray.readUInt16(offset: Int): Int {
        if (offset < 0 || offset + 2 > size) throw DocumentAttachmentException("富文档 ZIP 已损坏")
        return (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8)
    }

    private fun ByteArray.readUInt32(offset: Int): Long {
        if (offset < 0 || offset + 4 > size) throw DocumentAttachmentException("富文档 ZIP 已损坏")
        return (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun ByteArray.hasSignatureAt(offset: Int, signature: ByteArray): Boolean {
        if (offset < 0 || offset + signature.size > size) return false
        return signature.indices.all { index -> this[offset + index] == signature[index] }
    }

    private data class CentralDirectory(
        val startOffset: Int,
        val entries: List<CentralDirectoryEntry>,
    )

    private data class CentralDirectoryEntry(
        val name: String,
        val flags: Int,
        val crc32: Long,
        val compressedSize: Long,
        val expandedSize: Long,
        val localHeaderOffset: Long,
    )

    private const val CONTENT_TYPES_ENTRY = "[Content_Types].xml"
    private const val ENCRYPTED_FLAG = 0x1
    private const val DATA_DESCRIPTOR_FLAG = 0x8
    private const val ZIP64_EXTRA_FIELD_ID = 0x0001
    private const val EXTRA_FIELD_HEADER_SIZE = 4
    private const val CENTRAL_DIRECTORY_FIXED_SIZE = 46
    private const val LOCAL_FILE_HEADER_FIXED_SIZE = 30
    private const val MIN_EOCD_SIZE = 22
    private const val ZIP64_LOCATOR_SIZE = 20
    private const val MAX_EOCD_SEARCH_BYTES = MIN_EOCD_SIZE + 65_535
    private const val ZIP64_SENTINEL = 0xFFFF_FFFFL
    private val GENERIC_ZIP_MIME_TYPES = setOf(
        "application/zip",
        "application/x-zip-compressed",
        "application/octet-stream",
    )
    private val LOCAL_FILE_HEADER_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x03, 0x04)
    private val CENTRAL_DIRECTORY_HEADER_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x01, 0x02)
    private val END_OF_CENTRAL_DIRECTORY_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x05, 0x06)
    private val ZIP64_LOCATOR_SIGNATURE = byteArrayOf(0x50, 0x4B, 0x06, 0x07)
}
