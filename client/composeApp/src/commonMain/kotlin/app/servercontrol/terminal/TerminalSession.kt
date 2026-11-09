package app.servercontrol.terminal

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.servercontrol.SshAuth
import app.servercontrol.SshShell
import app.servercontrol.ioDispatcher
import app.servercontrol.openSsh
import app.servercontrol.terminalKeepAlive
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything that goes to the SSH server runs through one writer coroutine on an IO thread. */
private sealed interface Outgoing {
    class Data(val bytes: ByteArray) : Outgoing
    class Resize(val cols: Int, val rows: Int) : Outgoing
}

/**
 * One SSH terminal: emulator backend + shell + ordered writer.
 * Lives in AppState so it survives section switches (and, on Android, the app going to the background).
 * Like ConnectBot: all network I/O runs on IO threads, never on the UI thread.
 */
class TerminalSession(private val scope: CoroutineScope) {
    var connected by mutableStateOf(false)
        private set
    var title by mutableStateOf("")
        private set

    private var shell: SshShell? = null
    private var readJob: Job? = null
    private var writeJob: Job? = null
    private var outbox = Channel<Outgoing>(Channel.UNLIMITED)

    val backend: TerminalBackend = createTerminalBackend(
        onInput = { send(it) },
        onResize = { c, r -> if (connected) outbox.trySend(Outgoing.Resize(c, r)) },
    )

    /** Throws SshHostKeyUnknown / SshHostKeyChanged / auth errors. */
    suspend fun connect(host: String, port: Int, auth: SshAuth, knownHostKey: String) {
        close()
        val sh = openSsh(host, port, auth, knownHostKey, backend.cols, backend.rows)
        shell = sh
        connected = true
        title = "${auth.user}@$host"
        terminalKeepAlive(true, title)
        outbox = Channel(Channel.UNLIMITED)
        val box = outbox
        // the grid may have changed while connecting
        box.trySend(Outgoing.Resize(backend.cols, backend.rows))
        writeJob = scope.launch(ioDispatcher) {
            for (msg in box) runCatching {
                when (msg) {
                    is Outgoing.Data -> sh.write(msg.bytes)
                    is Outgoing.Resize -> sh.resize(msg.cols, msg.rows)
                }
            }
        }
        readJob = scope.launch(ioDispatcher) {
            val buf = ByteArray(32 * 1024)
            while (true) {
                val n = sh.read(buf)
                if (n < 0) break
                if (n > 0) backend.write(buf.copyOf(n))
            }
            // connection ended by the server (not by close())
            withContext(Dispatchers.Main) {
                if (shell === sh) {
                    shell = null
                    connected = false
                    terminalKeepAlive(false, "")
                    runCatching { backend.write("\r\n\u001b[33m[Verbindung beendet]\u001b[0m\r\n".encodeToByteArray()) }
                    scope.launch(ioDispatcher) { runCatching { sh.close() } }
                }
            }
        }
    }

    fun send(text: String) = send(text.encodeToByteArray())

    fun send(data: ByteArray) {
        if (connected) outbox.trySend(Outgoing.Data(data))
    }

    /**
     * Pastes text. Trailing line breaks are removed so nothing runs by itself:
     * with bracketed paste (bash default) all lines land in the input line and
     * one final Enter executes them.
     */
    fun paste(text: String) {
        val t = text.replace("\r\n", "\n").trimEnd('\n', '\r')
        if (t.isNotEmpty() && connected) backend.paste(t)
    }

    fun close() {
        val sh = shell
        shell = null
        readJob?.cancel()
        writeJob?.cancel()
        outbox.close()
        if (connected) terminalKeepAlive(false, "")
        connected = false
        if (sh != null) scope.launch(ioDispatcher) { runCatching { sh.close() } }
    }

    /** Close and release the emulator. */
    fun dispose() {
        close()
        backend.dispose()
    }
}
