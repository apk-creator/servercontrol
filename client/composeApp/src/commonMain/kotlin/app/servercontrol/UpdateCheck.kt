package app.servercontrol

import app.servercontrol.data.AppJson
import app.servercontrol.data.LoginResponse
import app.servercontrol.data.ProfileVault
import app.servercontrol.data.UpdatesInfo
import app.servercontrol.net.ApiClient
import app.servercontrol.net.ConnectionManager
import app.servercontrol.ui.arr
import app.servercontrol.ui.bool
import app.servercontrol.ui.get
import app.servercontrol.ui.screens.compareVersions
import app.servercontrol.ui.str
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Result of one update check over app, agent, Nextcloud, Immich and the system packages. */
data class UpdateReport(
    val agent: JsonElement? = null,
    val nextcloud: JsonElement? = null,
    val immichAbout: JsonElement? = null,
    val immichCheck: JsonElement? = null,
    val immichCompose: JsonElement? = null,
    val system: UpdatesInfo? = null,
    val checkedAt: Long = 0,
    val errors: List<String> = emptyList(),
) {
    val appAsset: String? get() = appUpdateAssetPattern?.let { p -> agent["assets"].arr.map { it["name"].str }.firstOrNull { p.matches(it) } }
    val appLatest: String? get() = agent["latest"].str.takeIf { it.isNotBlank() && compareVersions(it, APP_VERSION) > 0 && appAsset != null }
    val agentLatest: String? get() = agent?.takeIf { it["newer"].bool == true && it["hasAsset"].bool == true }?.let { it["latest"].str }
    val ncServer: String? get() = nextcloud["server"].str.takeIf { it.isNotBlank() }
    val ncApps: Int get() = nextcloud["apps"].arr.size
    val immichInstalled: String get() = immichAbout["version"].str.removePrefix("v")
    val immichLatest: String?
        get() = immichCheck["releaseVersion"].str.removePrefix("v")
            .takeIf { it.isNotBlank() && immichInstalled.isNotBlank() && compareVersions(it, immichInstalled) > 0 }
    val systemCount: Int get() = system?.packages?.size ?: 0
    val securityCount: Int get() = system?.packages?.count { it.Security } ?: 0

    /** e.g. ["App 1.9", "Nextcloud 34.0.6", "5 Systempakete"] */
    val summary: List<String>
        get() = listOfNotNull(
            appLatest?.let { "App $it" },
            agentLatest?.let { "Agent $it" },
            ncServer?.let { "Nextcloud $it" },
            ncApps.takeIf { it > 0 }?.let { "$it Nextcloud-App${if (it > 1) "s" else ""}" },
            immichLatest?.let { "Immich $it" },
            systemCount.takeIf { it > 0 }?.let { "$it Systempaket${if (it > 1) "e" else ""}" + if (securityCount > 0) " ($securityCount Sicherheit)" else "" },
        )
}

suspend fun checkAllUpdates(api: ApiClient, modules: Map<String, Boolean>, now: Long): UpdateReport = coroutineScope {
    suspend fun <T> safe(name: String, b: suspend () -> T): Pair<T?, String?> = try {
        b() to null
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        null to "$name: ${e.message ?: e}"
    }
    val agent = async { safe("Agent") { api.getJson("/api/agent/update/check", 90_000) } }
    val nc = async { if (modules["nextcloud"] == true) safe("Nextcloud") { api.getJson("/api/nextcloud/updatecheck", 180_000) } else null to null }
    val imAbout = async { if (modules["immich"] == true) safe("Immich") { api.getJson("/api/immich/server/about") } else null to null }
    val imCheck = async { if (modules["immich"] == true) safe("Immich") { api.getJson("/api/immich/server/version-check") } else null to null }
    val imCompose = async { if (modules["immich"] == true) safe("Immich") { api.getJson("/api/immich-compose") } else null to null }
    val sys = async { safe("System") { api.get<UpdatesInfo>("/api/system/updates", 120_000) } }
    val all = listOf(agent, nc, imAbout, imCheck, imCompose).map { it.await() }
    val s = sys.await()
    UpdateReport(
        agent = all[0].first, nextcloud = all[1].first, immichAbout = all[2].first, immichCheck = all[3].first, immichCompose = all[4].first,
        system = s.first, checkedAt = now,
        errors = (all.mapNotNull { it.second } + listOfNotNull(s.second)).distinct(),
    )
}

/**
 * Update check without UI (daily background job): signs in with the remembered device of the
 * last used server. Returns null when that is not possible (no device, server not reachable).
 */
suspend fun backgroundUpdateCheck(store: SecureStore, now: Long): UpdateReport? {
    val vault = store.load()?.let { runCatching { AppJson.decodeFromString<ProfileVault>(it) }.getOrNull() } ?: return null
    val p = vault.profiles.find { it.id == vault.lastProfileId && it.refreshToken.isNotEmpty() && it.certFingerprint.isNotEmpty() } ?: return null
    val conn = ConnectionManager()
    return try {
        val (api, _) = conn.connect(p) { false }
        val body = buildJsonObject { put("deviceId", p.deviceId); put("refreshToken", p.refreshToken) }.toString()
        val resp = AppJson.decodeFromString<LoginResponse>(api.raw(io.ktor.http.HttpMethod.Post, "/api/refresh", body))
        api.token = resp.token
        val report = checkAllUpdates(api, resp.modules, now)
        runCatching { api.postRaw("/api/logout") }
        report
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null
    } finally {
        conn.close()
    }
}
