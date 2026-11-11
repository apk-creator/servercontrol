package app.servercontrol

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState

/**
 * App-wide "back": Android back gesture/key, Escape on the desktop.
 * The most recently enabled handler wins (e.g. an open detail view before the section history).
 */
object BackStack {
    private val handlers = mutableListOf<() -> Unit>()

    internal fun add(h: () -> Unit) { handlers.add(h) }
    internal fun remove(h: () -> Unit) { handlers.remove(h) }

    /** true if a handler took the back event */
    fun handle(): Boolean {
        val h = handlers.lastOrNull() ?: return false
        h()
        return true
    }
}

@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val current = rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        val h: () -> Unit = { current.value() }
        if (enabled) BackStack.add(h)
        onDispose { BackStack.remove(h) }
    }
}
