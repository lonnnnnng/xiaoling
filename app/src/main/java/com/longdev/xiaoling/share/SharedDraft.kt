package com.longdev.xiaoling.share

internal data class SharedIntentInput(
    val action: String?,
    val mimeType: String?,
    val text: String? = null,
    val streamUri: String? = null,
    val clipItemCount: Int = 0,
)

data class SharedDraftPayload(
    val text: String,
    val imageUri: String?,
)

internal sealed interface SharedDraftImport {
    data class Accepted(val payload: SharedDraftPayload) : SharedDraftImport
    data class Rejected(val reason: SharedDraftRejectionReason) : SharedDraftImport
    data object Ignored : SharedDraftImport
}

internal enum class SharedDraftRejectionReason(val userMessage: String) {
    EMPTY_TEXT("分享内容为空"),
    TEXT_TOO_LONG("分享文本不能超过 20000 个字符"),
    UNSUPPORTED_TYPE("仅支持纯文本、PNG、JPEG 和 WEBP 分享"),
    MULTIPLE_ITEMS("一次只能分享一项内容"),
    IMAGE_REQUIRED("分享中没有可读取的图片"),
    UNSAFE_URI("图片必须通过受控的 content URI 分享"),
    MALFORMED_CONTENT("分享内容无法读取"),
}

internal object SharedDraftParser {
    const val ACTION_SEND = "android.intent.action.SEND"
    const val MAX_TEXT_CHARS = 20_000

    fun parse(input: SharedIntentInput): SharedDraftImport {
        if (input.action != ACTION_SEND) return SharedDraftImport.Ignored
        val normalizedMimeType = input.mimeType?.substringBefore(';')?.trim()?.lowercase()
        val normalizedText = input.text.orEmpty()
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalizedText.length > MAX_TEXT_CHARS) {
            return SharedDraftImport.Rejected(SharedDraftRejectionReason.TEXT_TOO_LONG)
        }
        // long: ACTION_SEND 也能携带多项 ClipData；单项边界必须先于 MIME 分支执行，避免文本分享绕过多附件拒绝。
        if (input.clipItemCount > 1) {
            return SharedDraftImport.Rejected(SharedDraftRejectionReason.MULTIPLE_ITEMS)
        }
        return when {
            normalizedMimeType == TEXT_MIME_TYPE -> {
                if (normalizedText.isBlank()) {
                    SharedDraftImport.Rejected(SharedDraftRejectionReason.EMPTY_TEXT)
                } else {
                    accepted(text = normalizedText, imageUri = null)
                }
            }

            normalizedMimeType in SUPPORTED_IMAGE_MIME_TYPES -> {
                when {
                    input.streamUri.isNullOrBlank() -> SharedDraftImport.Rejected(SharedDraftRejectionReason.IMAGE_REQUIRED)
                    !input.streamUri.trim().startsWith(CONTENT_URI_PREFIX) -> {
                        SharedDraftImport.Rejected(SharedDraftRejectionReason.UNSAFE_URI)
                    }

                    else -> accepted(
                        text = normalizedText,
                        imageUri = input.streamUri.trim(),
                    )
                }
            }

            else -> SharedDraftImport.Rejected(SharedDraftRejectionReason.UNSUPPORTED_TYPE)
        }
    }

    private fun accepted(text: String, imageUri: String?): SharedDraftImport {
        // long: 分享内容只进入待编辑草稿；解析层不暴露任何发送动作，确保外部 Intent 无法绕过用户确认。
        return SharedDraftImport.Accepted(
            SharedDraftPayload(
                text = text,
                imageUri = imageUri,
            ),
        )
    }

    private val SUPPORTED_IMAGE_MIME_TYPES = setOf("image/png", "image/jpeg", "image/jpg", "image/webp")
    private const val TEXT_MIME_TYPE = "text/plain"
    private const val CONTENT_URI_PREFIX = "content://"
}
