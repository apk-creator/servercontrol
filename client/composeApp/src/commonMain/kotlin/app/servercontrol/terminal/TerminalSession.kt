package app.servercontrol.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.servercontrol.SshAuth
import app.servercontrol.SshShell
import app.servercontrol.openSsh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One SSH terminal: emulator + shell + ordered writer. Lives in AppState so it survives section switches. */
class TerminalSession(private val scope: CoroutineScope) {
    val emu = TerminalEmulator(80, 24)
    var connected by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set

    private var shell: SshShell? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var outbox = Channel<ByteArray>(Channel.UNLIMITED)

    init {
        emu.onResponse = { send(it) }
    }

    /** Throws SshHostKeyUnknown / SshHostKeyChanged / auth errors. */
    suspend fun connect(host: String, port: Int, auth: SshAuth, knownHostKey: String) {
        close()
        val sh = openSsh(host, port, auth, knownHostKey, emu.cols, emu.rows)
        shell = sh
        connected = true
        title = "${auth.user}@$host"
        outbox = Channel(Channel.UNLIMITED)
        val box = outbox
        writeJob = scope.launch {
            for (data in box) runCatching { sh.write(data) }
        }
        readJob = scope.launch {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = sh.read(buf)
                if (n < 0) break
                if (n > 0) {
                    emu.feed(buf, n)
                    if (emu.title.isNotBlank()) title = emu.title
                }
            }
            connected = false
            emu.feed("\r\n\u001b[33m[Verbindung beendet]\u001b[0m\r\n".encodeToByteArray())
        }
    }

    fun send(text: String) = send(text.encodeToByteArray())

    fun send(data: ByteArray) {
        if (connected) outbox.trySend(data)
    }

    fun paste(text: String) {
        val t = text.replace("\r\n", "\r").replace('\n', '\r')
        send(if (emu.bracketedPaste) "\u001b[200~$t\u001b[201~" else t)
    }

    fun resize(cols: Int, rows: Int) {
        if (cols == emu.cols && rows == emu.rows) return
        emu.resize(cols, rows)
        shell?.resize(cols, rows)
    }

    fun close() {
        readJob?.cancel()
        writeJob?.cancel()
        outbox.close()
        shell?.close()
        shell = null
        connected = false
    }
}
