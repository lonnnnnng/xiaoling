package com.longdev.xiaoling.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

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

    private fun assertRejected(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail("Expected DocumentAttachmentException")
        } catch (error: DocumentAttachmentException) {
            assertTrue(error.message.orEmpty().contains(expectedMessage))
        }
    }
}
