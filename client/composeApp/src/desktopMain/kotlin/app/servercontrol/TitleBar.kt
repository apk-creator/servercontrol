package app.servercontrol

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/** Windows: colour the title bar of the window like the app background (Windows 11; dark mode also on 10). */
object TitleBar {
    @Suppress("FunctionName")
    private interface Dwm : Library {
        fun DwmSetWindowAttribute(hwnd: Pointer, attribute: Int, value: IntByReference, size: Int): Int
    }

    private val dwm: Dwm? by lazy { if (isWindows) runCatching { Native.load("dwmapi", Dwm::class.java) }.getOrNull() else null }

    private fun colorRef(c: Color): Int {
        val argb = c.toArgb()
        val r = argb shr 16 and 255
        val g = argb shr 8 and 255
        val b = argb and 255
        return r or (g shl 8) or (b shl 16)
    }

    fun apply(window: java.awt.Window, background: Color, text: Color, dark: Boolean) {
        val api = dwm ?: return
        runCatching {
            val hwnd = Native.getWindowPointer(window) ?: return
            api.DwmSetWindowAttribute(hwnd, 20, IntByReference(if (dark) 1 else 0), 4) // DWMWA_USE_IMMERSIVE_DARK_MODE
            api.DwmSetWindowAttribute(hwnd, 35, IntByReference(colorRef(background)), 4) // DWMWA_CAPTION_COLOR
            api.DwmSetWindowAttribute(hwnd, 36, IntByReference(colorRef(text)), 4) // DWMWA_TEXT_COLOR
            api.DwmSetWindowAttribute(hwnd, 34, IntByReference(colorRef(background)), 4) // DWMWA_BORDER_COLOR
        }
    }
}
