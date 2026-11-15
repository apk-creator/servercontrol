package app.servercontrol

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.servercontrol.data.AppJson
import app.servercontrol.data.LoginResponse
import app.servercontrol.data.ProfileVault
import app.servercontrol.data.ServerProfile
import app.servercontrol.net.ApiClient
import app.servercontrol.net.ConnectionManager
import app.servercontrol.terminal.TerminalSession
import app.servercontrol.ui.bool
import app.servercontrol.ui.get
import app.servercontrol.ui.str
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

sealed interface Screen {
    data object Profiles : Screen
    data class Login(val profile: ServerProfile) : Screen
    data object Main : Screen
}

enum class Section(val title: String, val module: String? = null) {
    OVERVIEW("Übersicht"), TERMINAL("Terminal"), SSH_KEYS("SSH-Schlüssel"), SERVICES("Dienste"), CONTAINERS("Container"), LOGS("Protokolle"),
    STORAGE("Speicher"), FILES("Dateien"), NETWORK("Netzwerk"), PROCESSES("Prozesse"), UPDATES("Updates"),
    ACCOUNTS("Konten"), KOPIA("Kopia"), NEXTCLOUD("Nextcloud"), IMMICH("Immich"),
    JOBS("Aufgaben"), SHELL("Befehl", "shell"), AGENT("Agent"),
}

class Session(
    profile: ServerProfile,
    var api: ApiClient,
    val modules: Map<String, Boolean>,
) {
    /** observable, so screens update when e.g. an SSH key is generated */
    var profile by mutableStateOf(profile)
}

/** Progress of an in-app update (survives leaving the Agent view and the app going to the background). */
data class AppUpdateProgress(
    val asset: String,
    val done: Long = 0,
    val total: Long = -1,
    val running: Boolean = true,
    val ok: Boolean = false,
    val message: String? = null,
)

class AppState(
    private val store: SecureStore,
    private val scope: CoroutineScope,
) {
    /** for actions that must finish even when the user leaves the view (e.g. updates) */
    val appScope: CoroutineScope get() = scope

    var vault by mutableStateOf(ProfileVault())
        private set
    var screen by mutableStateOf<Screen>(Screen.Profiles)
    var section by mutableStateOf(Section.OVERVIEW)

    /** sections visited before, for the back key */
    val sectionHistory = androidx.compose.runtime.mutableStateListOf<Section>()

    fun navigate(to: Section) {
        if (to == section) return
        sectionHistory.remove(to)
        sectionHistory.add(section)
        if (sectionHistory.size > 30) sectionHistory.removeAt(0)
        section = to
    }

    fun back(): Boolean {
        if (sectionHistory.isEmpty()) return false
        section = sectionHistory.removeAt(sectionHistory.lastIndex)
        return true
    }
    var session by mutableStateOf<Session?>(null)
        private set
    var busy by mutableStateOf<String?>(null)
    var error by mutableStateOf<String?>(null)
    /** Fingerprint waiting for user confirmation (TOFU dialog) */
    var pendingCert by mutableStateOf<Pair<String, CompletableDeferred<Boolean>>?>(null)

    /** Side menu in the user's order (drag & drop); new sections are appended. */
    fun orderedSections(): List<Section> {
        val byName = Section.entries.associateBy { it.name }
        val stored = vault.sectionOrder.mapNotNull { byName[it] }
        return stored + Section.entries.filter { it !in stored }
    }

    fun setSectionOrder(order: List<Section>) = scope.launch {
        vault = vault.copy(sectionOrder = order.map { it.name })
        persist()
    }

    /** SSH terminal – kept while switching sections */
    var terminal by mutableStateOf<TerminalSession?>(null)

    fun terminalSession(): TerminalSession = terminal ?: TerminalSession(scope).also { terminal = it }

    private fun closeTerminal() {
        terminal?.dispose()
        terminal = null
    }

    /** Persist a changed profile of the active session (e.g. pinned SSH host key). */
    fun updateSessionProfile(p: ServerProfile) {
        session?.profile = p
        saveProfile(p)
    }

    private val conn = ConnectionManager()

    private var autoResumed = false

    suspend fun loadVault() {
        val plain = store.load() ?: return
        vault = runCatching { AppJson.decodeFromString<ProfileVault>(plain) }.getOrElse { ProfileVault() }
        // app (re)started, e.g. after Android ended the process in the background:
        // reconnect to the last server with the remembered device
        if (!autoResumed && session == null && screen == Screen.Profiles) {
            autoResumed = true
            vault.profiles.find { it.id == vault.lastProfileId && it.refreshToken.isNotEmpty() }?.let { openProfile(it) }
        }
    }

    /** Profile tapped: sign in silently if this device is remembered, otherwise ask for the password. */
    fun openProfile(p: ServerProfile) {
        error = null
        screen = Screen.Login(p)
        if (p.refreshToken.isNotEmpty()) resume(p)
    }

    private suspend fun persist() = store.save(AppJson.encodeToString(vault))

    private suspend fun upsert(p: ServerProfile) {
        val list = vault.profiles.filterNot { it.id == p.id } + p
        vault = vault.copy(profiles = list.sortedBy { it.name.lowercase() })
        persist()
    }

    fun saveProfile(p: ServerProfile) = scope.launch { upsert(p) }

    fun deleteProfile(p: ServerProfile) = scope.launch {
        vault = vault.copy(profiles = vault.profiles.filterNot { it.id == p.id })
        persist()
    }

    private suspend fun confirmCert(fp: String): Boolean {
        val d = CompletableDeferred<Boolean>()
        pendingCert = fp to d
        return d.await().also { pendingCert = null }
    }

    private suspend fun connect(profile: ServerProfile): Pair<ApiClient, ServerProfile> {
        conn.status = { busy = it }
        busy = "Verbinde …"
        val (api, fp) = conn.connect(profile) { confirmCert(it) }
        var prof = profile
        if (fp != profile.certFingerprint) {
            prof = profile.copy(certFingerprint = fp)
            upsert(prof)
        }
        return api to prof
    }

    private suspend fun startSession(api: ApiClient, prof: ServerProfile, resp: LoginResponse) {
        api.token = resp.token
        api.onUnauthorized = { scope.launch { expire() } }
        val profileId = prof.id
        api.refresher = { failed -> renewSession(api, profileId, failed) }
        session = Session(prof, api, resp.modules)
        vault = vault.copy(lastProfileId = prof.id)
        persist()
        section = Section.OVERVIEW
        sectionHistory.clear()
        screen = Screen.Main
        checkUpdates()
        checkAgentVersion(api)
    }

    private val renewLock = Mutex()

    /** Gets a new session token with the remembered device (null = not possible). */
    private suspend fun renewSession(api: ApiClient, profileId: String, failed: String?): String? = renewLock.withLock {
        // another request already renewed the session meanwhile
        api.token?.let { if (it != failed) return@withLock it }
        requestRefresh(api, profileId)?.token
    }

    private suspend fun requestRefresh(api: ApiClient, profileId: String): LoginResponse? {
        val p = vault.profiles.find { it.id == profileId } ?: return null
        if (p.refreshToken.isEmpty()) return null
        val body = buildJsonObject { put("deviceId", p.deviceId); put("refreshToken", p.refreshToken) }.toString()
        return try {
            AppJson.decodeFromString<LoginResponse>(api.raw(io.ktor.http.HttpMethod.Post, "/api/refresh", body))
        } catch (e: app.servercontrol.net.ApiException) {
            // 401: device was removed on the server or the password was changed → password needed again
            if (e.status == 401) forgetDevice(profileId)
            null
        }
        // network errors (e.g. agent just restarting) propagate: the caller retries later,
        // the device is kept and the session is not ended
    }

    private suspend fun forgetDevice(profileId: String) {
        val p = vault.profiles.find { it.id == profileId } ?: return
        val cleared = p.copy(deviceId = "", refreshToken = "")
        upsert(cleared)
        if (session?.profile?.id == profileId) session?.profile = cleared
        (screen as? Screen.Login)?.let { if (it.profile.id == profileId) screen = Screen.Login(cleared) }
    }

    /** Silent sign-in with the remembered device. */
    private fun resume(profile: ServerProfile) = scope.launch {
        error = null
        try {
            val (api, prof) = connect(profile)
            busy = "Anmelden …"
            val resp = requestRefresh(api, prof.id)
            if (resp != null) {
                startSession(api, vault.profiles.find { it.id == prof.id } ?: prof, resp)
            } else {
                conn.close()
                val current = vault.profiles.find { it.id == prof.id } ?: prof
                if (current.refreshToken.isNotEmpty()) error = "Server nicht erreichbar – bitte erneut versuchen."
                screen = Screen.Login(current)
            }
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
            conn.close()
            if (e is app.servercontrol.net.ConnectException && (e.message ?: "").contains("nicht erreichbar")) detectMissingAgent(profile)
        } finally {
            busy = null
        }
    }

    fun login(profile: ServerProfile, user: String, password: String) = scope.launch {
        error = null
        agentMissing = null
        try {
            val (api, connected) = connect(profile)
            var prof = connected
            busy = "Anmelden …"
            val body = buildJsonObject {
                put("user", user)
                put("password", password)
                put("device", deviceName) // stay signed in on this device
            }.toString()
            val resp = AppJson.decodeFromString<LoginResponse>(api.raw(io.ktor.http.HttpMethod.Post, "/api/login", body))
            if (resp.refreshToken.isNotEmpty()) {
                val old = prof.deviceId
                prof = prof.copy(deviceId = resp.deviceId, refreshToken = resp.refreshToken)
                upsert(prof)
                api.token = resp.token
                if (old.isNotEmpty() && old != resp.deviceId) runCatching { api.postRaw("/api/devices/$old/revoke") }
            }
            startSession(api, prof, resp)
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
            conn.close()
            if (e is app.servercontrol.net.ConnectException && (e.message ?: "").contains("nicht erreichbar")) detectMissingAgent(profile)
        } finally {
            busy = null
        }
    }

    // ---------------- agent missing → install over SSH ----------------

    /** the server answers on SSH but not on the agent port: offer to install the agent */
    var agentMissing by mutableStateOf<ServerProfile?>(null)
    var installLog by mutableStateOf<String?>(null)
        private set
    var installing by mutableStateOf(false)
        private set
    /** SSH host key seen during installation, waiting for confirmation */
    var installHostKey by mutableStateOf<Pair<String, String>?>(null)

    fun hostOf(p: ServerProfile): Pair<String, Int> {
        val m = Regex("""^\w+://(\[[^\]]+\]|[^/:]+)(?::(\d+))?""").find(p.lanUrl.trim())
        val h = m?.groupValues?.get(1)?.trim('[', ']') ?: p.lanUrl
        val port = m?.groupValues?.get(2)?.toIntOrNull() ?: 8443
        return h to port
    }

    private suspend fun detectMissingAgent(p: ServerProfile) {
        val (h, port) = hostOf(p)
        busy = "Prüfe, ob ein Agent installiert ist …"
        if (!tcpReachable(h, port, 2500) && tcpReachable(h, p.sshPort, 2500)) {
            agentMissing = p
            error = "Auf $h antwortet kein ServerControl-Agent (Port $port), der Server ist aber per SSH erreichbar."
        }
    }

    /**
     * Installs the agent bundled with this app over SSH (sudo), pins its certificate
     * (the fingerprint comes over the authenticated SSH connection) and signs in.
     */
    fun installAgent(
        profile: ServerProfile, sshUser: String, sshPassword: String, sudoPassword: String,
        agentPassword: String, addKey: Boolean, trustedHostKey: String? = null,
    ) = scope.launch {
        installing = true
        installHostKey = null
        error = null
        val log = StringBuilder()
        fun say(t: String) { log.appendLine(t); installLog = log.toString().trim() }
        try {
            val pkg = bundledAgentPackage() ?: throw Exception("Diese App-Version enthält kein Agent-Paket.")
            val (host, _) = hostOf(profile)
            val known = trustedHostKey ?: profile.sshHostKey
            say("Verbinde per SSH mit $sshUser@$host …")
            val auth = SshAuth(sshUser, sshPassword.ifBlank { null }, profile.sshPrivateKey.ifBlank { null }, null)
            val files = mutableMapOf("agent.tar" to pkg, "pw" to agentPassword.encodeToByteArray())
            if (addKey && profile.sshPublicKey.isNotBlank()) files["pubkey"] = (profile.sshPublicKey.trim() + "\n").encodeToByteArray()
            say("Lade Agent ${APP_VERSION} hoch und installiere ihn …")
            val script = "set -e\ncd \"\$(dirname \"\$0\")\"\ntar -xf agent.tar\nexec sh ./install-remote.sh\n"
            val (code, out) = try {
                sshSudoScript(host, profile.sshPort, auth, known, sudoPassword.ifBlank { sshPassword }.ifBlank { null }, files, script)
            } catch (e: SshHostKeyUnknown) {
                installHostKey = e.fingerprint to e.keyType
                say("Unbekannter SSH-Hostschlüssel – bitte bestätigen.")
                return@launch
            }
            val kv = out.lines().mapNotNull { l -> l.substringBefore('=', "").takeIf { it.isNotEmpty() && it.all { c -> c.isUpperCase() } }?.let { it to l.substringAfter('=') } }.toMap()
            if (code != 0 || kv["FP"].isNullOrBlank()) {
                val msg = kv["ERROR"] ?: out.lines().filter { it.isNotBlank() }.takeLast(6).joinToString("\n").ifBlank { "Exit $code" }
                throw Exception(if (msg.contains("incorrect password") || msg.contains("Sorry, try again")) "sudo-Passwort falsch." else msg)
            }
            say("Agent ${kv["VER"]} läuft" + if (kv["ACTIVE"] == "0") " nicht – siehe Systemprotokoll." else ".")
            if (kv["KEY"] == "added") say("App-Schlüssel für das Terminal eingetragen.")
            val updated = (vault.profiles.find { it.id == profile.id } ?: profile).copy(
                certFingerprint = kv["FP"]!!.trim(), sshUser = sshUser, sshHostKey = known.ifBlank { profile.sshHostKey },
            )
            upsert(updated)
            agentMissing = null
            if (kv["FIREWALL"] == "closed") {
                val lan = Regex("""^(\d+\.\d+\.\d+)\.\d+$""").find(host)?.groupValues?.get(1)?.let { "$it.0/24" } ?: "<LAN>/24"
                say("Die Firewall lässt Port 8443 noch nicht durch. Im LAN freigeben, z. B.:\nsudo nft add rule inet <tabelle> input ip saddr $lan tcp dport 8443 accept")
            }
            if (kv["EXISTING"] == "1") {
                say("Der Agent war schon eingerichtet – bitte mit dem bisherigen Agent-Passwort anmelden.")
                screen = Screen.Login(updated)
            } else {
                say("Melde an …")
                kotlinx.coroutines.delay(2_000)
                screen = Screen.Login(updated)
                login(updated, kv["AGENTUSER"].orEmpty().ifBlank { "admin" }, agentPassword)
            }
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            say("Fehler: " + (e.message ?: e.toString()))
        } finally {
            installing = false
        }
    }

    /** remembers the confirmed SSH host key and continues the installation */
    fun confirmInstallHostKey(profile: ServerProfile, fp: String) {
        val updated = (vault.profiles.find { it.id == profile.id } ?: profile).copy(sshHostKey = fp)
        scope.launch { upsert(updated) }
        if (agentMissing?.id == profile.id) agentMissing = updated
        installHostKey = null
    }

    fun clearInstallLog() { installLog = null }

    // ---------------- agent version check after sign-in ----------------

    /** agent older than the app: offer the update right after sign-in */
    var agentOutdated by mutableStateOf<String?>(null)

    private fun checkAgentVersion(api: ApiClient) = scope.launch {
        val v = runCatching { api.getJson("/api/config")["version"].str }.getOrDefault("")
        agentOutdated = v.takeIf { it.isNotBlank() && it != "dev" && app.servercontrol.ui.screens.compareVersions(it, APP_VERSION) < 0 }
    }

    private suspend fun expire() {
        val s = session ?: return
        updateReport = null
        cancelAppUpdate()
        closeTerminal()
        session = null
        conn.close()
        error = "Sitzung abgelaufen – bitte erneut anmelden."
        screen = Screen.Login(vault.profiles.find { it.id == s.profile.id } ?: s.profile)
    }

    /** Explicit sign-out: also forgets this device, the next sign-in needs the password. */
    fun logout() = scope.launch {
        val s = session ?: return@launch
        updateReport = null
        cancelAppUpdate()
        closeTerminal()
        runCatching { s.api.postRaw("/api/logout?forget=1") }
        session = null
        conn.close()
        forgetDevice(s.profile.id)
        screen = Screen.Login(vault.profiles.find { it.id == s.profile.id } ?: s.profile)
    }

    // ---------------- update check (app, agent, Nextcloud, Immich, system) ----------------

    var updateReport by mutableStateOf<UpdateReport?>(null)
        private set
    var updatesChecking by mutableStateOf(false)
        private set
    /** summary already seen/dismissed – the notice shows again only for new updates */
    var updateNoticeSeen by mutableStateOf("")
    /** set by the "Updates verfügbar" notification */
    var openUpdatesWhenReady by mutableStateOf(false)

    fun checkUpdates(refreshLists: Boolean = false) {
        val s = session ?: return
        if (updatesChecking) return
        updatesChecking = true
        scope.launch {
            try {
                updateReport = checkAllUpdates(s.api, s.modules, app.servercontrol.ui.screens.currentTimeMs(), refreshLists)
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
            } finally {
                updatesChecking = false
            }
        }
    }

    // ---------------- agent self-update ----------------

    /** status text while the agent updates itself */
    var agentUpdating by mutableStateOf<String?>(null)
        private set
    var agentUpdateResult by mutableStateOf<Pair<Boolean, String>?>(null)

    fun startAgentUpdate() {
        val s = session ?: return
        if (agentUpdating != null) return
        var target = updateReport?.agentLatest
        agentUpdateResult = null
        agentUpdating = "Agent lädt das Release …"
        scope.launch {
            try {
                // answers at once (new agents) – old agents answer after the install
                runCatching { s.api.postRaw("/api/agent/update/install", timeoutMs = 300_000) }
                    .onFailure { e -> if (e is app.servercontrol.net.ApiException && e.status in 400..499) throw e }
                val deadline = app.servercontrol.ui.screens.currentTimeMs() + 5 * 60_000
                while (agentUpdateResult == null && app.servercontrol.ui.screens.currentTimeMs() < deadline) {
                    kotlinx.coroutines.delay(3_000)
                    try {
                        val st = s.api.getJson("/api/agent/update/status", 10_000)
                        st["target"].str.takeIf { it.isNotBlank() }?.let { target = it }
                        val cur = st["current"].str
                        val t = target
                        when {
                            t != null && cur.isNotBlank() && app.servercontrol.ui.screens.compareVersions(cur, t) >= 0 ->
                                agentUpdateResult = true to "Der Agent läuft jetzt mit Version $cur."
                            st["error"].str.isNotBlank() -> agentUpdateResult = false to st["error"].str
                            st["running"].bool == false && st["target"].str.isNotBlank() -> agentUpdating = "Agent startet neu …"
                        }
                    } catch (e: Throwable) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        // old agent without status endpoint, or restarting right now
                        if (e is app.servercontrol.net.ApiException && e.status == 404) {
                            val cur = runCatching { s.api.getJson("/api/config")["version"].str }.getOrDefault("")
                            val t = target
                            if (t != null && cur.isNotBlank() && app.servercontrol.ui.screens.compareVersions(cur, t) >= 0) {
                                agentUpdateResult = true to "Der Agent läuft jetzt mit Version $cur."
                            }
                        } else {
                            agentUpdating = "Agent startet neu …"
                        }
                    }
                }
                if (agentUpdateResult == null) agentUpdateResult = false to "Keine neue Version gemeldet. Details im Systemprotokoll (Dienst servercontrol-agent)."
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                agentUpdateResult = false to (e.message ?: e.toString())
            } finally {
                agentUpdating = null
                checkUpdates()
            }
        }
    }

    // ---------------- in-app update ----------------

    var appUpdate by mutableStateOf<AppUpdateProgress?>(null)
        private set
    private var updateJob: Job? = null

    fun startAppUpdate(asset: String) {
        if (updateJob?.isActive == true) return
        val s = session ?: return
        appUpdate = AppUpdateProgress(asset)
        updateJob = scope.launch {
            appUpdate = try {
                val msg = installAppUpdate(s.api, s.profile.certFingerprint, asset) { done, total ->
                    appUpdate = appUpdate?.copy(done = done, total = total)
                }
                appUpdate?.copy(running = false, ok = true, message = msg)
            } catch (e: kotlinx.coroutines.CancellationException) {
                null
            } catch (e: Throwable) {
                appUpdate?.copy(running = false, message = e.message ?: e.toString())
            }
        }
    }

    fun cancelAppUpdate() {
        updateJob?.cancel()
        updateJob = null
        appUpdate = null
    }

    fun dismissAppUpdate() {
        if (updateJob?.isActive != true) appUpdate = null
    }

    /** App is closing: log out. */
    suspend fun shutdown() {
        updateJob?.cancel()
        closeTerminal()
        val s = session ?: return
        runCatching { s.api.postRaw("/api/logout") }
        conn.close()
        session = null
    }
}
