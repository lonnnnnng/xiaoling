package com.longdev.xiaoling.ui.conversation

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.genui.*
import com.longdev.xiaoling.ui.ChatMessage
import com.longdev.xiaoling.ui.MessageMeta
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class GenUiPresentationInstrumentedTest {
    @get:Rule val rule=createComposeRule()
    @Test fun explicitlyExportOwnedFixtureScreenshot() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("stage290Capture")=="true")
        // long: Redmi 的 run-as 被 SELinux 拒绝；只显式导出本测试固定画面，不枚举或读取用户文件。
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val bytes=File(target.cacheDir,"stage290-genui.png").readBytes(); require(bytes.size in 1..262_144)
        InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {
            putString("stage290Image",android.util.Base64.encodeToString(bytes,android.util.Base64.NO_WRAP))
        })
    }
    @Test fun boundedDraftSurvivesRecomposeAndFailedPreflightRemainsRetryable() {
        val text=genUiFixtureText(true); val document=GenUiPolicy.projectAssistantText(text)!!.document
        val lease=GenUiActionLeases().issue("c","m",text)
        var tick by mutableStateOf(0); var key by mutableStateOf("c:m"); var accepted by mutableStateOf(false); var count=0
        rule.setContent { MaterialTheme { Column {
            Text("重组 $tick")
            GenUiMessageContent(document,MaterialTheme.colorScheme.onSurface,{},key,lease.takeUnless{accepted},onHostAction={id,drafts ->
                assertEquals("read",id); assertEquals("2",drafts["count"]); count++; if(count==2) accepted=true
            })
        } } }
        rule.onNodeWithText("范围：本机只读").assertExists()
        rule.onNodeWithTag("genui-action-read").assertIsNotEnabled()
        rule.onNodeWithTag("genui-input-count").performTextInput("2")
        rule.runOnIdle { tick++ }; rule.onNodeWithTag("genui-input-count").assertTextContains("2")
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val file=File(target.cacheDir,"stage290-genui.png")
        file.outputStream().use { rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it) }
        rule.onNodeWithTag("genui-action-read").performClick()
        rule.onNodeWithTag("genui-action-read").assertIsEnabled().performClick()
        rule.onNodeWithTag("genui-action-read").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(2,count); accepted=false; key="other:m" }
        rule.onNodeWithTag("genui-input-count").assertTextContains("会话条数")
        rule.onNodeWithTag("genui-action-read").assertIsNotEnabled()
    }
    @Test fun busyExpiredAndRestoredMessagesCannotRequestAction() {
        val document=GenUiPolicy.projectAssistantText(genUiFixtureText())!!.document
        var busy by mutableStateOf(true); var lease by mutableStateOf(GenUiActionLeases().issue("c","m",genUiFixtureText()))
        var count=0
        rule.setContent { MaterialTheme { GenUiMessageContent(document,MaterialTheme.colorScheme.onSurface,{},"c:m",lease,!busy,{_,_->count++}) } }
        rule.onNodeWithTag("genui-action-read").assertIsNotEnabled()
        rule.runOnIdle { busy=false }; rule.onNodeWithTag("genui-action-read").assertIsEnabled()
        rule.runOnIdle { lease=null }; rule.onNodeWithTag("genui-action-read").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0,count) }
    }
    @Test fun streamingAndFailedAssistantCannotBorrowCompletedLease() {
        val text=genUiFixtureText(); val lease=GenUiActionLeases().issue("c","m",text)
        var meta by mutableStateOf(MessageMeta(streaming=true)); var requests=0
        rule.setContent { MaterialTheme { ChatBubble(ChatMessage(id="m",role="assistant",text=text,meta=meta),
            emptyMap(),emptySet(),null,{},{},{},{},{},{},{},{},{},
            genUiDraftKey="c:m",genUiActionLease=lease,onGenUiAction={_,_->requests++}) } }
        rule.onNodeWithTag("genui-action-read").assertIsNotEnabled()
        rule.runOnIdle { meta=MessageMeta(streaming=true,latencyMs=10) }; rule.onNodeWithTag("genui-action-read").assertIsEnabled()
        rule.runOnIdle { meta=MessageMeta(streaming=true,finishReason="failed",errorKind="network") }
        rule.onNodeWithTag("genui-action-read").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0,requests) }
    }
}
