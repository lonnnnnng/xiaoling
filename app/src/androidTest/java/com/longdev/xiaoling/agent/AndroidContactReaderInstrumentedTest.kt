package com.longdev.xiaoling.agent

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AndroidContactReaderInstrumentedTest {
    @Test
    fun deniedPermissionFailsClosedWithoutContactFacts() = runBlocking {
        assumeTrue("第 245 阶段 Android 验收只允许 Redmi begonia", Build.DEVICE == "begonia")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertEquals(
            PackageManager.PERMISSION_DENIED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS),
        )

        val result = AndroidContactReader(context.contentResolver).searchContacts("小灵不存在联系人", limit = 5)

        assertTrue(result is ContactSearchResult.PermissionDenied)
    }

    @Test
    fun grantedProviderSupportsBoundedSearchAndStableDetailWithoutLoggingValues() = runBlocking {
        assumeTrue("第 245 阶段 Android 验收只允许 Redmi begonia", Build.DEVICE == "begonia")
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assumeTrue(
            "请先在小灵的“联系人访问”页面授权只读联系人",
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
                PackageManager.PERMISSION_GRANTED,
        )
        val reader = AndroidContactReader(context.contentResolver)
        val noMatch = reader.searchContacts("__xiaoling_stage245_no_such_contact__", limit = 5)
        assertTrue(noMatch is ContactSearchResult.Success)
        assertTrue((noMatch as ContactSearchResult.Success).contacts.isEmpty())

        val firstContactId = context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0).takeIf { it > 0L } else null
        }
        val detail = firstContactId?.let { contactId -> reader.getContact(contactId) }
        if (firstContactId == null) {
            assertTrue(detail == null)
        } else {
            assertTrue(detail is ContactDetailReadResult.Success)
            val contact = (detail as ContactDetailReadResult.Success).contact
            assertEquals(firstContactId, contact.contactId)
            assertTrue(contact.lookupKey?.isNotBlank() == true)
            assertTrue(contact.phoneNumbers.size <= 10)
            assertTrue(contact.emailAddresses.size <= 10)
        }

        // long: 真机日志只记录分支和数量，不输出联系人 ID、姓名、号码或邮箱，避免验收本身扩大敏感信息留存。
        println(
            "STAGE245_CONTACTS providerSuccess=true fixturePresent=${firstContactId != null} " +
                "phoneCount=${(detail as? ContactDetailReadResult.Success)?.contact?.phoneNumbers?.size ?: 0} " +
                "emailCount=${(detail as? ContactDetailReadResult.Success)?.contact?.emailAddresses?.size ?: 0} " +
                "privacySafe=true",
        )
    }
}
