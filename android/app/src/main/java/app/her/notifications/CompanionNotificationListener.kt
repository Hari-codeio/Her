package app.her.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import app.her.HerApplication
import app.her.core.greeting.GreetingComposer
import app.her.core.notify.NotificationSource
import app.her.core.notify.PartnerMatcher
import app.her.data.local.NotificationEntity
import app.her.domain.IncomingNotification
import app.her.domain.NotificationHub
import app.her.domain.ReactionKind
import kotlinx.coroutines.launch

/**
 * Reads notifications from the messaging apps the user switched on, on this phone only. It never
 * reads other people's accounts and never sends message content anywhere.
 */
class CompanionNotificationListener : NotificationListenerService() {
    private var lastSignature: String? = null

    override fun onListenerConnected() {
        NotificationHub.listenerConnected.value = true
    }

    override fun onListenerDisconnected() {
        NotificationHub.listenerConnected.value = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val container = (application as HerApplication).container
        val settings = container.settings.settings.value
        if (!settings.signedIn) return
        val source = NotificationSource.forPackage(sbn.packageName) ?: return
        if (source !in settings.enabledSources) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        // Android 15+ may hand third-party listeners a redacted copy (empty sender, placeholder text)
        // for sensitive content such as SMS from unknown numbers or one-time codes. That is
        // intentional platform privacy; the companion then gives a generic reaction.
        val (title, fullText) = senderAndText(n)
        // Messaging apps re-post the same notification as it updates; count each message once.
        val signature = "${sbn.key}|$title|$fullText"
        if (signature == lastSignature) return
        lastSignature = signature
        val text = if (settings.showPreviews) fullText?.take(200) else null
        val partnerNames = settings.partnerContactNames.ifEmpty {
            listOfNotNull(container.repo.state.value.partner?.displayName)
        }
        val fromPartner = PartnerMatcher.isPartner(title, partnerNames)
        val incoming = IncomingNotification(
            key = sbn.key,
            source = source,
            packageName = sbn.packageName,
            title = title,
            text = text,
            postedAt = sbn.postTime,
            fromPartner = fromPartner,
        )
        NotificationHub.post(incoming, n.contentIntent)
        container.scope.launch {
            container.db.notifications().insert(
                NotificationEntity(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    source = source.name,
                    title = title,
                    text = text,
                    postedAt = sbn.postTime,
                    fromPartner = fromPartner,
                ),
            )
        }
        if (settings.reactToNotifications) {
            val presence = container.director.presence.value
            if (presence.ready) {
                container.director.react(ReactionKind.NOTIFY)
                container.director.say(
                    GreetingComposer.notificationReaction(source.label, title, fromPartner, presence.greetingContext),
                    durationMs = 6000,
                )
            }
        }
    }

    /**
     * Who sent it and what it says. Chat apps use MessagingStyle, where the plain title can be empty
     * and the sender lives on the latest message, so read that first.
     */
    private fun senderAndText(n: Notification): Pair<String?, String?> {
        val extras = n.extras
        val style = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n) }.getOrNull()
        val last = style?.messages?.lastOrNull()
        val sender = listOf(
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE),
            style?.conversationTitle,
            last?.person?.name,
            extras.getCharSequence(Notification.EXTRA_TITLE),
            extras.getCharSequence(Notification.EXTRA_TITLE_BIG),
        ).firstNotNullOfOrNull { it?.toString()?.trim()?.takeIf { s -> s.isNotEmpty() } }
        val text = (last?.text ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()
        return sender to text
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotificationHub.remove(sbn.key)
        val container = (application as HerApplication).container
        container.scope.launch { container.db.notifications().markRead(sbn.key) }
    }
}
