package com.longdev.xiaoling.ui

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.agent.AndroidContactReader
import com.longdev.xiaoling.agent.ContactDetailReadResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * long: 第 246 阶段只用纯合成联系人验证答案级系统跳转；真实通讯录内容不会进入断言、日志或截图。
 */
@RunWith(AndroidJUnit4::class)
class Stage246ContactNavigationInstrumentedTest {
    @Test
    fun trustedContactOpensCurrentSystemAuthorityDetail() = runBlocking {
        assumeRedmi()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        assumeTrue(
            "请先授予小灵 READ_CONTACTS 后运行第 246 阶段正向验收",
            ContextCompat.checkSelfPermission(targetContext, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val fixtureName = "stage246_contact_${System.currentTimeMillis().toString().takeLast(10)}"
        val rawContactUri = createSyntheticContact(fixtureName)
        try {
            val contactId = awaitAggregateContactId(rawContactUri)
            val scenario = ActivityScenario.launch<MainActivity>(Intent(targetContext, MainActivity::class.java))
            try {
                lateinit var activity: MainActivity
                scenario.onActivity { current -> activity = current }

                val result = withContext(Dispatchers.Main.immediate) {
                    createAndroidContactOpenCoordinator(activity).open("contact-$contactId")
                }

                assertEquals(ContactOpenResult.OPENED, result)
                assertTrue(
                    "系统联系人详情页没有显示合成联系人",
                    awaitVisibleText(fixtureName, timeoutMs = 5_000L),
                )
                instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                instrumentation.waitForIdleSync()
            } finally {
                // long: MIUI 从系统联系人页返回后不会稳定上报 ActivityScenario 期待的 DESTROYED；主动 finish 后不再 close，避免每次单项验收额外等待 45 秒。
                scenario.onActivity(MainActivity::finish)
                instrumentation.waitForIdleSync()
            }
            println("STAGE246_CONTACT_NAV opened=true authoritativeLookup=true syntheticFixture=true privacySafe=true")
        } finally {
            deleteSyntheticContact(rawContactUri)
        }
    }

    @Test
    fun deletedContactFailsClosedBeforeLaunchingSystemDetail() = runBlocking {
        assumeRedmi()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        assumeTrue(
            "请先授予小灵 READ_CONTACTS 后运行第 246 阶段删除竞态验收",
            ContextCompat.checkSelfPermission(targetContext, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val rawContactUri = createSyntheticContact("stage246_deleted_${System.currentTimeMillis()}")
        val contactId = awaitAggregateContactId(rawContactUri)
        deleteSyntheticContact(rawContactUri)
        awaitContactMissing(contactId)

        ActivityScenario.launch<MainActivity>(Intent(targetContext, MainActivity::class.java)).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { current -> activity = current }
            val result = withContext(Dispatchers.Main.immediate) {
                createAndroidContactOpenCoordinator(activity).open("contact-$contactId")
            }
            assertEquals(ContactOpenResult.NOT_FOUND, result)
        }
        println("STAGE246_CONTACT_NAV deletedFailClosed=true syntheticFixture=true privacySafe=true")
    }

    @Test
    fun revokedPermissionFailsClosedBeforeReadingProvider() = runBlocking {
        assumeRedmi()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        assumeTrue(
            "撤销 READ_CONTACTS 后运行第 246 阶段撤权验收",
            ContextCompat.checkSelfPermission(targetContext, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_DENIED,
        )

        ActivityScenario.launch<MainActivity>(Intent(targetContext, MainActivity::class.java)).use { scenario ->
            lateinit var activity: MainActivity
            scenario.onActivity { current -> activity = current }
            val result = withContext(Dispatchers.Main.immediate) {
                createAndroidContactOpenCoordinator(activity).open("contact-42")
            }
            assertEquals(ContactOpenResult.PERMISSION_DENIED, result)
        }
        println("STAGE246_CONTACT_NAV permissionFailClosed=true privacySafe=true")
    }

    private fun assumeRedmi() {
        assumeTrue("第 246 阶段 Android 验收只允许 Redmi begonia", Build.DEVICE == "begonia")
    }

    private fun createSyntheticContact(displayName: String): Uri {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        )
        return try {
            val rawContactUri = requireNotNull(
                resolver.insert(
                    ContactsContract.RawContacts.CONTENT_URI,
                    ContentValues().apply {
                        putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
                        putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
                    },
                ),
            )
            val rawContactId = ContentUris.parseId(rawContactUri)
            resolver.insert(
                ContactsContract.Data.CONTENT_URI,
                ContentValues().apply {
                    put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                    put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, displayName)
                },
            )
            rawContactUri
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun deleteSyntheticContact(rawContactUri: Uri) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.adoptShellPermissionIdentity(
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        )
        try {
            instrumentation.targetContext.contentResolver.delete(rawContactUri, null, null)
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
        }
    }

    private fun awaitAggregateContactId(rawContactUri: Uri): Long {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val rawContactId = ContentUris.parseId(rawContactUri)
        val deadline = SystemClock.uptimeMillis() + 5_000L
        do {
            instrumentation.uiAutomation.adoptShellPermissionIdentity(Manifest.permission.READ_CONTACTS)
            val contactId = try {
                instrumentation.targetContext.contentResolver.query(
                    ContactsContract.RawContacts.CONTENT_URI,
                    arrayOf(ContactsContract.RawContacts.CONTACT_ID),
                    "${ContactsContract.RawContacts._ID} = ?",
                    arrayOf(rawContactId.toString()),
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
                }
            } finally {
                instrumentation.uiAutomation.dropShellPermissionIdentity()
            }
            if (contactId != null) return contactId
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("系统 Contacts Provider 未及时生成聚合联系人 ID")
    }

    private suspend fun awaitContactMissing(contactId: Long) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val reader = AndroidContactReader(context.contentResolver)
        val deadline = SystemClock.uptimeMillis() + 5_000L
        do {
            if (reader.getContact(contactId) is ContactDetailReadResult.NotFound) return
            SystemClock.sleep(50L)
        } while (SystemClock.uptimeMillis() < deadline)
        error("合成联系人删除后仍可从当前 Provider 读取")
    }

    private fun awaitVisibleText(expected: String, timeoutMs: Long): Boolean {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        do {
            if (uiAutomation.rootInActiveWindow?.containsText(expected) == true) return true
            SystemClock.sleep(100L)
        } while (SystemClock.uptimeMillis() < deadline)
        return false
    }

    private fun AccessibilityNodeInfo.containsText(expected: String): Boolean {
        if (text?.toString()?.contains(expected) == true) return true
        if (contentDescription?.toString()?.contains(expected) == true) return true
        repeat(childCount) { index ->
            if (getChild(index)?.containsText(expected) == true) return true
        }
        return false
    }
}
