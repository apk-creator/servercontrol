package app.servercontrol.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Special keys of the on-screen key bar. */
enum class TermKey { ESC, TAB, UP, DOWN, LEFT, RIGHT, HOME, END, PAGE_UP, PAGE_DOWN, ENTER, BACKSPACE }

/**
 * Terminal emulation + rendering behind a [TerminalSession].
 * Android: ConnectBot's termlib (libvterm). Desktop: the built-in [TerminalEmulator].
 */
interface TerminalBackend {
    val cols: Int
    val rows: Int

    /** Output from the server. Called from an IO thread, in order. */
    fun write(data: ByteArray)

    /** Pasted text (bracketed paste is handled by the emulator). */
    fun paste(text: String)

    /** Special key from the key bar, honours sticky Ctrl/Alt and application cursor mode. */
    fun sendKey(key: TermKey, mods: TerminalInputState)

    /** Character from the key bar, honours sticky Ctrl/Alt. */
    fun sendChar(c: Char, mods: TerminalInputState)

    /** Visible screen as text (desktop "copy screen"); null if the platform offers its own selection. */
    fun screenText(): String?

    /** Free native resources; the backend is unusable afterwards. */
    fun dispose()
}

/**
 * [onInput] receives bytes to send to the server (keyboard, terminal answers);
 * [onResize] is called when the visible grid changes.
 */
expect fun createTerminalBackend(onInput: (ByteArray) -> Unit, onResize: (cols: Int, rows: Int) -> Unit): TerminalBackend

/** Renders the terminal of [session] and handles touch, keyboard and selection. */
@Composable
expect fun TerminalPane(session: TerminalSession, mods: TerminalInputState, fontSizeSp: Float, modifier: Modifier)

/** Whether the platform terminal already offers copy/paste/zoom itself (Android: termlib). */
expect val terminalHasOwnSelection: Boolean
