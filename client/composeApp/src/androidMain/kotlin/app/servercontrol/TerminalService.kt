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
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/** Application context for platform code that runs outside an activity. */
object AppContext {
    lateinit var app: Context
}

/**
 * Foreground service that keeps the process (and with it the SSH session) alive
 * while the app is in the background. Shows a persistent "Verbunden" notification.
 */
class TerminalService : Service() {
    companion object {
        /** running instance (main thread only) */
        var instance: TerminalService? = null
        /** stop requested before the service reached startForeground() */
        var pendingStop = false
        const val CHANNEL = "terminal"
        const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "app.servercontrol.STOP_TERMINAL"
        const val EXTRA_TITLE = "title"
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        // Always enter the foreground state first: a service started with startForegroundService()
        // that stops without calling startForeground() crashes the app (ConnectBot does the same).
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "SSH"
        val n = buildNotification(title)
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        }
        if (intent?.action == ACTION_STOP) {
            // "Trennen" in the notification
            (application as ServerControlApp).state.terminal?.close()
            shutdown()
            return START_NOT_STICKY
        }
        if (pendingStop) {
            pendingStop = false
            shutdown()
            return START_NOT_STICKY
        }
        if (wakeLock == null) {
            wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ServerControl:ssh").apply { setReferenceCounted(false); acquire() }
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

    private fun buildNotification(title: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "SSH-Verbindung", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Zeigt an, dass ein SSH-Terminal im Hintergrund verbunden ist"
                    setShowBadge(false)
                }
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, TerminalService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_terminal)
            .setColor(Color.BLACK)
            .setContentTitle("Verbunden")
            .setContentText(title)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(Icon.createWithResource(this, R.drawable.ic_terminal), "Trennen", stop).build())
            .build()
    }
}

actual fun terminalKeepAlive(active: Boolean, title: String) {
    val ctx = AppContext.app
    if (active) {
        TerminalService.pendingStop = false
        val i = Intent(ctx, TerminalService::class.java).putExtra(TerminalService.EXTRA_TITLE, title)
        runCatching { ctx.startForegroundService(i) }.onFailure { CrashLog.record("Hintergrunddienst konnte nicht starten", it) }
    } else {
        val svc = TerminalService.instance
        if (svc != null) svc.shutdown() else TerminalService.pendingStop = true
    }
}
