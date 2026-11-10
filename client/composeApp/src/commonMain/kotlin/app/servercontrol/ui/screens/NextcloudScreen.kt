package app.servercontrol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.put
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.servercontrol.Bold
import app.servercontrol.ButtonRow
import app.servercontrol.CredentialFields
import app.servercontrol.SmallButton
import app.servercontrol.net.ApiClient
import app.servercontrol.ui.*

private const val NC = "/api/nextcloud"

@Composable
fun NextcloudMain(api: ApiClient, onSetup: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val host = rememberActionHost { api }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val act = { title: String, action: String, body: String? -> host.post(title, "$NC/action/$action", body) }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            listOf("Status", "Benutzer", "Apps", "Wartung", "Einrichtung").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> NcStatus(api, host) { confirm = it }
            1 -> NcUsers(api, host) { confirm = it }
            2 -> NcApps(api, host) { confirm = it }
            4 -> LaunchedEffect(Unit) { tab = 0; onSetup() }
            3 -> Column(Modifier.verticalScroll(rememberScrollState())) {
                NcMaintenanceWindow(api, host)
                SectionCard("Datenbank") {
                    ButtonRow {
                        SmallButton("Fehlende Indizes", { act("Fehlende Indizes", "add-missing-indices", null) })
                        SmallButton("Fehlende Spalten", { act("Fehlende Spalten", "add-missing-columns", null) })
                        SmallButton("Fehlende Primärschlüssel", { act("Primärschlüssel", "add-missing-primary-keys", null) })
                    }
                }
                SectionCard("Reparatur & Upgrade") {
                    ButtonRow {
                        SmallButton("Reparatur", { act("Reparatur", "repair", null) })
                        SmallButton("Reparatur (umfangreich)", { confirm = Confirm("Umfangreiche Reparatur?", "maintenance:repair --include-expensive kann lange dauern.") { act("Reparatur", "repair-expensive", null) } })
                        SmallButton("occ upgrade", { confirm = Confirm("occ upgrade ausführen?", "Nur nach einem Update von Nextcloud nötig.") { act("Upgrade", "upgrade", null) } })
                    }
                }
                SectionCard("Dateien") {
                    ButtonRow {
                        SmallButton("Alle Dateien scannen", { confirm = Confirm("Alle Dateien scannen?", "files:scan --all kann bei vielen Dateien Stunden dauern.") { act("Dateiscan", "files-scan-all", null) } })
                        SmallButton("Dateicache aufräumen", { act("Cleanup", "files-cleanup", null) })
                        SmallButton("Versionen aufräumen", { act("Versionen", "versions-cleanup", null) })
                        SmallButton("Papierkorb aller leeren", { confirm = Confirm("Papierkorb aller Benutzer leeren?", "Gelöschte Dateien aller Benutzer werden endgültig entfernt.") { act("Papierkorb", "trashbin-cleanup", null) } })
                    }
                }
                SectionCard("Hintergrundjobs") {
                    Loader(host.result == null, { api.getJson("$NC/background") }) { b, _ ->
                        KeyValues(
                            "Modus" to b["mode"].str,
                            b["lastCron"].str.toLongOrNull()?.let { "Letzter Lauf" to formatTime(it * 1000) },
                        )
                    }
                    ButtonRow {
                        listOf("cron", "webcron", "ajax").forEach { m ->
                            SmallButton(m, { act("Modus $m", "background-mode", """{"mode":"$m"}""") })
                        }
                    }
                }
            }
        }
    }
    ConfirmDialog(confirm) { confirm = null }
    ActionResultDialog(host, api)
}

@Composable
private fun NcStatus(api: ApiClient, host: ActionHost, ask: (Confirm) -> Unit) {
    var upd by remember { mutableStateOf<JsonElement?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Loader(host.result == null, { api.getJson("$NC/status") }) { st, reload ->
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionCard("Nextcloud", actions = { RefreshButton(reload) }) {
                val maint = st["maintenance"].bool == true
                KeyValues(
                    "Version" to st["versionstring"].str,
                    "Installiert" to st["installed"].str,
                    "Wartungsmodus" to if (maint) "AN" else "aus",
                )
                if (st["needsDbUpgrade"].bool == true) Text("Datenbank-Upgrade erforderlich (occ upgrade).", color = StatusWarn)
                ButtonRow {
                    if (maint) SmallButton("Wartungsmodus aus", { host.post("Wartungsmodus aus", "$NC/action/maintenance-off", after = reload) })
                    else SmallButton("Wartungsmodus an", { ask(Confirm("Wartungsmodus aktivieren?", "Benutzer können Nextcloud dann nicht nutzen.") { host.post("Wartungsmodus an", "$NC/action/maintenance-on", after = reload) }) })
                    SmallButton(if (checking) "Prüfe …" else "Nach Updates suchen", {
                        checking = true
                        scope.launch {
                            upd = runCatching { api.getJson("$NC/updatecheck") }.getOrElse { e ->
                                kotlinx.serialization.json.buildJsonObject { put("stderr", e.message ?: e.toString()) }
                            }
                            checking = false
                        }
                    }, enabled = !checking)
                }
            }
            upd?.let { u ->
                SectionCard("Updates") {
                    val server = u["server"].str
                    val apps = u["apps"].arr
                    if (server.isBlank() && apps.isEmpty()) {
                        Text(u["stdout"].str.ifBlank { u["stderr"].str }.ifBlank { "Alles aktuell." }.trim(), fontSize = TextSmall)
                    }
                    if (server.isNotBlank()) {
                        Text("Nextcloud $server ist verfügbar.", fontWeight = Bold, color = StatusWarn)
                        if (u["updaterAvailable"].bool == true) {
                            Button(onClick = {
                                ask(Confirm("Nextcloud auf $server aktualisieren?",
                                    "Der Updater schaltet den Wartungsmodus ein, lädt die neue Version, führt „occ upgrade“ aus und schaltet den Wartungsmodus wieder aus. " +
                                        "Vorher sollte ein aktuelles Backup existieren. Das kann einige Minuten dauern.") {
                                    host.post("Nextcloud-Update", "$NC/action/server-update")
                                })
                            }) { Text("Nextcloud aktualisieren") }
                        } else {
                            Text("Der Updater (updater/updater.phar neben occ) wurde nicht gefunden.", fontSize = TextSmall)
                        }
                    }
                    if (apps.isNotEmpty()) {
                        Text("${apps.size} App-Updates", fontWeight = Bold)
                        apps.forEachIndexed { i, a ->
                            Row(Modifier.fillMaxWidth().background(stripe(i)).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("${a["app"].str} → ${a["version"].str}", Modifier.weight(1f))
                                TextButton(onClick = { host.post("Update ${a["app"].str}", "$NC/action/app-update", """{"app":${jsonString(a["app"].str)}}""") }) { Text("Update") }
                            }
                        }
                        Button(onClick = { host.post("App-Updates", "$NC/action/app-update-all") }) { Text("Alle Apps aktualisieren") }
                    }
                }
            }
            SectionCard("Einrichtungsprüfung") {
                Loader(Unit, { api.getJson("$NC/setupchecks") }) { checks, _ ->
                    var n = 0
                    checks.obj.forEach { (cat, items) ->
                        items.obj.values.forEach { c ->
                            val sev = c["severity"].str
                            if (sev != "success" && sev != "info") {
                                Column(Modifier.fillMaxWidth().background(stripe(n++)).padding(4.dp)) {
                                    Text("$cat · ${c["name"].str}", fontWeight = Bold, color = if (sev == "error") StatusBad else StatusWarn)
                                    Text(c["description"].str, fontSize = TextSmall)
                                }
                            }
                        }
                    }
                    if (n == 0) Text("Keine Warnungen.", color = StatusOk)
                }
            }
        }
    }
}

/** maintenance_window_start: Nextcloud runs heavy background jobs in the 4 hours after this hour (UTC). */
@Composable
private fun NcMaintenanceWindow(api: ApiClient, host: ActionHost) {
    val offsetH = (currentUtcOffsetMs() / 3_600_000).toInt()
    fun toLocal(utc: Int) = (utc + offsetH).mod(24)
    fun toUtc(local: Int) = (local - offsetH).mod(24)
    fun hh(h: Int) = h.toString().padStart(2, '0') + ":00"
    Loader(host.result == null, { api.getJson("$NC/maintenance-window") }) { w, reload ->
        val set = w["set"].bool == true
        val utc = w["startUtc"].long?.toInt() ?: 1
        var local by remember(w) { mutableStateOf(if (set) toLocal(utc).toString() else "") }
        SectionCard("Wartungsfenster", actions = { RefreshButton(reload) }) {
            KeyValues(
                "Beginn" to if (set) "${hh(toLocal(utc))} Ortszeit (${hh(utc)} UTC)" else "nicht festgelegt",
                "Dauer" to "4 Stunden",
            )
            Text("In diesem Zeitraum erledigt Nextcloud aufwendige Hintergrundjobs (z. B. Aufräumen, Vorschaubilder). " +
                "Nextcloud speichert die Zeit in UTC; mit der Sommerzeit verschiebt sich die Ortszeit um eine Stunde.", fontSize = TextSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(local, { local = it.filter(Char::isDigit).take(2) }, Modifier.weight(1f),
                    label = { Text("Beginn (Stunde, Ortszeit)") }, singleLine = true)
                val h = local.toIntOrNull()
                Button(enabled = h != null && h in 0..23, onClick = {
                    val u = toUtc(h ?: 0)
                    host.run("Wartungsfenster", { api.putRaw("$NC/maintenance-window", """{"startUtc":$u}"""); "Beginn ${hh(h ?: 0)} Ortszeit (${hh(u)} UTC)." }, reload)
                }) { Text("Setzen") }
            }
            if (set) SmallButton("Entfernen", {
                host.run("Wartungsfenster entfernt", { api.putRaw("$NC/maintenance-window", """{"startUtc":-1}"""); "Kein Wartungsfenster mehr festgelegt." }, reload)
            })
        }
    }
}

@Composable
private fun NcUsers(api: ApiClient, host: ActionHost, ask: (Confirm) -> Unit) {
    var filter by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    Loader(host.result == null, { api.getJson("$NC/users") }) { users, reload ->
        Column {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filter, { filter = it }, Modifier.weight(1f), label = { Text("Suchen") }, singleLine = true)
                AddButton({ adding = true }, "Benutzer anlegen")
                RefreshButton(reload)
            }
            val list = users.obj.entries.filter { filter.isBlank() || it.key.contains(filter, true) || it.value["display_name"].str.contains(filter, true) }
            LazyColumn {
                itemsIndexed(list, key = { _, e -> e.key }) { i, (id, u) ->
                    val enabled = u["enabled"].bool != false
                    val used = u["storage"]["used"].long ?: u["quota"]["used"].long
                    StripedItem(
                        i,
                        leading = { StatusDot(if (enabled) StatusOk else StatusBad) },
                        headline = { Text("${u["display_name"].str.ifBlank { id }}  ($id)") },
                        supporting = {
                            Text(listOf(u["email"].str, "Kontingent ${u["quota"].str.takeIf { !it.startsWith("{") } ?: u["quota"]["quota"].str}",
                                used?.let { "belegt ${formatBytes(it)}" } ?: "", "zuletzt ${u["last_seen"].str}")
                                .filter { it.isNotBlank() }.joinToString(" · "), fontSize = TextSmall)
                        },
                        onClick = { selected = id },
                    )
                }
            }
        }
        selected?.let { id ->
            val u = users[id]
            var quota by remember(id) { mutableStateOf("") }
            var pw by remember(id) { mutableStateOf("") }
            val body = """{"user":${jsonString(id)}}"""
            AppDialog(
                onDismissRequest = { selected = null },
                title = { Text(id) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ButtonRow {
                            if (u["enabled"].bool != false) SmallButton("Deaktivieren", { ask(Confirm("$id deaktivieren?", "Der Benutzer kann sich nicht mehr anmelden.") { host.post("Deaktiviert", "$NC/action/user-disable", body, reload) }) })
                            else SmallButton("Aktivieren", { host.post("Aktiviert", "$NC/action/user-enable", body, reload) })
                            SmallButton("Dateien scannen", { host.post("Scan $id", "$NC/action/user-scan", body) })
                            SmallButton("Löschen", { ask(Confirm("$id LÖSCHEN?", "Benutzer und alle seine Dateien werden endgültig gelöscht!") { host.post("Gelöscht", "$NC/action/user-delete", body, reload) }) })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(quota, { quota = it }, Modifier.weight(1f), label = { Text("Kontingent (z. B. 50 GB, none)") }, singleLine = true)
                            TextButton(enabled = quota.isNotBlank(), onClick = {
                                host.post("Kontingent", "$NC/action/user-quota", """{"user":${jsonString(id)},"quota":${jsonString(quota.trim())}}""", reload)
                            }) { Text("Setzen") }
                        }
                        Text("Neues Passwort:", style = MaterialTheme.typography.bodySmall)
                        CredentialFields(id, {}, pw, { pw = it }, onSubmit = {})
                        TextButton(enabled = pw.length >= 10, onClick = {
                            ask(Confirm("Passwort von $id zurücksetzen?", "Das neue Passwort gilt sofort.") {
                                host.post("Passwort gesetzt", "$NC/action/user-resetpassword", """{"user":${jsonString(id)},"password":${jsonString(pw)}}""")
                                pw = ""
                            })
                        }) { Text("Passwort setzen") }
                    }
                },
                confirmButton = { TextButton(onClick = { selected = null }) { Text("Schließen") } },
            )
        }
        if (adding) NcAddUser(host) { adding = false; reload() }
    }
}

@Composable
private fun NcAddUser(host: ActionHost, onDone: () -> Unit) {
    var user by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var mail by remember { mutableStateOf("") }
    var group by remember { mutableStateOf("") }
    AppDialog(
        onDismissRequest = onDone,
        title = { Text("Nextcloud-Benutzer anlegen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CredentialFields(user, { user = it }, pw, { pw = it }, onSubmit = {})
                OutlinedTextField(name, { name = it }, label = { Text("Anzeigename") }, singleLine = true)
                OutlinedTextField(mail, { mail = it }, label = { Text("E-Mail") }, singleLine = true)
                OutlinedTextField(group, { group = it }, label = { Text("Gruppe (optional)") }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = user.isNotBlank() && pw.length >= 10, onClick = {
                host.post("Benutzer angelegt", "$NC/action/user-add",
                    """{"user":${jsonString(user)},"password":${jsonString(pw)},"displayName":${jsonString(name)},"email":${jsonString(mail)},"group":${jsonString(group)}}""")
                onDone()
            }) { Text("Anlegen") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
}

@Composable
private fun NcApps(api: ApiClient, host: ActionHost, ask: (Confirm) -> Unit) {
    var filter by remember { mutableStateOf("") }
    Loader(host.result == null, { api.getJson("$NC/apps") }) { apps, reload ->
        Column {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filter, { filter = it }, Modifier.weight(1f), label = { Text("Suchen") }, singleLine = true)
                RefreshButton(reload)
            }
            Row(Modifier.padding(horizontal = 12.dp)) {
                SmallButton("Alle Apps aktualisieren", { ask(Confirm("Alle Apps aktualisieren?", "occ app:update --all") { host.post("App-Updates", "$NC/action/app-update-all") }) })
            }
            val all = apps["enabled"].obj.map { Triple(it.key, it.value.str, true) } + apps["disabled"].obj.map { Triple(it.key, it.value.str, false) }
            LazyColumn {
                itemsIndexed(all.filter { filter.isBlank() || it.first.contains(filter, true) }, key = { _, a -> a.first }) { i, (id, ver, on) ->
                    val body = """{"app":${jsonString(id)}}"""
                    StripedItem(
                        i,
                        headline = { Text(id) },
                        supporting = { Text(ver.ifBlank { "–" }, fontSize = TextSmall) },
                        trailing = {
                            Row {
                                if (on) TextButton(onClick = { host.post("Update $id", "$NC/action/app-update", body) }) { Text("Update") }
                                Switch(on, onCheckedChange = {
                                    if (on) ask(Confirm("$id deaktivieren?", "Die App wird abgeschaltet.") { host.post("$id deaktiviert", "$NC/action/app-disable", body, reload) })
                                    else host.post("$id aktiviert", "$NC/action/app-enable", body, reload)
                                })
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun NextcloudScreen(api: ApiClient) {
    var refresh by remember { mutableStateOf(0) }
    var setup by remember { mutableStateOf(false) }
    Loader(refresh, { api.getJson("/api/config") }) { cfg, _ ->
        val mod = cfg["nextcloud"]
        if (mod["enabled"].bool != true || setup) {
            Column(Modifier.fillMaxSize()) {
                if (setup) TextButton(onClick = { setup = false }) { Text("← Zurück") }
                NextcloudSetup(api, mod) { setup = false; refresh++ }
            }
        } else {
            NextcloudMain(api) { setup = true }
        }
    }
}
