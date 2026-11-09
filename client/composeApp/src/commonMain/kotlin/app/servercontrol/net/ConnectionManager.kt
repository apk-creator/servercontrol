package app.servercontrol.net

import app.servercontrol.createHttpClient
import app.servercontrol.data.ServerProfile
import io.ktor.client.HttpClient

sealed interface Probe {
    data object Ok : Probe
    data object Unreachable : Probe
    data class CertMismatch(val seen: String) : Probe
}

class ConnectException(message: String) : Exception(message)

/** Connects to the agent and pins its TLS certificate (trust on first use). */
class ConnectionManager {
    private var http: HttpClient? = null
    var status: (String) -> Unit = {}

    private suspend fun probe(client: HttpClient, url: String, timeoutMs: Long): Probe = try {
        val ok = ApiClient(client, url).raw(io.ktor.http.HttpMethod.Get, "/api/ping", timeoutMs = timeoutMs)
            .contains("servercontrol-agent")
        if (ok) Probe.Ok else Probe.Unreachable
    } catch (e: Throwable) {
        val mismatch = generateSequence(e) { it.cause }.mapNotNull { it.message }
            .firstOrNull { it.contains("PIN_MISMATCH:") }
        if (mismatch != null) Probe.CertMismatch(mismatch.substringAfter("PIN_MISMATCH:").trim()) else Probe.Unreachable
    }

    /**
     * Connects to [profile]. If the profile has no pinned certificate yet,
     * [confirmCertificate] is asked; returning false aborts.
     * Returns the API client and the fingerprint that is now trusted.
     */
    suspend fun connect(
        profile: ServerProfile,
        confirmCertificate: suspend (String) -> Boolean,
    ): Pair<ApiClient, String> {
        close()
        var seen = ""
        var client = createHttpClient(profile.certFingerprint) { seen = it }
        http = client
        val url = profile.lanUrl

        status("Verbinde mit $url …")
        when (val r = probe(client, url, 8_000)) {
            is Probe.CertMismatch -> {
                close()
                throw ConnectException(
                    "Zertifikat des Servers hat sich geändert!\nErwartet: ${profile.certFingerprint}\nErhalten: ${r.seen}\n" +
                        "Verbindung abgebrochen. Falls du das Zertifikat bewusst erneuert hast, setze den Fingerprint im Profil zurück."
                )
            }
            Probe.Unreachable -> {
                close()
                throw ConnectException("Agent unter $url nicht erreichbar. Von unterwegs vorher das VPN ins Heimnetz verbinden.")
            }
            Probe.Ok -> {}
        }

        var fingerprint = profile.certFingerprint
        if (fingerprint.isBlank()) {
            if (seen.isBlank() || !confirmCertificate(seen)) {
                close()
                throw ConnectException("Zertifikat nicht bestätigt.")
            }
            fingerprint = seen
            client.close()
            client = createHttpClient(fingerprint) {}
            http = client
        }
        status("Verbunden")
        return ApiClient(client, url) to fingerprint
    }

    fun close() {
        http?.close()
        http = null
    }
}
