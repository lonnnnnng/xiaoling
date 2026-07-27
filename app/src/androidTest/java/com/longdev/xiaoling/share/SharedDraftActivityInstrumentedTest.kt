package com.longdev.xiaoling.share

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.ui.XiaoLingUiState
import com.longdev.xiaoling.ui.XiaoLingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedDraftActivityInstrumentedTest {
    @Test
    fun pngContentShareUsesExistingAttachmentValidationWithoutAutoSend() {
        val caption = "share-image-${System.nanoTime()}"
        val imageUri = createTestPng()
        try {
            val conflictingUri = Uri.parse("content://com.longdev.xiaoling.test/conflicting.png")
            val conflictingShare = Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, imageUri)
                clipData = android.content.ClipData.newRawUri("conflicting-image", conflictingUri)
            }
            assertEquals(
                SharedDraftImport.Rejected(SharedDraftRejectionReason.MULTIPLE_ITEMS),
                AndroidShareIntentReader.read(conflictingShare),
            )

            val launchIntent = Intent(
                ApplicationProviderHolder.context,
                MainActivity::class.java,
            ).apply {
                action = Intent.ACTION_SEND
                type = "image/png"
                putExtra(Intent.EXTRA_TEXT, caption)
                putExtra(Intent.EXTRA_STREAM, imageUri)
                clipData = android.content.ClipData.newUri(
                    ApplicationProviderHolder.context.contentResolver,
                    "shared-image",
                    imageUri,
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
                val state = scenario.awaitState {
                    it.prompt == caption && it.pendingImage != null && !it.attachingImage
                }
                assertEquals("image/png", state.pendingImage?.mimeType)
                assertTrue(state.sharedDraftImported)
                assertFalse(state.sendingMessage)
                assertFalse(state.chatMessages.any { it.role == "user" && it.text == caption })
                scenario.onActivity { activity ->
                    ViewModelProvider(activity)[XiaoLingViewModel::class.java].removePendingImage()
                }
                val removedState = scenario.awaitState { it.pendingImage == null }
                assertFalse(removedState.sharedDraftImported)
                val navigationVersionBeforeMissingImage = removedState.sharedDraftNavigationVersion

                val missingUri = Uri.parse("content://com.longdev.xiaoling.test/missing.png")
                scenario.onActivity { activity ->
                    val viewModel = ViewModelProvider(activity)[XiaoLingViewModel::class.java]
                    viewModel.updatePrompt("")
                    activity.startActivity(
                        Intent(activity, MainActivity::class.java).apply {
                            action = Intent.ACTION_SEND
                            type = "image/png"
                            putExtra(Intent.EXTRA_STREAM, missingUri)
                            clipData = android.content.ClipData.newRawUri("missing-image", missingUri)
                            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        },
                    )
                }
                val failedState = scenario.awaitState {
                    // long: 页面会立即把错误结果消费成轻提示；以导航版本和附件终态验证业务结果，避免轮询瞬时 UI 事件产生竞态。
                    it.sharedDraftNavigationVersion > navigationVersionBeforeMissingImage &&
                        !it.attachingImage &&
                        it.pendingImage == null
                }
                assertNull(failedState.pendingImage)
                assertFalse(failedState.sharedDraftImported)
                assertFalse(failedState.sendingMessage)
            }
        } finally {
            ApplicationProviderHolder.context.contentResolver.delete(imageUri, null, null)
        }
    }

    @Test
    fun coldAndWarmTextSharesStayEditableAndNeverAutoSend() {
        val firstText = "share-cold-${System.nanoTime()}"
        val editedFirstText = "$firstText-edited"
        val secondText = "share-warm-${System.nanoTime()}"
        val multipleTextItems = android.content.ClipData.newPlainText("first", firstText).apply {
            addItem(android.content.ClipData.Item(secondText))
        }
        assertEquals(
            SharedDraftImport.Rejected(SharedDraftRejectionReason.MULTIPLE_ITEMS),
            AndroidShareIntentReader.read(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, firstText)
                    clipData = multipleTextItems
                },
            ),
        )
        val launchIntent = Intent(
            ApplicationProviderHolder.context,
            MainActivity::class.java,
        ).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, firstText)
            // long: 外部发送方可以伪造任意 extra；分享入口仍需导入，不能把该值当成内部去重状态。
            putExtra("com.longdev.xiaoling.extra.SHARE_HANDLED", true)
        }

        ActivityScenario.launch<MainActivity>(launchIntent).use { scenario ->
            val coldState = scenario.awaitState { it.prompt == firstText }
            assertTrue(coldState.sharedDraftImported)
            assertFalse(coldState.sendingMessage)
            assertFalse(coldState.chatMessages.any { it.role == "user" && it.text == firstText })

            scenario.onActivity { activity ->
                ViewModelProvider(activity)[XiaoLingViewModel::class.java].updatePrompt(editedFirstText)
            }
            val editedState = scenario.awaitState { it.prompt == editedFirstText }
            assertFalse(editedState.sharedDraftImported)

            scenario.recreate()
            val recreatedState = scenario.awaitState { it.prompt == editedFirstText }
            assertNull(recreatedState.pendingSharedDraft)
            assertFalse(recreatedState.chatMessages.any { it.role == "user" && it.text == firstText })

            scenario.onActivity { activity ->
                activity.startActivity(
                    Intent(activity, MainActivity::class.java).apply {
                        action = Intent.ACTION_SEND
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, secondText)
                        addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    },
                )
            }

            val conflictState = scenario.awaitState { it.pendingSharedDraft?.text == secondText }
            assertEquals(editedFirstText, conflictState.prompt)
            assertFalse(conflictState.chatMessages.any { it.role == "user" && it.text == secondText })

            scenario.onActivity { activity ->
                ViewModelProvider(activity)[XiaoLingViewModel::class.java].openPendingSharedDraft()
            }
            val replacedState = scenario.awaitState { it.prompt == secondText }
            assertNull(replacedState.pendingSharedDraft)
            assertTrue(replacedState.sharedDraftImported)
            assertFalse(replacedState.sendingMessage)
            assertFalse(replacedState.chatMessages.any { it.role == "user" && it.text == secondText })

            scenario.onActivity { activity ->
                ViewModelProvider(activity)[XiaoLingViewModel::class.java].openNewConversation()
            }
            val switchedState = scenario.awaitState { !it.sharedDraftImported }
            assertFalse(switchedState.sharedDraftImported)
        }
    }

    private fun ActivityScenario<MainActivity>.awaitState(
        predicate: (XiaoLingUiState) -> Boolean,
    ): XiaoLingUiState {
        val deadline = System.currentTimeMillis() + STATE_TIMEOUT_MS
        var latest = XiaoLingUiState()
        while (System.currentTimeMillis() < deadline) {
            onActivity { activity ->
                latest = ViewModelProvider(activity)[XiaoLingViewModel::class.java].uiState
            }
            if (predicate(latest)) return latest
            Thread.sleep(STATE_POLL_MS)
        }
        throw AssertionError("Timed out waiting for shared draft state: $latest")
    }

    private fun createTestPng(): android.net.Uri {
        val resolver = ApplicationProviderHolder.context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "xiaoling-share-${System.nanoTime()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/XiaoLingTest")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Unable to create test MediaStore image")
        val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        try {
            resolver.openOutputStream(uri)?.use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
            } ?: error("Unable to write test MediaStore image")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                null,
                null,
            )
            return uri
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        } finally {
            bitmap.recycle()
        }
    }

    private object ApplicationProviderHolder {
        val context: android.content.Context =
            androidx.test.core.app.ApplicationProvider.getApplicationContext()
    }

    private companion object {
        const val STATE_TIMEOUT_MS = 15_000L
        const val STATE_POLL_MS = 50L
    }
}
