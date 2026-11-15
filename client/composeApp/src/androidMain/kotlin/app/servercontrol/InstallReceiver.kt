package app.servercontrol

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import java.io.File

/**
 * In-app update through a PackageInstaller session: the APK is copied into the session
 * (no file URI the installer could read half-written), and the system asks for confirmation.
 */
object ApkInstaller {
    fun install(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out, 256 * 1024) }
                s.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val sender = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallReceiver::class.java), flags).intentSender
            s.commit(sender)
        }
    }
}

class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)) ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Android does not allow opening the installer from the background → notification
                if (!UpdateService.appVisible || runCatching { ctx.startActivity(confirm) }.isFailure) UpdateService.ready(confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> UpdateService.failed(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Installation fehlgeschlagen ($status)")
        }
    }
}
