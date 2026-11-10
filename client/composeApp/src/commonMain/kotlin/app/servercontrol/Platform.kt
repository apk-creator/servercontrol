package app.servercontrol

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient

/** Encrypted storage of the profile vault (JSON). */
interface SecureStore {
    /** null = nothing stored yet */
    suspend fun load(): String?
    suspend fun save(plain: String)
}

/**
 * HTTP client that only trusts the agent certificate with [pinnedFingerprint].
 * If [pinnedFingerprint] is empty, any certificate is accepted once and its
 * fingerprint is reported through [onCertificate] so the user can confirm it (TOFU).
 */
expect fun createHttpClient(pinnedFingerprint: String, onCertificate: (String) -> Unit): HttpClient

/**
 * Username/password inputs. Android uses native EditTexts with autofill hints
 * (password managers can fill them), desktop uses normal fields with Tab/Enter handling.
 */
@Composable
expect fun CredentialFields(
    username: String,
    onUsername: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
)

expect val isDesktop: Boolean

// ---------------- SSH ----------------

/** An interactive SSH shell (PTY). */
interface SshShell {
    /** Blocks until data arrives; -1 = closed */
    suspend fun read(buf: ByteArray): Int
    suspend fun write(data: ByteArray)
    fun resize(cols: Int, rows: Int)
    fun close()
}

class SshAuth(val user: String, val password: String?, val privateKey: String?, val passphrase: String?)

/** Host key not yet trusted – ask the user, then reconnect with [fingerprint]. */
class SshHostKeyUnknown(val fingerprint: String, val keyType: String) : Exception("Unbekannter Hostschlüssel $keyType $fingerprint")

/** Host key differs from the pinned one – possible man-in-the-middle. */
class SshHostKeyChanged(val fingerprint: String) : Exception("Der SSH-Hostschlüssel des Servers hat sich geändert ($fingerprint)! Verbindung abgebrochen.")

/** Opens an SSH session with a PTY. [knownHostKey] = "SHA256:…" fingerprint, empty on first use. */
expect suspend fun openSsh(host: String, port: Int, auth: SshAuth, knownHostKey: String, cols: Int, rows: Int): SshShell

/** Single secret input (API key, repository password, token) – autofill-capable like [CredentialFields]. */
@Composable
expect fun SecretField(value: String, onValue: (String) -> Unit, label: String)

/** Lets the user pick a file; returns its content or null. */
expect suspend fun pickFile(): ByteArray?

/**
 * Keeps an SSH session alive while the app is in the background.
 * Android: foreground service with a "Verbunden" notification; desktop: nothing to do.
 */
expect fun terminalKeepAlive(active: Boolean, title: String)

/** Dispatcher for blocking I/O (network, files). */
expect val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher

/** Text of the last crash (Android), shown once on the next start. */
expect fun readCrashReport(): String?
expect fun clearCrashReport()

// ---------------- SSH key generation (modelled on ConnectBot's GeneratePubkey) ----------------

enum class SshKeyAlgo(val label: String) { ED25519("Ed25519"), ECDSA("ECDSA"), RSA("RSA") }

class GeneratedSshKey(
    /** OpenSSH private key ("-----BEGIN OPENSSH PRIVATE KEY-----"), unencrypted – stored in the encrypted vault */
    val privateKey: String,
    /** authorized_keys line: "<type> <base64> <comment>" */
    val publicKey: String,
    /** "SHA256:…" like ssh-keygen -l */
    val fingerprint: String,
)

/** [bits]: RSA 2048–8192, ECDSA 256/384/521, ignored for Ed25519. */
expect suspend fun generateSshKey(algo: SshKeyAlgo, bits: Int, comment: String): GeneratedSshKey
