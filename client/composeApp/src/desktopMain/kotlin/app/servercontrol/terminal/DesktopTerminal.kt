package app.servercontrol.terminal

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import javax.swing.SwingUtilities

/** Desktop: the built-in emulator, fed on the Swing/Compose UI thread. */
private class DesktopTerminalBackend(
    private val onInput: (ByteArray) -> Unit,
    private val onResize: (Int, Int) -> Unit,
) : TerminalBackend {
    val emu = TerminalEmulator(80, 24).also { e -> e.onResponse = { onInput(it.encodeToByteArray()) } }

    override val cols get() = emu.cols
    override val rows get() = emu.rows

    override fun write(data: ByteArray) {
        if (SwingUtilities.isEventDispatchThread()) emu.feed(data) else SwingUtilities.invokeLater { emu.feed(data) }
    }

    fun send(text: String) {
        // as soon as something is typed the input line jumps to the top
        emu.liftSequence()?.let { emu.feed(it) }
        onInput(text.encodeToByteArray())
    }

    override fun copyText(): String = emu.screenText()

    override fun clear() {
        emu.feed("\u001b[3J".encodeToByteArray())
        onInput("\u000c".encodeToByteArray())
    }

    override fun paste(text: String) {
        val t = text.replace('\n', '\r')
        send(if (emu.bracketedPaste) "\u001b[200~$t\u001b[201~" else t)
    }

    override fun sendKey(key: TermKey, mods: TerminalInputState) {
        val k = when (key) {
            TermKey.ESC -> Key.Escape
            TermKey.TAB -> Key.Tab
            TermKey.UP -> Key.DirectionUp
            TermKey.DOWN -> Key.DirectionDown
            TermKey.LEFT -> Key.DirectionLeft
            TermKey.RIGHT -> Key.DirectionRight
            TermKey.HOME -> Key.MoveHome
            TermKey.END -> Key.MoveEnd
            TermKey.PAGE_UP -> Key.PageUp
            TermKey.PAGE_DOWN -> Key.PageDown
            TermKey.ENTER -> Key.Enter
            TermKey.BACKSPACE -> Key.Backspace
        }
        val seq = keySequence(k, emu.appCursorKeys) ?: return
        val s = if (mods.altArmed) "\u001b$seq" else seq
        mods.altArmed = false
        mods.ctrlArmed = false
        send(s)
    }

    override fun sendChar(c: Char, mods: TerminalInputState) = send(applyModifiers(c.toString(), mods))

    override fun screenText(): String = emu.screenText()

    fun resize(c: Int, r: Int) {
        if (c == emu.cols && r == emu.rows) return
        emu.resize(c, r)
        onResize(c, r)
    }

    override fun dispose() {}
}

actual fun createTerminalBackend(onInput: (ByteArray) -> Unit, onResize: (cols: Int, rows: Int) -> Unit): TerminalBackend =
    DesktopTerminalBackend(onInput, onResize)

actual val terminalHasOwnSelection: Boolean = false

@Composable
actual fun TerminalPane(session: TerminalSession, mods: TerminalInputState, fontSizeSp: Float, modifier: Modifier) {
    val b = session.backend as DesktopTerminalBackend
    TerminalView(
        emu = b.emu,
        connected = session.connected,
        send = { b.send(it) },
        paste = { session.paste(it) },
        onResize = { c, r -> b.resize(c, r) },
        fontSizeSp = fontSizeSp,
        mods = mods,
        modifier = modifier,
    )
}
