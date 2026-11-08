package app.servercontrol.ui.screens

import androidx.compose.foundation.clickable
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
import app.servercontrol.Bold
import app.servercontrol.ButtonRow
import app.servercontrol.SmallButton
import app.servercontrol.data.*
import app.servercontrol.net.ApiClient
import app.servercontrol.ui.*

@Composable
fun KopiaScreen(api: ApiClient) {
    var open by remember { mutableStateOf<KopiaRepo?>(null) }
    var edit by remember { mutableStateOf<KopiaRepoDef?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val o = open
    if (o != null) {
        KopiaRepoDetail(api, o) { open = null }
        return
    }
    Loader(refresh, { api.get<List<KopiaRepo>>("/api/kopia/repos", timeoutMs = 120_000) }) { repos, reload ->
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${repos.size} Repositories", Modifier.weight(1f))
                SmallButton("Neu laden", reload)
                Spacer(Modifier.width(8.dp))
                Button(onClick = { edit = KopiaRepoDef("", "", "/root/.config/kopia/") }) { Text("Hinzufügen") }
            }
            if (repos.isEmpty()) Text(
                "Noch kein Repository eingetragen. Jedes Repository braucht eine eigene Kopia-Konfigurationsdatei " +
                    "(kopia --config-file=… repository connect …). Optional eine Passwortdatei (chmod 600).",
                Modifier.padding(16.dp),
            )
            LazyColumn {
                items(repos, key = { it.id }) { r ->
                    SectionCard {
                        Row(Modifier.fillMaxWidth().clickable { open = r }, verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(if (r.connected) StatusOk else StatusBad)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.name, fontWeight = Bold)
                                Text(r.configFile, style = MaterialTheme.typography.bodySmall)
                                if (r.sources.isNotEmpty()) Text("Quellen: " + r.sources.joinToString(), style = MaterialTheme.typography.bodySmall)
                                r.error?.takeIf { it.isNotBlank() }?.let { Text(it.take(300), color = StatusBad, fontSize = 12.sp) }
                            }
                            TextButton(onClick = { edit = KopiaRepoDef(r.id, r.name, r.configFile, r.passwordFile, r.sources) }) { Text("Bearbeiten") }
                        }
                    }
                }
            }
        }
    }
    edit?.let { def -> KopiaRepoEditor(api, def, onDone = { edit = null; refresh++ }) }
}

@Composable
private fun KopiaRepoEditor(api: ApiClient, initial: KopiaRepoDef, onDone: () -> Unit) {
    var d by remember { mutableStateOf(initial) }
    var sources by remember { mutableStateOf(initial.sources.joinToString("\n")) }
    var err by remember { mutableStateOf<String?>(null) }
    val host = rememberActionHost { api }
    val isNew = initial.id.isBlank()
    AlertDialog(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun KopiaRepoDetail(api: ApiClient, repo: KopiaRepo, onBack: () -> Unit) {
    val host = rememberActionHost { api }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var restore by remember { mutableStateOf<KopiaSnapshot?>(null) }
    var showSource by remember { mutableStateOf<String?>(null) }
    val base = "/api/kopia/repos/${repo.id}"
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text(repo.name, style = MaterialTheme.typography.titleMedium)
        }
        Loader(repo.id, {
            api.getJson("$base/status") to api.get<List<KopiaSnapshot>>("$base/snapshots", timeoutMs = 180_000)
        }) { (status, snaps), reload ->
            LazyColumn {
                item {
                    SectionCard("Repository") {
                        status["error"]?.let { Text(it.str, color = StatusBad) }
                        val st = status["status"]
                        KeyValue("Speicher", st["storage"]["type"].str + "  " + st["storage"]["config"]["path"].str)
                        KeyValue("Verschlüsselung", st["encryption"].str)
                        KeyValue("Hash / Splitter", "${st["hash"].str} / ${st["splitter"].str}")
                        KeyValue("Konfiguration", repo.configFile)
                        val m = status["maintenance"]
                        KeyValue("Wartung", "quick: ${m["quick"]["enabled"].str}, full: ${m["full"]["enabled"].str}")
                        m["runs"].obj.forEach { (k, v) ->
                            val last = v.arr.firstOrNull()
                            if (last != null) KeyValue("Letzte $k", last["end"].str.take(19).replace('T', ' ') + if (last["success"].bool == false) " ✗" else " ✓")
                        }
                        ButtonRow {
                            SmallButton("Snapshot jetzt", {
                                confirm = Confirm("Snapshot erstellen?",
                                    if (repo.sources.isEmpty()) "Alle bekannten Quellen in ${repo.name} sichern." else "Quellen: ${repo.sources.joinToString()}") {
                                    host.post("Snapshot", "$base/snapshot")
                                }
                            })
                            SmallButton("Wartung (full)", { host.post("Wartung", "$base/maintenance") })
                            SmallButton("Verifizieren (1 %)", { host.post("Verifizieren", "$base/verify", """{"percent":1}""") })
                            SmallButton("Neu laden", reload)
                        }
                    }
                }
                val bySource = snaps.groupBy { it.source?.path ?: "?" }
                bySource.forEach { (src, list) ->
                    val sorted = list.sortedByDescending { it.startTime }
                    val latest = sorted.firstOrNull()
                    item(key = "src-$src") {
                        SectionCard(src) {
                            Text("${list.size} Snapshots · letzter ${latest?.startTime?.take(19)?.replace('T', ' ') ?: "–"} · ${formatBytes(latest?.totalSize)}",
                                style = MaterialTheme.typography.bodySmall)
                            ButtonRow {
                                SmallButton("Jetzt sichern", { host.post("Snapshot $src", "$base/snapshot", """{"source":${jsonString(src)}}""") })
                                SmallButton(if (showSource == src) "Liste ausblenden" else "Snapshots anzeigen", { showSource = if (showSource == src) null else src })
                            }
                            if (showSource == src) sorted.forEach { s ->
                                HorizontalDivider()
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(s.startTime.take(19).replace('T', ' '), fontWeight = Bold)
                                        Text("${formatBytes(s.totalSize)} · ${s.files ?: "?"} Dateien" +
                                            (if ((s.errors ?: 0) > 0) " · ${s.errors} Fehler" else "") +
                                            (s.retention?.takeIf { it.isNotEmpty() }?.let { " · " + it.joinToString() } ?: ""),
                                            fontSize = 12.sp)
                                    }
                                    TextButton(onClick = { restore = s }) { Text("…") }
                                }
                            }
                        }
                    }
                }
                if (snaps.isEmpty()) item { Text("Keine Snapshots vorhanden.", Modifier.padding(16.dp)) }
            }
        }
    }
    restore?.let { s ->
        var target by remember(s.id) { mutableStateOf("/tmp/restore-${s.id.take(8)}") }
        AlertDialog(
            onDismissRequest = { restore = null },
            title = { Text("Snapshot ${s.id.take(12)}…") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Quelle: ${s.source?.path}\nZeit: ${s.startTime}")
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
