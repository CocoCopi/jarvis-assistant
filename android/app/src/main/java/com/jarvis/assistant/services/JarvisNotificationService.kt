package com.jarvis.assistant.services

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class JarvisNotificationService : NotificationListenerService() {

    private val active = LinkedHashMap<String, StatusBarNotification>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        active.clear()
        activeNotifications?.forEach { active[it.key] = it }
    }

    override fun onListenerDisconnected() {
        if (instance === this) instance = null
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        active[sbn.key] = sbn
        while (active.size > 100) {
            val first = active.keys.firstOrNull() ?: break
            active.remove(first)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        active.remove(sbn.key)
    }

    fun dump(): String {
        val items = active.values
            .sortedByDescending { it.postTime }
            .take(60)
        if (items.isEmpty()) return "No active notifications."
        return items.joinToString("\n") { sbn ->
            val extras = sbn.notification.extras
            val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
            val time = timeFormat.format(Date(sbn.postTime))
            val ongoing = if (sbn.isOngoing) " [ongoing]" else ""
            "$time ${sbn.packageName}$ongoing: $title - $text"
        }
    }

    companion object {
        @Volatile
        var instance: JarvisNotificationService? = null
            private set
    }
}
