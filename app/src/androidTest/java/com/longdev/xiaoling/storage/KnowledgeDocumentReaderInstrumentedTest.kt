package com.longdev.xiaoling.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.knowledge.KnowledgeDocumentException
import com.longdev.xiaoling.knowledge.KnowledgeTextPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class KnowledgeDocumentReaderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun readerCopiesSelectedTextAndKeepsProviderMetadata() {
        val file = File(context.cacheDir, "knowledge-reader.md").apply {
            delete()
            writeText("# 知识库\n正文", Charsets.UTF_8)
        }
        try {
            val imported = KnowledgeDocumentReader(context).read(Uri.fromFile(file))

            assertEquals(file.name, imported.fileName)
            assertEquals("# 知识库\n正文", imported.bytes.toString(Charsets.UTF_8))
            assertTrue(imported.declaredMimeType.isBlank() || imported.declaredMimeType == "text/markdown")
        } finally {
            file.delete()
        }
    }

    @Test
    fun readerRejectsUnknownLengthContentPastKnowledgeBudget() {
        val file = File(context.cacheDir, "knowledge-reader-large.txt").apply {
            delete()
            RandomAccessFile(this, "rw").use { it.setLength(KnowledgeTextPolicy.MAX_IMPORT_BYTES.toLong() + 1L) }
        }
        try {
            try {
                KnowledgeDocumentReader(context).read(Uri.fromFile(file))
                fail("Expected KnowledgeDocumentException")
            } catch (error: KnowledgeDocumentException) {
                assertTrue(error.message.orEmpty().contains("64 MB"))
            }
        } finally {
            file.delete()
        }
    }
}
