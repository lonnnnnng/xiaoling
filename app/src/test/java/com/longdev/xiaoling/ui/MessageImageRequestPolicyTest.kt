package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.ImageAttachmentPolicy
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageAttachmentSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageImageRequestPolicyTest {
    @Test
    fun onlyResponsesUserMessageCanForwardValidatedImages() {
        val attachment = ImageAttachmentPolicy.create(
            fileName = "receipt.png",
            mimeType = "image/png",
            data = pngSignature() + byteArrayOf(1, 2, 3),
        )
        val user = ChatMessage(
            id = "message-image-request",
            role = "user",
            text = "识别金额",
            origin = MessageOrigin.USER,
            parts = listOf(
                MessagePart.Image(id = "part-image-request", attachment = attachment),
                MessagePart.Text(id = "part-image-text", text = "识别金额"),
            ),
        )

        assertEquals(listOf(attachment), user.imagesForRequest(ApiMode.RESPONSES))
        assertTrue(user.imagesForRequest(ApiMode.CHAT_COMPLETIONS).isEmpty())
        assertEquals(MessageAttachmentSelection(image = attachment), user.attachmentsForAgent())
    }

    @Test
    fun assistantCannotForwardStoredUserImageEvenInResponsesMode() {
        val attachment = ImageAttachmentPolicy.create(
            fileName = "forged.png",
            mimeType = "image/png",
            data = pngSignature() + byteArrayOf(1),
        )
        val assistant = ChatMessage(
            id = "message-forged-image-request",
            role = "assistant",
            text = "普通回答",
            origin = MessageOrigin.ORDINARY_ASSISTANT,
            parts = listOf(MessagePart.Image(id = "part-forged-image", attachment = attachment)),
        )

        assertTrue(assistant.imagesForRequest(ApiMode.RESPONSES).isEmpty())
        assertEquals(MessageAttachmentSelection(), assistant.attachmentsForAgent())
    }

    private fun pngSignature(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
    )
}
