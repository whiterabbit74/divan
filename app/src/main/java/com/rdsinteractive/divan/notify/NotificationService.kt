package com.rdsinteractive.divan.notify

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class NotificationItem(
    val key: String,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postTime: Long,
    val clearable: Boolean,
    val autoCancel: Boolean,
    val contentIntent: PendingIntent?,
)

/**
 * Feeds the bell on the home screen. Android binds it only after the user allows
 * "notification access" for Divan in the system settings.
 */
class NotificationService : NotificationListenerService() {
    private val labels = HashMap<String, String>()

    override fun onListenerConnected() {
        NotificationCenter.service = this
        publish()
    }

    override fun onListenerDisconnected() {
        NotificationCenter.service = null
        NotificationCenter.items.value = emptyList()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = publish()

    override fun onNotificationRemoved(sbn: StatusBarNotification) = publish()

    private fun publish() {
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        NotificationCenter.items.value = active
            .filter(::worthShowing)
            .sortedByDescending { it.postTime }
            .map(::toItem)
    }

    /** Ongoing ones are services saying "I'm running" (IR service, the remote, casting); group summaries repeat their children. */
    private fun worthShowing(sbn: StatusBarNotification): Boolean {
        val flags = sbn.notification.flags
        if (sbn.packageName == packageName) return false
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return false
        if (flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        val extras = sbn.notification.extras
        return !extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrBlank() ||
            !extras.getCharSequence(Notification.EXTRA_TEXT).isNullOrBlank()
    }

    private fun toItem(sbn: StatusBarNotification): NotificationItem {
        val extras = sbn.notification.extras
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        return NotificationItem(
            key = sbn.key,
            packageName = sbn.packageName,
            appLabel = labels.getOrPut(sbn.packageName) { appLabel(sbn.packageName) },
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = text?.toString().orEmpty(),
            postTime = sbn.postTime,
            clearable = sbn.isClearable,
            autoCancel = sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0,
            contentIntent = sbn.notification.contentIntent,
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    companion object {
        fun component(context: Context) = ComponentName(context, NotificationService::class.java)
    }
}

/** Shared between the listener and the home screen; both live in the launcher's process. */
object NotificationCenter {
    internal val items = MutableStateFlow<List<NotificationItem>>(emptyList())
    val notifications: StateFlow<List<NotificationItem>> = items

    @Volatile
    internal var service: NotificationService? = null

    /** Same as tapping it in the system shade: runs its intent, auto-cancel ones go away. */
    fun open(context: Context, item: NotificationItem) {
        val intent = item.contentIntent
        if (intent != null) {
            try {
                // Android 14+ only lends our foreground status to the PendingIntent when asked.
                val options = if (Build.VERSION.SDK_INT >= 34) {
                    ActivityOptions.makeBasic()
                        .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                        .toBundle()
                } else {
                    null
                }
                intent.send(context, 0, null, null, null, null, options)
            } catch (error: PendingIntent.CanceledException) {
                Log.w("Divan", "Notification intent is gone: ${item.key}")
            }
        }
        if (item.autoCancel && item.clearable) dismiss(item)
    }

    fun dismiss(item: NotificationItem) {
        runCatching { service?.cancelNotification(item.key) }
    }

    fun dismissAll() {
        runCatching { service?.cancelAllNotifications() }
    }
}
