package app.servercontrol

import android.app.Application
import android.content.Intent
import android.os.Bundle
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
        runCatching { UpdateCheckWorker.schedule(this) }.onFailure { CrashLog.record("Update-Prüfung nicht geplant", it) }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        // back: previous view/section; at the start the app goes to the background (SSH sessions stay open)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!BackStack.handle()) moveTaskToBack(true)
            }
        })
        setContent { App(app.state) }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    /** "Updates verfügbar" notification opens the Updates section */
    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(UpdateCheckWorker.EXTRA_OPEN_UPDATES, false) == true) {
            (application as ServerControlApp).state.openUpdatesWhenReady = true
            intent.removeExtra(UpdateCheckWorker.EXTRA_OPEN_UPDATES)
        }
    }

    override fun onResume() {
        super.onResume()
        UpdateService.appVisible = true
    }

    override fun onPause() {
        UpdateService.appVisible = false
        super.onPause()
    }
}
