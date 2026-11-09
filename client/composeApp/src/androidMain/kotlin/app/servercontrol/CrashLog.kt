package app.servercontrol

import android.content.Context
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Writes crashes to a file so the app can show them on the next start. */
object CrashLog {
    private fun file(): File = File(AppContext.app.filesDir, "crash.txt")

    fun install(ctx: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            record("Absturz im Thread ${t.name}", e)
            previous?.uncaughtException(t, e)
        }
    }

    fun record(what: String, e: Throwable) {
        runCatching {
            val sw = StringWriter()
            e.printStackTrace(PrintWriter(sw))
            val version = runCatching {
                AppContext.app.packageManager.getPackageInfo(AppContext.app.packageName, 0).versionName
            }.getOrNull()
            val text = buildString {
                append("ServerControl ").append(version).append(" · Android ").append(android.os.Build.VERSION.RELEASE)
                append(" · ").append(android.os.Build.MODEL).append('\n')
                append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.GERMANY).format(Date())).append(" – ").append(what).append("\n\n")
                append(sw.toString().take(30_000))
            }
            file().writeText(text)
        }
    }

    fun read(): String? = runCatching { file().takeIf { it.exists() }?.readText() }.getOrNull()
    fun clear() { runCatching { file().delete() } }
}

actual fun readCrashReport(): String? = CrashLog.read()
actual fun clearCrashReport() = CrashLog.clear()
