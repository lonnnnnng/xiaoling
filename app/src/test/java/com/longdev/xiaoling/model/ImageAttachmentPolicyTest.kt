package com.longdev.xiaoling.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ImageAttachmentPolicyTest {
    @Test
    fun supportedImageIsCopiedAndNormalizedForMessageStorage() {
        val source = pngBytes()

        val attachment = ImageAttachmentPolicy.create(
            fileName = "folder/sample.png",
            mimeType = "IMAGE/PNG",
            data = source,
        )
        source[source.lastIndex] = 0
        val exported = attachment.copyData().also { it[it.lastIndex] = 0 }

        assertEquals("sample.png", attachment.fileName)
        assertEquals("image/png", attachment.mimeType)
        assertEquals(MessageImageDetail.AUTO, attachment.detail)
        assertTrue(attachment.copyData().contentEquals(pngBytes()))
        assertTrue(exported.contentEquals(pngBytes().also { it[it.lastIndex] = 0 }))
    }

    @Test
    fun unsupportedMimeOversizedAndSpoofedImagesAreRejected() {
        assertRejected("不支持的图片格式") {
            ImageAttachmentPolicy.create("sample.gif", "image/gif", byteArrayOf(1, 2, 3))
        }
        assertRejected("图片不能超过") {
            ImageAttachmentPolicy.create(
                "large.png",
                "image/png",
                ByteArray(ImageAttachmentPolicy.MAX_IMAGE_BYTES + 1).also { bytes ->
                    pngSignature().copyInto(bytes)
                },
            )
        }
        assertRejected("图片内容与格式不一致") {
            ImageAttachmentPolicy.create("spoofed.png", "image/png", byteArrayOf(1, 2, 3, 4))
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

    private fun pngBytes(): ByteArray = pngSignature() + byteArrayOf(1, 2, 3, 4)

    private fun pngSignature(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}
