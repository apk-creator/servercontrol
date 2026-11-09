package app.servercontrol.terminal

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
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
import androidx.compose.runtime.rememberUpdatedState
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

/** Text selection in absolute line coordinates (0 = oldest scrollback line), columns inclusive. */
data class TermSel(val aLine: Int, val aCol: Int, val bLine: Int, val bCol: Int) {
    fun norm(): TermSel = if (aLine < bLine || (aLine == bLine && aCol <= bCol)) this else TermSel(bLine, bCol, aLine, aCol)
}

private data class Cell(val line: Int, val col: Int)

private fun lineAt(emu: TerminalEmulator, abs: Int): TerminalEmulator.Line? {
    val sb = emu.scrollback
    return if (abs < sb.size) sb.getOrNull(abs) else emu.screen.getOrNull(abs - sb.size)
}

/** Text of a selection, lines joined with \n, trailing blanks removed. */
fun selectionText(emu: TerminalEmulator, sel: TermSel): String {
    val n = sel.norm()
    return (n.aLine..n.bLine).joinToString("\n") { abs ->
        val l = lineAt(emu, abs) ?: return@joinToString ""
        val from = if (abs == n.aLine) n.aCol else 0
        val to = if (abs == n.bLine) n.bCol + 1 else l.chars.size
        if (from >= l.chars.size) "" else l.chars.concatToString(from, to.coerceAtMost(l.chars.size)).trimEnd()
    }
}

/** Draws the visible terminal (rows from [off] lines up in the scrollback) incl. cursor and selection. */
private fun DrawScope.drawTerminal(
    emu: TerminalEmulator,
    measurer: TextMeasurer,
    style: TextStyle,
    cw: Int,
    ch: Int,
    off: Int,
    showCursor: Boolean,
    sel: TermSel?,
) {
    val screen = emu.screen
    val sb = emu.scrollback
    val ns = sel?.norm()
    for (vy in 0 until emu.rows) {
        val idx = vy - off
        val abs = sb.size + idx
        val line = if (idx >= 0) screen.getOrNull(idx) else sb.getOrNull(sb.size + idx)
        line ?: continue
        val y = vy * ch.toFloat()
        val n = minOf(line.chars.size, emu.cols)
        val isCursorRow = showCursor && off == 0 && idx == emu.cursorY && emu.cursorVisible
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
            if (c0.code < 128 && !(isCursorRow && x == emu.cursorX)) {
                while (end < n && line.chars[end].code in 32..126 && line.attr[end] == a && line.fg[end] == f &&
                    !(isCursorRow && end == emu.cursorX)) end++
            }
            val text = line.chars.concatToString(x, end)
            var fgc = if (a and INVERSE != 0) {
                if (line.bg[x] == DEFAULT) TermBg else termColor(line.bg[x], false)
            } else {
                val ff = if (a and BOLD != 0 && f in 0..7) f + 8 else f
                termColor(ff, true)
            }
            if (isCursorRow && x == emu.cursorX) fgc = TermBg
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
        // selection highlight
        if (ns != null && abs in ns.aLine..ns.bLine) {
            val from = if (abs == ns.aLine) ns.aCol else 0
            val to = if (abs == ns.bLine) ns.bCol else emu.cols - 1
            if (to >= from) drawRect(SelColor, Offset(from * cw.toFloat(), y), Size((to - from + 1) * cw.toFloat(), ch.toFloat()))
        }
    }
}

val SelColor = Color(0x66FFFFFF)
private const val TAP = 1
private const val MOVE = 2

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
    val clipboard = LocalClipboardManager.current
    val density = LocalDensity.current
    var input by remember { mutableStateOf(TextFieldValue(SENTINEL, TextRange(SENTINEL.length))) }
    var selection by remember { mutableStateOf<TermSel?>(null) }
    /** point (px, in view coordinates) the magnifier shows; null = hidden */
    var magnifierAt by remember { mutableStateOf<Offset?>(null) }
    var copied by remember { mutableStateOf(false) }

    fun toBottom() { scrollLines = 0f }

    fun sendUser(text: String) {
        if (text.isEmpty()) return
        toBottom()
        selection = null
        session.send(applyModifiers(text, mods))
    }

    fun copySelection() {
        val sel = selection ?: return
        clipboard.setText(AnnotatedString(selectionText(emu, sel)))
        selection = null
        copied = true
    }

    fun pasteClipboard() {
        clipboard.getText()?.text?.let { toBottom(); selection = null; session.paste(it) }
    }

    fun onKey(e: KeyEvent): Boolean {
        if (e.type != KeyEventType.KeyDown) return false
        // desktop shortcuts: Ctrl+Shift+C / Ctrl+Shift+V
        if (e.isCtrlPressed && e.isShiftPressed && e.key == Key.C) { copySelection(); return true }
        if (e.isCtrlPressed && e.isShiftPressed && e.key == Key.V) { pasteClipboard(); return true }
        keySequence(e.key, emu.appCursorKeys)?.let { seq ->
            val s = if (e.isAltPressed || mods.altArmed) { mods.altArmed = false; "\u001b$seq" } else seq
            toBottom(); selection = null
            session.send(s)
            return true
        }
        if (e.isCtrlPressed) {
            val i = letterKeys.indexOf(e.key)
            if (i >= 0) { toBottom(); selection = null; session.send((i + 1).toChar().toString()); return true }
        }
        if (e.isAltPressed && e.utf16CodePoint > 0) {
            toBottom()
            session.send("\u001b" + e.utf16CodePoint.toChar())
            return true
        }
        return false
    }

    LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }

    BoxWithConstraints(modifier.background(TermBg).clipToBounds()) {
        val cw = cell.width.coerceAtLeast(1)
        val ch = cell.height.coerceAtLeast(1)
        val cols = (constraints.maxWidth / cw).coerceAtLeast(20)
        val rows = (constraints.maxHeight / ch).coerceAtLeast(5)
        LaunchedEffect(cols, rows) { session.resize(cols, rows) }

        fun maxScroll() = if (emu.useAlt) 0f else emu.scrollback.size.toFloat()
        fun cellAt(p: Offset): Cell {
            val vy = (p.y / ch).toInt().coerceIn(0, emu.rows - 1)
            val col = (p.x / cw).toInt().coerceIn(0, emu.cols - 1)
            return Cell(emu.scrollback.size - scrollLines.toInt() + vy, col)
        }
        fun wordAt(c: Cell): TermSel {
            val l = lineAt(emu, c.line) ?: return TermSel(c.line, c.col, c.line, c.col)
            fun isWord(i: Int) = i in l.chars.indices && !l.chars[i].isWhitespace()
            if (!isWord(c.col)) return TermSel(c.line, c.col, c.line, c.col)
            var a = c.col; var b = c.col
            while (isWord(a - 1)) a--
            while (isWord(b + 1)) b++
            return TermSel(c.line, a, c.line, b)
        }
        /** view position of a cell (top-left) */
        fun cellPos(line: Int, col: Int): Offset {
            val vy = line - (emu.scrollback.size - scrollLines.toInt())
            return Offset(col * cw.toFloat(), vy * ch.toFloat())
        }
        fun onTap(p: Offset) {
            if (selection != null) { selection = null; return }
            focus.requestFocus()
            keyboard?.show()
            // tap into the input line moves the cursor (sends arrow keys)
            val c = cellAt(p)
            val cursorAbs = emu.scrollback.size + emu.cursorY
            if (scrollLines.toInt() == 0 && c.line == cursorAbs && session.connected) {
                val dx = c.col - emu.cursorX
                if (dx != 0) {
                    val seq = keySequence(if (dx > 0) Key.DirectionRight else Key.DirectionLeft, emu.appCursorKeys)!!
                    session.send(seq.repeat(kotlin.math.abs(dx)))
                }
            }
        }

        Canvas(
            Modifier.fillMaxSize()
                .pointerInput(cw, ch) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        if (down.type == PointerType.Mouse) {
                            // mouse: drag = select, click = tap
                            val start = cellAt(down.position)
                            var selecting = false
                            while (true) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                if (!selecting && (c.position - down.position).getDistance() > slop) {
                                    selecting = true
                                }
                                if (selecting) {
                                    val e = cellAt(c.position)
                                    selection = TermSel(start.line, start.col, e.line, e.col)
                                    c.consume()
                                }
                            }
                            if (!selecting) onTap(down.position)
                            return@awaitEachGesture
                        }
                        // touch: quick tap / drag = scroll / long press = select word (+ drag to extend, with magnifier)
                        val res = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var r = 0
                            while (r == 0) {
                                val ev = awaitPointerEvent()
                                val c = ev.changes.firstOrNull { it.id == down.id }
                                r = when {
                                    c == null || !c.pressed -> TAP
                                    (c.position - down.position).getDistance() > slop -> MOVE
                                    else -> 0
                                }
                            }
                            r
                        }
                        when (res) {
                            TAP -> onTap(down.position)
                            MOVE -> {
                                var lastY = down.position.y
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    val dy = c.position.y - lastY
                                    lastY = c.position.y
                                    scrollLines = (scrollLines + dy / ch).coerceIn(0f, maxScroll())
                                    c.consume()
                                }
                            }
                            else -> { // long press
                                val w = wordAt(cellAt(down.position))
                                selection = w
                                magnifierAt = down.position
                                while (true) {
                                    val ev = awaitPointerEvent()
                                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!c.pressed) break
                                    val e = cellAt(c.position)
                                    selection = TermSel(w.aLine, w.aCol, e.line, e.col)
                                    magnifierAt = c.position
                                    c.consume()
                                }
                                magnifierAt = null
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    // mouse wheel
                    awaitPointerEventScope {
                        while (true) {
                            val ev = awaitPointerEvent()
                            if (ev.type == PointerEventType.Scroll) {
                                val d = ev.changes.firstOrNull()?.scrollDelta?.y ?: 0f
                                scrollLines = (scrollLines - d * 3f).coerceIn(0f, maxScroll())
                                ev.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
        ) {
            @Suppress("UNUSED_VARIABLE") val v = emu.version // redraw on change
            drawTerminal(emu, measurer, style, cw, ch, scrollLines.toInt(), session.connected, selection)
            if (scrollLines.toInt() > 0) {
                drawRect(Color(0x66FFFFFF), Offset(size.width - 4.dp.toPx(), 0f), Size(4.dp.toPx(), size.height))
            }
        }

        // ---- selection handles (drag them; the magnifier shows the exact position) ----
        val sel = selection?.norm()
        if (sel != null) {
            val handleSize = 28.dp
            val hs = with(density) { handleSize.toPx() }
            listOf(true, false).forEach { isStart ->
                val anchor = if (isStart) cellPos(sel.aLine, sel.aCol) + Offset(0f, ch.toFloat())
                else cellPos(sel.bLine, sel.bCol) + Offset(cw.toFloat(), ch.toFloat())
                val anchorNow = rememberUpdatedState(anchor)
                Box(
                    Modifier
                        .offset { IntOffset((anchor.x - hs / 2).toInt(), anchor.y.toInt()) }
                        .size(handleSize)
                        .pointerInput(isStart, cw, ch) {
                            // p = centre of the cell the handle belongs to
                            var p = Offset.Zero
                            detectDragGestures(
                                onDragStart = {
                                    p = anchorNow.value - Offset(if (isStart) -cw / 2f else cw / 2f, ch / 2f)
                                    magnifierAt = p
                                },
                                onDragEnd = { magnifierAt = null },
                                onDragCancel = { magnifierAt = null },
                            ) { change, drag ->
                                change.consume()
                                p += drag
                                magnifierAt = p
                                val c = cellAt(p)
                                val cur = selection?.norm() ?: return@detectDragGestures
                                selection = if (isStart) TermSel(c.line, c.col, cur.bLine, cur.bCol) else TermSel(cur.aLine, cur.aCol, c.line, c.col)
                            }
                        }
                ) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(Color.White, radius = size.minDimension / 3.2f, center = Offset(size.width / 2, size.height / 2))
                        drawLine(Color.White, Offset(size.width / 2, 0f), Offset(size.width / 2, size.height / 2), strokeWidth = 2.dp.toPx())
                    }
                }
            }
            // copy bar
            Row(
                Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(20.dp)).padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = { copySelection() }) { Text("Kopieren", color = Color.Black) }
                TextButton(onClick = {
                    val last = emu.scrollback.size + emu.rows - 1
                    selection = TermSel(0, 0, last, emu.cols - 1)
                }) { Text("Alles", color = Color.Black) }
                TextButton(onClick = { selection = null }) { Text("Abbrechen", color = Color.Black) }
            }
        }
        if (copied) {
            Text(
                "Kopiert", color = Color.Black, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                    .background(Color(0xFFEEEEEE), RoundedCornerShape(16.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }

        // ---- magnifier ----
        magnifierAt?.let { at ->
            val mw = 200.dp
            val mh = 72.dp
            val zoom = 2f
            val mwPx = with(density) { mw.toPx() }
            val mhPx = with(density) { mh.toPx() }
            val gap = with(density) { 56.dp.toPx() }
            val left = (at.x - mwPx / 2).coerceIn(0f, (constraints.maxWidth - mwPx).coerceAtLeast(0f))
            val top = (at.y - gap - mhPx).let { if (it < 0) at.y + gap / 2 else it }
            Canvas(
                Modifier.offset { IntOffset(left.toInt(), top.toInt()) }.size(mw, mh)
                    .shadow(6.dp, RoundedCornerShape(12.dp)).clip(RoundedCornerShape(12.dp))
                    .background(TermBg).border(1.dp, Color(0xFF9E9E9E), RoundedCornerShape(12.dp))
            ) {
                @Suppress("UNUSED_VARIABLE") val v = emu.version
                withTransform({
                    translate(size.width / 2 - at.x * zoom, size.height / 2 - at.y * zoom)
                    scale(zoom, zoom, pivot = Offset.Zero)
                }) {
                    drawTerminal(emu, measurer, style, cw, ch, scrollLines.toInt(), session.connected, selection)
                }
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
                    else -> {
                        val typed = if (t.startsWith(SENTINEL)) t.substring(SENTINEL.length) else t.trim()
                        // multi-line text (pasted via keyboard) goes in as a paste: no auto-execution
                        if (typed.length > 1 && (typed.contains('\n') || typed.contains('\r'))) {
                            toBottom(); selection = null; session.paste(typed)
                        } else {
                            sendUser(typed.replace('\n', '\r'))
                        }
                    }
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
