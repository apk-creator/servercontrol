package app.servercontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Window icon from resources/icon.png */
private val appIcon: androidx.compose.ui.graphics.painter.Painter? by lazy {
    runCatching {
        val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("icon.png")!!.use { it.readBytes() }
        androidx.compose.ui.graphics.painter.BitmapPainter(
            org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
        )
    }.getOrNull()
}

fun main() = application {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    var state by remember { mutableStateOf<AppState?>(null) }
    val windowState = rememberWindowState(size = DpSize(1180.dp, 800.dp))

    val s = state
    val title = when (val scr = s?.screen) {
        null -> "ServerControl – Entsperren"
        is Screen.Login -> "ServerControl – Login ${scr.profile.name}"
        else -> "ServerControl"
    }

    Window(
        onCloseRequest = {
            runBlocking { withTimeoutOrNull(10_000) { state?.shutdown() } }
            exitApplication()
        },
        title = title,
        state = windowState,
        icon = appIcon,
        // Escape = back (previous view / section)
        onPreviewKeyEvent = { e ->
            e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown &&
                e.key == androidx.compose.ui.input.key.Key.Escape &&
                // the terminal needs Escape itself (vim, less …)
                !(state?.screen == Screen.Main && state?.section == Section.TERMINAL) && BackStack.handle()
        },
    ) {
        if (s == null) {
            UnlockScreen { pass ->
                val store = DesktopSecureStore(pass)
                // throws WrongPassphraseException; on first run create the (empty) vault right away
                if (store.load() == null) store.save("{\"profiles\":[]}")
                state = AppState(store, scope)
            }
        } else {
            App(s)
        }
    }
}

@Composable
fun UnlockScreen(onUnlock: suspend (CharArray) -> Unit) {
    val firstRun = remember { !DesktopSecureStore.exists() }
    var pw by remember { mutableStateOf("") }
    var pw2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    val submit = {
        if (!busy) {
            when {
                firstRun && pw.length < 12 -> err = "Mindestens 12 Zeichen."
                firstRun && pw != pw2 -> err = "Passwörter stimmen nicht überein."
                else -> {
                    busy = true
                    scope.launch {
                        try {
                            onUnlock(pw.toCharArray())
                        } catch (e: WrongPassphraseException) {
                            err = e.message
                        } catch (e: Throwable) {
                            err = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                }
            }
        }
        Unit
    }
    val enter = Modifier.onPreviewKeyEvent { e ->
        if (e.type == KeyEventType.KeyDown && (e.key == Key.Enter || e.key == Key.NumPadEnter)) { submit(); true } else false
    }
    AppTheme {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(Modifier.widthIn(max = 420.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("ServerControl", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        if (firstRun) "Lege ein Master-Passwort fest. Damit werden die Server-Adressen auf diesem Rechner verschlüsselt."
                        else "Master-Passwort eingeben.",
                    )
                    PasswordInput(pw, { pw = it }, "Master-Passwort", Modifier.fillMaxWidth().focusRequester(focus).then(enter))
                    if (firstRun) PasswordInput(pw2, { pw2 = it }, "Wiederholen", Modifier.fillMaxWidth().then(enter))
                    Button(onClick = submit, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (firstRun) "Festlegen" else "Entsperren") }
                    err?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
}
