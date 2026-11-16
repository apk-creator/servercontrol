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

/** font of the UI (desktop: the system UI font of Windows); null = Compose default */
expect val appFontFamily: androidx.compose.ui.text.font.FontFamily?

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

/** Uploads [files] into a private temp folder and runs [script] there with sudo; returns exit code and output. */
expect suspend fun sshSudoScript(
    host: String, port: Int, auth: SshAuth, knownHostKey: String, sudoPassword: String?,
    files: Map<String, ByteArray>, script: String,
): Pair<Int, String>

expect suspend fun tcpReachable(host: String, port: Int, timeoutMs: Int): Boolean

/** agent package of this app version (servercontrol-agent.tar.gz): bundled (desktop) or from the GitHub release */
expect suspend fun agentPackage(): ByteArray

/** GitHub repository with the releases */
const val APP_REPO = "apk-creator/servercontrol"

/** Single secret input (API key, repository password, token) – autofill-capable like [CredentialFields]. */
@Composable
expect fun SecretField(value: String, onValue: (String) -> Unit, label: String)

/** Lets the user pick a file; returns its content or null. */
expect suspend fun pickFile(): ByteArray?

/** Picks a file: (file name, content) or null when cancelled. */
expect suspend fun pickNamedFile(): Pair<String, ByteArray>?

/**
 * Asks where to save [name] and streams the content through [write] into it.
 * Returns where it went (for a message) or null when cancelled.
 */
expect suspend fun saveFile(name: String, write: suspend (sink: (ByteArray, Int) -> Unit) -> Unit): String?

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

// ---------------- app self-update (package comes from the release via the agent) ----------------

/** Name pattern of this platform's package in a release (null = no in-app update). */
expect val appUpdateAssetPattern: Regex?

/**
 * Downloads [assetName] through the agent and starts the platform installer.
 * Returns a message for the user.
 */
expect suspend fun installAppUpdate(
    api: app.servercontrol.net.ApiClient,
    certFingerprint: String,
    assetName: String,
    onProgress: (Long, Long) -> Unit,
): String

/** Name under which this installation appears in the agent's device list. */
expect val deviceName: String

/** public key line + SHA256 fingerprint of a private key (null: unreadable or wrong passphrase) */
expect fun sshPublicKeyOf(privateKey: String, passphrase: String?): Pair<String, String>?

/** pasted keys: Windows line endings and stray spaces break the key parser */
fun normalizeKey(text: String): String =
    text.replace("\r\n", "\n").replace('\r', '\n').lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n") + "\n"
