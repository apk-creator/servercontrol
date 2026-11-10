package app.servercontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Keeps the process alive while an app update downloads in the background
 * and shows the download progress as a notification.
 */
class UpdateService : Service() {
    companion object {
        var instance: UpdateService? = null
        var pendingStop = false
        const val CHANNEL = "update"
        const val PROGRESS_ID = 43
        const val READY_ID = 44
        private var lastNotify = 0L

        /** true while an activity of the app is visible (installer can be opened directly) */
        @Volatile var appVisible = false

        fun start() {
            pendingStop = false
            val ctx = AppContext.app
            runCatching { ctx.startForegroundService(Intent(ctx, UpdateService::class.java)) }
                .onFailure { CrashLog.record("Update-Dienst konnte nicht starten", it) }
        }

        fun stop() {
            val svc = instance
            if (svc != null) svc.shutdown() else pendingStop = true
        }

        fun progress(done: Long, total: Long) {
            val now = System.currentTimeMillis()
            if (now - lastNotify < 1000 && done != total) return
            lastNotify = now
            val ctx = AppContext.app
            ctx.getSystemService(NotificationManager::class.java).notify(PROGRESS_ID, progressNotification(ctx, done, total))
        }

        /** Notification "Update bereit – tippen zum Installieren" (when the app is not visible). */
        fun ready(install: Intent) {
            val ctx = AppContext.app
            ensureChannel(ctx)
            val pi = PendingIntent.getActivity(ctx, 3, install, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setColor(Color.BLACK)
                .setContentTitle("Update bereit")
                .setContentText("Tippen zum Installieren")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(READY_ID, n)
        }

        fun failed(message: String) {
            val ctx = AppContext.app
            ensureChannel(ctx)
            val n = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setColor(Color.BLACK)
                .setContentTitle("Update fehlgeschlagen")
                .setContentText(message)
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build()
            ctx.getSystemService(NotificationManager::class.java).notify(READY_ID, n)
        }

        private fun openApp(ctx: Context) = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "App-Update", NotificationManager.IMPORTANCE_LOW).apply {
                        description = "Fortschritt und Abschluss von App-Updates"
                        setShowBadge(false)
                    }
                )
            }
        }

        private fun progressNotification(ctx: Context, done: Long, total: Long): Notification {
            ensureChannel(ctx)
            val b = Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setColor(Color.BLACK)
                .setContentTitle("App-Update wird geladen")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(openApp(ctx))
            if (total > 0) {
                val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                b.setProgress(100, pct, false).setContentText("$pct %  ·  ${done / 1_048_576} / ${total / 1_048_576} MB")
            } else {
                b.setProgress(0, 0, true).setContentText("${done / 1_048_576} MB")
            }
            return b.build()
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        val n = progressNotification(this, 0, -1)
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(PROGRESS_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(PROGRESS_ID, n)
            }
        }
        if (pendingStop) {
            pendingStop = false
            shutdown()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ServerControl:update").apply {
                    setReferenceCounted(false)
                    acquire(30 * 60_000L)
                }
        }
        return START_NOT_STICKY
    }

    fun shutdown() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }
}
