package com.longdev.xiaoling.agent

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri

sealed interface ContactDialerResult {
    data object Opened : ContactDialerResult
    data object Unavailable : ContactDialerResult
    data object Failed : ContactDialerResult
}

fun interface ContactDialer {
    fun open(phoneNumber: String): ContactDialerResult
}

object UnavailableContactDialer : ContactDialer {
    override fun open(phoneNumber: String): ContactDialerResult = ContactDialerResult.Unavailable
}

class AndroidContactDialer(
    private val context: Context,
) : ContactDialer {
    override fun open(phoneNumber: String): ContactDialerResult {
        val intent = Intent(
            Intent.ACTION_DIAL,
            Uri.fromParts("tel", phoneNumber, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            // long: ACTION_DIAL 只把号码交给系统拨号页，真正呼叫仍要求用户在系统应用中再次确认；应用不申请 CALL_PHONE。
            context.startActivity(intent)
            ContactDialerResult.Opened
        } catch (_: ActivityNotFoundException) {
            ContactDialerResult.Unavailable
        } catch (_: SecurityException) {
            ContactDialerResult.Failed
        } catch (_: RuntimeException) {
            ContactDialerResult.Failed
        }
    }
}
