package app.servercontrol.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.servercontrol.Bold
import app.servercontrol.ButtonRow
import app.servercontrol.APP_VERSION
import app.servercontrol.SecretField
import app.servercontrol.appUpdateAssetPattern
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import app.servercontrol.SmallButton
import app.servercontrol.data.AppJson
import app.servercontrol.net.ApiClient
import app.servercontrol.pickFile
import app.servercontrol.ui.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Splits a command line, honouring double quotes. */
fun splitArgs(cmd: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var q = false
    var has = false
    for (c in cmd) when {
        c == '"' -> { q = !q; has = true }
        c.isWhitespace() && !q -> if (has) { out += cur.toString(); cur.clear(); has = false }
        else -> { cur.append(c); has = true }
    }
    if (has) out += cur.toString()
    return out
}

private fun result(json: JsonElement): Pair<Boolean, String> = (json["ok"].bool == true) to json["message"].str

// ======================= Nextcloud setup =======================

@Composable
fun NextcloudSetup(api: ApiClient, current: JsonElement?, onSaved: () -> Unit) {
    val currentOcc = current["occ"].arr.map { it.str }
    val guess = when {
        currentOcc.firstOrNull() == "docker" -> 1
        currentOcc.firstOrNull() == "nextcloud.occ" -> 2
        currentOcc.size == 6 && currentOcc[0] == "runuser" -> 0
        currentOcc.isEmpty() -> 0
        else -> 3
    }
    var mode by remember { mutableStateOf(guess) }
    var path by remember { mutableStateOf(if (guess == 0 && currentOcc.size == 6) currentOcc[5].removeSuffix("/occ") else "/var/www/nextcloud") }
    var container by remember { mutableStateOf(if (guess == 1) currentOcc.getOrNull(4) ?: "nextcloud" else "nextcloud") }
    var custom by remember { mutableStateOf(currentOcc.joinToString(" ") { if (it.contains(' ')) "\"$it\"" else it }) }
    var test by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val host = rememberActionHost { api }
    val scope = rememberCoroutineScope()

    fun occ(): List<String> = when (mode) {
        0 -> listOf("runuser", "-u", "www-data", "--", "php", path.trimEnd('/') + "/occ")
        1 -> listOf("docker", "exec", "-u", "www-data", container.trim(), "php", "occ")
        2 -> listOf("nextcloud.occ")
        else -> splitArgs(custom)
    }
    fun body(enabled: Boolean) = buildJsonObject {
        put("enabled", enabled)
        put("occ", buildJsonArray { occ().forEach { add(JsonPrimitive(it)) } })
    }.toString()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionCard("Nextcloud verbinden") {
            Text("ServerControl steuert Nextcloud über occ auf dem Server. Wie ist Nextcloud installiert?", fontSize = 13.sp)
            listOf("Klassisch (Webserver + PHP)", "Docker-Container", "Snap", "Eigener Befehl").forEachIndexed { i, t ->
                Row(Modifier.fillMaxWidth().selectable(mode == i, onClick = { mode = i }).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(mode == i, { mode = i }); Text(t)
                }
            }
            when (mode) {
                0 -> OutlinedTextField(path, { path = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Nextcloud-Verzeichnis") }, singleLine = true)
                1 -> OutlinedTextField(container, { container = it }, Modifier.fillMaxWidth(), label = { Text("Container-Name (AIO: nextcloud-aio-nextcloud)") }, singleLine = true)
                3 -> OutlinedTextField(custom, { custom = it }, Modifier.fillMaxWidth(), label = { Text("occ-Befehl") }, singleLine = true)
            }
            Text("Befehl: " + occ().joinToString(" "), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            ButtonRow {
                SmallButton("Testen", {
                    scope.launch {
                        test = runCatching { result(AppJson.parseToJsonElement(api.postRaw("/api/config/nextcloud/test", body(true)))) }
                            .getOrElse { false to (it.message ?: "Fehler") }
                    }
                })
                Button(onClick = { host.run("Nextcloud", { api.putRaw("/api/config/nextcloud", body(true)); "Nextcloud ist verbunden." }, onSaved) }) { Text("Speichern & aktivieren") }
                if (current["enabled"].bool == true) SmallButton("Deaktivieren", {
                    host.run("Nextcloud", { api.putRaw("/api/config/nextcloud", body(false)); "Nextcloud-Modul deaktiviert." }, onSaved)
                })
            }
            test?.let { (ok, msg) -> Text(msg, color = if (ok) StatusOk else StatusBad) }
        }
    }
    ActionResultDialog(host, api)
}

// ======================= Immich setup =======================

@Composable
fun ImmichSetup(api: ApiClient, current: JsonElement?, onSaved: () -> Unit) {
    var url by remember { mutableStateOf(current["url"].str.ifBlank { "http://127.0.0.1:2283" }) }
    var key by remember { mutableStateOf("") }
    var test by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val host = rememberActionHost { api }
    val scope = rememberCoroutineScope()
    fun body(enabled: Boolean) = buildJsonObject { put("enabled", enabled); put("url", url.trim()); put("apiKey", key.trim()) }.toString()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SectionCard("Immich verbinden") {
            Text("Adresse von Immich aus Sicht des Servers (meist http://127.0.0.1:2283) und ein API-Schlüssel eines Admin-Kontos " +
                "(Immich → Kontoeinstellungen → API-Schlüssel, Rechte: alle oder server.*, job.*, adminUser.*, library.*).", fontSize = 13.sp)
            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Immich-URL") }, singleLine = true)
            SecretField(key, { key = it }, if (current["hasApiKey"].bool == true) "API-Schlüssel (leer = gespeicherten behalten)" else "API-Schlüssel")
            Text("Der Schlüssel wird nur auf dem Server gespeichert (/etc/servercontrol/immich.key, nur root).", fontSize = 12.sp)
            ButtonRow {
                SmallButton("Testen", {
                    scope.launch {
                        test = runCatching { result(AppJson.parseToJsonElement(api.postRaw("/api/config/immich/test", body(true)))) }
                            .getOrElse { false to (it.message ?: "Fehler") }
                    }
                })
                Button(onClick = { host.run("Immich", { api.putRaw("/api/config/immich", body(true)); key = ""; "Immich ist verbunden." }, onSaved) }) { Text("Speichern & aktivieren") }
                if (current["enabled"].bool == true) SmallButton("Deaktivieren", {
                    host.run("Immich", { api.putRaw("/api/config/immich", body(false)); "Immich-Modul deaktiviert." }, onSaved)
                })
            }
            test?.let { (ok, msg) -> Text(msg, color = if (ok) StatusOk else StatusBad) }
        }
    }
    ActionResultDialog(host, api)
}

// ======================= Agent: version, update, settings =======================

@Composable
fun AgentScreen(state: app.servercontrol.AppState, api: ApiClient) {
    val host = rememberActionHost { api }
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var check by remember { mutableStateOf<JsonElement?>(null) }
    var checking by remember { mutableStateOf(false) }
    Loader(host.result == null, { api.getJson("/api/config") }) { cfg, reload ->
        var repo by remember(cfg) { mutableStateOf(cfg["update"]["repo"].str) }
        var token by remember(cfg) { mutableStateOf("") }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionCard("Agent") {
                KeyValue("Agent-Version", cfg["version"].str)
                KeyValue("App-Version", APP_VERSION)
                KeyValue("Nextcloud", if (cfg["nextcloud"]["enabled"].bool == true) "verbunden" else "nicht eingerichtet")
                KeyValue("Immich", if (cfg["immich"]["enabled"].bool == true) "verbunden" else "nicht eingerichtet")
                KeyValue("Kopia-Repos", cfg["kopiaCount"].str)
                KeyValue("Root-Befehle", if (cfg["allowShell"].bool == true) "erlaubt (allowShell)" else "aus")
            }
            SectionCard("Agent aktualisieren") {
                ButtonRow {
                    SmallButton(if (checking) "Prüfe …" else "Nach Updates suchen", {
                        checking = true
                        scope.launch {
                            check = runCatching { api.getJson("/api/agent/update/check") }.getOrNull()
                            checking = false
                        }
                    }, enabled = !checking)
                    SmallButton("Datei hochladen …", {
                        scope.launch {
                            val data = pickFile() ?: return@launch
                            confirm = Confirm("Agent aus Datei installieren?",
                                "${formatBytes(data.size.toLong())} – erwartet wird servercontrol-agent.tar.gz aus einem Release oder die Linux-Binärdatei. " +
                                    "Der Agent startet danach neu, du musst dich erneut anmelden.") {
                                host.run("Agent-Update", { updateMessage(AppJson.parseToJsonElement(api.postBytes("/api/agent/update/upload", data))) })
                            }
                        }
                    })
                }
                check?.let { c ->
                    val err = c["error"].str
                    if (err.isNotBlank()) Text(err, color = StatusBad, fontSize = 13.sp)
                    else {
                        KeyValue("Installiert", c["current"].str)
                        KeyValue("Neuestes Release", c["latest"].str + "  (" + c["publishedAt"].str.take(10) + ")")
                        if (c["newer"].bool == true && c["hasAsset"].bool == true) {
                            Text("Update verfügbar.", color = StatusWarn, fontWeight = Bold)
                            c["notes"].str.takeIf { it.isNotBlank() }?.let { Text(it.take(1500), fontSize = 12.sp) }
                            Button(onClick = {
                                confirm = Confirm("Agent auf ${c["latest"].str} aktualisieren?",
                                    "Der Agent lädt das Release von GitHub, ersetzt sich selbst und startet neu. Danach musst du dich erneut anmelden.") {
                                    host.run("Agent-Update", { updateMessage(AppJson.parseToJsonElement(api.postRaw("/api/agent/update/install"))) })
                                }
                            }) { Text("Jetzt installieren") }
                        } else if (c["newer"].bool == true) {
                            Text("Das Release enthält keine Agent-Datei.", color = StatusWarn)
                        } else {
                            Text("Der Agent ist aktuell.", color = StatusOk)
                        }
                    }
                }
            }
            AppUpdateCard(state, check)
            DevicesCard(api)
            SectionCard("Update-Quelle") {
                Text("Für ein privates Repository braucht der Server ein GitHub-Token mit Lesezugriff (Fine-grained Token: nur dieses Repository, Berechtigung „Contents: Read-only“).", fontSize = 13.sp)
                OutlinedTextField(repo, { repo = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Repository (owner/name)") }, singleLine = true)
                SecretField(token, { token = it }, if (cfg["update"]["hasToken"].bool == true) "GitHub-Token (leer = behalten)" else "GitHub-Token")
                ButtonRow {
                    Button(onClick = {
                        val b = buildJsonObject { put("repo", repo); put("token", token) }.toString()
                        host.run("Update-Quelle", { api.putRaw("/api/config/update", b); token = ""; "Gespeichert." }, reload)
                    }) { Text("Speichern") }
                    if (cfg["update"]["hasToken"].bool == true) SmallButton("Token löschen", {
                        val b = buildJsonObject { put("repo", repo); put("clearToken", true) }.toString()
                        host.run("Update-Quelle", { api.putRaw("/api/config/update", b); "Token gelöscht." }, reload)
                    })
                }
            }
        }
    }
    ConfirmDialog(confirm) { confirm = null }
    ActionResultDialog(host, api)
}

/** Update of the app itself: the package is downloaded through the agent (which holds the GitHub token). */
@Composable
private fun AppUpdateCard(state: app.servercontrol.AppState, check: JsonElement?) {
    val pattern = appUpdateAssetPattern ?: return
    val progress = state.appUpdate
    SectionCard("App aktualisieren") {
        KeyValue("Installiert", APP_VERSION)
        if (progress != null) {
            if (progress.running) {
                Text("Lädt ${progress.asset} – läuft auch im Hintergrund weiter.", fontSize = 13.sp)
                if (progress.total > 0) {
                    LinearProgressIndicator(progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("${formatBytes(progress.done)}${if (progress.total > 0) " von ${formatBytes(progress.total)}" else ""}", fontSize = 12.sp)
                SmallButton("Abbrechen", { state.cancelAppUpdate() })
            } else {
                Text(
                    if (progress.ok) progress.message ?: "Fertig." else "Update fehlgeschlagen: ${progress.message}",
                    color = if (progress.ok) StatusOk else StatusBad, fontSize = 13.sp,
                )
                SmallButton("OK", { state.dismissAppUpdate() })
            }
            return@SectionCard
        }
        if (check == null) {
            Text("Oben „Nach Updates suchen“ tippen.", fontSize = 13.sp)
            return@SectionCard
        }
        val latest = check["latest"].str
        val asset = check["assets"].arr.map { it["name"].str }.firstOrNull { pattern.matches(it) }
        if (latest.isNotBlank()) KeyValue("Neuestes Release", latest)
        val newer = latest.isNotBlank() && compareVersions(latest, APP_VERSION) > 0
        when {
            latest.isBlank() -> {}
            asset == null -> Text("Im Release ist kein Paket für diese Plattform.", color = StatusWarn, fontSize = 13.sp)
            !newer -> Text("Die App ist aktuell.", color = StatusOk)
            else -> Button(onClick = { state.startAppUpdate(asset) }) { Text("App auf $latest aktualisieren") }
        }
    }
}

/** Installations that stay signed in (remembered devices). */
@Composable
private fun DevicesCard(api: ApiClient) {
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<JsonElement?>(null) }
    var reload by remember { mutableStateOf(0) }
    var askRevoke by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(reload) { list = runCatching { api.getJson("/api/devices") }.getOrNull() }
    SectionCard("Angemeldete Geräte") {
        Text("Diese Geräte bleiben angemeldet, auch wenn die App lange im Hintergrund war. „Abmelden“ im Menü entfernt das eigene Gerät.", fontSize = 13.sp)
        val items = list?.arr
        if (items == null) {
            Text("…", fontSize = 13.sp)
        } else if (items.isEmpty()) {
            Text("Keine.", fontSize = 13.sp)
        } else {
            items.forEach { d ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d["name"].str + if (d["current"].bool == true) "  (dieses Gerät)" else "", fontWeight = Bold, fontSize = 14.sp)
                        Text("zuletzt ${d["lastUsed"].str.take(10)} · seit ${d["created"].str.take(10)}", fontSize = 12.sp)
                    }
                    if (d["current"].bool != true) SmallButton("Entfernen", { askRevoke = d["id"].str to d["name"].str })
                }
            }
        }
    }
    askRevoke?.let { (id, name) ->
        AppDialog(
            onDismissRequest = { askRevoke = null },
            title = { Text("Gerät entfernen?") },
            text = { Text("„$name“ muss sich danach wieder mit Passwort anmelden.") },
            confirmButton = {
                TextButton(onClick = {
                    askRevoke = null
                    scope.launch { runCatching { api.postRaw("/api/devices/$id/revoke") }; reload++ }
                }) { Text("Entfernen") }
            },
            dismissButton = { TextButton(onClick = { askRevoke = null }) { Text("Abbrechen") } },
        )
    }
}

/** -1, 0, 1 for versions like 1.4.0 */
fun compareVersions(a: String, b: String): Int {
    val pa = a.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    val pb = b.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val x = pa.getOrElse(i) { 0 }
        val y = pb.getOrElse(i) { 0 }
        if (x != y) return x.compareTo(y)
    }
    return 0
}

private fun updateMessage(j: JsonElement): String =
    "Version ${j["oldVersion"].str} → ${j["newVersion"].str}\n${j["message"].str}"
