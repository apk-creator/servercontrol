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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    STORAGE("Speicher"), NETWORK("Netzwerk"), PROCESSES("Prozesse"), UPDATES("Updates"),
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

class AppState(
    private val store: SecureStore,
    private val scope: CoroutineScope,
) {
    var vault by mutableStateOf(ProfileVault())
        private set
    var screen by mutableStateOf<Screen>(Screen.Profiles)
    var section by mutableStateOf(Section.OVERVIEW)
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

    suspend fun loadVault() {
        val plain = store.load() ?: return
        vault = runCatching { AppJson.decodeFromString<ProfileVault>(plain) }.getOrElse { ProfileVault() }
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

    fun login(profile: ServerProfile, user: String, password: String) = scope.launch {
        error = null
        try {
            conn.status = { busy = it }
            busy = "Verbinde …"
            val (api, fp) = conn.connect(profile) { confirmCert(it) }
            var prof = profile
            if (fp != profile.certFingerprint) {
                prof = profile.copy(certFingerprint = fp)
                upsert(prof)
            }
            busy = "Anmelden …"
            val body = buildJsonObject { put("user", user); put("password", password) }.toString()
            val resp = AppJson.decodeFromString<LoginResponse>(api.raw(io.ktor.http.HttpMethod.Post, "/api/login", body))
            api.token = resp.token
            api.onUnauthorized = { scope.launch { expire() } }
            session = Session(prof, api, resp.modules)
            vault = vault.copy(lastProfileId = prof.id)
            persist()
            section = Section.OVERVIEW
            screen = Screen.Main
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
            conn.close()
        } finally {
            busy = null
        }
    }

    private suspend fun expire() {
        val s = session ?: return
        closeTerminal()
        session = null
        conn.close()
        error = "Sitzung abgelaufen – bitte erneut anmelden."
        screen = Screen.Login(s.profile)
    }

    fun logout() = scope.launch {
        val s = session ?: return@launch
        closeTerminal()
        runCatching { s.api.postRaw("/api/logout") }
        session = null
        conn.close()
        screen = Screen.Login(s.profile)
    }

    /** App is closing: log out. */
    suspend fun shutdown() {
        closeTerminal()
        val s = session ?: return
        runCatching { s.api.postRaw("/api/logout") }
        conn.close()
        session = null
    }
}
