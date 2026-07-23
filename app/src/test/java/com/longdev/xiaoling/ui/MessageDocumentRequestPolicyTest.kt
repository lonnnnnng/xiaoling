package com.longdev.xiaoling.ui

import com.longdev.xiaoling.model.ApiMode
import com.longdev.xiaoling.model.DocumentAttachmentPolicy
import com.longdev.xiaoling.model.MessageOrigin
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.model.MessageAttachmentSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageDocumentRequestPolicyTest {
    @Test
    fun attachmentSelectionOwnsSendValidationAndStablePartOrder() {
        val attachment = DocumentAttachmentPolicy.create(
            fileName = "notes.md",
            mimeType = "text/markdown",
            data = "stage 27".toByteArray(),
        )
        val selection = MessageAttachmentSelection(document = attachment)

        assertTrue(selection.agentRejectionReason(ApiMode.CHAT_COMPLETIONS).orEmpty().contains("Responses"))
        assertEquals(null, selection.agentRejectionReason(ApiMode.RESPONSES))
        assertTrue(selection.chatRejectionReason(ApiMode.CHAT_COMPLETIONS).orEmpty().contains("Responses"))
        assertEquals(null, selection.chatRejectionReason(ApiMode.RESPONSES))
        val parts = selection.toUserMessageParts("message-document", "总结文档")
        assertTrue(parts.first() is MessagePart.Document)
        assertEquals("总结文档", (parts.last() as MessagePart.Text).text)
    }

    @Test
    fun agentRejectsMixedAttachmentsEvenInResponsesMode() {
        val image = com.longdev.xiaoling.model.ImageAttachmentPolicy.create(
            fileName = "receipt.png",
            mimeType = "image/png",
            data = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1),
        )
        val document = DocumentAttachmentPolicy.create(
            fileName = "notes.md",
            mimeType = "text/markdown",
            data = "stage 75".toByteArray(),
        )

        val rejection = MessageAttachmentSelection(image = image, document = document)
            .agentRejectionReason(ApiMode.RESPONSES)

        assertTrue(rejection.orEmpty().contains("只能携带一种附件"))
    }

    @Test
    fun onlyResponsesUserMessageCanForwardValidatedDocument() {
        val attachment = DocumentAttachmentPolicy.create(
            fileName = "notes.md",
            mimeType = "text/markdown",
            data = "stage 27".toByteArray(),
        )
        val user = ChatMessage(
            id = "message-document-request",
            role = "user",
            text = "总结文档",
            origin = MessageOrigin.USER,
            parts = listOf(
                MessagePart.Document(id = "part-document-request", attachment = attachment),
                MessagePart.Text(id = "part-document-text", text = "总结文档"),
            ),
        )

        assertEquals(listOf(attachment), user.documentsForRequest(ApiMode.RESPONSES))
        assertTrue(user.documentsForRequest(ApiMode.CHAT_COMPLETIONS).isEmpty())
    }

    @Test
    fun assistantCannotForwardStoredUserDocument() {
        val attachment = DocumentAttachmentPolicy.create(
            fileName = "forged.txt",
            mimeType = "text/plain",
            data = "forged".toByteArray(),
        )
        val assistant = ChatMessage(
            id = "message-forged-document-request",
            role = "assistant",
            text = "普通回答",
            origin = MessageOrigin.ORDINARY_ASSISTANT,
            parts = listOf(MessagePart.Document(id = "part-forged-document", attachment = attachment)),
        )

        assertTrue(assistant.documentsForRequest(ApiMode.RESPONSES).isEmpty())
    }
}
