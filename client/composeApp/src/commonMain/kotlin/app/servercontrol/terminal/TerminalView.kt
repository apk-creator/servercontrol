package app.servercontrol.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.servercontrol.terminal.TerminalEmulator.Companion.BOLD
import app.servercontrol.terminal.TerminalEmulator.Companion.DEFAULT
import app.servercontrol.terminal.TerminalEmulator.Companion.DIM
import app.servercontrol.terminal.TerminalEmulator.Companion.INVERSE
import app.servercontrol.terminal.TerminalEmulator.Companion.ITALIC
import app.servercontrol.terminal.TerminalEmulator.Companion.TRUECOLOR
import app.servercontrol.terminal.TerminalEmulator.Companion.UNDERLINE

val TermBg = Color(0xFF141414)
val TermFg = Color(0xFFD4D4D4)

private val base16 = longArrayOf(
    0xFF000000, 0xFFCD3131, 0xFF0DBC79, 0xFFE5E510, 0xFF2472C8, 0xFFBC3FBC, 0xFF11A8CD, 0xFFE5E5E5,
    0xFF666666, 0xFFF14C4C, 0xFF23D18B, 0xFFF5F543, 0xFF3B8EEA, 0xFFD670D6, 0xFF29B8DB, 0xFFFFFFFF,
).map { Color(it) }

fun termColor(c: Int, fg: Boolean): Color = when {
    c == DEFAULT -> if (fg) TermFg else TermBg
    c and TRUECOLOR != 0 -> Color(0xFF000000 or (c and 0xFFFFFF).toLong())
    c < 16 -> base16[c]
    c < 232 -> {
        val i = c - 16
        fun lv(v: Int) = if (v == 0) 0 else 55 + v * 40
        Color(lv(i / 36), lv((i % 36) / 6), lv(i % 6))
    }
    c < 256 -> { val g = 8 + (c - 232) * 10; Color(g, g, g) }
    else -> if (fg) TermFg else TermBg
}

private const val SENTINEL = "  "
private val letterKeys = listOf(
    Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
    Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
)

/** Escape sequence for special keys, honouring application cursor mode. */
fun keySequence(key: Key, appCursor: Boolean): String? {
    val ss3 = if (appCursor) "\u001bO" else "\u001b["
    return when (key) {
        Key.Enter, Key.NumPadEnter -> "\r"
        Key.Backspace -> "\u007f"
        Key.Tab -> "\t"
        Key.Escape -> "\u001b"
        Key.DirectionUp -> "${ss3}A"
        Key.DirectionDown -> "${ss3}B"
        Key.DirectionRight -> "${ss3}C"
        Key.DirectionLeft -> "${ss3}D"
        Key.MoveHome -> "${ss3}H"
        Key.MoveEnd -> "${ss3}F"
        Key.PageUp -> "\u001b[5~"
        Key.PageDown -> "\u001b[6~"
        Key.Delete -> "\u001b[3~"
        Key.Insert -> "\u001b[2~"
        Key.F1 -> "\u001bOP"
        Key.F2 -> "\u001bOQ"
        Key.F3 -> "\u001bOR"
        Key.F4 -> "\u001bOS"
        Key.F5 -> "\u001b[15~"
        Key.F6 -> "\u001b[17~"
        Key.F7 -> "\u001b[18~"
        Key.F8 -> "\u001b[19~"
        Key.F9 -> "\u001b[20~"
        Key.F10 -> "\u001b[21~"
        Key.F11 -> "\u001b[23~"
        Key.F12 -> "\u001b[24~"
        else -> null
    }
}

class TerminalInputState {
    var ctrlArmed by mutableStateOf(false)
    var altArmed by mutableStateOf(false)
}

/** Applies sticky Ctrl/Alt from the key bar to typed text. */
private fun applyModifiers(text: String, mods: TerminalInputState): String {
    if (text.length != 1) return text
    var t = text
    if (mods.ctrlArmed) {
        val c = t[0]
        t = when {
            c in 'a'..'z' -> (c - 'a' + 1).toChar().toString()
            c in 'A'..'Z' -> (c - 'A' + 1).toChar().toString()
            c == ' ' || c == '@' -> "\u0000"
            c == '[' -> "\u001b"
            c == '\\' -> "\u001c"
            c == ']' -> "\u001d"
            else -> t
        }
        mods.ctrlArmed = false
    }
    if (mods.altArmed) { t = "\u001b" + t; mods.altArmed = false }
    return t
}

@Composable
fun TerminalView(
    session: TerminalSession,
    fontSizeSp: Float,
    mods: TerminalInputState,
    modifier: Modifier = Modifier,
) {
    val emu = session.emu
    val measurer = rememberTextMeasurer(cacheSize = 1024)
    val style = remember(fontSizeSp) { TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSizeSp.sp, color = TermFg) }
    val cell = remember(fontSizeSp) { measurer.measure("W", style).size }
    var scrollLines by remember { mutableFloatStateOf(0f) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var input by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }

    fun sendUser(text: String) {
        if (text.isEmpty()) return
        scrollLines = 0f
        session.send(applyModifiers(text, mods))
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        keySequence(e.key, emu.appCursorKeys)?.let { seq ->
            val s = if (e.isAltPressed || mods.altArmed) { mods.altArmed = false; "\u001b$seq" } else seq
            scrollLines = 0f
            session.send(s)
            return true
        }
        if (e.isCtrlPressed) {
            val i = letterKeys.indexOf(e.key)
            if (i >= 0) { scrollLines = 0f; session.send((i + 1).toChar().toString()); return true }
        }
        if (e.isAltPressed && e.utf16CodePoint > 0) {
            scrollLines = 0f
            session.send("\u001b" + e.utf16CodePoint.toChar())
            return true
        }
        return false
    }

    BoxWithConstraints(modifier.background(TermBg)) {
        val cw = cell.width.coerceAtLeast(1)
        val ch = cell.height.coerceAtLeast(1)
        val cols = (constraints.maxWidth / cw).coerceAtLeast(20)
        val rows = (constraints.maxHeight / ch).coerceAtLeast(5)
        LaunchedEffect(cols, rows) { session.resize(cols, rows) }

        val scrollState = rememberScrollableState { delta ->
            val maxLines = emu.scrollback.size.toFloat()
            val before = scrollLines
            scrollLines = (scrollLines + delta / ch).coerceIn(0f, if (emu.useAlt) 0f else maxLines)
            (scrollLines - before) * ch
        }

        Canvas(
            Modifier.fillMaxSize()
                .scrollable(scrollState, Orientation.Vertical)
                .pointerInput(Unit) {
                    detectTapGestures { focus.requestFocus(); keyboard?.show() }
                }
        ) {
            @Suppress("UNUSED_VARIABLE") val v = emu.version // redraw on change
            val off = scrollLines.toInt()
            val screen = emu.screen
            val sb = emu.scrollback
            for (vy in 0 until emu.rows) {
                val idx = vy - off
                val line = if (idx >= 0) screen.getOrNull(idx) else sb.getOrNull(sb.size + idx)
                line ?: continue
                val y = vy * ch.toFloat()
                val n = minOf(line.chars.size, emu.cols)
                val isCursorRow = off == 0 && idx == emu.cursorY && emu.cursorVisible && session.connected
                // backgrounds
                var x = 0
                while (x < n) {
                    val inv = line.attr[x] and INVERSE != 0
                    val bgc = if (inv) line.fg[x] else line.bg[x]
                    var end = x + 1
                    while (end < n && (if (line.attr[end] and INVERSE != 0) line.fg[end] else line.bg[end]) == bgc &&
                        (line.attr[end] and INVERSE != 0) == inv) end++
                    if (bgc != DEFAULT || inv) {
                        drawRect(termColor(bgc, fg = inv && bgc == DEFAULT), Offset(x * cw.toFloat(), y), Size((end - x) * cw.toFloat(), ch.toFloat()))
                    }
                    x = end
                }
                if (isCursorRow) drawRect(TermFg, Offset(emu.cursorX * cw.toFloat(), y), Size(cw.toFloat(), ch.toFloat()))
                // text runs (ASCII runs with same style; other characters one by one to keep the grid)
                x = 0
                while (x < n) {
                    val c0 = line.chars[x]
                    if (c0 == ' ' && line.attr[x] and UNDERLINE == 0) { x++; continue }
                    val a = line.attr[x]
                    val f = line.fg[x]
                    var end = x + 1
                    if (c0.code < 128) {
                        while (end < n && line.chars[end].code in 32..126 && line.attr[end] == a && line.fg[end] == f &&
                            !(isCursorRow && end == emu.cursorX) && !(isCursorRow && x == emu.cursorX)) end++
                    }
                    val text = line.chars.concatToString(x, end)
                    val underCursor = isCursorRow && x == emu.cursorX
                    var fgc = if (a and INVERSE != 0) {
                        if (line.bg[x] == DEFAULT) TermBg else termColor(line.bg[x], false)
                    } else {
                        val ff = if (a and BOLD != 0 && f in 0..7) f + 8 else f
                        termColor(ff, true)
                    }
                    if (underCursor) fgc = TermBg
                    if (a and DIM != 0) fgc = fgc.copy(alpha = 0.6f)
                    drawText(
                        measurer, text, Offset(x * cw.toFloat(), y),
                        style.copy(
                            color = fgc,
                            fontWeight = if (a and BOLD != 0) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (a and ITALIC != 0) FontStyle.Italic else FontStyle.Normal,
                            textDecoration = if (a and UNDERLINE != 0) TextDecoration.Underline else null,
                        ),
                    )
                    x = end
                }
            }
            if (off > 0) {
                drawRect(Color(0x66000000), Offset(size.width - 6.dp.toPx(), 0f), Size(6.dp.toPx(), size.height))
            }
        }

        // invisible input field – receives soft keyboard and hardware keys
        BasicTextField(
            value = input,
            onValueChange = { nv ->
                if (nv.composition != null) { input = nv; return@BasicTextField }
                val t = nv.text
                when {
                    t == SENTINEL -> {}
                    t.length < SENTINEL.length -> repeat(SENTINEL.length - t.length) { sendUser("\u007f") }
                    t.startsWith(SENTINEL) -> sendUser(t.substring(SENTINEL.length).replace('\n', '\r'))
                    else -> sendUser(t.trim().replace('\n', '\r'))
                }
                input = TextFieldValue(SENTINEL, TextRange(SENTINEL.length))
            },
            modifier = Modifier.size(1.dp).alpha(0f).focusRequester(focus).onPreviewKeyEvent { onKey(it) },
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.None,
            ),
            singleLine = false,
        )
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    }
}

/** Extra keys for touch keyboards. */
@Composable
fun TerminalKeyBar(session: TerminalSession, mods: TerminalInputState) {
    val app = session.emu.appCursorKeys
    val keys = listOf(
        "Esc" to "\u001b", "Tab" to "\t", "←" to keySequence(Key.DirectionLeft, app)!!, "↑" to keySequence(Key.DirectionUp, app)!!,
        "↓" to keySequence(Key.DirectionDown, app)!!, "→" to keySequence(Key.DirectionRight, app)!!,
        "Pos1" to keySequence(Key.MoveHome, app)!!, "Ende" to keySequence(Key.MoveEnd, app)!!,
        "Bild↑" to "\u001b[5~", "Bild↓" to "\u001b[6~", "|" to "|", "/" to "/", "-" to "-", "~" to "~", ":" to ":",
        "^C" to "\u0003", "^D" to "\u0004", "^Z" to "\u001a",
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ToggleKey("Strg", mods.ctrlArmed) { mods.ctrlArmed = !mods.ctrlArmed }
        ToggleKey("Alt", mods.altArmed) { mods.altArmed = !mods.altArmed }
        keys.forEach { (label, seq) ->
            OutlinedButton(
                onClick = {
                    val s = if (mods.altArmed) { mods.altArmed = false; "\u001b$seq" } else seq
                    session.send(s)
                },
                contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(36.dp),
            ) { Text(label, fontSize = 13.sp) }
        }
    }
}

@Composable
private fun ToggleKey(label: String, on: Boolean, flip: () -> Unit) {
    if (on) FilledTonalButton(onClick = flip, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(36.dp)) { Text(label) }
    else OutlinedButton(onClick = flip, contentPadding = PaddingValues(horizontal = 10.dp), modifier = Modifier.height(36.dp)) { Text(label) }
}
