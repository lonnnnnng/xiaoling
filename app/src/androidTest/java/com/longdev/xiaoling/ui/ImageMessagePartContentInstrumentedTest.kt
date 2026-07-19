package com.longdev.xiaoling.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.longdev.xiaoling.model.ImageAttachmentPolicy
import com.longdev.xiaoling.model.MessagePart
import com.longdev.xiaoling.ui.theme.XiaoLingTheme
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream

class ImageMessagePartContentInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun validatedImagePartRendersWithStableFileDescription() {
        val png = ByteArrayOutputStream().use { output ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            try {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            } finally {
                bitmap.recycle()
            }
            output.toByteArray()
        }
        val part = MessagePart.Image(
            id = "image-ui-test",
            attachment = ImageAttachmentPolicy.create(
                fileName = "preview.png",
                mimeType = "image/png",
                data = png,
            ),
        )

        composeRule.setContent {
            XiaoLingTheme { ImageMessagePartContent(part) }
        }

        composeRule.onNodeWithContentDescription("preview.png").assertExists()
    }
}
