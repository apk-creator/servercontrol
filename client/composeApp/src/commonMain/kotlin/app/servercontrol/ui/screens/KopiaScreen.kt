package app.servercontrol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.LaunchedEffect
import kotlinx.serialization.json.JsonElement
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.servercontrol.BackHandler
import app.servercontrol.Bold
import app.servercontrol.ButtonRow
import app.servercontrol.SecretField
import app.servercontrol.SmallButton
import app.servercontrol.data.*
import app.servercontrol.net.ApiClient
import app.servercontrol.ui.*
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun KopiaScreen(api: ApiClient) {
    var open by remember { mutableStateOf<KopiaRepo?>(null) }
    var edit by remember { mutableStateOf<KopiaRepoDef?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var addMenu by remember { mutableStateOf(false) }
    var discover by remember { mutableStateOf(false) }
    var connect by remember { mutableStateOf(false) }
    val o = open
    if (o != null) {
        KopiaRepoDetail(api, o) { open = null }
        return
    }
    Loader(refresh, { api.get<List<KopiaRepo>>("/api/kopia/repos", timeoutMs = 120_000) }) { repos, reload ->
        Column(Modifier.fillMaxSize()) {
            ListHeader("${repos.size} Repositories") {
                AddButton({ addMenu = true }, "Repository hinzufügen")
                RefreshButton(reload)
            }
            if (repos.isEmpty()) Text(
                "Noch kein Repository eingetragen. Über + auf dem Server suchen, verbinden oder neu anlegen.",
                Modifier.padding(16.dp),
            )
            LazyColumn {
                itemsIndexed(repos, key = { _, r -> r.id }) { i, r ->
                    StripedItem(
                        i,
                        leading = { StatusDot(if (r.connected) StatusOk else StatusBad) },
                        headline = { Text(r.name, fontWeight = Bold) },
                        supporting = {
                            Column {
                                Text(if (r.sources.isNotEmpty()) "Sicherung: " + r.sources.joinToString() else r.configFile, fontSize = TextSmall)
                                r.error?.takeIf { it.isNotBlank() }?.let { Text(it.take(300), color = StatusBad, fontSize = TextSmall) }
                            }
                        },
                        trailing = {
                            IconButton(onClick = { edit = KopiaRepoDef(r.id, r.name, r.configFile, r.passwordFile, r.sources) }) {
                                Icon(Icons.Default.Settings, "Einstellungen")
                            }
                        },
                        onClick = { open = r },
                    )
                }
            }
        }
    }
    if (addMenu) AppDialog(
        onDismissRequest = { addMenu = false },
        title = { Text("Repository hinzufügen") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { addMenu = false; discover = true }, Modifier.fillMaxWidth()) { Text("Auf dem Server suchen") }
                Button(onClick = { addMenu = false; connect = true }, Modifier.fillMaxWidth()) { Text("Verbinden / neu anlegen") }
                Button(onClick = { addMenu = false; edit = KopiaRepoDef("", "", "/root/.config/kopia/") }, Modifier.fillMaxWidth()) { Text("Manuell via Config-Datei") }
            }
        },
        confirmButton = { TextButton(onClick = { addMenu = false }) { Text("Abbrechen") } },
    )
    edit?.let { def -> KopiaRepoEditor(api, def, onDone = { edit = null; refresh++ }) }
    if (discover) KopiaDiscoverDialog(api, onPick = { def -> discover = false; edit = def }, onDismiss = { discover = false })
    if (connect) KopiaConnectDialog(api) { connect = false; refresh++ }
}

/** /root/.config/kopia-data1/repository.config → "data1", /root/.config/kopia/repository.config → "kopia" */
private fun idFromFile(path: String): String {
    val file = path.substringAfterLast('/').removeSuffix(".config")
    val base = if (file == "repository") path.substringBeforeLast('/').substringAfterLast('/').removePrefix("kopia-") else file
    return base.lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').take(32).ifBlank { "repo" }
}

@Composable
private fun KopiaDiscoverDialog(api: ApiClient, onPick: (KopiaRepoDef) -> Unit, onDismiss: () -> Unit) {
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kopia-Konfigurationen auf dem Server") },
        text = {
            Loader(Unit, { api.getJson("/api/kopia/discover") }) { list, _ ->
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (list.arr.isEmpty()) Text("Keine gefunden. Gesucht in /root/.config/kopia*, /home/*/.config/kopia* und /etc/servercontrol/kopia.")
                    list.arr.forEachIndexed { i, f ->
                        val cf = f["configFile"].str
                        Column(Modifier.fillMaxWidth().background(stripe(i)).padding(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                StatusDot(if (f["connected"].bool == true) StatusOk else StatusBad)
                                Spacer(Modifier.width(8.dp))
                                Text(cf, fontWeight = Bold, fontSize = TextSmall)
                            }
                            if (f["connected"].bool == true) Text("${f["storage"].str}: ${f["path"].str}", fontSize = TextSmall)
                            else Text(f["error"].str.take(200), fontSize = TextSmall, color = StatusBad)
                            if (f["knownAs"].str.isNotBlank()) Text("bereits eingebunden als „${f["knownAs"].str}“", fontSize = TextSmall, color = StatusOk)
                            else TextButton(onClick = { onPick(KopiaRepoDef(idFromFile(cf), idFromFile(cf), cf)) }) { Text("Übernehmen") }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}

@Composable
private fun KopiaConnectDialog(api: ApiClient, onDone: () -> Unit) {
    var create by remember { mutableStateOf(false) }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/mnt/") }
    var password by remember { mutableStateOf("") }
    var store by remember { mutableStateOf(true) }
    var sources by remember { mutableStateOf("") }
    val host = rememberActionHost { api }
    AppDialog(
        onDismissRequest = onDone,
        title = { Text(if (create) "Neues Kopia-Repository" else "Mit Kopia-Repository verbinden") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilterPill(!create, { create = false }, "Vorhandenes")
                    Spacer(Modifier.width(8.dp))
                    FilterPill(create, { create = true }, "Neu anlegen")
                }
                Text(
                    if (create) "Legt ein neues, verschlüsseltes Repository im angegebenen Verzeichnis an (z. B. auf einer HDD)."
                    else "Verbindet ein bestehendes Repository (Verzeichnis mit kopia.repository.f). Die Daten bleiben unverändert.",
                    fontSize = TextSmall,
                )
                OutlinedTextField(id, { id = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }.take(32) },
                    Modifier.fillMaxWidth(), label = { Text("Kennung (z. B. hdd1)") }, singleLine = true)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Anzeigename") }, singleLine = true)
                OutlinedTextField(path, { path = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Repository-Verzeichnis auf dem Server") }, singleLine = true)
                SecretField(password, { password = it }, "Repository-Passwort")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(store, { store = it }); Text("Passwort auf dem Server hinterlegen (für Aktionen aus der App)", fontSize = TextSmall)
                }
                OutlinedTextField(sources, { sources = it }, Modifier.fillMaxWidth(), label = { Text("Snapshot-Quellen, eine pro Zeile (optional)") }, minLines = 2)
                if (create) Text("Wichtig: Ohne Passwort sind die Daten nicht wiederherstellbar – sicher aufbewahren!", color = StatusWarn, fontSize = TextSmall)
            }
        },
        confirmButton = {
            TextButton(enabled = id.isNotBlank() && path.startsWith("/") && password.length >= 8, onClick = {
                val body = buildJsonObject {
                    put("id", id); put("name", name.ifBlank { id }); put("path", path); put("password", password)
                    put("create", create); put("storePassword", store)
                    put("sources", buildJsonArray { sources.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) } })
                }.toString()
                host.run(if (create) "Repository angelegt" else "Verbunden", {
                    val r = api.postRaw("/api/kopia/connect", body)
                    password = ""
                    AppJson.parseToJsonElement(r)["output"].str.ifBlank { "OK" }
                }, after = { if ((host.result as? ActionResult.Message)?.ok == true) onDone() })
            }) { Text(if (create) "Anlegen" else "Verbinden") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
    ActionResultDialog(host, api)
}

@Composable
private fun KopiaRepoEditor(api: ApiClient, initial: KopiaRepoDef, onDone: () -> Unit) {
    var d by remember { mutableStateOf(initial) }
    var sources by remember { mutableStateOf(initial.sources.joinToString("\n")) }
    var err by remember { mutableStateOf<String?>(null) }
    val host = rememberActionHost { api }
    val isNew = initial.id.isBlank()
    AppDialog(
        onDismissRequest = onDone,
        title = { Text(if (isNew) "Kopia-Repository hinzufügen" else "Repository bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(d.id, { d = d.copy(id = it.lowercase().trim()) }, label = { Text("Kennung (z. B. ssd1)") }, singleLine = true, enabled = isNew)
                OutlinedTextField(d.name, { d = d.copy(name = it) }, label = { Text("Anzeigename") }, singleLine = true)
                OutlinedTextField(d.configFile, { d = d.copy(configFile = it.trim()) }, label = { Text("Kopia-Konfigurationsdatei") }, singleLine = true)
                OutlinedTextField(d.passwordFile, { d = d.copy(passwordFile = it.trim()) }, label = { Text("Passwortdatei auf dem Server (optional)") }, singleLine = true)
                OutlinedTextField(sources, { sources = it }, label = { Text("Snapshot-Quellen, eine pro Zeile (leer = alle)") }, minLines = 2)
                Text("Es wird nur die Definition gespeichert – das Repository selbst und seine Daten bleiben unverändert.",
                    style = MaterialTheme.typography.bodySmall)
                if (!isNew) {
                    var pw by remember { mutableStateOf("") }
                    SecretField(pw, { pw = it }, "Repository-Passwort auf dem Server hinterlegen")
                    TextButton(enabled = pw.isNotEmpty(), onClick = {
                        val b = buildJsonObject { put("password", pw) }.toString()
                        host.run("Passwort", { api.postRaw("/api/kopia/repos/${initial.id}/password", b); pw = ""; "Passwort hinterlegt." })
                    }) { Text("Passwort speichern") }
                }
                err?.let { Text(it, color = StatusBad) }
                if (!isNew) TextButton(onClick = {
                    host.run("Entfernt", { api.deleteRaw("/api/kopia/repos/${initial.id}"); "Definition entfernt." }, after = onDone)
                }) { Text("Aus ServerControl entfernen", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val def = d.copy(sources = sources.lines().map { it.trim() }.filter { it.isNotEmpty() })
                host.run("Gespeichert", {
                    api.postRaw("/api/kopia/repos", AppJson.encodeToString(KopiaRepoDef.serializer(), def)); "Repository „${def.name.ifBlank { def.id }}“ gespeichert."
                }, after = { if (host.result is ActionResult.Message && (host.result as ActionResult.Message).ok) onDone() })
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
    ActionResultDialog(host, api)
}

private fun localTime(iso: String): String = isoToMs(iso)?.let { formatTime(it).dropLast(3) } ?: iso.take(16).replace('T', ' ')

private fun sourceId(src: KopiaSource?): String? = src?.let { "${it.userName}@${it.host}:${it.path}" }

/** kopia JSON duration (nanoseconds) → "6 h" */
private fun hoursOf(ns: Long?): String = ns?.let { n ->
    val h = n / 3_600_000_000_000.0
    if (h >= 1 && h % 24.0 == 0.0 && h >= 48) "${(h / 24).toLong()} Tage" else if (h >= 1) "${h.toLong()} h" else "${(n / 60_000_000_000)} min"
} ?: "–"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun KopiaRepoDetail(api: ApiClient, repo: KopiaRepo, onBack: () -> Unit) {
    val host = rememberActionHost { api }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var restore by remember { mutableStateOf<KopiaSnapshot?>(null) }
    var tick by remember { mutableStateOf(0) }
    var editSchedule by remember { mutableStateOf<Pair<String, JsonElement?>?>(null) }
    var editMaint by remember { mutableStateOf<JsonElement?>(null) }
    val base = "/api/kopia/repos/${repo.id}"
    var maintAsk by remember { mutableStateOf(false) }
    BackHandler { onBack() }
    Column(Modifier.fillMaxSize()) {
        Text(repo.name, Modifier.padding(start = 16.dp, top = 8.dp), fontWeight = Bold)
        // all actions in one row
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(onClick = {
                confirm = Confirm("Snapshot starten?",
                    if (repo.sources.isEmpty()) "Alle bekannten Quellen in ${repo.name} sichern." else "Sicherung von ${repo.sources.joinToString()}") {
                    host.post("Snapshot", "$base/snapshot")
                }
            }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)) { Text("Snapshot", maxLines = 1) }
            SmallButton("Verifizieren", {
                confirm = Confirm("Repository verifizieren?", "Liest 1 % der gespeicherten Daten zurück und prüft sie. Weckt die Platte.") {
                    host.post("Verifizieren", "$base/verify", """{"percent":1}""")
                }
            })
            SmallButton("Wartung", { maintAsk = true })
            Spacer(Modifier.weight(1f))
            RefreshButton { tick++ }
        }
        Loader(repo.id to tick, {
            Triple(
                api.getJson("$base/status"),
                api.get<List<KopiaSnapshot>>("$base/snapshots", timeoutMs = 180_000),
                Unit,
            )
        }) { (status, snaps, _), _ ->
            val bySource = snaps.groupBy { it.source?.path ?: "?" }
                .mapValues { (_, l) -> l.sortedByDescending { isoToMs(it.startTime) ?: 0L } }
            val sources = (repo.sources + bySource.keys).distinct().filter { it != "?" }
            LazyColumn {
                item {
                    SectionCard("Repository", stateKey = "kopia-repo") {
                        status["error"]?.let { Text(it.str, color = StatusBad) }
                        val st = status["status"]
                        val d = status["drive"]
                        val total = d["total"].long ?: 0
                        KeyValues(
                            "Verzeichnis" to st["storage"]["config"]["path"].str.ifBlank { st["storage"]["type"].str },
                            "Laufwerk" to (d["disk"].str.ifBlank { d["device"].str }.ifBlank { "–" }),
                            "Name" to (d["model"].str.ifBlank { "–" } + (d["rotational"].bool?.let { if (it) " (HDD)" else " (SSD)" } ?: "")),
                            "Ausnutzung" to if (total > 0) "${100 * (d["used"].long ?: 0) / total} % · ${formatBytes(d["used"].long)} von ${formatBytes(total)}" else "–",
                            "Konfiguration" to repo.configFile,
                        )
                    }
                }
                sources.forEach { src ->
                    val list = bySource[src].orEmpty()
                    val suffix = if (sources.size > 1) " · $src" else ""
                    item(key = "src-$src") {
                        SectionCard("Sicherung$suffix", stateKey = "kopia-src") {
                            KeyValues(
                                "Verzeichnis" to src,
                                "Snapshots" to if (list.isEmpty()) "keine" else "${list.size} · letzter ${localTime(list.first().startTime)} · ${formatBytes(list.first().totalSize)}",
                            )
                            if (sources.size > 1) ButtonRow {
                                SmallButton("Nur diese Quelle sichern", { host.post("Snapshot $src", "$base/snapshot", """{"source":${jsonString(src)}}""") })
                            }
                        }
                    }
                    item(key = "snaps-$src") {
                        SectionCard("Snapshots$suffix", stateKey = "kopia-snaps") {
                            if (list.isEmpty()) Text("Keine Snapshots vorhanden.", fontSize = TextSmall)
                            list.forEachIndexed { i, s ->
                                Row(
                                    Modifier.fillMaxWidth().background(stripe(i)).clickable { restore = s }.padding(start = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                        Text(localTime(s.startTime))
                                        Text("${formatBytes(s.totalSize)} · ${s.files ?: "?"} Dateien" +
                                            (if ((s.errors ?: 0) > 0) " · ${s.errors} Fehler" else "") +
                                            (s.retention?.takeIf { it.isNotEmpty() }?.let { " · " + it.joinToString() } ?: ""),
                                            fontSize = TextSmall, color = if ((s.errors ?: 0) > 0) StatusWarn else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = { restore = s }) { Icon(Icons.Default.Settings, "Wiederherstellen oder löschen", Modifier.size(20.dp)) }
                                }
                            }
                        }
                    }
                }
                if (snaps.isEmpty() && sources.isEmpty()) item { Text("Keine Snapshots vorhanden.", Modifier.padding(16.dp)) }
                item(key = "schedules") {
                    SectionCard("Zeitplan", stateKey = "kopia-schedules") {
                        val srcIds = snaps.mapNotNull { sourceId(it.source) }.distinct()
                        if (srcIds.isEmpty()) Text("Zeitpläne gelten je Sicherung; sie erscheinen nach dem ersten Snapshot.", fontSize = TextSmall)
                        srcIds.forEach { id -> ScheduleBlock(api, base, id, tick, srcIds.size > 1) { pol -> editSchedule = id to pol } }
                    }
                }
                item(key = "maintenance") {
                    SectionCard("Wartung", stateKey = "kopia-maint") {
                        val m = status["maintenance"]
                        val rows = mutableListOf(
                            "Kurzwartung" to (if (m["quick"]["enabled"].bool == true) "alle ${hoursOf(m["quick"]["interval"].long)}" else "aus"),
                            "Wartung" to (if (m["full"]["enabled"].bool == true) "alle ${hoursOf(m["full"]["interval"].long)}" else "aus"),
                        )
                        m["owner"].str.takeIf { it.isNotBlank() }?.let { rows += "Zuständig" to it }
                        m["runs"].obj.forEach { (k, v) ->
                            val last = v.arr.firstOrNull()
                            if (last != null) rows += "Zuletzt $k" to localTime(last["end"].str) + if (last["success"].bool == false) " ✗" else " ✓"
                        }
                        rows.forEachIndexed { i, (k, v) -> KeyValue(k, v, i) }
                        Text("Laufen die Wartungen über eigene Skripte (z. B. nach dem Snapshot), hier „aus“ lassen.", fontSize = TextSmall)
                        ButtonRow { SmallButton("Wartung einstellen", { editMaint = m ?: kotlinx.serialization.json.JsonObject(emptyMap()) }) }
                    }
                }
                item { Spacer(Modifier.heightIn(min = 24.dp)) }
            }
        }
    }
    editSchedule?.let { (src, pol) ->
        ScheduleEditor(api, base, src, pol, host) { editSchedule = null; tick++ }
    }
    editMaint?.let { m ->
        MaintenanceEditor(base, m, host) { editMaint = null; tick++ }
    }
    if (maintAsk) AppDialog(
        onDismissRequest = { maintAsk = false },
        title = { Text("Wartung starten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Kurzwartung räumt Indexe auf und geht schnell. Die vollständige Wartung entfernt außerdem abgelaufene Snapshots und nicht mehr benötigte Daten – das kann bei großen Repositories lange dauern.", fontSize = TextSmall)
                Button(onClick = { maintAsk = false; host.post("Kurzwartung", "$base/quick-maintenance") }, Modifier.fillMaxWidth()) { Text("Kurzwartung") }
                Button(onClick = { maintAsk = false; host.post("Wartung", "$base/maintenance") }, Modifier.fillMaxWidth()) { Text("Vollständige Wartung") }
            }
        },
        confirmButton = { TextButton(onClick = { maintAsk = false }) { Text("Abbrechen") } },
    )
    restore?.let { s ->
        var target by remember(s.id) { mutableStateOf("/tmp/restore-${s.id.take(8)}") }
        AppDialog(
            onDismissRequest = { restore = null },
            title = { Text("Snapshot ${s.id.take(12)}…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quelle: ${s.source?.path}\nZeit: ${localTime(s.startTime)}")
                    OutlinedTextField(target, { target = it.trim() }, label = { Text("Wiederherstellen nach (leeres Verzeichnis)") }, singleLine = true)
                }
            },
            confirmButton = {
                Row {
                    TextButton(onClick = {
                        host.post("Wiederherstellen", "$base/restore", """{"snapshotId":${jsonString(s.id)},"target":${jsonString(target)}}"""); restore = null
                    }) { Text("Wiederherstellen") }
                    TextButton(onClick = {
                        val id = s.id
                        restore = null
                        confirm = Confirm("Snapshot löschen?", "Snapshot $id von ${s.startTime} wird endgültig gelöscht.") {
                            host.post("Snapshot löschen", "$base/delete-snapshot", """{"snapshotId":${jsonString(id)}}""")
                        }
                    }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                }
            },
            dismissButton = { TextButton(onClick = { restore = null }) { Text("Abbrechen") } },
        )
    }
    ConfirmDialog(confirm) { confirm = null }
    ActionResultDialog(host, api)
}

/** Snapshot schedule and retention of one source (effective policy). */
@Composable
private fun ScheduleBlock(api: ApiClient, base: String, source: String, tick: Int, showTitle: Boolean, onEdit: (JsonElement?) -> Unit) {
    var pol by remember(source, tick) { mutableStateOf<JsonElement?>(null) }
    var err by remember(source, tick) { mutableStateOf<String?>(null) }
    LaunchedEffect(source, tick) {
        try { pol = api.getJson("$base/schedule?source=${encQ(source)}") } catch (e: Throwable) { err = e.message }
    }
    if (showTitle) Text(source.substringAfter(':'), fontWeight = Bold)
    err?.let { Text(it, color = StatusBad, fontSize = TextSmall) }
    val p = pol ?: run { if (err == null) LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val sch = p["scheduling"]
    val times = sch["timeOfDay"].arr.joinToString(", ") { "${it["hour"].long?.toString()?.padStart(2, '0')}:${it["min"].long?.toString()?.padStart(2, '0')}" }
    val interval = sch["intervalSeconds"].long?.takeIf { it > 0 }?.let { hoursOf(it * 1_000_000_000) }
    val cron = sch["cron"].arr.joinToString("; ") { it.str }
    val ret = p["retention"]
    // Retention: only values greater than 1, e.g. "täglich 14"
    val retention = listOf("keepLatest" to "letzte", "keepHourly" to "stündlich", "keepDaily" to "täglich",
        "keepWeekly" to "wöchentlich", "keepMonthly" to "monatlich", "keepAnnual" to "jährlich")
        .mapNotNull { (key, label) -> ret[key].long?.takeIf { it > 1 }?.let { "$label $it" } }
        .joinToString(" · ")
    val rows = listOfNotNull(
        "Uhrzeiten" to times.ifBlank { "–" },
        interval?.let { "Intervall" to "alle $it" },
        cron.takeIf { it.isNotBlank() }?.let { "Crontab" to it },
        if (sch["manual"].bool == true) "Modus" to "nur manuell" else null,
        "Retention" to retention.ifBlank { "–" },
    )
    rows.forEachIndexed { i, (a, b) -> KeyValue(a, b, i) }
    ButtonRow { SmallButton("Zeitplan einstellen", { onEdit(p) }) }
}

private fun encQ(s: String) = s.replace("%", "%25").replace(" ", "%20").replace("&", "%26").replace("+", "%2B").replace("#", "%23").replace("?", "%3F")

@Composable
private fun ScheduleEditor(api: ApiClient, base: String, source: String, pol: JsonElement?, host: ActionHost, onDone: () -> Unit) {
    val sch = pol["scheduling"]
    val ret = pol["retention"]
    val initialInterval = sch["intervalSeconds"].long?.takeIf { it > 0 }?.let { (it / 3600).toString() } ?: ""
    var times by remember { mutableStateOf(sch["timeOfDay"].arr.joinToString(", ") { "${it["hour"].long?.toString()?.padStart(2, '0')}:${it["min"].long?.toString()?.padStart(2, '0')}" }) }
    var interval by remember { mutableStateOf(initialInterval) }
    var cron by remember { mutableStateOf(sch["cron"].arr.joinToString("; ") { it.str }) }
    val keys = listOf("latest" to "Letzte", "hourly" to "Stündlich", "daily" to "Täglich", "weekly" to "Wöchentlich", "monthly" to "Monatlich", "annual" to "Jährlich")
    val keep = remember {
        androidx.compose.runtime.mutableStateMapOf<String, String>().apply {
            keys.forEach { (k, _) -> put(k, ret["keep" + k.replaceFirstChar { it.uppercase() }].long?.toString() ?: "") }
        }
    }
    AppDialog(
        onDismissRequest = onDone,
        title = { Text("Zeitplan · ${source.substringAfter(':')}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Die Werte gelten für diese Sicherung. Leere Felder übernehmen die globale Vorgabe des Repositorys.", fontSize = TextSmall)
                OutlinedTextField(times, { times = it }, Modifier.fillMaxWidth(), label = { Text("Uhrzeiten (z. B. 01:00, 13:00)") }, singleLine = true)
                OutlinedTextField(interval, { interval = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(),
                    label = { Text("Zusätzlich alle … Stunden (0 = kein Intervall)") }, singleLine = true)
                OutlinedTextField(cron, { cron = it }, Modifier.fillMaxWidth(), label = { Text("Crontab (optional, z. B. 0 1 * * *)") }, singleLine = true)
                Text("Retention (Anzahl Snapshots)", fontWeight = Bold)
                keys.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { (k, label) ->
                            OutlinedTextField(keep[k] ?: "", { v -> keep[k] = v.filter(Char::isDigit).take(5) }, Modifier.weight(1f), label = { Text(label) }, singleLine = true)
                        }
                    }
                }
                Text("Der Kopia-Dienst auf dem Server übernimmt geänderte Zeitpläne, sobald er die Richtlinien neu einliest; im Zweifel seinen Dienst neu starten.", fontSize = TextSmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val body = buildJsonObject {
                    put("source", source)
                    put("times", times.replace(" ", ""))
                    put("crontab", cron.trim())
                    if (interval != initialInterval) put("intervalHours", interval.toIntOrNull() ?: 0)
                    put("keep", buildJsonObject { keys.forEach { (k, _) -> put(k, keep[k] ?: "") } })
                }.toString()
                host.run("Zeitplan gespeichert", { api.postRaw("$base/schedule", body); "Richtlinie für $source gespeichert." }, after = {
                    if ((host.result as? ActionResult.Message)?.ok == true) onDone()
                })
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
}

@Composable
private fun MaintenanceEditor(base: String, m: JsonElement, host: ActionHost, onDone: () -> Unit) {
    fun h(ns: Long?) = ns?.let { (it / 3_600_000_000_000).toString() } ?: ""
    var quick by remember { mutableStateOf(m["quick"]["enabled"].bool == true) }
    var full by remember { mutableStateOf(m["full"]["enabled"].bool == true) }
    var quickH by remember { mutableStateOf(h(m["quick"]["interval"].long)) }
    var fullH by remember { mutableStateOf(h(m["full"]["interval"].long)) }
    AppDialog(
        onDismissRequest = onDone,
        title = { Text("Automatische Wartung") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Wird vom Kopia-Dienst ausgeführt und weckt dafür die Platte des Repositorys.", fontSize = TextSmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Kurzwartung", Modifier.weight(1f)); Switch(quick, { quick = it })
                }
                OutlinedTextField(quickH, { quickH = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), label = { Text("alle … Stunden") }, singleLine = true, enabled = quick)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Wartung", Modifier.weight(1f)); Switch(full, { full = it })
                }
                OutlinedTextField(fullH, { fullH = it.filter(Char::isDigit).take(4) }, Modifier.fillMaxWidth(), label = { Text("alle … Stunden") }, singleLine = true, enabled = full)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val body = buildJsonObject {
                    put("quickEnabled", quick); put("fullEnabled", full)
                    put("quickHours", quickH.toIntOrNull() ?: 0); put("fullHours", fullH.toIntOrNull() ?: 0)
                }.toString()
                host.post("Wartung eingestellt", "$base/maintenance-schedule", body, onDone)
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Abbrechen") } },
    )
}
