package com.sora.mockgps.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.sora.mockgps.MainActivity
import com.sora.mockgps.R
import java.util.Locale

internal object MockLocationNotification {
    const val CHANNEL_ID = "mock_location_active"
    const val ROUTE_CHANNEL_ID = "route_progress"
    const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
        // A channel's importance is fixed after the first install. Route progress needs its own
        // default-importance channel so the lock screen shows a card instead of a silent row.
        manager.createNotificationChannel(
            NotificationChannel(
                ROUTE_CHANNEL_ID,
                context.getString(R.string.route_notification_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = context.getString(R.string.route_notification_channel_description)
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun build(
        context: Context,
        latitude: Double,
        longitude: Double,
        routeAction: RouteNotificationAction? = null,
        sessionToken: ServiceSessionToken? = null,
        routeStatus: RouteNotificationStatus? = null,
    ): Notification {
        val openAppIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            context,
            2,
            Intent(context, MockLocationForegroundService::class.java).apply {
                action = MockLocationForegroundService.ACTION_STOP
                putSessionToken(sessionToken)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val routeActionIntent = routeAction?.let { action ->
            PendingIntent.getService(
                context,
                3,
                Intent(context, MockLocationForegroundService::class.java).apply {
                    this.action = when (action) {
                        RouteNotificationAction.Pause -> MockLocationForegroundService.ACTION_PAUSE_ROUTE
                        RouteNotificationAction.Resume -> MockLocationForegroundService.ACTION_RESUME_ROUTE
                    }
                    putSessionToken(sessionToken)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val title: String
        val text: String
        if (routeStatus != null) {
            val clock = formatTripClock(routeStatus.remainingSeconds)
            title = context.getString(
                if (routeStatus.paused) {
                    R.string.route_notification_paused_title
                } else {
                    R.string.route_notification_time_title
                },
                clock,
            )
            text = context.getString(
                R.string.route_notification_summary,
                routeStatus.title,
                routeStatus.percent,
            )
        } else {
            title = context.getString(R.string.notification_active_title)
            text = "%.6f, %.6f".format(Locale.US, latitude, longitude)
        }
        val channelId = if (routeStatus != null) ROUTE_CHANNEL_ID else CHANNEL_ID
        return NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setColor(context.getColor(R.color.bloomwalk_coral))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                if (routeStatus != null) {
                    val collapsed = routeStatusViews(context, R.layout.notification_route_status, routeStatus)
                    val expanded = routeStatusViews(
                        context,
                        R.layout.notification_route_status_expanded,
                        routeStatus,
                    )
                    setCustomContentView(collapsed)
                    setCustomBigContentView(expanded)
                    setStyle(NotificationCompat.DecoratedCustomViewStyle())
                }
            }
            .apply {
                if (routeAction != null && routeActionIntent != null) {
                    addAction(
                        0,
                        context.getString(
                            when (routeAction) {
                                RouteNotificationAction.Pause -> R.string.action_pause_route
                                RouteNotificationAction.Resume -> R.string.action_resume_route
                            },
                        ),
                        routeActionIntent,
                    )
                }
            }
            .addAction(0, context.getString(R.string.action_stop), stopIntent)
            .build()
    }
}

internal enum class RouteNotificationAction { Pause, Resume }

internal data class RouteNotificationStatus(
    val title: String,
    val percent: Int,
    val remainingSeconds: Double,
    val paused: Boolean,
)

private fun routeStatusViews(
    context: Context,
    layoutId: Int,
    status: RouteNotificationStatus,
): RemoteViews {
    val clock = formatTripClock(status.remainingSeconds)
    val caption = context.getString(
        if (status.paused) {
            R.string.route_notification_paused_caption
        } else {
            R.string.route_notification_running_caption
        },
        status.title,
    )
    return RemoteViews(context.packageName, layoutId).apply {
        setTextViewText(R.id.route_notification_time, clock)
        setTextViewText(R.id.route_notification_percent, context.getString(R.string.route_progress_percent, status.percent))
        setTextViewText(R.id.route_notification_caption, caption)
        setProgressBar(R.id.route_notification_progress, 100, status.percent, false)
        if (status.paused) {
            setViewVisibility(R.id.route_notification_clock, View.GONE)
            setViewVisibility(R.id.route_notification_time, View.VISIBLE)
        } else {
            setViewVisibility(R.id.route_notification_clock, View.VISIBLE)
            setViewVisibility(R.id.route_notification_time, View.GONE)
            val remainingMillis = (status.remainingSeconds * 1_000.0).toLong().coerceAtLeast(0L)
            setChronometer(
                R.id.route_notification_clock,
                SystemClock.elapsedRealtime() + remainingMillis,
                null,
                true,
            )
        }
    }
}

private fun formatTripClock(remainingSeconds: Double): String {
    val totalSeconds = remainingSeconds.toInt().coerceAtLeast(0)
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours == 0) {
        "%d:%02d".format(Locale.US, minutes, seconds)
    } else {
        "%d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
    }
}

private fun Intent.putSessionToken(sessionToken: ServiceSessionToken?) {
    sessionToken ?: return
    putExtra(MockLocationForegroundService.EXTRA_SESSION_ID, sessionToken.sessionId)
    putExtra(MockLocationForegroundService.EXTRA_SESSION_GENERATION, sessionToken.generation)
}
