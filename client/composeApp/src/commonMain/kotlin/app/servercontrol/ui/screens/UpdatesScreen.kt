package app.servercontrol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.servercontrol.APP_VERSION
import app.servercontrol.AppState
import app.servercontrol.Bold
import app.servercontrol.ButtonRow
import app.servercontrol.SmallButton
import app.servercontrol.UpdateReport
import app.servercontrol.data.AppJson
import app.servercontrol.net.ApiClient
import app.servercontrol.pickFile
import app.servercontrol.ui.*
import kotlinx.coroutines.launch

/** App, agent, Nextcloud, Immich and system packages on one page, with one refresh for everything. */
@Composable
fun UpdatesScreen(state: AppState, api: ApiClient) {
    // app-wide scope: the view refreshes after an update even if it was left meanwhile
    val host = remember { ActionHost(state.appScope) { api } }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val r = state.updateReport
    val modules = state.session?.modules.orEmpty()
    LaunchedEffect(Unit) { if (state.updateReport == null) state.checkUpdates() }
    val recheck = { state.checkUpdates() }
    Column(Modifier.fillMaxSize()) {
        ListHeader(
            when {
                state.updatesChecking -> "Prüfe auf Updates (inkl. Paketlisten des Servers) …"
                r != null -> "Zuletzt geprüft um ${formatClock(r.checkedAt)}" + if (r.summary.isEmpty()) " · alles aktuell" else ""
                else -> ""
            },
        ) { RefreshButton { state.checkUpdates(refreshLists = true) } }
        if (state.updatesChecking) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (r != null) CardColumn("cards.updates", Modifier.weight(1f)) {
            if (r.errors.isNotEmpty()) card("Prüfung") {
                SectionCard("Prüfung unvollständig") {
                    r.errors.forEachIndexed { i, e -> Text(e, Modifier.fillMaxWidth().background(stripe(i)).padding(4.dp), color = StatusWarn, fontSize = TextSmall) }
                }
            }
            card("App") { AppUpdateCard(state, r) }
            card("Agent") { AgentUpdateCard(state, api, r, host) { confirm = it } }
            if (modules["nextcloud"] == true) card("Nextcloud") { NextcloudUpdateCard(r, host, recheck) { confirm = it } }
            if (modules["immich"] == true) card("Immich") { ImmichUpdateCard(r, host, recheck) { confirm = it } }
            val sys = r.system
            card("Server") {
                SectionCard("Server") {
                    if (sys == null) {
                        Text("Nicht verfügbar.", fontSize = TextSmall)
                        return@SectionCard
                    }
                    if (sys.packages.isEmpty()) Text("System ist aktuell.", color = StatusOk)
                    else Text("${sys.packages.size} Updates verfügbar (${r.securityCount} Sicherheit)", fontWeight = Bold, color = StatusWarn)
                    if (sys.rebootRequired) Text("Neustart erforderlich.", color = StatusWarn)
                    ButtonRow {
                        if (sys.packages.isNotEmpty()) {
                            SmallButton("Installieren", { confirm = Confirm("Updates installieren?", "apt-get upgrade für ${sys.packages.size} Pakete.") { host.post("Upgrade", "/api/system/updates/upgrade", after = recheck) } })
                            SmallButton("Full-Upgrade", { confirm = Confirm("Full-Upgrade ausführen?", "apt-get full-upgrade kann auch Pakete entfernen oder neu installieren.") { host.post("Full-Upgrade", "/api/system/updates/upgrade?mode=full", after = recheck) } })
                        }
                    }
                    sys.packages.forEachIndexed { i, p ->
                        StripedItem(
                            i,
                            headline = { Text(p.Name, color = if (p.Security) StatusBad else MaterialTheme.colorScheme.onSurface) },
                            supporting = { Text("${p.OldVersion} → ${p.NewVersion}\n${p.Origin}", fontSize = TextSmall) },
                        )
                    }
                }
            }
        }
    }
    ConfirmDialog(confirm) { confirm = null }
    ActionResultDialog(host, api)
}

@Composable
private fun AppUpdateCard(state: AppState, r: UpdateReport) {
    val progress = state.appUpdate
    SectionCard("App") {
        val latest = r.agent["latest"].str
        KeyValues("Installiert" to APP_VERSION, latest.takeIf { it.isNotBlank() }?.let { "Neueste" to it })
        when {
            progress != null && progress.running -> {
                Text("Lädt ${progress.asset} – läuft auch im Hintergrund weiter.", fontSize = TextSmall)
                if (progress.total > 0) {
                    LinearProgressIndicator(progress = { (progress.done.toFloat() / progress.total).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Text("${formatBytes(progress.done)}${if (progress.total > 0) " von ${formatBytes(progress.total)}" else ""}", fontSize = TextSmall)
                SmallButton("Abbrechen", { state.cancelAppUpdate() })
            }
            progress != null && !progress.ok -> {
                Text("Update fehlgeschlagen: ${progress.message}", color = StatusBad, fontSize = TextSmall)
                if (r.appLatest != null) Button(onClick = { r.appAsset?.let { state.startAppUpdate(it) } }) { Text("Erneut versuchen") }
            }
            progress != null -> Text(progress.message ?: "Fertig.", color = StatusOk, fontSize = TextSmall)
            r.appLatest != null -> Button(onClick = { r.appAsset?.let { state.startAppUpdate(it) } }) { Text("App auf ${r.appLatest} aktualisieren") }
            latest.isNotBlank() && compareVersions(latest, APP_VERSION) > 0 -> Text("Im Release ist kein Paket für diese Plattform.", color = StatusWarn, fontSize = TextSmall)
            latest.isNotBlank() -> Text("Die App ist aktuell.", color = StatusOk)
        }
    }
}

@Composable
private fun AgentUpdateCard(state: AppState, api: ApiClient, r: UpdateReport, host: ActionHost, ask: (Confirm) -> Unit) {
    val scope = rememberCoroutineScope()
    SectionCard("Agent") {
        val c = r.agent
        val err = c["error"].str
        KeyValues("Installiert" to c["current"].str, c["latest"].str.takeIf { it.isNotBlank() }?.let { "Neueste" to it + "  (" + c["publishedAt"].str.take(10) + ")" })
        if (err.isNotBlank()) Text(err, color = StatusBad, fontSize = TextSmall)
        val busy = state.agentUpdating
        val res = state.agentUpdateResult
        when {
            busy != null -> {
                Text(busy, fontSize = TextSmall)
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            res != null && !res.first && r.agentLatest != null -> {
                Text(res.second, color = StatusBad, fontSize = TextSmall)
                Button(onClick = { state.startAgentUpdate() }) { Text("Erneut versuchen") }
            }
            r.agentLatest != null -> {
                c["notes"].str.takeIf { it.isNotBlank() }?.let { Text(it.take(1500), fontSize = TextSmall) }
                Button(onClick = {
                    ask(Confirm("Agent auf ${r.agentLatest} aktualisieren?",
                        "Der Agent lädt das Release von GitHub, ersetzt sich selbst und startet neu. Die App bleibt angemeldet.") {
                        state.startAgentUpdate()
                    })
                }) { Text("Agent aktualisieren") }
            }
            c["newer"].bool == true -> Text("Das Release enthält keine Agent-Datei.", color = StatusWarn, fontSize = TextSmall)
            err.isBlank() && c != null -> Text("Der Agent ist aktuell.", color = StatusOk)
        }
    }
}

@Composable
private fun NextcloudUpdateCard(r: UpdateReport, host: ActionHost, recheck: () -> Unit, ask: (Confirm) -> Unit) {
    val nc = "/api/nextcloud"
    SectionCard("Nextcloud") {
        val u = r.nextcloud
        if (u == null) {
            Text("Prüfung nicht möglich.", fontSize = TextSmall)
            return@SectionCard
        }
        val server = r.ncServer
        val apps = u["apps"].arr
        val installed = u["installed"].str
        if (installed.isNotBlank()) KeyValues("Installiert" to installed, "Neueste" to (server ?: installed))
        if (server == null && apps.isEmpty()) {
            val out = u["stderr"].str.ifBlank { u["stdout"].str }.trim()
            Text(if (u["exitCode"].long == 0L || out.isBlank()) "Nextcloud und Apps sind aktuell." else out, color = if (u["exitCode"].long == 0L) StatusOk else StatusWarn)
        }
        if (server != null) {
            if (installed.isBlank()) Text("Nextcloud $server ist verfügbar.", fontWeight = Bold, color = StatusWarn)
            if (u["updaterAvailable"].bool == true) {
                Button(onClick = {
                    ask(Confirm("Nextcloud auf $server aktualisieren?",
                        "Der Updater schaltet den Wartungsmodus ein, lädt die neue Version, führt „occ upgrade“ aus und schaltet den Wartungsmodus wieder aus. " +
                            "Vorher sollte ein aktuelles Backup existieren. Das kann einige Minuten dauern.") {
                        host.post("Nextcloud-Update", "$nc/action/server-update", after = recheck)
                    })
                }) { Text("Nextcloud aktualisieren") }
            } else {
                Text("Der Updater (updater/updater.phar neben occ) wurde nicht gefunden.", fontSize = TextSmall)
            }
        }
        if (apps.isNotEmpty()) {
            Text("${apps.size} App-Updates", fontWeight = Bold)
            apps.forEachIndexed { i, a ->
                Row(Modifier.fillMaxWidth().background(stripe(i)).padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${a["app"].str} → ${a["version"].str}", Modifier.weight(1f))
                    TextButton(onClick = { host.post("Update ${a["app"].str}", "$nc/action/app-update", """{"app":${jsonString(a["app"].str)}}""", recheck) }) { Text("Update") }
                }
            }
            Button(onClick = { host.post("App-Updates", "$nc/action/app-update-all", after = recheck) }) { Text("Alle Apps aktualisieren") }
        }
    }
}

@Composable
private fun ImmichUpdateCard(r: UpdateReport, host: ActionHost, recheck: () -> Unit, ask: (Confirm) -> Unit) {
    SectionCard("Immich") {
        val dir = r.immichCompose["dir"].str
        val pinned = r.immichCompose["immichVersion"].str
        KeyValues(
            "Installiert" to r.immichInstalled.ifBlank { "–" },
            r.immichCheck["releaseVersion"].str.takeIf { it.isNotBlank() }?.let { "Neueste" to it.removePrefix("v") },
            dir.takeIf { it.isNotBlank() }?.let { "Compose" to it },
            pinned.takeIf { it.isNotBlank() }?.let { "IMMICH_VERSION" to it },
        )
        val latest = r.immichLatest
        if (latest == null) {
            if (r.immichInstalled.isNotBlank()) Text("Immich ist aktuell.", color = StatusOk)
        } else {
            Text("Immich $latest ist verfügbar.", fontWeight = Bold, color = StatusWarn)
            val fixed = Regex("""^v?\d+\.\d+""").containsMatchIn(pinned)
            if (fixed) Text("In .env ist die Version fest auf $pinned eingestellt – vorher dort auf v$latest (oder release) ändern, sonst bleibt die alte Version.",
                color = StatusWarn, fontSize = TextSmall)
        }
        if (dir.isNotBlank()) ButtonRow {
            SmallButton(if (latest != null) "Immich aktualisieren" else "Images neu laden", {
                ask(Confirm("Immich aktualisieren?",
                    "Lädt die Container-Images neu (docker compose pull) und startet Immich neu (docker compose up -d). " +
                        "Vorher die Release-Hinweise auf Breaking Changes prüfen. Immich ist dabei kurz nicht erreichbar.") {
                    host.post("Immich-Update", "/api/immich-compose/update", after = recheck)
                })
            })
        } else {
            Text("Compose-Projekt von Immich nicht gefunden.", fontSize = TextSmall)
        }
    }
}
