package com.longdev.xiaoling.share

import org.junit.Assert.assertEquals
import org.junit.Test

class SharedDraftParserTest {
    @Test
    fun malformedOrUnsupportedSharesAreRejectedBeforeDraftProjection() {
        val cases = listOf(
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "text/plain",
                text = "   ",
            ) to SharedDraftRejectionReason.EMPTY_TEXT,
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "text/plain",
                text = "x".repeat(SharedDraftParser.MAX_TEXT_CHARS + 1),
            ) to SharedDraftRejectionReason.TEXT_TOO_LONG,
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "image/gif",
                streamUri = "content://images/1",
            ) to SharedDraftRejectionReason.UNSUPPORTED_TYPE,
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "image/png",
                streamUri = "content://images/1",
                clipItemCount = 2,
            ) to SharedDraftRejectionReason.MULTIPLE_ITEMS,
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "text/plain",
                text = "第一项",
                clipItemCount = 2,
            ) to SharedDraftRejectionReason.MULTIPLE_ITEMS,
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "image/jpeg",
            ) to SharedDraftRejectionReason.IMAGE_REQUIRED,
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "image/webp",
                streamUri = "file:///sdcard/Pictures/private.webp",
                clipItemCount = 1,
            ) to SharedDraftRejectionReason.UNSAFE_URI,
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "image/png",
                streamUri = "CONTENT://images/uppercase-scheme",
                clipItemCount = 1,
            ) to SharedDraftRejectionReason.UNSAFE_URI,
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "application/pdf",
            ) to SharedDraftRejectionReason.DOCUMENT_REQUIRED,
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "application/pdf",
                streamUri = "file:///sdcard/Download/private.pdf",
                clipItemCount = 1,
            ) to SharedDraftRejectionReason.UNSAFE_DOCUMENT_URI,
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "application/zip",
                streamUri = "content://documents/archive.zip",
                clipItemCount = 1,
            ) to SharedDraftRejectionReason.UNSUPPORTED_TYPE,
        )

        cases.forEach { (input, expectedReason) ->
            assertEquals(SharedDraftImport.Rejected(expectedReason), SharedDraftParser.parse(input))
        }
    }

    @Test
    fun supportedImageShareKeepsSingleContentUriAndOptionalText() {
        val result = SharedDraftParser.parse(
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "image/png",
                text = " https://example.com/screenshot ",
                streamUri = "content://com.example.images/shared/42",
                clipItemCount = 1,
            ),
        )

        assertEquals(
            SharedDraftImport.Accepted(
                SharedDraftPayload(
                    text = "https://example.com/screenshot",
                    imageUri = "content://com.example.images/shared/42",
                ),
            ),
            result,
        )
    }

    @Test
    fun supportedDocumentShareKeepsSingleContentUriAndOptionalCaption() {
        val result = SharedDraftParser.parse(
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "text/markdown; charset=utf-8",
                text = " 请总结这个文档 ",
                streamUri = "content://com.example.documents/shared/42",
                clipItemCount = 1,
            ),
        )

        assertEquals(
            SharedDraftImport.Accepted(
                SharedDraftPayload(
                    text = "请总结这个文档",
                    imageUri = null,
                    documentUri = "content://com.example.documents/shared/42",
                ),
            ),
            result,
        )
    }

    @Test
    fun textPlainWithStreamIsDocumentWhileTextWithoutStreamRemainsPlainDraft() {
        assertEquals(
            SharedDraftImport.Accepted(
                SharedDraftPayload(
                    text = "可选说明",
                    imageUri = null,
                    documentUri = "content://com.example.documents/shared/readme.txt",
                ),
            ),
            SharedDraftParser.parse(
                SharedIntentInput(
                    action = SharedDraftParser.ACTION_SEND,
                    mimeType = "text/plain",
                    text = "可选说明",
                    streamUri = "content://com.example.documents/shared/readme.txt",
                    clipItemCount = 1,
                ),
            ),
        )
    }

    @Test
    fun launcherIntentIsIgnoredInsteadOfCreatingEmptySharedDraft() {
        val result = SharedDraftParser.parse(
            SharedIntentInput(
                action = "android.intent.action.MAIN",
                mimeType = null,
            ),
        )

        assertEquals(SharedDraftImport.Ignored, result)
    }

    @Test
    fun textShareBecomesEditableDraftWithoutAutomaticSubmission() {
        val result = SharedDraftParser.parse(
            SharedIntentInput(
                action = SharedDraftParser.ACTION_SEND,
                mimeType = "text/plain",
                text = "  第一行\r\n第二行  ",
            ),
        )

        assertEquals(
            SharedDraftImport.Accepted(
                SharedDraftPayload(
                    text = "第一行\n第二行",
                    imageUri = null,
                ),
            ),
            result,
        )
    }
}
