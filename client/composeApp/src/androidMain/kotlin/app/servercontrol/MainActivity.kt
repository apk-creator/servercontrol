package app.servercontrol

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.MainScope

class ServerControlApp : Application() {
    lateinit var state: AppState
        private set
    lateinit var vpn: AndroidVpn
        private set

    override fun onCreate() {
        super.onCreate()
        vpn = AndroidVpn(this)
        // Survives activity recreation; the session lives as long as the process.
        state = AppState(AndroidSecureStore(this), vpn, MainScope())
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = state.onForeground()
            // VPN is switched off 60 s after the app leaves the screen (only if the app switched it on)
            override fun onStop(owner: LifecycleOwner) = state.onBackground(60_000)
        })
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // no screenshots / no preview in the recent-apps list
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val app = application as ServerControlApp
        FilePicker.context = applicationContext
        FilePicker.launcher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
            FilePicker.pending?.complete(uri)
            FilePicker.pending = null
        }
        if (!app.vpn.hasPermission()) {
            runCatching { requestPermissions(arrayOf(AndroidVpn.PERMISSION), 1) }
        }
        setContent { App(app.state) }
    }
}
