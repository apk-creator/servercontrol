package app.servercontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

actual val isDesktop: Boolean = true

val osName: String = System.getProperty("os.name").lowercase()
val isWindows = osName.contains("win")

// ---------------- encrypted vault with master password (PBKDF2 + AES-256-GCM) ----------------

class WrongPassphraseException : Exception("Falsches Master-Passwort")

class DesktopSecureStore(private val passphrase: CharArray) : SecureStore {
    companion object {
        val dir: File = File(System.getProperty("user.home"), if (isWindows) "AppData/Roaming/ServerControl" else ".config/servercontrol")
        val file = File(dir, "vault.enc")
        private val MAGIC = "SCV1".toByteArray()
        private const val ITER = 310_000
        fun exists() = file.exists()
    }

    private fun derive(salt: ByteArray): SecretKeySpec {
        val f = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val k = f.generateSecret(PBEKeySpec(passphrase, salt, ITER, 256)).encoded
        return SecretKeySpec(k, "AES")
    }

    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val d = file.readBytes()
        require(d.size > 32 && d.copyOfRange(0, 4).contentEquals(MAGIC)) { "Unbekanntes Dateiformat" }
        val salt = d.copyOfRange(4, 20)
        val iv = d.copyOfRange(20, 32)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, derive(salt), GCMParameterSpec(128, iv))
        try {
            String(c.doFinal(d, 32, d.size - 32), Charsets.UTF_8)
        } catch (e: AEADBadTagException) {
            throw WrongPassphraseException()
        }
    }

    override suspend fun save(plain: String) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val rnd = SecureRandom()
        val salt = ByteArray(16).also(rnd::nextBytes)
        val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, derive(salt), GCMParameterSpec(128, iv))
        val out = MAGIC + salt + iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val tmp = File(dir, "vault.enc.tmp")
        tmp.writeBytes(out)
        runCatching { Files.setPosixFilePermissions(tmp.toPath(), PosixFilePermissions.fromString("rw-------")) }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        Unit
    }
}

// ---------------- WireGuard via configurable commands ----------------

class DesktopVpn : VpnController {
    private val defaultUp: String
    private val defaultDown: String

    init {
        if (isWindows) {
            val wg = "\"C:\\Program Files\\WireGuard\\wireguard.exe\""
            defaultUp = "$wg /installtunnelservice \"C:\\Program Files\\WireGuard\\Data\\Configurations\\{tunnel}.conf.dpapi\""
            defaultDown = "$wg /uninstalltunnelservice {tunnel}"
        } else if (osName.contains("linux")) {
            defaultUp = "nmcli connection up {tunnel}"
            defaultDown = "nmcli connection down {tunnel}"
        } else {
            defaultUp = ""
            defaultDown = ""
        }
    }

    override val description = when {
        isWindows -> "Tunnelname aus der WireGuard-App. Windows: ServerControl muss dafür als Administrator laufen (oder eigene Befehle eintragen)."
        osName.contains("linux") -> "NetworkManager-Verbindungsname (nmcli connection import type wireguard file wg0.conf)."
        else -> "Eigene Befehle unten eintragen."
    }

    override fun isAvailable(tunnel: String, upCmd: String, downCmd: String) =
        tunnel.isNotBlank() && (upCmd.isNotBlank() || defaultUp.isNotBlank())

    private suspend fun exec(template: String, tunnel: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            require(tunnel.matches(Regex("^[A-Za-z0-9_.=+-]{1,32}$"))) { "Ungültiger Tunnelname" }
            val args = tokenize(template.replace("{tunnel}", tunnel))
            require(args.isNotEmpty()) { "Kein Befehl konfiguriert" }
            val p = ProcessBuilder(args).redirectErrorStream(true).start()
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); error("Zeitüberschreitung") }
            val out = p.inputStream.bufferedReader().readText()
            if (p.exitValue() != 0) error("Exit ${p.exitValue()}: ${out.take(400)}")
        }
    }

    override suspend fun up(tunnel: String, upCmd: String) = exec(upCmd.ifBlank { defaultUp }, tunnel)
    override suspend fun down(tunnel: String, downCmd: String) = exec(downCmd.ifBlank { defaultDown }, tunnel)
}

/** Splits a command line, honouring double quotes. */
fun tokenize(cmd: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quoted = false
    var has = false
    for (ch in cmd) {
        when {
            ch == '"' -> { quoted = !quoted; has = true }
            ch.isWhitespace() && !quoted -> { if (has) { out += cur.toString(); cur.clear(); has = false } }
            else -> { cur.append(ch); has = true }
        }
    }
    if (has) out += cur.toString()
    return out
}

// ---------------- pinned TLS ----------------

private fun sha256Fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

actual fun createHttpClient(pinnedFingerprint: String, onCertificate: (String) -> Unit): HttpClient {
    val pin = pinnedFingerprint.trim().uppercase()
    val tm = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            throw CertificateException("client certificates not supported")
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("no certificate")
            val fp = sha256Fingerprint(leaf)
            onCertificate(fp)
            if (pin.isNotEmpty() && fp != pin) throw CertificateException("PIN_MISMATCH:$fp")
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), SecureRandom()) }
    val ok = OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, tm)
        .hostnameVerifier { _, _ -> true }
        .build()
    return HttpClient(OkHttp) {
        engine { preconfigured = ok }
        app.servercontrol.net.ApiClient.configure(this)
    }
}

// ---------------- credentials (KeePassXC Auto-Type: {USERNAME}{TAB}{PASSWORD}{ENTER}) ----------------

@Composable
actual fun CredentialFields(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val focus = LocalFocusManager.current
    val first = remember { FocusRequester() }
    val keys = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (e.key) {
            Key.Tab -> { focus.moveFocus(FocusDirection.Next); true }
            Key.Enter, Key.NumPadEnter -> { onSubmit(); true }
            else -> false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(username, onUsername, Modifier.fillMaxWidth().focusRequester(first).then(keys),
            label = { Text("Benutzername") }, singleLine = true)
        OutlinedTextField(password, onPassword, Modifier.fillMaxWidth().then(keys),
            label = { Text("Passwort") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}
