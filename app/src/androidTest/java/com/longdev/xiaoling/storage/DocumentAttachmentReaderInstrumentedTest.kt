package com.longdev.xiaoling.storage

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.model.DocumentAttachmentException
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class DocumentAttachmentReaderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        files.forEach(File::delete)
    }

    @Test
    fun validUtf8MarkdownIsCopiedAndExtracted() {
        val file = testFile("reader-notes.md")
        file.writeText("# 标题\n正文", Charsets.UTF_8)

        val attachment = DocumentAttachmentReader(context).read(Uri.fromFile(file))

        assertEquals("reader-notes.md", attachment.fileName)
        assertEquals("text/markdown", attachment.mimeType)
        assertEquals("# 标题\n正文", attachment.extractedText)
        assertTrue(attachment.copyData().contentEquals(file.readBytes()))
    }

    @Test
    fun validPdfIsParsedAndPageCounted() {
        val file = createPdf("reader-valid.pdf", pageCount = 2)

        val attachment = DocumentAttachmentReader(context).read(Uri.fromFile(file))

        assertEquals("application/pdf", attachment.mimeType)
        assertEquals(2, attachment.pageCount)
        assertEquals(null, attachment.extractedText)
    }

    @Test
    fun invalidUtf8AndTooManyPdfPagesAreRejected() {
        val invalidText = testFile("reader-invalid.txt").apply {
            writeBytes(byteArrayOf(0xC3.toByte(), 0x28))
        }
        assertRejected("UTF-8") {
            DocumentAttachmentReader(context).read(Uri.fromFile(invalidText))
        }

        val longPdf = createPdf("reader-too-many.pdf", DocumentAttachmentPolicy.MAX_PDF_PAGES + 1)
        assertRejected("页") {
            DocumentAttachmentReader(context).read(Uri.fromFile(longPdf))
        }
    }

    @Test
    fun oversizedDocumentIsRejectedBeforeEnteringMessageModel() {
        val file = testFile("reader-large.txt")
        RandomAccessFile(file, "rw").use { it.setLength(DocumentAttachmentPolicy.MAX_DOCUMENT_BYTES.toLong() + 1L) }

        assertRejected("文档不能超过") {
            DocumentAttachmentReader(context).read(Uri.fromFile(file))
        }
    }

    @Test
    fun validDocxIsStructurallyValidatedWithoutUtf8Decoding() {
        val file = testFile("reader-valid.docx")
        ZipOutputStream(file.outputStream()).use { zip ->
            listOf("[Content_Types].xml", "word/document.xml").forEach { name ->
                zip.putNextEntry(ZipEntry(name))
                zip.write("<xml/>".toByteArray())
                zip.closeEntry()
            }
        }

        val attachment = DocumentAttachmentReader(context).read(Uri.fromFile(file))

        assertEquals(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            attachment.mimeType,
        )
        assertEquals(null, attachment.extractedText)
        assertEquals(null, attachment.pageCount)
    }

    private fun createPdf(name: String, pageCount: Int): File {
        val file = testFile(name)
        val pdf = PdfDocument()
        try {
            repeat(pageCount) { index ->
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(100, 100, index + 1).create())
                pdf.finishPage(page)
            }
            file.outputStream().use(pdf::writeTo)
        } finally {
            pdf.close()
        }
        return file
    }

    private fun assertRejected(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail("Expected DocumentAttachmentException")
        } catch (error: DocumentAttachmentException) {
            assertTrue(error.message.orEmpty().contains(expectedMessage))
        }
    }

    private fun testFile(name: String): File {
        return File(context.cacheDir, name).also {
            it.delete()
            files += it
        }
    }
}
