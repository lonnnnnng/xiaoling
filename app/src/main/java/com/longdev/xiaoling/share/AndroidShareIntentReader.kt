package com.longdev.xiaoling.share

import android.content.Intent
import android.net.Uri

internal object AndroidShareIntentReader {
    fun read(intent: Intent): SharedDraftImport {
        if (intent.action != Intent.ACTION_SEND) return SharedDraftImport.Ignored
        return runCatching {
            val clipData = intent.clipData
            val extraStreamUri = intent.streamUri()
            val clipStreamUri = clipData
                ?.takeIf { it.itemCount == 1 }
                ?.getItemAt(0)
                ?.uri
            val streamUri = extraStreamUri ?: clipStreamUri
            val clipItemCount = clipData?.itemCount ?: 0
            // long: Android 分享方常把同一 URI 同时放进 EXTRA_STREAM 和 ClipData；只有两处 URI 不同时才代表多图，不能因兼容性重复字段误拒绝单图。
            val sharedItemCount = when {
                clipItemCount > 1 -> clipItemCount
                extraStreamUri != null && clipStreamUri != null && extraStreamUri != clipStreamUri -> 2
                streamUri != null -> 1
                else -> 0
            }
            SharedDraftParser.parse(
                SharedIntentInput(
                    action = intent.action,
                    mimeType = intent.type,
                    text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString(),
                    streamUri = streamUri?.toString(),
                    clipItemCount = sharedItemCount,
                ),
            )
        }.getOrElse {
            SharedDraftImport.Rejected(SharedDraftRejectionReason.MALFORMED_CONTENT)
        }
    }

    private fun Intent.streamUri(): Uri? = parcelableUriExtra(Intent.EXTRA_STREAM)

    private fun Intent.parcelableUriExtra(name: String): Uri? {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(name, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(name)
        }
    }
}
