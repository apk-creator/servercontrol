package app.servercontrol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
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

// Windows: the system UI font (Compose's fallback font looks odd there)
actual val appFontFamily: androidx.compose.ui.text.font.FontFamily? = if (isWindows) androidx.compose.ui.text.font.FontFamily(
    androidx.compose.ui.text.platform.SystemFont("Segoe UI", androidx.compose.ui.text.font.FontWeight.Normal),
    androidx.compose.ui.text.platform.SystemFont("Segoe UI Semibold", androidx.compose.ui.text.font.FontWeight.SemiBold),
    androidx.compose.ui.text.platform.SystemFont("Segoe UI", androidx.compose.ui.text.font.FontWeight.Bold),
) else null

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

// ---------------- pinned TLS ----------------

private fun sha256Fingerprint(cert: X509Certificate): String =
    MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString(":") { "%02X".format(it) }

internal fun pinnedOkHttp(pinnedFingerprint: String, onCertificate: (String) -> Unit): OkHttpClient {
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
    return OkHttpClient.Builder()
        .sslSocketFactory(ssl.socketFactory, tm)
        .hostnameVerifier { _, _ -> true }
        .build()
}

actual fun createHttpClient(pinnedFingerprint: String, onCertificate: (String) -> Unit): HttpClient {
    val ok = pinnedOkHttp(pinnedFingerprint, onCertificate)
    return HttpClient(OkHttp) {
        engine { preconfigured = ok }
        app.servercontrol.net.ApiClient.configure(this)
    }
}

// ---------------- credentials (Tab moves to the next field, Enter submits) ----------------

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
        PasswordInput(password, onPassword, "Passwort", Modifier.fillMaxWidth().then(keys))
    }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
}

@Composable
actual fun SecretField(value: String, onValue: (String) -> Unit, label: String) {
    PasswordInput(value, onValue, label, Modifier.fillMaxWidth())
}

/** Masked text field with an eye button to reveal the input. */
@Composable
fun PasswordInput(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value, onValue, modifier,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                    if (visible) "Passwort verbergen" else "Passwort anzeigen",
                )
            }
        },
    )
}

actual suspend fun pickFile(): ByteArray? {
    val path = withContext(Dispatchers.Main) {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "Datei wählen", java.awt.FileDialog.LOAD)
        d.isVisible = true
        if (d.file == null) null else File(d.directory, d.file)
    } ?: return null
    return withContext(Dispatchers.IO) { path.readBytes() }
}

actual suspend fun pickNamedFile(): Pair<String, ByteArray>? {
    val f = withContext(Dispatchers.Main) {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "Datei hochladen", java.awt.FileDialog.LOAD)
        d.isVisible = true
        if (d.file == null) null else File(d.directory, d.file)
    } ?: return null
    return withContext(Dispatchers.IO) { f.name to f.readBytes() }
}

actual suspend fun saveFile(name: String, write: suspend (sink: (ByteArray, Int) -> Unit) -> Unit): String? {
    val f = withContext(Dispatchers.Main) {
        val d = java.awt.FileDialog(null as java.awt.Frame?, "Speichern unter", java.awt.FileDialog.SAVE)
        d.file = name
        d.isVisible = true
        if (d.file == null) null else File(d.directory, d.file)
    } ?: return null
    withContext(Dispatchers.IO) {
        f.outputStream().buffered(256 * 1024).use { out -> write { b, n -> out.write(b, 0, n) } }
    }
    return f.absolutePath
}

/** Desktop: the session stays connected as long as the window is open. */
actual fun terminalKeepAlive(active: Boolean, title: String) {}

actual val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

actual fun readCrashReport(): String? = null
actual fun clearCrashReport() {}

// ---------------- app self-update ----------------

actual val appUpdateAssetPattern: Regex? = when {
    isWindows -> Regex("""^ServerControl-[0-9.]+\.msi$""")
    osName.contains("linux") -> Regex("""^servercontrol_[0-9.]+-[0-9]+_amd64\.deb$""")
    else -> null
}

actual suspend fun installAppUpdate(api: app.servercontrol.net.ApiClient, certFingerprint: String, assetName: String, onProgress: (Long, Long) -> Unit): String {
    val file = File(System.getProperty("java.io.tmpdir"), assetName)
    fastDownload(api, certFingerprint, "/api/agent/release/asset?name=$assetName", file, onProgress)
    return if (isWindows) {
        // the installer replaces the program files, so ServerControl closes itself
        ProcessBuilder("msiexec", "/i", file.absolutePath, "/passive").start()
        Thread {
            Thread.sleep(1500)
            kotlin.system.exitProcess(0)
        }.start()
        "Installer gestartet – ServerControl wird beendet. Nach der Installation bitte neu starten."
    } else {
        ProcessBuilder("xdg-open", file.absolutePath).start()
        "Paket heruntergeladen (${file.absolutePath}) und an die Softwareverwaltung übergeben."
    }
}

// ---------------- fast download (app update) ----------------

/** Downloads an agent path straight to [file] with large buffers; progress at most 4×/s. */
internal suspend fun fastDownload(
    api: app.servercontrol.net.ApiClient,
    certFingerprint: String,
    path: String,
    file: File,
    onProgress: (Long, Long) -> Unit,
) = withContext(Dispatchers.IO) {
    val client = pinnedOkHttp(certFingerprint, {}).newBuilder()
        .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(30, java.util.concurrent.TimeUnit.MINUTES)
        .build()
    fun call() = client.newCall(
        okhttp3.Request.Builder().url(api.baseUrl.trimEnd('/') + path)
            .apply { api.token?.let { header("Authorization", "Bearer $it") } }
            .build(),
    ).execute()
    var resp = call()
    if (resp.code == 401) {
        resp.close()
        if (!api.renew()) throw app.servercontrol.net.ApiException(401, "Sitzung abgelaufen")
        resp = call()
    }
    resp.use { r ->
        if (!r.isSuccessful) throw app.servercontrol.net.ApiException(r.code, "Download fehlgeschlagen (HTTP ${r.code})")
        val body = r.body ?: throw app.servercontrol.net.ApiException(r.code, "leere Antwort")
        val total = body.contentLength()
        val tmp = File(file.parentFile, file.name + ".part")
        body.byteStream().use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                var last = 0L
                while (true) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = System.currentTimeMillis()
                    if (now - last >= 250) { last = now; onProgress(done, total) }
                }
                onProgress(done, total)
            }
        }
        if (total > 0 && tmp.length() != total) throw app.servercontrol.net.ApiException(0, "Download unvollständig")
        file.delete()
        if (!tmp.renameTo(file)) throw app.servercontrol.net.ApiException(0, "Datei konnte nicht gespeichert werden")
    }
}

actual val deviceName: String = runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrNull()
    ?.takeIf { it.isNotBlank() }?.let { "$it (${System.getProperty("os.name")})" } ?: System.getProperty("os.name")

actual suspend fun agentPackage(): ByteArray = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching { object {}.javaClass.getResourceAsStream("/agent/servercontrol-agent.tar.gz")?.use { it.readBytes() } }.getOrNull()
        ?: downloadAgentRelease()
}

/** Agent package of this app version from the public GitHub release, checked against GitHub's SHA-256. */
internal fun downloadAgentRelease(): ByteArray {
    fun get(url: String, accept: String): java.net.HttpURLConnection =
        (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 15_000; readTimeout = 60_000; instanceFollowRedirects = true
            setRequestProperty("Accept", accept); setRequestProperty("User-Agent", "ServerControl/$APP_VERSION")
        }
    val api = get("https://api.github.com/repos/$APP_REPO/releases/tags/v$APP_VERSION", "application/vnd.github+json")
    if (api.responseCode != 200) throw Exception("Release v$APP_VERSION nicht gefunden (HTTP ${api.responseCode})")
    val rel = app.servercontrol.data.AppJson.parseToJsonElement(api.inputStream.use { it.readBytes().decodeToString() })
    val asset = (rel as kotlinx.serialization.json.JsonObject)["assets"]!!.let { it as kotlinx.serialization.json.JsonArray }
        .map { it as kotlinx.serialization.json.JsonObject }
        .firstOrNull { (it["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "servercontrol-agent.tar.gz" }
        ?: throw Exception("Release v$APP_VERSION enthält kein Agent-Paket")
    val url = (asset["browser_download_url"] as kotlinx.serialization.json.JsonPrimitive).content
    val digest = (asset["digest"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.removePrefix("sha256:")
        ?: throw Exception("Release ohne Prüfsumme")
    val conn = get(url, "application/octet-stream")
    if (conn.responseCode != 200) throw Exception("Download fehlgeschlagen (HTTP ${conn.responseCode})")
    val data = conn.inputStream.use { it.readBytes() }
    val sum = java.security.MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    if (!sum.equals(digest, ignoreCase = true)) throw Exception("Prüfsumme des Agent-Pakets stimmt nicht")
    return data
}

