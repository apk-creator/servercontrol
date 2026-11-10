package app.servercontrol.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    explicitNulls = false
    coerceInputValues = true
    encodeDefaults = true
}

/**
 * One server connection. Everything in here is stored encrypted on the device.
 * Passwords are never stored.
 */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    /** Agent URL, e.g. https://192.168.1.10:8443 (from outside: connect the Router VPN first) */
    val lanUrl: String,
    /** SHA-256 fingerprint of the agent certificate (pinned after first trust) */
    val certFingerprint: String = "",
    /** SSH terminal (host = same address as the agent) */
    val sshPort: Int = 22,
    val sshUser: String = "",
    /** pinned SSH host key, "SHA256:…" */
    val sshHostKey: String = "",
    val sshHostKeyType: String = "",
    /** optional private key (OpenSSH/PEM), stored encrypted like everything else in the vault */
    val sshPrivateKey: String = "",
    /** public key line of a key generated in the app (for authorized_keys) */
    val sshPublicKey: String = "",
    val sshKeyFingerprint: String = "",
)

@Serializable
data class ProfileVault(
    val profiles: List<ServerProfile> = emptyList(),
    val lastProfileId: String? = null,
    /** order of the side menu (Section names), changed by drag & drop */
    val sectionOrder: List<String> = emptyList(),
)

// ---------------- API DTOs ----------------

@Serializable
data class LoginResponse(
    val token: String,
    val idleMinutes: Int = 30,
    val modules: Map<String, Boolean> = emptyMap(),
)

@Serializable
data class Temp(val chip: String = "", val label: String = "", val celsius: Double = 0.0)

@Serializable
data class Overview(
    val hostname: String = "",
    val os: String = "",
    val kernel: String = "",
    val cpuModel: String = "",
    val cpuCores: Int = 0,
    val cpuPercent: Double = 0.0,
    val load: List<String> = emptyList(),
    val uptimeSeconds: Long = 0,
    val memTotal: Long = 0,
    val memAvailable: Long = 0,
    val swapTotal: Long = 0,
    val swapFree: Long = 0,
    val temperatures: List<Temp> = emptyList(),
    val rebootRequired: Boolean = false,
    val failedUnits: Int = 0,
)

@Serializable
data class ServiceUnit(
    val unit: String = "",
    val load: String = "",
    val active: String = "",
    val sub: String = "",
    val description: String = "",
    val enabled: String? = null,
)

@Serializable
data class LogEntry(
    val time: Long = 0,
    val priority: String = "",
    val unit: String = "",
    val ident: String = "",
    val message: String = "",
)

@Serializable
data class MountInfo(
    val device: String = "",
    val mount: String = "",
    val fstype: String = "",
    val total: Long = 0,
    val used: Long = 0,
    val free: Long = 0,
)

@Serializable
data class ProcessInfo(
    val pid: Int = 0,
    val user: String = "",
    val cpu: Double = 0.0,
    val mem: Double = 0.0,
    val rssKb: Long = 0,
    val elapsedSeconds: Long = 0,
    val comm: String = "",
    val args: String = "",
)

@Serializable
data class UpgradablePackage(
    val Name: String = "",
    val NewVersion: String = "",
    val OldVersion: String = "",
    val Origin: String = "",
    val Security: Boolean = false,
)

@Serializable
data class UpdatesInfo(
    val packages: List<UpgradablePackage> = emptyList(),
    val rebootRequired: Boolean = false,
)

@Serializable
data class JobInfo(
    val id: String = "",
    val title: String = "",
    val started: String = "",
    val finished: String? = null,
    val exitCode: Int? = null,
    val output: String = "",
)

@Serializable
data class JobStarted(val jobId: String = "", val title: String = "")

@Serializable
data class CmdResult(
    val cmd: String = "",
    val exitCode: Int = 0,
    val stdout: String = "",
    val stderr: String = "",
)

@Serializable
data class KopiaRepo(
    val id: String = "",
    val name: String = "",
    val configFile: String = "",
    val passwordFile: String = "",
    val sources: List<String> = emptyList(),
    val connected: Boolean = false,
    val error: String? = null,
)

@Serializable
data class KopiaRepoDef(
    val id: String,
    val name: String,
    val configFile: String,
    val passwordFile: String = "",
    val sources: List<String> = emptyList(),
)

@Serializable
data class KopiaSnapshot(
    val id: String = "",
    val startTime: String = "",
    val endTime: String = "",
    val totalSize: Long? = null,
    val files: Long? = null,
    val errors: Long? = null,
    val retention: List<String>? = null,
    val description: String = "",
    val source: KopiaSource? = null,
)

@Serializable
data class KopiaSource(val host: String = "", val userName: String = "", val path: String = "")
