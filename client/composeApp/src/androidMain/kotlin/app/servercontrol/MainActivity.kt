package app.servercontrol

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.plus

class ServerControlApp : Application() {
    lateinit var state: AppState
        private set

    override fun onCreate() {
        super.onCreate()
        AppContext.app = applicationContext
        CrashLog.install(this)
        // Survives activity recreation; the session lives as long as the process.
        // Errors in background coroutines are recorded instead of killing the app.
        val handler = kotlinx.coroutines.CoroutineExceptionHandler { _, e -> CrashLog.record("Fehler in Hintergrundaufgabe", e) }
        state = AppState(AndroidSecureStore(this), MainScope() + handler)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // no screenshots / no preview in the recent-apps list
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val app = application as ServerControlApp
        // the "Verbunden" notification of the SSH terminal needs this on Android 13+
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        FilePicker.context = applicationContext
        FilePicker.launcher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            FilePicker.pending?.complete(uri)
            FilePicker.pending = null
        }
        setContent { App(app.state) }
    }
}
