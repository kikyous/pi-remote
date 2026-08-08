package com.piremote.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.piremote.MainActivity
import com.piremote.R

/**
 * Keeps the WebSocket alive while an agent is running.
 *
 * Without a foreground service, Android is free to freeze the process — and
 * with it the socket — once the app leaves the screen. A run that takes minutes
 * would then silently stop delivering events, and the completion notification
 * would never fire. The service exists only for the duration of a run.
 */
class AgentForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val count = intent?.getIntExtra(EXTRA_COUNT, 1) ?: 1
        startForegroundCompat(buildNotification(count))
        return START_STICKY
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ONGOING_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ONGOING_ID, notification)
        }
    }

    private fun buildNotification(count: Int) =
        NotificationCompat.Builder(this, CHANNEL_RUNNING)
            .setContentTitle(if (count > 1) "$count 个会话正在运行" else "会话正在运行")
            .setContentText("保持与 PC 的连接")
            .setSmallIcon(R.drawable.ic_stat_pi)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openAppIntent(this, null))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    companion object {
        private const val ONGOING_ID = 1001
        private const val EXTRA_COUNT = "count"

        const val CHANNEL_RUNNING = "agent_running"
        const val CHANNEL_DONE = "agent_done"

        fun start(context: Context, runningCount: Int) {
            val intent = Intent(context, AgentForegroundService::class.java).putExtra(EXTRA_COUNT, runningCount)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentForegroundService::class.java))
        }

        /** Both channels are created once, before anything tries to post. */
        fun ensureChannels(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return

            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_RUNNING, "运行中", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "会话运行期间保持连接"
                    setShowBadge(false)
                },
            )
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_DONE, "完成提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "会话跑完时通知"
                },
            )
        }

        /**
         * Tell the user a run finished. Notification id is derived from the
         * session so a second completion replaces the first rather than piling up.
         */
        fun notifyFinished(context: Context, sessionId: String, title: String, preview: String) {
            val notification = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setContentTitle(title)
                .setContentText(preview.take(120))
                .setStyle(NotificationCompat.BigTextStyle().bigText(preview.take(400)))
                .setSmallIcon(R.drawable.ic_stat_pi)
                .setAutoCancel(true)
                .setContentIntent(openAppIntent(context, sessionId))
                .build()

            runCatching {
                NotificationManagerCompat.from(context).notify(sessionId.hashCode(), notification)
            }
            // Throws without POST_NOTIFICATIONS on API 33+; the run itself is
            // unaffected, so a missing permission must not crash anything.
        }

        private fun openAppIntent(context: Context, sessionId: String?): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                sessionId?.let { putExtra(MainActivity.EXTRA_OPEN_SESSION, it) }
            }
            return PendingIntent.getActivity(
                context,
                sessionId?.hashCode() ?: 0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
