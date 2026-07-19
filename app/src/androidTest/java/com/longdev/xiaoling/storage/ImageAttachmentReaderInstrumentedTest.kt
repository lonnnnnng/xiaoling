package com.longdev.xiaoling.storage

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.model.ImageAttachmentException
import com.longdev.xiaoling.model.ImageAttachmentPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class ImageAttachmentReaderInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val files = mutableListOf<File>()

    @After
    fun tearDown() {
        files.forEach(File::delete)
    }

    @Test
    fun validPngIsCopiedFromUriAndDecoded() {
        val file = testFile("reader-valid.png")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        } finally {
            bitmap.recycle()
        }

        val attachment = ImageAttachmentReader(context.contentResolver).read(Uri.fromFile(file))

        assertEquals("reader-valid.png", attachment.fileName)
        assertEquals("image/png", attachment.mimeType)
        assertTrue(attachment.copyData().contentEquals(file.readBytes()))
    }

    @Test
    fun signatureOnlyPngIsRejectedAsUndecodable() {
        val file = testFile("reader-broken.png")
        file.writeBytes(
            byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1),
        )

        assertRejected("图片文件已损坏") {
            ImageAttachmentReader(context.contentResolver).read(Uri.fromFile(file))
        }
    }

    @Test
    fun pngWithValidBoundsButTruncatedPixelsIsRejectedByActualDecode() {
        val complete = testFile("reader-source.png")
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        try {
            complete.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
        } finally {
            bitmap.recycle()
        }
        val truncated = testFile("reader-truncated.png")
        truncated.writeBytes(complete.readBytes().copyOf(33))

        assertRejected("图片文件已损坏") {
            ImageAttachmentReader(context.contentResolver).read(Uri.fromFile(truncated))
        }
    }

    @Test
    fun oversizedFileIsRejectedBeforeEnteringMessageModel() {
        val file = testFile("reader-large.png")
        RandomAccessFile(file, "rw").use { it.setLength(ImageAttachmentPolicy.MAX_IMAGE_BYTES.toLong() + 1L) }

        assertRejected("图片不能超过") {
            ImageAttachmentReader(context.contentResolver).read(Uri.fromFile(file))
        }
    }

    private fun assertRejected(expectedMessage: String, block: () -> Unit) {
        try {
            block()
            fail("Expected ImageAttachmentException")
        } catch (error: ImageAttachmentException) {
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
