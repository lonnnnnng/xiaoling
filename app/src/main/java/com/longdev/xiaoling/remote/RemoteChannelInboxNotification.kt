package com.longdev.xiaoling.remote

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.longdev.xiaoling.MainActivity
import com.longdev.xiaoling.R
import com.longdev.xiaoling.agent.RemoteChannelBackgroundRegistry
import com.longdev.xiaoling.agent.RemoteChannelInboxNotifier
import java.util.UUID
import org.json.JSONObject

/** long: 通知只持有一次性私有导航令牌，不携带正文、远程参数或执行许可。 */
class RemoteChannelInboxNavigationStore(context: Context, preferencesName: String = "xiaoling_remote_inbox_navigation", private val clock: () -> Long = System::currentTimeMillis) {
    private val preferences = context.applicationContext.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
    fun issue(identity: String): String? = synchronized(LOCK) {
        val token = UUID.randomUUID().toString()
        val states = read() ?: return@synchronized null
        states.keys().asSequence().toList().filter { states.getJSONObject(it).getLong("expiresAt") <= clock() }.forEach(states::remove)
        val key = RemoteChannelBackgroundRegistry.digest(identity)
        if (states.length() >= 32 && !states.has(key)) return@synchronized null
        val expiresAt = runCatching { Math.addExact(clock(), 24 * 60 * 60 * 1000L) }.getOrNull() ?: return@synchronized null
        states.put(key, JSONObject().put("token", token).put("expiresAt", expiresAt))
        if (preferences.edit().putString("tokens", states.toString()).commit()) token else null
    }
    fun consume(token: String): Boolean = synchronized(LOCK) {
        if (!validToken(token)) return@synchronized false
        val states = read() ?: return@synchronized false
        val key = states.keys().asSequence().firstOrNull { states.getJSONObject(it).getString("token") == token } ?: return@synchronized false
        val matched = states.getJSONObject(key).getLong("expiresAt") > clock()
        states.remove(key)
        // long: 同步消费成功后才导航，Activity 重建或重复点击不能重放旧通知；即便导航通过也只进入待确认列表。
        matched && preferences.edit().putString("tokens", states.toString()).commit()
    }
    private fun read(): JSONObject? = runCatching {
        val raw = preferences.getString("tokens", null) ?: return@runCatching JSONObject()
        require(raw.length <= 12_000)
        JSONObject(raw).also { root ->
            require(root.length() <= 32)
            root.keys().forEach { key ->
                require(key.matches(Regex("[0-9a-f]{64}")))
                val entry = root.getJSONObject(key)
                require(entry.keys().asSequence().toSet() == setOf("token", "expiresAt"))
                require(validToken(entry.getString("token")))
                require(entry.opt("expiresAt") is Long || entry.opt("expiresAt") is Int)
            }
        }
    }.getOrNull()
    companion object {
        private val LOCK = Any()
        fun validToken(token: String) = token.length == 36 && runCatching { UUID.fromString(token).toString() == token }.getOrDefault(false)
    }
}

object RemoteChannelInboxNavigationIntent {
    const val ACTION = "com.longdev.xiaoling.OPEN_REMOTE_INBOX"
    const val TOKEN = "remoteInboxNavigationToken"
    fun create(context: Context, token: String) = Intent(context, MainActivity::class.java).apply {
        action = ACTION
        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        putExtra(TOKEN, token)
    }
    fun read(intent: Intent): String? {
        val forbidden = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        if (intent.action != ACTION || intent.flags and forbidden != 0 || intent.data != null || intent.clipData != null || intent.selector != null || intent.type != null) return null
        return runCatching { intent.getStringExtra(TOKEN) }.getOrNull()?.takeIf(RemoteChannelInboxNavigationStore::validToken)
    }
}

class AndroidRemoteChannelInboxNotifier(
    private val context: Context,
    private val tokens: RemoteChannelInboxNavigationStore = RemoteChannelInboxNavigationStore(context),
) : RemoteChannelInboxNotifier {
    override fun show(identity: String): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "远程待确认消息", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "只提示已验签入箱的待确认消息，点击后由你选择是否打开"
        })
        if (!manager.areNotificationsEnabled() || manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE ||
            (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)) return false
        val token = tokens.issue(identity) ?: return false
        val pending = PendingIntent.getActivity(context, identity.hashCode(), RemoteChannelInboxNavigationIntent.create(context, token),
            PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_xiaoling_notification)
            .setContentTitle("有远程消息待确认").setContentText("点击查看待确认列表；打开后仍需你确认")
            .setContentIntent(pending).setAutoCancel(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_MESSAGE).build()
        return runCatching { manager.notify(identity, 1, notification); true }.getOrDefault(false)
    }
    override fun cancel(identity: String) { context.getSystemService(NotificationManager::class.java)?.cancel(identity, 1) }
    companion object { const val CHANNEL = "remote_channel_inbox" }
}
