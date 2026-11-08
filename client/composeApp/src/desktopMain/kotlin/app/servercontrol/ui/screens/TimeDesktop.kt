package app.servercontrol.ui.screens

actual fun currentUtcOffsetMs(): Long = java.util.TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong()
actual fun currentTimeMs(): Long = System.currentTimeMillis()
