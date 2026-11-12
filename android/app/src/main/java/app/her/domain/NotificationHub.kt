package app.her.domain

import android.app.PendingIntent
import android.content.Context
import app.her.core.notify.NotificationSource
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

data class IncomingNotification(
    val key: String,
    val source: NotificationSource,
    val packageName: String,
    val title: String?,
    val text: String?,
    val postedAt: Long,
    val fromPartner: Boolean,
)

/**
 * In-process bridge between the notification listener and the companion UI. Holds only what the
 * user allowed; nothing here is sent to the server except an optional short hint.
 */
object NotificationHub {
    private val intents = ConcurrentHashMap<String, PendingIntent>()
    private val _unread = MutableStateFlow<List<IncomingNotification>>(emptyList())
    val unread: StateFlow<List<IncomingNotification>> = _unread.asStateFlow()
    private val _arrivals = MutableSharedFlow<IncomingNotification>(extraBufferCapacity = 16)
    val arrivals: SharedFlow<IncomingNotification> = _arrivals.asSharedFlow()
    val listenerConnected = MutableStateFlow(false)

    fun post(notification: IncomingNotification, contentIntent: PendingIntent?) {
        contentIntent?.let { intents[notification.key] = it }
        _unread.update { list -> (list.filterNot { it.key == notification.key } + notification).takeLast(30) }
        _arrivals.tryEmit(notification)
    }

    fun remove(key: String) {
        intents.remove(key)
        _unread.update { list -> list.filterNot { it.key == key } }
    }

    fun clear(source: NotificationSource? = null) {
        _unread.update { list -> if (source == null) emptyList() else list.filterNot { it.source == source } }
    }

    /** Opens the conversation in the originating app (or the app itself) and clears the badge. */
    fun open(context: Context, notification: IncomingNotification) {
        val intent = intents.remove(notification.key)
        val opened = intent?.let { runCatching { sendAllowingActivityStart(context, it); true }.getOrDefault(false) } ?: false
        if (!opened) {
            context.packageManager.getLaunchIntentForPackage(notification.packageName)?.let {
                it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(it) }
            }
        }
        remove(notification.key)
    }

    /**
     * Sends a PendingIntent from the floating companion. Since Android 14 the sender must opt in
     * to letting it start an activity; the user tapped it, so that's what they asked for.
     */
    fun sendAllowingActivityStart(context: Context, pendingIntent: PendingIntent) {
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            val options = android.app.ActivityOptions.makeBasic()
                .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
        } else {
            pendingIntent.send()
        }
    }

    /** "WhatsApp message from Maya" for the latest partner message in the last 30 minutes. */
    fun latestHint(): String? {
        val recent = _unread.value.lastOrNull { it.fromPartner && System.currentTimeMillis() - it.postedAt < 30 * 60_000 }
            ?: return null
        return "${recent.source.label} message from ${recent.title ?: "your partner"}"
    }
}
