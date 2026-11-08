package app.servercontrol.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * Small xterm-compatible terminal emulator (enough for bash, nano, htop, less, vim, mc).
 * All methods must be called from the same thread (the UI thread).
 *
 * Colors: -1 = default, 0..255 = xterm palette, (1 shl 24) or 0xRRGGBB = true color.
 */
class TerminalEmulator(cols: Int, rows: Int, private val scrollbackMax: Int = 3000) {
    companion object {
        const val DEFAULT = -1
        const val TRUECOLOR = 1 shl 24
        const val BOLD = 1
        const val DIM = 2
        const val ITALIC = 4
        const val UNDERLINE = 8
        const val INVERSE = 16
    }

    class Line(cols: Int) {
        var chars = CharArray(cols) { ' ' }
        var fg = IntArray(cols) { DEFAULT }
        var bg = IntArray(cols) { DEFAULT }
        var attr = IntArray(cols)

        fun resize(n: Int) {
            val old = chars.size
            chars = chars.copyOf(n).also { for (i in old until n) it[i] = ' ' }
            fg = fg.copyOf(n).also { for (i in old until n) it[i] = DEFAULT }
            bg = bg.copyOf(n).also { for (i in old until n) it[i] = DEFAULT }
            attr = attr.copyOf(n)
        }

        fun clear(from: Int, to: Int, bgColor: Int) {
            for (i in from.coerceAtLeast(0) until to.coerceAtMost(chars.size)) {
                chars[i] = ' '; fg[i] = DEFAULT; bg[i] = bgColor; attr[i] = 0
            }
        }

        fun copy(): Line = Line(0).also { it.chars = chars.copyOf(); it.fg = fg.copyOf(); it.bg = bg.copyOf(); it.attr = attr.copyOf() }
        fun text(): String = chars.concatToString().trimEnd()
    }

    var cols = cols; private set
    var rows = rows; private set

    private var main = Array(rows) { Line(cols) }
    private var alt = Array(rows) { Line(cols) }
    var useAlt = false; private set
    val screen: Array<Line> get() = if (useAlt) alt else main
    val scrollback = ArrayDeque<Line>()

    var cursorX = 0; private set
    var cursorY = 0; private set
    var cursorVisible = true; private set
    var appCursorKeys = false; private set
    var bracketedPaste = false; private set
    var title = ""; private set

    /** Bumped after every change so Compose redraws. */
    var version by mutableLongStateOf(0L)
        private set

    /** Answers to the host (cursor position report, device attributes). */
    var onResponse: (String) -> Unit = {}

    private var fg = DEFAULT
    private var bg = DEFAULT
    private var attr = 0
    private var autoWrap = true
    private var wrapPending = false
    private var originMode = false
    private var insertMode = false
    private var top = 0
    private var bottom = rows - 1
    private var saved = IntArray(5)
    private var g0Graphics = false
    private var g1Graphics = false
    private var shiftOut = false
    private var tabStops = BooleanArray(cols) { it % 8 == 0 }

    // parser
    private enum class State { NORMAL, ESC, CSI, OSC, OSC_ESC, CHARSET0, CHARSET1, SKIP1 }
    private var state = State.NORMAL
    private val params = StringBuilder()
    private val osc = StringBuilder()
    private var utf8Need = 0
    private var utf8Cp = 0

    // ---------------- input ----------------

    fun feed(data: ByteArray, len: Int = data.size) {
        for (i in 0 until len) {
            val b = data[i].toInt() and 0xff
            if (utf8Need > 0) {
                if (b and 0xC0 == 0x80) {
                    utf8Cp = (utf8Cp shl 6) or (b and 0x3F)
                    if (--utf8Need == 0) process(utf8Cp)
                    continue
                } else {
                    utf8Need = 0
                    process(0xFFFD)
                }
            }
            when {
                b < 0x80 -> process(b)
                b and 0xE0 == 0xC0 -> { utf8Cp = b and 0x1F; utf8Need = 1 }
                b and 0xF0 == 0xE0 -> { utf8Cp = b and 0x0F; utf8Need = 2 }
                b and 0xF8 == 0xF0 -> { utf8Cp = b and 0x07; utf8Need = 3 }
                else -> process(0xFFFD)
            }
        }
        version++
    }

    private fun process(cp: Int) {
        when (state) {
            State.NORMAL -> normal(cp)
            State.ESC -> esc(cp)
            State.CSI -> {
                if (cp in 0x40..0x7E) {
                    csi(cp.toChar())
                    state = State.NORMAL
                } else if (cp < 0x20) {
                    control(cp)
                } else {
                    params.append(cp.toChar())
                }
            }
            State.OSC -> when (cp) {
                7 -> { endOsc(); state = State.NORMAL }
                0x1B -> state = State.OSC_ESC
                else -> if (osc.length < 512) osc.append(cp.toChar())
            }
            State.OSC_ESC -> { endOsc(); state = State.NORMAL; if (cp != '\\'.code) process(cp) }
            State.CHARSET0 -> { g0Graphics = cp == '0'.code; state = State.NORMAL }
            State.CHARSET1 -> { g1Graphics = cp == '0'.code; state = State.NORMAL }
            State.SKIP1 -> state = State.NORMAL
        }
    }

    private fun endOsc() {
        val s = osc.toString()
        if (s.startsWith("0;") || s.startsWith("2;")) title = s.substring(2)
        osc.clear()
    }

    private fun normal(cp: Int) {
        if (cp < 0x20 || cp == 0x7F) control(cp) else put(cp)
    }

    private fun control(cp: Int) {
        when (cp) {
            0x07 -> {}
            0x08 -> { if (cursorX > 0) cursorX--; wrapPending = false }
            0x09 -> { var x = cursorX + 1; while (x < cols - 1 && !tabStops[x]) x++; cursorX = x.coerceAtMost(cols - 1); wrapPending = false }
            0x0A, 0x0B, 0x0C -> lineFeed()
            0x0D -> { cursorX = 0; wrapPending = false }
            0x0E -> shiftOut = true
            0x0F -> shiftOut = false
            0x1B -> { state = State.ESC }
        }
    }

    private fun esc(cp: Int) {
        state = State.NORMAL
        when (cp.toChar()) {
            '[' -> { params.clear(); state = State.CSI }
            ']' -> { osc.clear(); state = State.OSC }
            '(' -> state = State.CHARSET0
            ')' -> state = State.CHARSET1
            '*', '+', '#', '%' -> state = State.SKIP1
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'D' -> lineFeed()
            'E' -> { cursorX = 0; lineFeed() }
            'M' -> reverseIndex()
            'H' -> if (cursorX < cols) tabStops[cursorX] = true
            'c' -> reset()
            '=', '>' -> {}
            else -> {}
        }
    }

    private val graphics = mapOf(
        '`' to '◆', 'a' to '▒', 'f' to '°', 'g' to '±', 'j' to '┘', 'k' to '┐', 'l' to '┌', 'm' to '└', 'n' to '┼',
        'o' to '⎺', 'p' to '⎻', 'q' to '─', 'r' to '⎼', 's' to '⎽', 't' to '├', 'u' to '┤', 'v' to '┴', 'w' to '┬',
        'x' to '│', 'y' to '≤', 'z' to '≥', '{' to 'π', '|' to '≠', '}' to '£', '~' to '·',
    )

    private fun put(cp: Int) {
        var ch = if (cp > 0xFFFF) '�' else cp.toChar()
        if ((if (shiftOut) g1Graphics else g0Graphics)) ch = graphics[ch] ?: ch
        if (wrapPending) {
            if (autoWrap) { cursorX = 0; lineFeed() }
            wrapPending = false
        }
        val line = screen[cursorY]
        if (insertMode) {
            for (i in cols - 1 downTo cursorX + 1) {
                line.chars[i] = line.chars[i - 1]; line.fg[i] = line.fg[i - 1]; line.bg[i] = line.bg[i - 1]; line.attr[i] = line.attr[i - 1]
            }
        }
        line.chars[cursorX] = ch
        line.fg[cursorX] = fg
        line.bg[cursorX] = bg
        line.attr[cursorX] = attr
        if (cursorX == cols - 1) wrapPending = true else cursorX++
    }

    private fun lineFeed() {
        wrapPending = false
        if (cursorY == bottom) scrollUp(1) else if (cursorY < rows - 1) cursorY++
    }

    private fun reverseIndex() {
        wrapPending = false
        if (cursorY == top) scrollDown(1) else if (cursorY > 0) cursorY--
    }

    private fun scrollUp(n: Int) {
        val s = screen
        repeat(n.coerceAtMost(bottom - top + 1)) {
            val first = s[top]
            if (!useAlt && top == 0) {
                scrollback.addLast(first.copy())
                while (scrollback.size > scrollbackMax) scrollback.removeFirst()
            }
            for (y in top until bottom) s[y] = s[y + 1]
            s[bottom] = Line(cols).also { it.clear(0, cols, bg) }
        }
    }

    private fun scrollDown(n: Int) {
        val s = screen
        repeat(n.coerceAtMost(bottom - top + 1)) {
            for (y in bottom downTo top + 1) s[y] = s[y - 1]
            s[top] = Line(cols).also { it.clear(0, cols, bg) }
        }
    }

    private fun saveCursor() {
        saved = intArrayOf(cursorX, cursorY, fg, bg, attr)
    }

    private fun restoreCursor() {
        cursorX = saved[0].coerceIn(0, cols - 1); cursorY = saved[1].coerceIn(0, rows - 1)
        fg = saved[2]; bg = saved[3]; attr = saved[4]; wrapPending = false
    }

    fun reset() {
        main = Array(rows) { Line(cols) }; alt = Array(rows) { Line(cols) }
        useAlt = false; cursorX = 0; cursorY = 0; fg = DEFAULT; bg = DEFAULT; attr = 0
        top = 0; bottom = rows - 1; autoWrap = true; wrapPending = false; originMode = false; insertMode = false
        appCursorKeys = false; cursorVisible = true; bracketedPaste = false; g0Graphics = false; g1Graphics = false; shiftOut = false
        tabStops = BooleanArray(cols) { it % 8 == 0 }
    }

    // ---------------- CSI ----------------

    private fun csi(final: Char) {
        val raw = params.toString()
        val private = raw.firstOrNull()?.takeIf { it == '?' || it == '>' || it == '=' || it == '<' }
        val body = if (private != null) raw.substring(1) else raw
        val intermediate = body.lastOrNull()?.takeIf { it == ' ' || it == '"' || it == '$' || it == '\'' || it == '!' }
        val clean = if (intermediate != null) body.dropLast(1) else body
        val p = if (clean.isEmpty()) emptyList() else clean.split(';', ':').map { it.toIntOrNull() ?: 0 }
        fun arg(i: Int, def: Int = 1) = p.getOrNull(i)?.takeIf { it != 0 } ?: def
        if (intermediate != null) return // e.g. DECSCUSR (cursor shape) – ignored
        if (private == '?') { decPrivate(final, p); return }
        if (private != null) {
            if (final == 'c') onResponse("\u001b[>0;276;0c")
            return
        }
        when (final) {
            'A' -> moveTo(cursorX, cursorY - arg(0), clampRegion = true)
            'B', 'e' -> moveTo(cursorX, cursorY + arg(0), clampRegion = true)
            'C', 'a' -> moveTo(cursorX + arg(0), cursorY)
            'D' -> moveTo(cursorX - arg(0), cursorY)
            'E' -> moveTo(0, cursorY + arg(0), clampRegion = true)
            'F' -> moveTo(0, cursorY - arg(0), clampRegion = true)
            'G', '`' -> moveTo(arg(0) - 1, cursorY)
            'd' -> moveTo(cursorX, (arg(0) - 1) + if (originMode) top else 0)
            'H', 'f' -> moveTo(arg(1) - 1, (arg(0) - 1) + if (originMode) top else 0)
            'J' -> eraseDisplay(p.getOrNull(0) ?: 0)
            'K' -> eraseLine(p.getOrNull(0) ?: 0)
            'L' -> if (cursorY in top..bottom) { val t = top; top = cursorY; scrollDown(arg(0)); top = t }
            'M' -> if (cursorY in top..bottom) { val t = top; top = cursorY; scrollUp(arg(0)); top = t }
            'P' -> deleteChars(arg(0))
            '@' -> insertChars(arg(0))
            'X' -> screen[cursorY].clear(cursorX, cursorX + arg(0), bg)
            'S' -> scrollUp(arg(0))
            'T' -> scrollDown(arg(0))
            'm' -> sgr(p)
            'r' -> {
                val t = arg(0) - 1
                val b = arg(1, rows) - 1
                if (t < b && b < rows) { top = t; bottom = b } else { top = 0; bottom = rows - 1 }
                moveTo(0, if (originMode) top else 0)
            }
            's' -> saveCursor()
            'u' -> restoreCursor()
            'h' -> if (p.contains(4)) insertMode = true
            'l' -> if (p.contains(4)) insertMode = false
            'g' -> when (p.getOrNull(0) ?: 0) { 0 -> if (cursorX < cols) tabStops[cursorX] = false; 3 -> tabStops.fill(false) }
            'n' -> when (p.getOrNull(0)) {
                5 -> onResponse("\u001b[0n")
                6 -> onResponse("\u001b[${cursorY + 1};${cursorX + 1}R")
            }
            'c' -> onResponse("\u001b[?62;22c")
            't' -> if (p.getOrNull(0) == 18) onResponse("\u001b[8;$rows;${cols}t")
            'b' -> {} // REP – rarely used
        }
    }

    private fun decPrivate(final: Char, p: List<Int>) {
        val on = final == 'h'
        if (final != 'h' && final != 'l') return
        for (m in p) when (m) {
            1 -> appCursorKeys = on
            6 -> { originMode = on; moveTo(0, if (on) top else 0) }
            7 -> autoWrap = on
            25 -> cursorVisible = on
            47, 1047 -> switchAlt(on, clear = false)
            1048 -> if (on) saveCursor() else restoreCursor()
            1049 -> { if (on) saveCursor(); switchAlt(on, clear = true); if (!on) restoreCursor() }
            2004 -> bracketedPaste = on
        }
    }

    private fun switchAlt(on: Boolean, clear: Boolean) {
        if (on == useAlt) return
        useAlt = on
        if (on && clear) alt.forEach { it.clear(0, cols, DEFAULT) }
        wrapPending = false
    }

    private fun moveTo(x: Int, y: Int, clampRegion: Boolean = false) {
        cursorX = x.coerceIn(0, cols - 1)
        val (minY, maxY) = if ((clampRegion && cursorY in top..bottom) || originMode) top to bottom else 0 to rows - 1
        cursorY = y.coerceIn(minY, maxY)
        wrapPending = false
    }

    private fun eraseDisplay(mode: Int) {
        val s = screen
        when (mode) {
            0 -> { s[cursorY].clear(cursorX, cols, bg); for (y in cursorY + 1 until rows) s[y].clear(0, cols, bg) }
            1 -> { s[cursorY].clear(0, cursorX + 1, bg); for (y in 0 until cursorY) s[y].clear(0, cols, bg) }
            2 -> s.forEach { it.clear(0, cols, bg) }
            3 -> scrollback.clear()
        }
    }

    private fun eraseLine(mode: Int) {
        val l = screen[cursorY]
        when (mode) {
            0 -> l.clear(cursorX, cols, bg)
            1 -> l.clear(0, cursorX + 1, bg)
            2 -> l.clear(0, cols, bg)
        }
    }

    private fun deleteChars(n: Int) {
        val l = screen[cursorY]
        val k = n.coerceAtMost(cols - cursorX)
        for (i in cursorX until cols - k) {
            l.chars[i] = l.chars[i + k]; l.fg[i] = l.fg[i + k]; l.bg[i] = l.bg[i + k]; l.attr[i] = l.attr[i + k]
        }
        l.clear(cols - k, cols, bg)
    }

    private fun insertChars(n: Int) {
        val l = screen[cursorY]
        val k = n.coerceAtMost(cols - cursorX)
        for (i in cols - 1 downTo cursorX + k) {
            l.chars[i] = l.chars[i - k]; l.fg[i] = l.fg[i - k]; l.bg[i] = l.bg[i - k]; l.attr[i] = l.attr[i - k]
        }
        l.clear(cursorX, cursorX + k, bg)
    }

    private fun sgr(p: List<Int>) {
        if (p.isEmpty()) { fg = DEFAULT; bg = DEFAULT; attr = 0; return }
        var i = 0
        while (i < p.size) {
            when (val c = p[i]) {
                0 -> { fg = DEFAULT; bg = DEFAULT; attr = 0 }
                1 -> attr = attr or BOLD
                2 -> attr = attr or DIM
                3 -> attr = attr or ITALIC
                4 -> attr = attr or UNDERLINE
                7 -> attr = attr or INVERSE
                22 -> attr = attr and (BOLD or DIM).inv()
                23 -> attr = attr and ITALIC.inv()
                24 -> attr = attr and UNDERLINE.inv()
                27 -> attr = attr and INVERSE.inv()
                in 30..37 -> fg = c - 30
                39 -> fg = DEFAULT
                in 40..47 -> bg = c - 40
                49 -> bg = DEFAULT
                in 90..97 -> fg = c - 90 + 8
                in 100..107 -> bg = c - 100 + 8
                38, 48 -> {
                    val mode = p.getOrNull(i + 1)
                    var color = DEFAULT
                    if (mode == 5) { color = p.getOrNull(i + 2) ?: 0; i += 2 }
                    else if (mode == 2) {
                        val r = p.getOrNull(i + 2) ?: 0; val g = p.getOrNull(i + 3) ?: 0; val b = p.getOrNull(i + 4) ?: 0
                        color = TRUECOLOR or ((r and 255) shl 16) or ((g and 255) shl 8) or (b and 255); i += 4
                    }
                    if (c == 38) fg = color else bg = color
                }
            }
            i++
        }
    }

    // ---------------- resize ----------------

    fun resize(newCols: Int, newRows: Int) {
        if ((newCols == cols && newRows == rows) || newCols < 2 || newRows < 2) return
        scrollback.forEach { it.resize(newCols) }
        // when shrinking, drop lines from the top so the cursor line stays visible
        val drop = if (newRows < rows) (cursorY - (newRows - 1)).coerceAtLeast(0) else 0
        fun adapt(arr: Array<Line>, isMain: Boolean): Array<Line> {
            arr.forEach { it.resize(newCols) }
            val list = arr.toMutableList()
            repeat(drop) {
                val l = list.removeAt(0)
                if (isMain) { scrollback.addLast(l); while (scrollback.size > scrollbackMax) scrollback.removeFirst() }
            }
            while (list.size > newRows) list.removeAt(list.size - 1)
            while (list.size < newRows) list.add(Line(newCols))
            return list.toTypedArray()
        }
        main = adapt(main, true)
        alt = adapt(alt, false)
        cols = newCols; rows = newRows
        top = 0; bottom = rows - 1
        cursorX = cursorX.coerceIn(0, cols - 1)
        cursorY = (cursorY - drop).coerceIn(0, rows - 1)
        tabStops = BooleanArray(cols) { it % 8 == 0 }
        wrapPending = false
        version++
    }

    /** Visible screen as plain text (for copying). */
    fun screenText(): String = screen.joinToString("\n") { it.text() }.trimEnd()
}
