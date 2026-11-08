package app.servercontrol

import androidx.compose.runtime.Composable
import io.ktor.client.HttpClient

/** Encrypted storage of the profile vault (JSON). */
interface SecureStore {
    /** null = nothing stored yet */
    suspend fun load(): String?
    suspend fun save(plain: String)
}

/** Controls the WireGuard tunnel. */
interface VpnController {
    val description: String
    /** true if the platform can switch the tunnel for this profile */
    fun isAvailable(tunnel: String, upCmd: String, downCmd: String): Boolean
    suspend fun up(tunnel: String, upCmd: String): Result<Unit>
    suspend fun down(tunnel: String, downCmd: String): Result<Unit>
}

/**
 * HTTP client that only trusts the agent certificate with [pinnedFingerprint].
 * If [pinnedFingerprint] is empty, any certificate is accepted once and its
 * fingerprint is reported through [onCertificate] so the user can confirm it (TOFU).
 */
expect fun createHttpClient(pinnedFingerprint: String, onCertificate: (String) -> Unit): HttpClient

/**
 * Username/password inputs. Android uses native EditTexts with autofill hints
 * (KeePassDX / Keepass2Android autofill), desktop uses normal fields that work
 * with KeePassXC Auto-Type.
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
