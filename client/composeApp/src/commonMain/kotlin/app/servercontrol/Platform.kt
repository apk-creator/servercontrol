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
