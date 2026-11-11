package app.servercontrol

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Daily update check in the background: signs in with the remembered device of the last
 * server (only works at home or with the VPN connected) and notifies about new updates.
 * The same set of updates is reported only once.
 */
class UpdateCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    companion object {
        const val EXTRA_OPEN_UPDATES = "openUpdates"
        private const val CHANNEL = "updatecheck"
        private const val NOTIFICATION_ID = 45
        private const val PREFS = "updatecheck"

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS, 2, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("daily-update-check", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val report = backgroundUpdateCheck(AndroidSecureStore(ctx), System.currentTimeMillis()) ?: return Result.success()
        val summary = report.summary
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val sig = summary.joinToString("|")
        if (summary.isEmpty() || prefs.getString("notified", "") == sig) {
            if (summary.isEmpty()) prefs.edit().remove("notified").apply()
            return Result.success()
        }
        prefs.edit().putString("notified", sig).apply()
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Update-Prüfung", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Tägliche Prüfung auf Updates für App, Agent, Nextcloud, Immich und System"
            })
        }
        val open = PendingIntent.getActivity(
            ctx, 4,
            Intent(ctx, MainActivity::class.java).putExtra(EXTRA_OPEN_UPDATES, true)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setColor(Color.BLACK)
            .setContentTitle("Updates verfügbar")
            .setContentText(summary.joinToString(" · "))
            .setStyle(Notification.BigTextStyle().bigText(summary.joinToString("\n")))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID, n) }
        return Result.success()
    }
}
