package com.sora.mockgps.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.sora.mockgps.MainActivity
import com.sora.mockgps.R

internal object MockLocationNotification {
    const val CHANNEL_ID = "mock_location_active"
    const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(
        context: Context,
        latitude: Double,
        longitude: Double,
        routeAction: RouteNotificationAction? = null,
        sessionToken: ServiceSessionToken? = null,
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

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notification_active_title))
            .setContentText("%.6f, %.6f".format(java.util.Locale.US, latitude, longitude))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
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

private fun Intent.putSessionToken(sessionToken: ServiceSessionToken?) {
    sessionToken ?: return
    putExtra(MockLocationForegroundService.EXTRA_SESSION_ID, sessionToken.sessionId)
    putExtra(MockLocationForegroundService.EXTRA_SESSION_GENERATION, sessionToken.generation)
}
