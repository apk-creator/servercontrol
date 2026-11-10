package app.servercontrol.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

private const val IM = "/api/immich"

/** Readable names for the Immich job queues */
private val jobNames = mapOf(
    "thumbnailGeneration" to "Vorschaubilder", "metadataExtraction" to "Metadaten", "videoConversion" to "Videokonvertierung",
    "faceDetection" to "Gesichtserkennung", "facialRecognition" to "Gesichtszuordnung", "smartSearch" to "Smarte Suche",
    "duplicateDetection" to "Duplikate", "backgroundTask" to "Hintergrundaufgaben", "storageTemplateMigration" to "Speichervorlage",
    "migration" to "Migration", "search" to "Suche", "sidecar" to "Sidecar-Dateien", "library" to "Externe Bibliotheken",
    "notifications" to "Benachrichtigungen", "backupDatabase" to "Datenbank-Backup", "ocr" to "Texterkennung",
)

@Composable
fun ImmichMain(api: ApiClient, onSetup: () -> Unit) {
    var tab by remember { mutableStateOf(0) }
    val host = rememberActionHost { api }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    Column(Modifier.fillMaxSize()) {
        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            listOf("Status", "Aufträge", "Benutzer", "Bibliotheken", "Einrichtung").forEachIndexed { i, t ->
                Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
            }
        }
        when (tab) {
            0 -> ImmichStatus(api)
            1 -> ImmichJobs(api, host)
            2 -> ImmichUsers(api, host) { confirm = it }
            3 -> ImmichLibraries(api, host)
            4 -> LaunchedEffect(Unit) { tab = 0; onSetup() }
        }
    }
    ConfirmDialog(confirm) { confirm = null }
    ActionResultDialog(host, api)
}

@Composable
private fun ImmichStatus(api: ApiClient) {
    Loader(Unit, {
        listOf("about", "storage", "statistics", "version-check").associateWith { p ->
            runCatching { api.getJson("$IM/server/$p") }.getOrNull()
        }
    }) { d, reload ->
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionCard("Immich") {
                val about = d["about"]
                KeyValue("Version", about["version"].str)
                d["version-check"]?.let { vc -> if (vc["releaseVersion"].str.isNotBlank()) KeyValue("Neueste Version", vc["releaseVersion"].str) }
                KeyValue("Node / FFmpeg", "${about["nodejs"].str} / ${about["ffmpeg"].str}")
                KeyValue("Lizenziert", about["licensed"].str)
                SmallButton("Neu laden", reload)
            }
            d["storage"]?.let { s ->
                SectionCard("Speicher") {
                    UsageBar("Bibliothek", s["diskUseRaw"].long ?: 0, s["diskSizeRaw"].long ?: 0, "${s["diskUse"].str} / ${s["diskSize"].str}")
                }
            }
            d["statistics"]?.let { st ->
                SectionCard("Statistik") {
                    KeyValue("Fotos", st["photos"].str)
                    KeyValue("Videos", st["videos"].str)
                    KeyValue("Belegt", formatBytes(st["usage"].long))
                    st["usageByUser"].arr.forEach { u ->
                        KeyValue(u["userName"].str, "${u["photos"].str} Fotos · ${u["videos"].str} Videos · ${formatBytes(u["usage"].long)}")
                    }
                }
            } ?: SectionCard { Text("Statistik nicht verfügbar – der API-Schlüssel braucht Admin-Rechte (server.statistics).", fontSize = 12.sp) }
        }
    }
}

@Composable
private fun ImmichJobs(api: ApiClient, host: ActionHost) {
    var jobs by remember { mutableStateOf<JsonElement?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(api, host.result) {
        while (true) {
            try { jobs = api.getJson("$IM/jobs"); err = null } catch (e: Throwable) { err = e.message }
            delay(4_000)
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        err?.let { ErrorCard(it) }
        jobs.obj.forEach { (name, j) ->
            val c = j["jobCounts"]
            val qs = j["queueStatus"]
            val paused = qs["isPaused"].bool == true
            val active = qs["isActive"].bool == true
            fun cmd(command: String, force: Boolean = false) =
                host.run("${jobNames[name] ?: name}: $command", {
                    api.putRaw("$IM/jobs/$name", """{"command":"$command","force":$force}""")
                    "Befehl „$command“ gesendet."
                })
            SectionCard(jobNames[name] ?: name) {
                Text("aktiv ${c["active"].str} · wartend ${c["waiting"].str} · verzögert ${c["delayed"].str} · fehlgeschlagen ${c["failed"].str}" +
                    (if (paused) " · PAUSIERT" else ""), fontSize = 12.sp,
                    color = if ((c["failed"].long ?: 0) > 0) StatusWarn else MaterialTheme.colorScheme.onSurface)
                ButtonRow {
                    SmallButton("Fehlende", { cmd("start") })
                    SmallButton("Alle", { cmd("start", true) })
                    if (paused) SmallButton("Fortsetzen", { cmd("resume") }) else if (active || (c["waiting"].long ?: 0) > 0) SmallButton("Pausieren", { cmd("pause") })
                    if ((c["waiting"].long ?: 0) > 0) SmallButton("Leeren", { cmd("empty") })
                    if ((c["failed"].long ?: 0) > 0) SmallButton("Fehler löschen", { cmd("clear-failed") })
                }
            }
        }
    }
}

@Composable
private fun ImmichUsers(api: ApiClient, host: ActionHost, ask: (Confirm) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<JsonElement?>(null) }
    Loader(host.result == null, { api.getJson("$IM/admin/users?withDeleted=true") }) { users, reload ->
        Column {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${users.arr.size} Benutzer", Modifier.weight(1f))
                TextButton(onClick = { adding = true }) { Text("Neu") }
            }
            LazyColumn {
                items(users.arr) { u ->
                    val deleted = u["deletedAt"].str.isNotBlank()
                    ListItem(
                        leadingContent = { StatusDot(if (deleted) StatusBad else StatusOk) },
                        headlineContent = { Text(u["name"].str + if (u["isAdmin"].bool == true) "  (Admin)" else "") },
                        supportingContent = {
                            Text("${u["email"].str} · belegt ${formatBytes(u["quotaUsageInBytes"].long)}" +
                                (u["quotaSizeInBytes"].long?.let { " von ${formatBytes(it)}" } ?: "") +
                                (if (deleted) " · gelöscht" else ""), fontSize = 12.sp)
                        },
                        modifier = Modifier.clickable { sel = u },
                    )
                    HorizontalDivider()
                }
            }
        }
        sel?.let { u ->
            val id = u["id"].str
            var quotaGb by remember(id) { mutableStateOf("") }
            AppDialog(
                onDismissRequest = { sel = null },
                title = { Text(u["name"].str) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(u["email"].str)
                        OutlinedTextField(quotaGb, { quotaGb = it.filter { c -> c.isDigit() } }, label = { Text("Kontingent in GB (leer = unbegrenzt)") }, singleLine = true)
                        ButtonRow {
                            SmallButton("Kontingent setzen", {
                                val bytes = quotaGb.toLongOrNull()?.let { (it * 1024 * 1024 * 1024).toString() } ?: "null"
                                host.run("Kontingent", { api.putRaw("$IM/admin/users/$id", """{"quotaSizeInBytes":$bytes}"""); "Gesetzt." }, reload)
                            })
                            if (u["deletedAt"].str.isNotBlank()) SmallButton("Wiederherstellen", { host.post("Wiederhergestellt", "$IM/admin/users/$id/restore", null, reload) })
                            else SmallButton("Löschen", {
                                ask(Confirm("${u["name"].str} löschen?", "Der Benutzer wird zur Löschung vorgemerkt (Wiederherstellung in der Frist möglich).") {
                                    host.run("Gelöscht", { api.deleteRaw("$IM/admin/users/$id"); "Zur Löschung vorgemerkt." }, reload)
                                })
                            })
                        }
                    }
                },
                confirmButton = { TextButton(onClick = { sel = null }) { Text("Schließen") } },
            )
        }
        if (adding) {
            var mail by remember { mutableStateOf("") }
            var pw by remember { mutableStateOf("") }
            var name by remember { mutableStateOf("") }
            AppDialog(
                onDismissRequest = { adding = false },
                title = { Text("Immich-Benutzer anlegen") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CredentialFields(mail, { mail = it }, pw, { pw = it }, onSubmit = {})
                        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                        Text("Benutzername = E-Mail-Adresse", style = MaterialTheme.typography.bodySmall)
                    }
                },
                confirmButton = {
                    TextButton(enabled = mail.contains('@') && pw.length >= 8 && name.isNotBlank(), onClick = {
                        host.post("Benutzer angelegt", "$IM/admin/users",
                            """{"email":${jsonString(mail)},"password":${jsonString(pw)},"name":${jsonString(name)}}""", reload)
                        adding = false
                    }) { Text("Anlegen") }
                },
                dismissButton = { TextButton(onClick = { adding = false }) { Text("Abbrechen") } },
            )
        }
    }
}

@Composable
private fun ImmichLibraries(api: ApiClient, host: ActionHost) {
    Loader(Unit, { api.getJson("$IM/libraries") }) { libs, _ ->
        Column(Modifier.verticalScroll(rememberScrollState())) {
            if (libs.arr.isEmpty()) Text("Keine externen Bibliotheken.", Modifier.padding(16.dp))
            libs.arr.forEach { l ->
                SectionCard(l["name"].str) {
                    Text(l["importPaths"].arr.joinToString { it.str }, fontSize = 12.sp)
                    Text("${l["assetCount"].str} Dateien · zuletzt gescannt ${l["refreshedAt"].str.take(19).replace('T', ' ')}", fontSize = 12.sp, fontWeight = Bold)
                    SmallButton("Jetzt scannen", { host.post("Scan ${l["name"].str}", "$IM/libraries/${l["id"].str}/scan") })
                }
            }
        }
    }
}

@Composable
fun ImmichScreen(api: ApiClient) {
    var refresh by remember { mutableStateOf(0) }
    var setup by remember { mutableStateOf(false) }
    Loader(refresh, { api.getJson("/api/config") }) { cfg, _ ->
        val mod = cfg["immich"]
        if (mod["enabled"].bool != true || setup) {
            Column(Modifier.fillMaxSize()) {
                if (setup) TextButton(onClick = { setup = false }) { Text("← Zurück") }
                ImmichSetup(api, mod) { setup = false; refresh++ }
            }
        } else {
            ImmichMain(api) { setup = true }
        }
    }
}
