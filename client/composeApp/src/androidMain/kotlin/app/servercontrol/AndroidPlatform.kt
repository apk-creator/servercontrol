package app.servercontrol

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.text.InputType
import android.text.method.HideReturnsTransformationMethod
import android.text.method.PasswordTransformationMethod
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
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

// ---------------- credentials (native fields with Android autofill hints) ----------------

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
    var visible by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { ctx ->
                val user = EditText(ctx).apply {
                    hint = "Benutzername"
                    setSingleLine(true)
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                    setAutofillHints(View.AUTOFILL_HINT_USERNAME)
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
                    imeOptions = EditorInfo.IME_ACTION_NEXT
                }
                val pass = passwordEditText(ctx, "Passwort").apply { imeOptions = EditorInfo.IME_ACTION_DONE }
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
                setMasked(pass, !visible)
            },
        )
        EyeButton(visible) { visible = !visible }
    }
}

/** EditText for secrets. Masking must be applied AFTER setSingleLine (which resets the transformation). */
private fun passwordEditText(ctx: Context, label: String) = EditText(ctx).apply {
    hint = label
    setSingleLine(true)
    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    transformationMethod = PasswordTransformationMethod.getInstance()
    setAutofillHints(View.AUTOFILL_HINT_PASSWORD)
    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
}

private fun setMasked(et: EditText, masked: Boolean) {
    val want = if (masked) PasswordTransformationMethod.getInstance() else HideReturnsTransformationMethod.getInstance()
    if (et.transformationMethod !== want) {
        val sel = et.selectionEnd
        et.transformationMethod = want
        et.setSelection(sel.coerceIn(0, et.text.length))
    }
}

@Composable
private fun EyeButton(visible: Boolean, toggle: () -> Unit) {
    IconButton(onClick = toggle) {
        Icon(
            if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            if (visible) "Passwort verbergen" else "Passwort anzeigen",
        )
    }
}

private class SimpleWatcher(val cb: (String) -> Unit) : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = cb(s?.toString() ?: "")
}

@Composable
actual fun SecretField(value: String, onValue: (String) -> Unit, label: String) {
    val cb = rememberUpdatedState(onValue)
    var visible by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        AndroidView(
            modifier = Modifier.weight(1f),
            factory = { ctx -> passwordEditText(ctx, label).apply { addTextChangedListener(SimpleWatcher { cb.value(it) }) } },
            update = { et ->
                if (et.text.toString() != value) et.setText(value)
                setMasked(et, !visible)
            },
        )
        EyeButton(visible) { visible = !visible }
    }
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
