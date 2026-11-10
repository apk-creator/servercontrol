package app.servercontrol.terminal

import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.sp
import org.connectbot.terminal.ModifierManager
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.VTermKey
import org.connectbot.terminal.TerminalEmulator as VtEmulator

// libvterm modifier bits
private const val MOD_ALT = 2
private const val MOD_CTRL = 4

/**
 * Android: ConnectBot's termlib (libvterm via JNI) renders the terminal and handles
 * keyboard/IME, selection with magnifier, copy/paste menu and pinch-zoom.
 */
internal class AndroidTerminalBackend(
    onInput: (ByteArray) -> Unit,
    onResize: (Int, Int) -> Unit,
) : TerminalBackend {
    private val onInputRef = onInput
    private val main = Handler(Looper.getMainLooper())

    @Volatile override var cols = 80
        private set

    @Volatile override var rows = 24
        private set

    /** pixel size of the character grid, for tap-to-move */
    @Volatile var gridWidthPx = 0
        private set

    @Volatile var gridHeightPx = 0
        private set

    /**
     * Lightweight shadow emulator fed with the same data. termlib keeps its screen state
     * internal, the shadow tells us where the cursor is (tap-to-move) and whether a
     * full-screen program (alternate screen) is running. It never answers the host.
     */
    val shadow = TerminalEmulator(80, 24)

    val emulator: VtEmulator = TerminalEmulatorFactory.create(
        initialRows = 24,
        initialCols = 80,
        defaultForeground = TermFg,
        defaultBackground = TermBg,
        // as soon as something is typed the input line jumps to the top (more room for the output)
        onKeyboardInput = { data -> liftInputLine(); onInput(data) },
        onResize = { d ->
            cols = d.columns
            rows = d.rows
            gridWidthPx = d.widthPixels
            gridHeightPx = d.heightPixels
            main.post { shadow.resize(d.columns, d.rows) }
            onResize(d.columns, d.rows)
        },
    )

    /** set by TerminalPane – termlib's selection (for the copy button) */
    @Volatile var selectionController: SelectionController? = null

    /** main thread: moves the cursor line to the top, locally in both emulators */
    private fun liftInputLine() {
        val seq = shadow.liftSequence() ?: return
        emulator.writeInput(seq, 0, seq.size)
        shadow.feed(seq)
    }

    override fun copyText(): String {
        val sc = selectionController
        if (sc != null && sc.isSelectionActive) {
            val t = sc.copySelection()
            sc.clearSelection()
            if (t.isNotEmpty()) return t
        }
        return shadow.screenText()
    }

    override fun clear() {
        val seq = "\u001b[3J".encodeToByteArray() // drop scrollback (libvterm -> clearScrollback)
        emulator.writeInput(seq, 0, seq.size)
        main.post { shadow.feed(seq) }
        onInputRef("\u000c".encodeToByteArray()) // Ctrl+L: shell clears and redraws the prompt
    }

    override fun write(data: ByteArray) {
        emulator.writeInput(data, 0, data.size)
        main.post { runCatching { shadow.feed(data) } }
    }

    override fun paste(text: String) = emulator.pasteText(text)

    private fun modBits(mods: TerminalInputState): Int {
        var m = 0
        if (mods.ctrlArmed) m = m or MOD_CTRL
        if (mods.altArmed) m = m or MOD_ALT
        mods.ctrlArmed = false
        mods.altArmed = false
        return m
    }

    override fun sendKey(key: TermKey, mods: TerminalInputState) {
        val k = when (key) {
            TermKey.ESC -> VTermKey.ESCAPE
            TermKey.TAB -> VTermKey.TAB
            TermKey.UP -> VTermKey.UP
            TermKey.DOWN -> VTermKey.DOWN
            TermKey.LEFT -> VTermKey.LEFT
            TermKey.RIGHT -> VTermKey.RIGHT
            TermKey.HOME -> VTermKey.HOME
            TermKey.END -> VTermKey.END
            TermKey.PAGE_UP -> VTermKey.PAGEUP
            TermKey.PAGE_DOWN -> VTermKey.PAGEDOWN
            TermKey.ENTER -> VTermKey.ENTER
            TermKey.BACKSPACE -> VTermKey.BACKSPACE
        }
        emulator.dispatchKey(modBits(mods), k)
    }

    override fun sendChar(c: Char, mods: TerminalInputState) = emulator.dispatchCharacter(modBits(mods), c.code)

    /** Moves the cursor within the input line to the tapped column (sends arrow keys). */
    fun tapAt(x: Float, y: Float) {
        if (gridWidthPx <= 0 || gridHeightPx <= 0 || cols <= 0 || rows <= 0 || shadow.useAlt) return
        val col = (x / (gridWidthPx.toFloat() / cols)).toInt()
        val row = (y / (gridHeightPx.toFloat() / rows)).toInt()
        if (row != shadow.cursorY || col !in 0 until cols) return
        val dx = col - shadow.cursorX
        val key = if (dx > 0) VTermKey.RIGHT else VTermKey.LEFT
        repeat(kotlin.math.abs(dx).coerceAtMost(cols)) { emulator.dispatchKey(0, key) }
    }

    override fun screenText(): String? = null

    override fun dispose() {
        runCatching { emulator.close() }
    }
}

actual fun createTerminalBackend(onInput: (ByteArray) -> Unit, onResize: (cols: Int, rows: Int) -> Unit): TerminalBackend =
    AndroidTerminalBackend(onInput, onResize)

actual val terminalHasOwnSelection: Boolean = true

@Composable
actual fun TerminalPane(session: TerminalSession, mods: TerminalInputState, fontSizeSp: Float, modifier: Modifier) {
    val b = session.backend as AndroidTerminalBackend
    // sticky Ctrl/Alt of our key bar also apply to the soft keyboard
    val modifierManager = remember(mods) {
        object : ModifierManager {
            override fun isCtrlActive() = mods.ctrlArmed
            override fun isAltActive() = mods.altArmed
            override fun isShiftActive() = false
            override fun clearTransients() {
                mods.ctrlArmed = false
                mods.altArmed = false
            }
        }
    }
    Box(
        modifier.pointerInput(b) {
            // observe quick taps (without consuming them) for tap-to-move-cursor
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                val start = down.uptimeMillis
                var tap = true
                while (true) {
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if ((c.position - down.position).getDistance() > slop) tap = false
                    if (!c.pressed) {
                        if (tap && c.uptimeMillis - start < viewConfiguration.longPressTimeoutMillis && session.connected) {
                            b.tapAt(down.position.x, down.position.y)
                        }
                        break
                    }
                }
            }
        },
    ) {
        Terminal(
            terminalEmulator = b.emulator,
            modifier = Modifier.fillMaxSize(),
            initialFontSize = fontSizeSp.sp,
            backgroundColor = TermBg,
            foregroundColor = TermFg,
            keyboardEnabled = true,
            showSoftKeyboard = true,
            modifierManager = modifierManager,
            onSelectionControllerAvailable = { b.selectionController = it },
        )
    }
}
