package com.longdev.xiaoling.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentAttachmentPolicyTest {
    @Test
    fun textDocumentCopiesBytesAndKeepsBoundedExtractedText() {
        val source = "第一行\n第二行".toByteArray(Charsets.UTF_8)
        val attachment = DocumentAttachmentPolicy.create(
            fileName = "notes.md",
            mimeType = "text/markdown",
            data = source,
        )

        source[0] = 0
        val firstCopy = attachment.copyData()
        firstCopy[0] = 0

        assertEquals("第一行\n第二行", attachment.extractedText)
        assertNull(attachment.pageCount)
        assertTrue(attachment.copyData().contentEquals("第一行\n第二行".toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun pdfRequiresSignatureAndValidatedPageCount() {
        val attachment = DocumentAttachmentPolicy.create(
            fileName = "report.pdf",
            mimeType = "application/pdf",
            data = "%PDF-1.7\nbody".toByteArray(Charsets.US_ASCII),
            pageCount = 3,
        )

        assertEquals(3, attachment.pageCount)
        assertNull(attachment.extractedText)
        assertRejected("格式不一致") {
            DocumentAttachmentPolicy.create(
                fileName = "report.pdf",
                mimeType = "application/pdf",
                data = "not-pdf".toByteArray(),
                pageCount = 1,
            )
        }
        assertRejected("页") {
            DocumentAttachmentPolicy.create(
                fileName = "report.pdf",
                mimeType = "application/pdf",
                data = "%PDF-1.7".toByteArray(Charsets.US_ASCII),
                pageCount = DocumentAttachmentPolicy.MAX_PDF_PAGES + 1,
            )
        }
    }

    @Test
    fun invalidOrOversizedUtf8TextIsRejected() {
        assertRejected("UTF-8") {
            DocumentAttachmentPolicy.create(
                fileName = "broken.txt",
                mimeType = "text/plain",
                data = byteArrayOf(0xC3.toByte(), 0x28),
            )
        }
        assertRejected("字符") {
            DocumentAttachmentPolicy.create(
                fileName = "large.txt",
                mimeType = "text/plain",
                data = "a".repeat(DocumentAttachmentPolicy.MAX_TEXT_CHARACTERS + 1).toByteArray(),
            )
        }
    }

    @Test
    fun pdfSignatureCannotMasqueradeAsTextAndMisreportedPdfStillResolvesAsPdf() {
        val pdfData = "%PDF-1.7\nbody".toByteArray(Charsets.US_ASCII)

        assertEquals(
            "application/pdf",
            DocumentAttachmentPolicy.resolveMimeType(
                fileName = "report.pdf",
                declaredMimeType = "text/plain",
                data = pdfData,
            ),
        )
        assertRejected("PDF 必须按 PDF 解析") {
            DocumentAttachmentPolicy.create(
                fileName = "report.pdf",
                mimeType = "text/plain",
                data = pdfData,
            )
        }
        assertRejected("扩展名") {
            DocumentAttachmentPolicy.resolveMimeType(
                fileName = "report.txt",
                declaredMimeType = "text/plain",
                data = pdfData,
            )
        }
    }

    @Test
    fun unsupportedDocumentTypeIsRejected() {
        assertRejected("仅支持") {
            DocumentAttachmentPolicy.create(
                fileName = "archive.zip",
                mimeType = "application/zip",
                data = byteArrayOf(1),
            )
        }
    }

    @Test
    fun openXmlDocumentsRequireMatchingOpcRootAndKeepBinaryPayload() {
        val cases = listOf(
            Triple("report.docx", OpenXmlDocumentType.DOCX, "word/document.xml"),
            Triple("slides.pptx", OpenXmlDocumentType.PPTX, "ppt/presentation.xml"),
            Triple("sheet.xlsx", OpenXmlDocumentType.XLSX, "xl/workbook.xml"),
        )

        cases.forEach { (fileName, type, rootEntry) ->
            val data = openXmlPackage(rootEntry)
            val attachment = DocumentAttachmentPolicy.create(
                fileName = fileName,
                mimeType = type.mimeType,
                data = data,
            )

            assertEquals(type.mimeType, attachment.mimeType)
            assertNull(attachment.extractedText)
            assertNull(attachment.pageCount)
            assertTrue(attachment.copyData().contentEquals(data))
        }
    }

    @Test
    fun openXmlResolutionRejectsInvalidOrMismatchedPackages() {
        val docxData = openXmlPackage("word/document.xml")
        assertEquals(
            OpenXmlDocumentType.DOCX.mimeType,
            DocumentAttachmentPolicy.resolveMimeType(
                fileName = "report.docx",
                declaredMimeType = "application/zip",
                data = docxData,
            ),
        )
        assertRejected("MIME") {
            DocumentAttachmentPolicy.resolveMimeType(
                fileName = "report.docx",
                declaredMimeType = "text/plain",
                data = docxData,
            )
        }
        assertRejected("格式不匹配") {
            DocumentAttachmentPolicy.create(
                fileName = "report.pptx",
                mimeType = OpenXmlDocumentType.PPTX.mimeType,
                data = docxData,
            )
        }
        assertRejected("ZIP/OPC") {
            DocumentAttachmentPolicy.resolveMimeType(
                fileName = "report.docx",
                declaredMimeType = OpenXmlDocumentType.DOCX.mimeType,
                data = "not-a-zip".toByteArray(),
            )
        }
        assertRejected("扩展名") {
            DocumentAttachmentPolicy.resolveMimeType(
                fileName = "report.txt",
                declaredMimeType = "text/plain",
                data = docxData,
            )
        }
    }

    @Test
    fun openXmlCentralDirectoryRejectsExpandedBudgetAndEncryption() {
        val expanded = openXmlPackage("word/document.xml")
        val centralOffset = expanded.indexOfSignature(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
        expanded.writeUInt32(
            centralOffset + 24,
            OpenXmlDocumentPolicy.MAX_EXPANDED_BYTES.toLong() + 1L,
        )
        assertRejected("展开后") {
            DocumentAttachmentPolicy.create(
                fileName = "report.docx",
                mimeType = OpenXmlDocumentType.DOCX.mimeType,
                data = expanded,
            )
        }

        val encrypted = openXmlPackage("word/document.xml")
        val encryptedCentralOffset = encrypted.indexOfSignature(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
        encrypted[encryptedCentralOffset + 8] =
            (encrypted[encryptedCentralOffset + 8].toInt() or 0x1).toByte()
        assertRejected("加密") {
            DocumentAttachmentPolicy.create(
                fileName = "report.docx",
                mimeType = OpenXmlDocumentType.DOCX.mimeType,
                data = encrypted,
            )
        }

        val localEncrypted = openXmlPackage("word/document.xml")
        localEncrypted[6] = (localEncrypted[6].toInt() or 0x1).toByte()
        assertRejected("本地条目标志") {
            DocumentAttachmentPolicy.create(
                fileName = "report.docx",
                mimeType = OpenXmlDocumentType.DOCX.mimeType,
                data = localEncrypted,
            )
        }

        val split = openXmlPackage("word/document.xml")
        val splitCentralOffset = split.indexOfSignature(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
        split[splitCentralOffset + 34] = 1
        assertRejected("分卷") {
            DocumentAttachmentPolicy.create(
                fileName = "report.docx",
                mimeType = OpenXmlDocumentType.DOCX.mimeType,
                data = split,
            )
        }

        val zip64 = openXmlPackage("word/document.xml")
        val zip64CentralOffset = zip64.indexOfSignature(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
        zip64.writeUInt32(zip64CentralOffset + 42, 0xFFFF_FFFFL)
        assertRejected("ZIP64") {
            DocumentAttachmentPolicy.create(
                fileName = "report.docx",
                mimeType = OpenXmlDocumentType.DOCX.mimeType,
                data = zip64,
            )
        }

        val forgedRoot = openXmlPackage("fake/document.xml")
        val secondCentralOffset = forgedRoot.indexOfSignature(
            byteArrayOf(0x50, 0x4B, 0x01, 0x02),
            occurrence = 2,
        )
        "word/document.xml".toByteArray().copyInto(forgedRoot, destinationOffset = secondCentralOffset + 46)
        assertRejected("名称不一致") {
            DocumentAttachmentPolicy.create(
                fileName = "report.docx",
                mimeType = OpenXmlDocumentType.DOCX.mimeType,
                data = forgedRoot,
            )
        }

        val actualSizeMismatch = openXmlPackage("word/document.xml")
        val mismatchCentralOffset = actualSizeMismatch.indexOfSignature(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
        actualSizeMismatch.writeUInt32(mismatchCentralOffset + 24, 5L)
        assertRejected("实际大小") {
            DocumentAttachmentPolicy.create(
                fileName = "report.docx",
                mimeType = OpenXmlDocumentType.DOCX.mimeType,
                data = actualSizeMismatch,
            )
        }

        val crcMismatch = openXmlPackage("word/document.xml")
        val crcCentralOffset = crcMismatch.indexOfSignature(byteArrayOf(0x50, 0x4B, 0x01, 0x02))
        crcMismatch.writeUInt32(crcCentralOffset + 16, 0L)
        assertRejected("CRC") {
            DocumentAttachmentPolicy.create(
                fileName = "report.docx",
                mimeType = OpenXmlDocumentType.DOCX.mimeType,
                data = crcMismatch,
            )
        }

        val compressedSizeMismatch = openXmlPackage("word/document.xml")
        val compressedCentralOffset = compressedSizeMismatch.indexOfSignature(
            byteArrayOf(0x50, 0x4B, 0x01, 0x02),
        )
        val compressedSize = compressedSizeMismatch.readUInt32(compressedCentralOffset + 20)
        compressedSizeMismatch.writeUInt32(compressedCentralOffset + 20, compressedSize - 1L)
        assertRejected("压缩大小") {
            DocumentAttachmentPolicy.create(
                fileName = "report.docx",
                mimeType = OpenXmlDocumentType.DOCX.mimeType,
                data = compressedSizeMismatch,
            )
        }
    }

    private fun openXmlPackage(rootEntry: String): ByteArray {
        return ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                listOf("[Content_Types].xml", rootEntry).forEach { name ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write("<xml/>".toByteArray())
                    zip.closeEntry()
                }
            }
            output.toByteArray()
        }
    }

    private fun ByteArray.indexOfSignature(signature: ByteArray, occurrence: Int = 1): Int {
        var remaining = occurrence
        indices.forEach { offset ->
            if (offset + signature.size <= size &&
                signature.indices.all { index -> this[offset + index] == signature[index] }
            ) {
                remaining -= 1
                if (remaining == 0) return offset
            }
        }
        error("ZIP signature not found")
    }

    private fun ByteArray.writeUInt32(offset: Int, value: Long) {
        repeat(4) { index ->
            this[offset + index] = ((value shr (index * 8)) and 0xFF).toByte()
        }
    }

    private fun ByteArray.readUInt32(offset: Int): Long {
        return (this[offset].toLong() and 0xFF) or
            ((this[offset + 1].toLong() and 0xFF) shl 8) or
            ((this[offset + 2].toLong() and 0xFF) shl 16) or
            ((this[offset + 3].toLong() and 0xFF) shl 24)
    }

    private fun assertRejected(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail("Expected DocumentAttachmentException")
        } catch (error: DocumentAttachmentException) {
            assertTrue(error.message.orEmpty().contains(expectedMessage))
        }
    }
}
