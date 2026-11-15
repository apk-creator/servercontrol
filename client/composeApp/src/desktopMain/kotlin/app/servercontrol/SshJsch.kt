package app.servercontrol

import com.jcraft.jsch.ChannelShell
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.JSchException
import com.jcraft.jsch.Session
import com.jcraft.jsch.UIKeyboardInteractive
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Base64

// NOTE: identical file in androidMain and desktopMain (both use JSch on the JVM).

private fun keyType(blob: ByteArray): String = runCatching {
    val len = ((blob[0].toInt() and 0xff) shl 24) or ((blob[1].toInt() and 0xff) shl 16) or
        ((blob[2].toInt() and 0xff) shl 8) or (blob[3].toInt() and 0xff)
    String(blob, 4, len, Charsets.US_ASCII)
}.getOrDefault("?")

private class JschShell(
    private val session: Session,
    private val channel: ChannelShell,
    private val input: InputStream,
    private val output: OutputStream,
) : SshShell {
    override suspend fun read(buf: ByteArray): Int = withContext(Dispatchers.IO) {
        try { input.read(buf) } catch (e: Exception) { -1 }
    }

    override suspend fun write(data: ByteArray) = withContext(Dispatchers.IO) {
        output.write(data)
        output.flush()
    }

    override fun resize(cols: Int, rows: Int) {
        runCatching { channel.setPtySize(cols, rows, cols * 8, rows * 16) }
    }

    override fun close() {
        runCatching { channel.disconnect() }
        runCatching { session.disconnect() }
    }
}

private fun jschConnect(host: String, port: Int, auth: SshAuth, knownHostKey: String): Session {
        val jsch = JSch()
        var seenFp = ""
        var seenType = ""
        jsch.hostKeyRepository = object : HostKeyRepository {
            override fun check(host: String?, key: ByteArray?): Int {
                val k = key ?: return HostKeyRepository.NOT_INCLUDED
                seenFp = "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(k))
                seenType = keyType(k)
                return when {
                    knownHostKey.isBlank() -> HostKeyRepository.NOT_INCLUDED
                    seenFp == knownHostKey.trim() -> HostKeyRepository.OK
                    else -> HostKeyRepository.CHANGED
                }
            }
            override fun add(hostkey: HostKey?, ui: UserInfo?) {}
            override fun remove(host: String?, type: String?) {}
            override fun remove(host: String?, type: String?, key: ByteArray?) {}
            override fun getKnownHostsRepositoryID(): String = "servercontrol"
            override fun getHostKey(): Array<HostKey> = emptyArray()
            override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
        }
        if (!auth.privateKey.isNullOrBlank()) {
            jsch.addIdentity("servercontrol", auth.privateKey.toByteArray(), null, auth.passphrase?.takeIf { it.isNotEmpty() }?.toByteArray())
        }
        val session = jsch.getSession(auth.user, host, port)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.setConfig("PreferredAuthentications", "publickey,keyboard-interactive,password")
        auth.password?.takeIf { it.isNotEmpty() }?.let { session.setPassword(it) }
        session.userInfo = object : UserInfo, UIKeyboardInteractive {
            override fun getPassphrase(): String? = auth.passphrase
            override fun getPassword(): String? = auth.password
            override fun promptPassword(message: String?) = auth.password != null
            override fun promptPassphrase(message: String?) = auth.passphrase != null
            override fun promptYesNo(message: String?) = false
            override fun showMessage(message: String?) {}
            override fun promptKeyboardInteractive(
                destination: String?, name: String?, instruction: String?, prompt: Array<out String>?, echo: BooleanArray?,
            ): Array<String>? = if (auth.password == null) null else Array(prompt?.size ?: 0) { auth.password }
        }
        session.serverAliveInterval = 30_000
        try {
            session.connect(15_000)
        } catch (e: JSchException) {
            val m = e.message ?: ""
            when {
                m.contains("reject HostKey") && knownHostKey.isBlank() && seenFp.isNotEmpty() -> throw SshHostKeyUnknown(seenFp, seenType)
                m.contains("HostKey has been changed") || (seenFp.isNotEmpty() && knownHostKey.isNotBlank() && seenFp != knownHostKey.trim()) ->
                    throw SshHostKeyChanged(seenFp)
                m.contains("Auth fail") || m.contains("Auth cancel") -> throw Exception("Anmeldung abgelehnt – Benutzername/Passwort oder Schlüssel prüfen")
                else -> throw Exception("SSH-Verbindung fehlgeschlagen: $m", e)
            }
        }
        return session
}

actual suspend fun openSsh(host: String, port: Int, auth: SshAuth, knownHostKey: String, cols: Int, rows: Int): SshShell =
    withContext(Dispatchers.IO) {
        val session = jschConnect(host, port, auth, knownHostKey)
        val channel = session.openChannel("shell") as ChannelShell
        channel.setPtyType("xterm-256color")
        channel.setPtySize(cols, rows, cols * 8, rows * 16)
        channel.setEnv("LANG", "C.UTF-8")
        val input = channel.inputStream
        val output = channel.outputStream
        channel.connect(10_000)
        JschShell(session, channel, input, output)
    }

/** Uploads [files] into a fresh private temp folder and runs [script] there with sudo (agent installation). */
actual suspend fun sshSudoScript(
    host: String, port: Int, auth: SshAuth, knownHostKey: String, sudoPassword: String?,
    files: Map<String, ByteArray>, script: String,
): Pair<Int, String> = withContext(Dispatchers.IO) {
    val session = jschConnect(host, port, auth, knownHostKey)
    val dir = "/tmp/servercontrol-install-" + java.util.UUID.randomUUID().toString().take(8)
    fun exec(cmd: String, stdin: String?): Pair<Int, String> {
        val ch = session.openChannel("exec") as com.jcraft.jsch.ChannelExec
        ch.setCommand(cmd)
        val out = ch.inputStream
        val inp = ch.outputStream
        ch.connect(15_000)
        if (stdin != null) inp.write(stdin.toByteArray())
        inp.flush()
        inp.close()
        val text = out.readBytes().decodeToString()
        var waited = 0
        while (!ch.isClosed && waited < 100) { Thread.sleep(50); waited++ }
        val code = ch.exitStatus
        ch.disconnect()
        return code to text
    }
    try {
        val sftp = session.openChannel("sftp") as com.jcraft.jsch.ChannelSftp
        sftp.connect(15_000)
        sftp.mkdir(dir)
        sftp.chmod("700".toInt(8), dir)
        (files + ("install.sh" to script.toByteArray())).forEach { (name, data) ->
            sftp.put(java.io.ByteArrayInputStream(data), "$dir/$name")
            sftp.chmod("600".toInt(8), "$dir/$name")
        }
        sftp.disconnect()
        val cmd = if (auth.user == "root") "sh $dir/install.sh 2>&1" else "sudo -S -p '' sh $dir/install.sh 2>&1"
        exec(cmd, (sudoPassword ?: "") + "\n")
    } finally {
        // the folder holds the agent password: remove it in any case
        runCatching { exec("rm -rf $dir", null) }
        session.disconnect()
    }
}

actual suspend fun tcpReachable(host: String, port: Int, timeoutMs: Int): Boolean = withContext(Dispatchers.IO) {
    runCatching { java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), timeoutMs) } }.isSuccess
}
