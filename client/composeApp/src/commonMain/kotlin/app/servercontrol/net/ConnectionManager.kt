package app.servercontrol.net

import app.servercontrol.VpnController
import app.servercontrol.createHttpClient
import app.servercontrol.data.ServerProfile
import io.ktor.client.HttpClient
import kotlinx.coroutines.delay

enum class Route { LAN, VPN }

sealed interface Probe {
    data object Ok : Probe
    data object Unreachable : Probe
    data class CertMismatch(val seen: String) : Probe
}

class ConnectException(message: String) : Exception(message)

/**
 * Picks LAN or VPN and switches WireGuard automatically:
 *  1. agent reachable on the LAN address → no VPN
 *  2. otherwise → tunnel up, wait for the agent behind the tunnel
 * The tunnel is only switched off again if this app switched it on.
 */
class ConnectionManager(private val vpn: VpnController) {
    var route: Route? = null
        private set
    var vpnStartedByApp = false
        private set
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
        val vpnUrl = profile.vpnUrl.ifBlank { profile.lanUrl }

        status("Prüfe Heimnetz …")
        var url = profile.lanUrl
        var result = probe(client, url, 2_500)
        route = Route.LAN

        if (result == Probe.Unreachable) {
            if (!vpn.isAvailable(profile.wgTunnel, profile.vpnUpCommand, profile.vpnDownCommand)) {
                throw ConnectException("Server im Heimnetz nicht erreichbar und kein WireGuard-Tunnel konfiguriert.")
            }
            status("Außerhalb des Heimnetzes – aktiviere WireGuard „${profile.wgTunnel}“ …")
            // If the tunnel is already up (e.g. switched on manually), we don't own it.
            if (vpnUrl != url && probe(client, vpnUrl, 2_000) == Probe.Ok) {
                result = Probe.Ok
            } else {
                vpn.up(profile.wgTunnel, profile.vpnUpCommand).getOrElse {
                    throw ConnectException("WireGuard konnte nicht aktiviert werden: ${it.message}")
                }
                vpnStartedByApp = true
                status("Warte auf Tunnel …")
                for (i in 0 until 15) {
                    delay(1_500)
                    result = probe(client, vpnUrl, 2_500)
                    if (result != Probe.Unreachable) break
                }
            }
            url = vpnUrl
            route = Route.VPN
        }

        when (val r = result) {
            is Probe.CertMismatch -> {
                close()
                throw ConnectException(
                    "Zertifikat des Servers hat sich geändert!\nErwartet: ${profile.certFingerprint}\nErhalten: ${r.seen}\n" +
                        "Verbindung abgebrochen. Falls du das Zertifikat bewusst erneuert hast, setze den Fingerprint im Profil zurück."
                )
            }
            Probe.Unreachable -> {
                close()
                throw ConnectException("Agent unter $url nicht erreichbar.")
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
        status("Verbunden über ${if (route == Route.LAN) "Heimnetz" else "WireGuard"}")
        return ApiClient(client, url) to fingerprint
    }

    /** Closes the HTTP client and switches the tunnel off if we switched it on. */
    suspend fun close(profile: ServerProfile? = null) {
        http?.close()
        http = null
        if (vpnStartedByApp && profile != null) {
            vpn.down(profile.wgTunnel, profile.vpnDownCommand)
        }
        if (profile != null) vpnStartedByApp = false
        route = null
    }
}
