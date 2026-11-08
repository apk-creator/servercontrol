package app.servercontrol

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

actual val isDesktop: Boolean = false

// ---------------- encrypted profile storage (Android Keystore, AES-256-GCM) ----------------

class AndroidSecureStore(context: Context) : SecureStore {
    private val file = File(context.filesDir, "vault.enc")
    private val alias = "servercontrol-vault"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    override suspend fun load(): String? = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext null
        val data = file.readBytes()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
    }

    override suspend fun save(plain: String) = withContext(Dispatchers.IO) {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val out = c.iv + c.doFinal(plain.toByteArray(Charsets.UTF_8))
        val tmp = File(file.parentFile, "vault.enc.tmp")
        tmp.writeBytes(out)
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        Unit
    }
}

// ---------------- WireGuard (official app, remote control intents) ----------------

class AndroidVpn(private val context: Context) : VpnController {
    override val description =
        "Name des Tunnels in der WireGuard-App. Dort unter Einstellungen „Fernsteuerung durch Apps erlauben“ aktivieren."

    private fun installed() = runCatching {
        context.packageManager.getPackageInfo(WG_PACKAGE, 0); true
    }.getOrDefault(false)

    fun hasPermission() = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    override fun isAvailable(tunnel: String, upCmd: String, downCmd: String) = tunnel.isNotBlank() && installed()

    private fun send(action: String, tunnel: String): Result<Unit> = runCatching {
        if (!installed()) error("WireGuard-App ist nicht installiert")
        if (!hasPermission()) error("Berechtigung zur WireGuard-Steuerung fehlt (App-Einstellungen → Berechtigungen)")
        context.sendBroadcast(Intent(action).setPackage(WG_PACKAGE).putExtra("tunnel", tunnel))
    }

    override suspend fun up(tunnel: String, upCmd: String) = send("$WG_PACKAGE.action.SET_TUNNEL_UP", tunnel)
    override suspend fun down(tunnel: String, downCmd: String) = send("$WG_PACKAGE.action.SET_TUNNEL_DOWN", tunnel)

    companion object {
        const val WG_PACKAGE = "com.wireguard.android"
        const val PERMISSION = "com.wireguard.android.permission.CONTROL_TUNNELS"
    }
}

// ---------------- pinned TLS ----------------

internal fun sha256Fingerprint(cert: X509Certificate): String =
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
        .hostnameVerifier { _, _ -> true } // identity is guaranteed by the pinned fingerprint
        .build()
    return HttpClient(OkHttp) {
        engine { preconfigured = ok }
        app.servercontrol.net.ApiClient.configure(this)
    }
}

// ---------------- credentials with Android autofill (KeePassDX, Keepass2Android) ----------------

@Composable
actual fun CredentialFields(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val onU = rememberUpdatedState(onUsername)
    val onP = rememberUpdatedState(onPassword)
    val submit = rememberUpdatedState(onSubmit)
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            fun field(hint: String, autofill: String, type: Int) = EditText(ctx).apply {
                this.hint = hint
                inputType = type
                setSingleLine(true)
                setAutofillHints(autofill)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            }
            val user = field("Benutzername", View.AUTOFILL_HINT_USERNAME, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
            val pass = field("Passwort", View.AUTOFILL_HINT_PASSWORD, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            user.imeOptions = EditorInfo.IME_ACTION_NEXT
            pass.imeOptions = EditorInfo.IME_ACTION_DONE
            user.addTextChangedListener(SimpleWatcher { onU.value(it) })
            pass.addTextChangedListener(SimpleWatcher { onP.value(it) })
            pass.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { submit.value(); true } else false
            }
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_AUTO
                addView(user, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                addView(pass, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                tag = user to pass
            }
        },
        update = { layout ->
            val pair = layout.tag as Pair<*, *>
            val user = pair.first as EditText
            val pass = pair.second as EditText
            if (user.text.toString() != username) user.setText(username)
            if (pass.text.toString() != password) pass.setText(password)
        },
    )
}

private class SimpleWatcher(val cb: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = cb(s?.toString() ?: "")
}

@Composable
actual fun SecretField(value: String, onValue: (String) -> Unit, label: String) {
    val cb = rememberUpdatedState(onValue)
    AndroidView(
        modifier = Modifier.fillMaxWidth(),
        factory = { ctx ->
            EditText(ctx).apply {
                hint = label
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                setSingleLine(true)
                setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                addTextChangedListener(SimpleWatcher { cb.value(it) })
            }
        },
        update = { et -> if (et.text.toString() != value) et.setText(value) },
    )
}

/** Bridge to the activity's file picker (registered in MainActivity). */
object FilePicker {
    var launcher: androidx.activity.result.ActivityResultLauncher<String>? = null
    var pending: kotlinx.coroutines.CompletableDeferred<android.net.Uri?>? = null
    lateinit var context: Context
}

actual suspend fun pickFile(): ByteArray? {
    val l = FilePicker.launcher ?: return null
    val d = kotlinx.coroutines.CompletableDeferred<android.net.Uri?>()
    FilePicker.pending = d
    l.launch("*/*")
    val uri = d.await() ?: return null
    return withContext(Dispatchers.IO) {
        FilePicker.context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
    }
}
