package com.longdev.xiaoling.ui

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.longdev.xiaoling.agent.AndroidContactReader

internal fun createAndroidContactOpenCoordinator(context: Context): ContactOpenCoordinator = ContactOpenCoordinator(
    hasReadContactsPermission = {
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
            PackageManager.PERMISSION_GRANTED
    },
    contactReader = AndroidContactReader(context.contentResolver),
    systemContactLauncher = AndroidSystemContactLauncher(context),
)

internal class AndroidSystemContactLauncher(
    private val context: Context,
) : SystemContactLauncher {
    override fun open(contactId: Long, lookupKey: String): SystemContactLaunchResult {
        return try {
            val lookupUri = ContactsContract.Contacts.getLookupUri(contactId, lookupKey)
                ?: return SystemContactLaunchResult.FAILED
            val intent = Intent(Intent.ACTION_VIEW, lookupUri).apply {
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // long: startActivity 不需要预查询目标包；直接启动并处理异常，既兼容 Android 11+ 包可见性，也不扩大 Manifest 的应用查询范围。
            context.startActivity(intent)
            SystemContactLaunchResult.OPENED
        } catch (_: ActivityNotFoundException) {
            SystemContactLaunchResult.NO_HANDLER
        } catch (_: SecurityException) {
            SystemContactLaunchResult.DENIED
        } catch (_: RuntimeException) {
            SystemContactLaunchResult.FAILED
        }
    }
}
