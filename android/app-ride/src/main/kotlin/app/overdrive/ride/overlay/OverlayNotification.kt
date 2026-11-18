package app.overdrive.ride.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import app.overdrive.ride.R

/**
 * The foreground service's notification: one low-importance channel (no sound, no peek), one line of text, and a tap that
 * opens the app. The tap is also the fallback for the long press: a notification's own `PendingIntent` is a documented
 * exemption from the background-activity-start limits (ADR 0006).
 */
internal object OverlayNotification {
    const val CHANNEL_ID = "ride"
    const val ID = 1

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(CHANNEL_ID, "Ride", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a ride is on, so the overlay can stay over other apps."
                setShowBadge(false)
            }
        manager.createNotificationChannel(channel)
    }

    /** "Ride in progress" while riding or paused; "Ride finished" while the summary waits to be seen. */
    fun build(
        context: Context,
        finished: Boolean,
    ): Notification {
        val open =
            PendingIntent.getActivity(
                context,
                0,
                openAppIntent(context),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        return Notification
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_ride_notification)
            .setContentTitle(if (finished) "Ride finished" else "Ride in progress")
            .setContentText(if (finished) "Tap to see the summary" else "Tap to open Overdrive")
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun update(
        context: Context,
        finished: Boolean,
    ) {
        context.getSystemService(NotificationManager::class.java)?.notify(ID, build(context, finished))
    }
}
