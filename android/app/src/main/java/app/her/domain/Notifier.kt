package app.her.domain

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.her.MainActivity
import app.her.R

object Notifier {
    const val CHANNEL_COMPANION = "companion"
    const val CHANNEL_OVERLAY = "overlay"
    const val EXTRA_ROUTE = "route"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_COMPANION, "Companion messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Greetings, reminders and messages from your AI companion"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_OVERLAY, "Floating companion", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while the floating companion is on screen"
                setShowBadge(false)
            },
        )
    }

    fun openAppIntent(context: Context, route: String? = null, requestCode: Int = 0): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            route?.let { putExtra(EXTRA_ROUTE, it) }
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Companion-initiated message. The title always says it's the AI companion. */
    fun companionMessage(context: Context, id: Int, companionName: String, text: String, route: String = "chat") {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val notification = NotificationCompat.Builder(context, CHANNEL_COMPANION)
            .setSmallIcon(R.drawable.ic_stat_companion)
            .setContentTitle("$companionName · AI companion")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, route, id))
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
