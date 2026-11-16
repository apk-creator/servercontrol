package app.servercontrol.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.servercontrol.BackHandler
import app.servercontrol.Bold
import app.servercontrol.ButtonRow
import app.servercontrol.SecretField
import app.servercontrol.SmallButton
import app.servercontrol.data.AppJson
import app.servercontrol.data.KopiaSnapshot
import app.servercontrol.data.KopiaSource
import app.servercontrol.net.ApiClient
import app.servercontrol.net.ApiException
import app.servercontrol.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

// Kopia like its web UI: Snapshots · Policies · Tasks · Repository · Preferences.
// Everything goes through the agent to the API of the running Kopia server (no disk wake-up).

// ---------------- JSON helpers ----------------

internal fun JsonElement?.at(path: String): JsonElement? =
    path.split('.').fold(this) { e, k -> (e as? JsonObject)?.get(k) }?.takeIf { it !is JsonNull }

/** copy of this object with [path] set to [v] (null removes it, empty parents disappear) */
internal fun JsonObject.with(path: String, v: JsonElement?): JsonObject {
    val keys = path.split('.')
    fun go(o: JsonObject, i: Int): JsonObject {
        val m = o.toMutableMap()
        val k = keys[i]
        if (i == keys.lastIndex) {
            if (v == null) m.remove(k) else m[k] = v
        } else {
            val child = go((o[k] as? JsonObject) ?: JsonObject(emptyMap()), i + 1)
            if (child.isEmpty()) m.remove(k) else m[k] = child
        }
        return JsonObject(m)
    }
    return go(this, 0)
}

/** a policy target: global, user@host or user@host:path */
data class KopiaTarget(val userName: String = "", val host: String = "", val path: String = "") {
    val query get() = "userName=${encQuery(userName)}&host=${encQuery(host)}&path=${encQuery(path)}"
    val label get() = when {
        userName.isBlank() && host.isBlank() && path.isBlank() -> "Globale Richtlinie"
        path.isBlank() && userName.isBlank() -> "Rechner $host"
        path.isBlank() -> "$userName@$host"
        else -> path
    }
    val sortKey get() = when {
        userName.isBlank() && host.isBlank() && path.isBlank() -> "0"
        path.isBlank() && userName.isBlank() -> "1$host"
        path.isBlank() -> "2$userName@$host"
        else -> "3$path"
    }

    companion object {
        fun of(e: JsonElement?) = KopiaTarget(e["userName"].str, e["host"].str, e["path"].str)
    }
}

private fun kLocal(iso: String): String = isoToMs(iso)?.let { formatTime(it).dropLast(3) } ?: if (iso.isBlank()) "–" else iso.take(16).replace('T', ' ')

/** "vor 3 h" style for durations between two ISO times */
private fun kDuration(a: String, b: String): String {
    val s = ((isoToMs(b) ?: return "") - (isoToMs(a) ?: return "")) / 1000
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60} min"
        else -> "${s / 3600} h ${(s % 3600) / 60} min"
    }
}

// ---------------- Snapshots tab ----------------

@Composable
internal fun KopiaSnapshotsTab(api: ApiClient, base: String, host: ActionHost, tick: Int, onRestore: (KopiaSnapshot) -> Unit, onPolicy: (KopiaTarget) -> Unit, onOpenSource: (KopiaTarget) -> Unit) {
    var openSource by remember { mutableStateOf<KopiaTarget?>(null) }
    // the snapshot history opens as its own page (see KopiaRepoDetail)
    LaunchedEffect(openSource) { openSource?.let { onOpenSource(it); openSource = null } }
    var newSnap by remember { mutableStateOf(false) }
    var srcMenu by remember { mutableStateOf<JsonElement?>(null) }
    var refresh by remember { mutableStateOf(0) }
    // sources refresh by themselves while a snapshot is running
    var live by remember { mutableStateOf(0) }
    Loader(Triple(tick, refresh, live), { api.getJson("$base/srv/sources") }) { d, _ ->
        val sources = d["sources"].arr
        LaunchedEffect(d) {
            if (sources.any { it["status"].str == "UPLOADING" || it["status"].str == "PENDING" }) {
                delay(3_000); live++
            }
        }
        Column(Modifier.fillMaxSize()) {
            ListHeader("${sources.size} Quellen · ${d["localUsername"].str}@${d["localHost"].str}") {
                AddButton({ newSnap = true }, "Neuer Snapshot")
                RefreshButton { refresh++ }
            }
            Column {
                sources.forEachIndexed { i, s ->
                    val t = KopiaTarget.of(s["source"])
                    val last = s["lastSnapshot"]
                    val size = last.at("rootEntry.summ.size").long ?: last.at("stats.totalSize").long
                    val status = s["status"].str
                    StripedItem(
                        i,
                        headline = { Text(t.path, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supporting = {
                            Column {
                                Text(
                                    "${t.userName}@${t.host} · letzter ${kLocal(last["startTime"].str)}" + (size?.let { " · ${formatBytes(it)}" } ?: ""),
                                    fontSize = TextSmall,
                                )
                                val next = s["nextSnapshotTime"].str
                                if (status == "UPLOADING") {
                                    val u = s["upload"]
                                    val done = (u["hashedBytes"].long ?: 0) + (u["cachedBytes"].long ?: 0)
                                    val est = u["estimatedBytes"].long ?: 0
                                    Text("Snapshot läuft: ${formatBytes(done)}" + if (est > 0) " von ${formatBytes(est)}" else "", fontSize = TextSmall, color = StatusWarn)
                                    if (est > 0) LinearProgressIndicator(progress = { (done.toFloat() / est).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                } else {
                                    Text("nächster ${if (next.isBlank()) "– (kein Zeitplan)" else kLocal(next)}" + if (status != "IDLE") " · $status" else "", fontSize = TextSmall)
                                }
                            }
                        },
                        trailing = { IconButton(onClick = { srcMenu = s }) { Icon(Icons.Default.Settings, "Aktionen", Modifier.size(20.dp)) } },
                        onClick = { openSource = t },
                    )
                }
            }
        }
    }
    srcMenu?.let { s ->
        val t = KopiaTarget.of(s["source"])
        val uploading = s["status"].str == "UPLOADING"
        AppDialog(
            onDismissRequest = { srcMenu = null },
            title = { Text(t.path, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uploading) Button(onClick = { srcMenu = null; host.post("Snapshot abbrechen", "$base/srv/sources/cancel?${t.query}", after = { refresh++ }) }, Modifier.fillMaxWidth()) { Text("Snapshot abbrechen") }
                    else Button(onClick = { srcMenu = null; host.post("Snapshot", "$base/srv/sources/upload?${t.query}", after = { refresh++ }) }, Modifier.fillMaxWidth()) { Text("Jetzt sichern") }
                    OutlinedButton(onClick = { srcMenu = null; openSource = t }, Modifier.fillMaxWidth()) { Text("Snapshot-Verlauf") }
                    OutlinedButton(onClick = { srcMenu = null; onPolicy(t) }, Modifier.fillMaxWidth()) { Text("Richtlinie") }
                }
            },
            confirmButton = { TextButton(onClick = { srcMenu = null }) { Text("Schließen") } },
        )
    }
    if (newSnap) {
        var path by remember { mutableStateOf("") }
        AppDialog(
            onDismissRequest = { newSnap = false },
            title = { Text("Neuer Snapshot") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ordner auf dem Server, der gesichert werden soll. Er wird als neue Quelle angelegt und sofort gesichert; die Richtlinie lässt sich danach einstellen.", fontSize = TextSmall)
                    OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), label = { Text("Pfad, z. B. /srv/daten") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = path.startsWith("/"), onClick = {
                    newSnap = false
                    host.post("Neuer Snapshot", "$base/srv/sources", buildJsonObject {
                        put("path", path.trim()); put("createSnapshot", true); put("policy", JsonObject(emptyMap()))
                    }.toString(), after = { refresh++ })
                }) { Text("Sichern") }
            },
            dismissButton = { TextButton(onClick = { newSnap = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
internal fun KopiaHistory(api: ApiClient, base: String, host: ActionHost, t: KopiaTarget, tick: Int, onRestore: (KopiaSnapshot) -> Unit) {
    var all by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    var menu by remember { mutableStateOf<JsonElement?>(null) }
    var browse by remember { mutableStateOf<List<Pair<String, String>>?>(null) } // (name, object id) path
    var editDesc by remember { mutableStateOf<JsonElement?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val b = browse
    if (b != null) {
        BackHandler { browse = if (b.size > 1) b.dropLast(1) else null }
        KopiaBrowse(api, base, b) { browse = it }
        return
    }
    Loader(Triple(tick, refresh, all), { api.getJson("$base/srv/snapshots?${t.query}" + if (all) "&all=1" else "") }) { d, _ ->
        val snaps = d["snapshots"].arr.sortedByDescending { isoToMs(it["startTime"].str) ?: 0L }
        Column(Modifier.fillMaxSize()) {
            Text(t.path, Modifier.padding(start = 16.dp, top = 6.dp), fontWeight = Bold)
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${snaps.size} Snapshots" + (d["unfilteredCount"].long?.takeIf { !all && it > snaps.size }?.let { " (von $it)" } ?: ""),
                    Modifier.weight(1f), fontSize = TextSmall)
                FilterPill(all, { all = !all }, "Alle anzeigen")
                RefreshButton { refresh++ }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(snaps) { i, s ->
                    val pins = s["pins"].arr.map { it.str }
                    StripedItem(
                        i,
                        leading = if (pins.isNotEmpty()) ({ Icon(Icons.Default.PushPin, "angeheftet", Modifier.size(18.dp)) }) else null,
                        headline = { Text(kLocal(s["startTime"].str)) },
                        supporting = {
                            Column {
                                Text(
                                    listOfNotNull(
                                        s.at("summary.size").long?.let { formatBytes(it) },
                                        s.at("summary.files").long?.let { "$it Dateien" },
                                        s.at("summary.dirs").long?.let { "$it Ordner" },
                                        s.at("summary.numFailed").long?.takeIf { it > 0 }?.let { "$it Fehler" },
                                        kDuration(s["startTime"].str, s["endTime"].str).takeIf { it.isNotBlank() },
                                    ).joinToString(" · "),
                                    fontSize = TextSmall,
                                )
                                val tags = s["retention"].arr.map { it.str } + pins.map { "📌 $it" }
                                if (tags.isNotEmpty()) Text(tags.joinToString(", "), fontSize = TextSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (s["description"].str.isNotBlank()) Text(s["description"].str, fontSize = TextSmall)
                                if (s["incomplete"].str.isNotBlank()) Text("unvollständig: ${s["incomplete"].str}", fontSize = TextSmall, color = StatusWarn)
                            }
                        },
                        trailing = { IconButton(onClick = { menu = s }) { Icon(Icons.Default.Settings, "Aktionen", Modifier.size(20.dp)) } },
                        onClick = { menu = s },
                    )
                }
            }
        }
    }
    menu?.let { s ->
        val id = s["id"].str
        val pins = s["pins"].arr.map { it.str }
        fun edit(body: JsonObject, title: String) = host.post(title, "$base/srv/snapshots/edit", body.toString(), after = { refresh++ })
        AppDialog(
            onDismissRequest = { menu = null },
            title = { Text("Snapshot ${kLocal(s["startTime"].str)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ID ${id.take(16)}…", fontSize = TextSmall)
                    Button(onClick = { menu = null; browse = listOf(t.path to s["rootID"].str) }, Modifier.fillMaxWidth()) { Text("Durchsuchen") }
                    Button(onClick = {
                        menu = null
                        onRestore(KopiaSnapshot(id = id, startTime = s["startTime"].str, source = KopiaSource(t.host, t.userName, t.path)))
                    }, Modifier.fillMaxWidth()) { Text("Wiederherstellen") }
                    OutlinedButton(onClick = { menu = null; editDesc = s }, Modifier.fillMaxWidth()) { Text("Beschreibung ändern") }
                    if (pins.isEmpty()) OutlinedButton(onClick = {
                        menu = null
                        edit(buildJsonObject { put("snapshots", buildJsonArray { add(JsonPrimitive(id)) }); put("addPins", buildJsonArray { add(JsonPrimitive("do-not-delete")) }) }, "Angeheftet")
                    }, Modifier.fillMaxWidth()) { Text("Anheften (vor Löschen schützen)") }
                    pins.forEach { p ->
                        OutlinedButton(onClick = {
                            menu = null
                            edit(buildJsonObject { put("snapshots", buildJsonArray { add(JsonPrimitive(id)) }); put("removePins", buildJsonArray { add(JsonPrimitive(p)) }) }, "Pin entfernt")
                        }, Modifier.fillMaxWidth()) { Text("Pin „$p“ entfernen") }
                    }
                    OutlinedButton(onClick = {
                        menu = null
                        confirm = Confirm("Snapshot löschen?", "Snapshot vom ${kLocal(s["startTime"].str)} wird gelöscht. Der Speicher wird bei der nächsten vollständigen Wartung frei.") {
                            host.post("Snapshot löschen", "$base/srv/snapshots/delete", buildJsonObject {
                                put("source", buildJsonObject { put("host", t.host); put("userName", t.userName); put("path", t.path) })
                                put("snapshotManifestIds", buildJsonArray { add(JsonPrimitive(id)) })
                                put("deleteSourceAndPolicy", false)
                            }.toString(), after = { refresh++ })
                        }
                    }, Modifier.fillMaxWidth()) { Text("Löschen", color = StatusBad) }
                }
            },
            confirmButton = { TextButton(onClick = { menu = null }) { Text("Schließen") } },
        )
    }
    editDesc?.let { s ->
        var text by remember { mutableStateOf(s["description"].str) }
        AppDialog(
            onDismissRequest = { editDesc = null },
            title = { Text("Beschreibung") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("Beschreibung") }) },
            confirmButton = {
                TextButton(onClick = {
                    editDesc = null
                    host.post("Beschreibung", "$base/srv/snapshots/edit", buildJsonObject {
                        put("snapshots", buildJsonArray { add(JsonPrimitive(s["id"].str)) }); put("description", text)
                    }.toString(), after = { refresh++ })
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { editDesc = null }) { Text("Abbrechen") } },
        )
    }
    ConfirmDialog(confirm) { confirm = null }
}

/** read-only view of a snapshot's folders */
@Composable
private fun KopiaBrowse(api: ApiClient, base: String, path: List<Pair<String, String>>, onNavigate: (List<Pair<String, String>>?) -> Unit) {
    val oid = path.last().second
    Loader(oid, { api.getJson("$base/srv/objects/$oid") }) { d, _ ->
        val entries = d["entries"].arr.sortedWith(compareBy({ it["type"].str != "d" }, { it["name"].str.lowercase() }))
        Column(Modifier.fillMaxSize()) {
            Text(path.joinToString("/") { it.first }.replace("//", "/"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp), fontWeight = Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            ListHeader("${entries.size} Einträge · nur ansehen; Wiederherstellen über den Snapshot")
            LazyColumn(Modifier.fillMaxSize()) {
                itemsIndexed(entries) { i, e ->
                    val dir = e["type"].str == "d"
                    val size = e.at("summ.size").long ?: e["size"].long
                    StripedItem(
                        i,
                        leading = { Icon(if (dir) Icons.Default.Folder else Icons.AutoMirrored.Filled.InsertDriveFile, null, Modifier.size(20.dp)) },
                        headline = { Text(e["name"].str, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supporting = {
                            Text(listOfNotNull(size?.let { formatBytes(it) }, e.at("summ.files").long?.let { "$it Dateien" },
                                kLocal(e["mtime"].str), e["mode"].str.takeIf { it.isNotBlank() }).joinToString(" · "), fontSize = TextSmall)
                        },
                        onClick = if (dir) ({ onNavigate(path + (e["name"].str to e["obj"].str)) }) else null,
                    )
                }
            }
        }
    }
}

// ---------------- Policies tab ----------------

@Composable
internal fun KopiaPoliciesTab(api: ApiClient, base: String, host: ActionHost, tick: Int, open: KopiaTarget?, onOpen: (KopiaTarget?) -> Unit) {
    var adding by remember { mutableStateOf(false) }
    var refresh by remember { mutableStateOf(0) }
    if (open != null) {
        BackHandler { onOpen(null) }
        KopiaPolicyEditor(api, base, host, open) { onOpen(null); refresh++ }
        return
    }
    Loader(tick to refresh, { api.getJson("$base/srv/policies") to runCatching { api.getJson("$base/srv/current-user") }.getOrNull() }) { (d, me), _ ->
        val list = d["policies"].arr.map { KopiaTarget.of(it["target"]) to it["policy"] }.sortedBy { it.first.sortKey }
        Column(Modifier.fillMaxSize()) {
            ListHeader("${list.size} Richtlinien") {
                AddButton({ adding = true }, "Richtlinie anlegen")
                RefreshButton { refresh++ }
            }
            Column {
                list.forEachIndexed { i, (t, p) ->
                    val parts = listOfNotNull(
                        p.at("retention")?.let { "Aufbewahrung" },
                        p.at("files")?.let { "Dateien" },
                        p.at("errorHandling")?.let { "Fehler" },
                        p.at("compression")?.let { "Komprimierung" },
                        p.at("scheduling")?.let { "Zeitplan" },
                        p.at("upload")?.let { "Hochladen" },
                        p.at("actions")?.let { "Aktionen" },
                        p.at("logging")?.let { "Protokoll" },
                    )
                    StripedItem(
                        i,
                        headline = { Text(t.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = if (t.sortKey == "0") Bold else null) },
                        supporting = {
                            Text((if (t.path.isNotBlank()) "${t.userName}@${t.host} · " else "") + (parts.joinToString(", ").ifBlank { "nichts festgelegt" }), fontSize = TextSmall)
                        },
                        trailing = { IconButton(onClick = { onOpen(t) }) { Icon(Icons.Default.Settings, "Bearbeiten", Modifier.size(20.dp)) } },
                        onClick = { onOpen(t) },
                    )
                }
            }
        }
        if (adding) {
            var path by remember { mutableStateOf("") }
            var level by remember { mutableStateOf("path") }
            val user = me["username"].str
            val hst = me["hostname"].str
            AppDialog(
                onDismissRequest = { adding = false },
                title = { Text("Richtlinie anlegen") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterPill(level == "path", { level = "path" }, "Ordner")
                            FilterPill(level == "user", { level = "user" }, "$user@$hst")
                            FilterPill(level == "host", { level = "host" }, "Rechner")
                        }
                        if (level == "path") OutlinedTextField(path, { path = it }, Modifier.fillMaxWidth(), label = { Text("Pfad, z. B. /srv/daten") }, singleLine = true)
                    }
                },
                confirmButton = {
                    TextButton(enabled = level != "path" || path.startsWith("/"), onClick = {
                        adding = false
                        onOpen(
                            when (level) {
                                "user" -> KopiaTarget(user, hst, "")
                                "host" -> KopiaTarget("", hst, "")
                                else -> KopiaTarget(user, hst, path.trim().trimEnd('/').ifBlank { "/" })
                            },
                        )
                    }) { Text("Weiter") }
                },
                dismissButton = { TextButton(onClick = { adding = false }) { Text("Abbrechen") } },
            )
        }
    }
}

/** Policy editor with the sections of the Kopia web UI, in its order. Empty = inherited. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KopiaPolicyEditor(api: ApiClient, base: String, host: ActionHost, t: KopiaTarget, onClose: () -> Unit) {
    var policy by remember(t) { mutableStateOf<JsonObject?>(null) }
    var isNew by remember(t) { mutableStateOf(false) }
    var loadErr by remember(t) { mutableStateOf<String?>(null) }
    var resolved by remember(t) { mutableStateOf<JsonElement?>(null) }
    var algorithms by remember { mutableStateOf<JsonElement?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var showJson by remember { mutableStateOf(false) }
    LaunchedEffect(t) {
        algorithms = runCatching { api.getJson("$base/srv/repo/algorithms") }.getOrNull()
        try {
            policy = api.getJson("$base/srv/policy?${t.query}") as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: ApiException) {
            if (e.status == 404 || (e.message ?: "").contains("NOT_FOUND")) { policy = JsonObject(emptyMap()); isNew = true } else loadErr = e.message
        } catch (e: Throwable) {
            loadErr = e.message
        }
    }
    val p = policy
    LaunchedEffect(p) {
        if (p == null) return@LaunchedEffect
        delay(400)
        resolved = runCatching {
            AppJson.parseToJsonElement(api.postRaw("$base/srv/policy/resolve?${t.query}", buildJsonObject {
                put("updates", p); put("numUpcomingSnapshotTimes", 5)
            }.toString()))
        }.getOrNull()
    }
    if (loadErr != null) { ErrorCard(loadErr ?: ""); return }
    if (p == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return }
    val set: (String, JsonElement?) -> Unit = { path, v -> policy = (policy ?: JsonObject(emptyMap())).with(path, v) }
    val ctx = PolicyCtx(p, resolved, t, set)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(t.label, fontWeight = Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text((if (t.path.isNotBlank()) "${t.userName}@${t.host} · " else "") + if (isNew) "neu – noch nicht gespeichert" else "leere Felder übernehmen den Wert von oben",
                    fontSize = TextSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(onClick = {
                host.run("Richtlinie gespeichert", { api.putRaw("$base/srv/policy?${t.query}", (policy ?: JsonObject(emptyMap())).toString()); "${t.label} gespeichert." }, after = onClose)
            }) { Text("Speichern") }
            if (!isNew && t.sortKey != "0") SmallButton("Löschen", {
                confirm = Confirm("Richtlinie löschen?", "${t.label}: danach gelten die Werte der übergeordneten Richtlinie.") {
                    host.run("Richtlinie gelöscht", { api.deleteRaw("$base/srv/policy?${t.query}"); "${t.label} gelöscht." }, after = onClose)
                }
            })
            SmallButton("Schließen", onClose)
        }

        PolicySection("Snapshot-Aufbewahrung", "retention") {
            ctx.Num("Neueste Snapshots", "retention.keepLatest")
            ctx.Num("Stündliche", "retention.keepHourly")
            ctx.Num("Tägliche", "retention.keepDaily")
            ctx.Num("Wöchentliche", "retention.keepWeekly")
            ctx.Num("Monatliche", "retention.keepMonthly")
            ctx.Num("Jährliche", "retention.keepAnnual")
            ctx.Bool("Identische Snapshots ignorieren", "retention.ignoreIdenticalSnapshots")
        }
        PolicySection("Dateien", "files") {
            ctx.Lines("Ignorierte Dateien (Muster, eine pro Zeile)", "files.ignore", "z. B. *.tmp oder /cache/")
            ctx.Bool("Übergeordnete Ignorier-Regeln nicht übernehmen", "files.noParentIgnore", required = true)
            ctx.Lines("Ignorier-Dateien (Namen, eine pro Zeile)", "files.ignoreDotFiles", "z. B. .kopiaignore")
            ctx.Bool("Übergeordnete Ignorier-Dateien nicht übernehmen", "files.noParentDotFiles", required = true)
            ctx.Bool("Cache-Ordner ignorieren (CACHEDIR.TAG)", "files.ignoreCacheDirs")
            ctx.Num("Dateien größer als … Byte ignorieren", "files.maxFileSize")
            ctx.Bool("Nur dieses Dateisystem", "files.oneFileSystem")
        }
        PolicySection("Fehlerbehandlung", "errors") {
            ctx.Bool("Ordner-Lesefehler ignorieren", "errorHandling.ignoreDirectoryErrors")
            ctx.Bool("Datei-Lesefehler ignorieren", "errorHandling.ignoreFileErrors")
            ctx.Bool("Unbekannte Dateitypen ignorieren", "errorHandling.ignoreUnknownTypes")
        }
        PolicySection("Komprimierung", "compression") {
            val algos = algorithms["compression"].arr.map { it.str }.ifEmpty { listOf("zstd", "zstd-fastest", "zstd-better-compression", "s2-default", "gzip", "pgzip", "lz4", "deflate-default") }
            ctx.Select("Komprimierung", "compression.compressorName",
                listOf<Pair<JsonElement?, String>>(null to "(übernehmen)", JsonPrimitive("none") to "keine") + algos.filter { it != "none" }.map { JsonPrimitive(it) to it })
            ctx.Num("Mindestgröße (Byte)", "compression.minSize")
            ctx.Num("Höchstgröße (Byte)", "compression.maxSize")
            ctx.Lines("Nur diese Endungen komprimieren", "compression.onlyCompress", "z. B. .txt")
            ctx.Lines("Diese Endungen nie komprimieren", "compression.neverCompress", "z. B. .zip")
        }
        PolicySection("Zeitplan", "scheduling") {
            ctx.Select("Häufigkeit", "scheduling.intervalSeconds", listOf<Pair<JsonElement?, String>>(null to "(übernehmen / keine)") +
                listOf(600L to "alle 10 Minuten", 900L to "alle 15 Minuten", 1200L to "alle 20 Minuten", 1800L to "alle 30 Minuten", 3600L to "stündlich",
                    10800L to "alle 3 Stunden", 21600L to "alle 6 Stunden", 43200L to "alle 12 Stunden").map { JsonPrimitive(it.first) to it.second })
            ctx.Times("Uhrzeiten (HH:MM, durch Komma getrennt)", "scheduling.timeOfDay")
            ctx.Lines("Crontab-Ausdrücke (eine pro Zeile)", "scheduling.cron", "z. B. 0 1 * * *")
            ctx.Bool("Verpasste Snapshots nachholen", "scheduling.runMissed")
            ctx.Bool("Nur manuelle Snapshots", "scheduling.manual")
            val up = resolved["upcomingSnapshotTimes"].arr
            if (up.isNotEmpty()) Text("Nächste: " + up.joinToString(", ") { kLocal(it.str) }, fontSize = TextSmall)
            resolved["schedulingError"].str.takeIf { it.isNotBlank() }?.let { Text(it, color = StatusBad, fontSize = TextSmall) }
        }
        PolicySection("Hochladen", "upload") {
            ctx.Num("Gleichzeitige Snapshots", "upload.maxParallelSnapshots")
            ctx.Num("Gleichzeitig gelesene Dateien", "upload.maxParallelFileReads")
            ctx.Num("Parallel hochladen ab Dateigröße (Byte)", "upload.parallelUploadAboveSize")
        }
        PolicySection("Snapshot-Aktionen", "snapshot-actions") {
            ctx.Action("Vor dem Snapshot", "actions.beforeSnapshotRoot")
            ctx.Action("Nach dem Snapshot", "actions.afterSnapshotRoot")
        }
        PolicySection("Ordner-Aktionen", "folder-actions") {
            ctx.Action("Vor dem Ordner", "actions.beforeFolder")
            ctx.Action("Nach dem Ordner", "actions.afterFolder")
        }
        PolicySection("Protokollierung", "logging") {
            val levels = listOf<Pair<JsonElement?, String>>(null to "(übernehmen)", JsonPrimitive(0) to "keine", JsonPrimitive(1) to "minimal",
                JsonPrimitive(3) to "wenig", JsonPrimitive(5) to "normal", JsonPrimitive(7) to "viel", JsonPrimitive(10) to "maximal")
            ctx.Select("Ordner gesichert", "logging.directories.snapshotted", levels)
            ctx.Select("Ordner ignoriert", "logging.directories.ignored", levels)
            ctx.Select("Datei gesichert", "logging.entries.snapshotted", levels)
            ctx.Select("Datei ignoriert", "logging.entries.ignored", levels)
            ctx.Select("Cache-Treffer", "logging.entries.cacheHit", levels)
            ctx.Select("Cache-Fehlschlag", "logging.entries.cacheMiss", levels)
        }
        PolicySection("Sonstiges", "other") {
            ctx.Bool("Übergeordnete Richtlinien nicht übernehmen", "noParent", required = true)
            SmallButton(if (showJson) "JSON ausblenden" else "JSON-Darstellung", { showJson = !showJson })
            if (showJson) MonoText(AppJson.encodeToString(JsonElement.serializer(), p))
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
    ConfirmDialog(confirm) { confirm = null }
}

@Composable
private fun PolicySection(title: String, key: String, content: @Composable () -> Unit) {
    SectionCard(title, stateKey = "kpol-$key", initiallyCollapsed = true) { content() }
}

/** state of the policy editor and its field types */
private class PolicyCtx(
    val p: JsonObject,
    val resolved: JsonElement?,
    val t: KopiaTarget,
    val set: (String, JsonElement?) -> Unit,
) {
    private fun show(v: JsonElement?): String = when (v) {
        null, JsonNull -> "–"
        is JsonArray -> if (v.isEmpty()) "–" else v.joinToString(", ") { e ->
            if (e is JsonObject && e["hour"] != null) "${(e["hour"].long ?: 0).toString().padStart(2, '0')}:${(e["min"].long ?: 0).toString().padStart(2, '0')}" else e.str
        }
        is JsonPrimitive -> when (v.booleanOrNull) { true -> "ja"; false -> "nein"; null -> v.content }
        is JsonObject -> v.toString()
    }

    private fun from(path: String): String {
        val d = resolved["definition"].at(path) ?: return ""
        val src = KopiaTarget.of(d)
        return if (src == t) "hier festgelegt" else "von ${src.label}"
    }

    @Composable
    fun Effective(path: String) {
        val eff = resolved["effective"].at(path)
        val f = from(path)
        Text("wirksam: ${show(eff)}" + if (f.isNotBlank()) " · $f" else "", fontSize = TextSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    @Composable
    fun Num(label: String, path: String) {
        val cur = p.at(path)?.jsonPrimitive?.longOrNull?.toString() ?: ""
        var text by remember(path, cur) { mutableStateOf(cur) }
        OutlinedTextField(text, { v ->
            text = v.filter(Char::isDigit).take(15)
            set(path, text.toLongOrNull()?.let { JsonPrimitive(it) })
        }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, placeholder = { Text("übernehmen") })
        Effective(path)
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    fun Bool(label: String, path: String, required: Boolean = false) {
        val cur = p.at(path)?.jsonPrimitive?.booleanOrNull
        Text(label)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!required) FilterPill(cur == null, { set(path, null) }, "übernehmen")
            FilterPill(cur == true, { set(path, JsonPrimitive(true)) }, "ja")
            FilterPill(if (required) cur != true else cur == false, { set(path, if (required) null else JsonPrimitive(false)) }, "nein")
        }
        if (!required) Effective(path)
    }

    @Composable
    fun Lines(label: String, path: String, hint: String) {
        val cur = p.at(path).arr.joinToString("\n") { it.str }
        var text by remember(path, cur) { mutableStateOf(cur) }
        OutlinedTextField(text, { v ->
            text = v
            val items = v.lines().map { it.trim() }.filter { it.isNotEmpty() }
            set(path, if (items.isEmpty()) null else JsonArray(items.map { JsonPrimitive(it) }))
        }, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(hint) }, minLines = 2)
        Effective(path)
    }

    @Composable
    fun Times(label: String, path: String) {
        val cur = show(p.at(path)).takeIf { it != "–" } ?: ""
        var text by remember(path, cur) { mutableStateOf(cur) }
        var bad by remember { mutableStateOf(false) }
        OutlinedTextField(text, { v ->
            text = v
            val parts = v.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }
            val parsed = parts.map { s -> Regex("""^(\d{1,2}):(\d{2})$""").find(s)?.groupValues?.let { it[1].toInt() to it[2].toInt() } }
            bad = parsed.any { it == null || it.first > 23 || it.second > 59 }
            if (!bad) set(path, if (parsed.isEmpty()) null else JsonArray(parsed.filterNotNull().map { (h, m) -> buildJsonObject { put("hour", h); put("min", m) } }))
        }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true, isError = bad, placeholder = { Text("z. B. 01:00, 13:30") })
        Effective(path)
    }

    @Composable
    fun Select(label: String, path: String, options: List<Pair<JsonElement?, String>>) {
        val cur = p.at(path)
        var open by remember { mutableStateOf(false) }
        val curLabel = options.firstOrNull { (v, _) -> v?.toString() == cur?.toString() }?.second ?: cur?.let { show(it) } ?: options.first().second
        Text(label)
        Box {
            OutlinedButton(onClick = { open = true }) { Text(curLabel, maxLines = 1) }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (v, l) ->
                    DropdownMenuItem(text = { Text(l) }, onClick = { open = false; set(path, v) })
                }
            }
        }
        Effective(path)
    }

    @Composable
    fun Action(label: String, path: String) {
        Text(label, fontWeight = Bold)
        val script = p.at("$path.script").str
        var text by remember(path, script) { mutableStateOf(script) }
        OutlinedTextField(text, { v ->
            text = v
            set("$path.script", v.takeIf { it.isNotBlank() }?.let { JsonPrimitive(it) })
        }, Modifier.fillMaxWidth(), label = { Text("Skript bzw. Befehl") }, minLines = 2, textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace))
        Effective("$path.script")
        Num("Zeitlimit (Sekunden)", "$path.timeout")
        Select("Modus", "$path.mode", listOf<Pair<JsonElement?, String>>(null to "(übernehmen)", JsonPrimitive("essential") to "muss gelingen",
            JsonPrimitive("optional") to "Fehler ignorieren", JsonPrimitive("async") to "asynchron, Fehler ignorieren"))
    }
}

// ---------------- Tasks tab ----------------

@Composable
internal fun KopiaTasksTab(api: ApiClient, base: String, host: ActionHost, tick: Int) {
    var refresh by remember { mutableStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("all") }
    LaunchedEffect(Unit) {
        while (true) { delay(5_000); refresh++ }
    }
    Loader(tick to refresh, { api.getJson("$base/srv/tasks") }, showSpinnerOnReload = false) { d, _ ->
        val tasks = d["tasks"].arr.filter {
            when (filter) {
                "running" -> it["status"].str == "RUNNING"
                "failed" -> it["status"].str == "FAILED"
                else -> true
            }
        }.sortedByDescending { isoToMs(it["startTime"].str) ?: 0L }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterPill(filter == "all", { filter = "all" }, "Alle")
                FilterPill(filter == "running", { filter = "running" }, "Laufend")
                FilterPill(filter == "failed", { filter = "failed" }, "Fehlgeschlagen")
                Spacer(Modifier.weight(1f))
                RefreshButton { refresh++ }
            }
            if (tasks.isEmpty()) Text("Keine Aufgaben seit dem Start des Kopia-Servers.", Modifier.padding(16.dp))
            Column {
                tasks.forEachIndexed { i, x ->
                    val st = x["status"].str
                    StripedItem(
                        i,
                        leading = { StatusDot(when (st) { "SUCCESS" -> StatusOk; "RUNNING" -> StatusWarn; "CANCELED" -> MaterialTheme.colorScheme.outline; else -> StatusBad }) },
                        headline = { Text("${x["kind"].str}: ${x["description"].str}", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                        supporting = {
                            Text(kLocal(x["startTime"].str) + " · " + taskStatus(st) +
                                (x["endTime"].str.takeIf { it.isNotBlank() }?.let { " · " + kDuration(x["startTime"].str, it) } ?: "") +
                                (x["progressInfo"].str.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""), fontSize = TextSmall)
                        },
                        onClick = { open = x["id"].str },
                    )
                }
            }
        }
    }
    open?.let { id -> KopiaTaskDialog(api, base, host, id) { open = null; refresh++ } }
}

private fun taskStatus(s: String) = when (s) {
    "RUNNING" -> "läuft"; "SUCCESS" -> "erfolgreich"; "FAILED" -> "fehlgeschlagen"; "CANCELED" -> "abgebrochen"; "CANCELING" -> "wird abgebrochen"; else -> s
}

@Composable
private fun KopiaTaskDialog(api: ApiClient, base: String, host: ActionHost, id: String, onClose: () -> Unit) {
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(id) { while (true) { delay(3_000); tick++ } }
    AppDialog(
        onDismissRequest = onClose,
        title = { Text("Aufgabe") },
        text = {
            Loader(id to tick, { api.getJson("$base/srv/tasks/$id") to runCatching { api.getJson("$base/srv/tasks/$id/logs") }.getOrNull() }, showSpinnerOnReload = false) { (x, logs), _ ->
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val rows = listOf(
                        "Art" to x["kind"].str,
                        "Beschreibung" to x["description"].str,
                        "Status" to taskStatus(x["status"].str),
                        "Beginn" to kLocal(x["startTime"].str),
                        "Ende" to kLocal(x["endTime"].str),
                    ) + x["counters"].obj.map { (k, v) ->
                        val n = v["value"].long ?: 0
                        k to if (v["units"].str == "bytes") formatBytes(n) else n.toString()
                    }
                    rows.forEachIndexed { i, (k, v) -> KeyValue(k, v, i) }
                    x["errorMessage"].str.takeIf { it.isNotBlank() }?.let { Text(it, color = StatusBad, fontSize = TextSmall) }
                    if (x["status"].str == "RUNNING") SmallButton("Abbrechen", { host.post("Abbrechen", "$base/srv/tasks/$id/cancel") })
                    val lines = logs["logs"].arr
                    if (lines.isNotEmpty()) {
                        Text("Protokoll", fontWeight = Bold)
                        MonoText(lines.takeLast(300).joinToString("\n") { l -> l["msg"].str.ifBlank { l.toString() } })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Schließen") } },
    )
}

// ---------------- Repository tab (server part) ----------------

@Composable
internal fun KopiaServerRepoCard(api: ApiClient, base: String, host: ActionHost, tick: Int) {
    var refresh by remember { mutableStateOf(0) }
    var editDesc by remember { mutableStateOf<String?>(null) }
    Loader(tick to refresh, { api.getJson("$base/srv/repo/status") }) { st, _ ->
        SectionCard("Repository-Status", stateKey = "kopia-srv-repo") {
            if (st["readonly"].bool == true) Text("Nur lesend verbunden.", color = StatusWarn)
            KeyValues(
                "Beschreibung" to st["description"].str.ifBlank { "–" },
                "Verbunden als" to "${st["username"].str}@${st["hostname"].str}",
                "Konfiguration" to st["configFile"].str,
                "Speicher" to st["storage"].str,
                "Verschlüsselung" to st["encryption"].str,
                "Hash" to st["hash"].str,
                "Splitter" to st["splitter"].str,
                "Format" to st["formatVersion"].str,
                "Fehlerkorrektur" to ((st["eccOverheadPercent"].long ?: 0).takeIf { it > 0 }?.let { "$it % (${st["ecc"].str})" } ?: "aus"),
                "Interne Komprimierung" to if (st["supportsContentCompression"].bool == true) "ja" else "nein",
            )
            ButtonRow {
                SmallButton("Beschreibung ändern", { editDesc = st["description"].str })
                SmallButton("Mit Repository abgleichen", {
                    host.post("Abgleich", "$base/srv/repo/sync", after = { refresh++ })
                })
            }
        }
    }
    editDesc?.let { cur ->
        var text by remember { mutableStateOf(cur) }
        AppDialog(
            onDismissRequest = { editDesc = null },
            title = { Text("Beschreibung") },
            text = { OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Beschreibung des Repositorys") }) },
            confirmButton = {
                TextButton(enabled = text.isNotBlank(), onClick = {
                    editDesc = null
                    host.post("Beschreibung", "$base/srv/repo/description", buildJsonObject { put("description", text) }.toString(), after = { refresh++ })
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { editDesc = null }) { Text("Abbrechen") } },
        )
    }
}

// ---------------- Preferences tab: notifications ----------------

private val severities = listOf(-100 to "Ausführlich", -10 to "Erfolg", 0 to "Bericht", 10 to "Warnung", 20 to "Fehler")
private val methodNames = mapOf("email" to "E-Mail", "pushover" to "Pushover", "webhook" to "Webhook")

@Composable
internal fun KopiaPrefsTab(api: ApiClient, base: String, host: ActionHost, tick: Int) {
    var refresh by remember { mutableStateOf(0) }
    var edit by remember { mutableStateOf<JsonObject?>(null) }
    var menu by remember { mutableStateOf<JsonElement?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    Loader(tick to refresh, { api.getJson("$base/srv/notificationProfiles") }) { d, _ ->
        val list = (d as? JsonArray) ?: d["profiles"].arr
        Column {
            SectionCard("Benachrichtigungen", stateKey = "kopia-notify", actions = {
                AddButton({ edit = buildJsonObject { put("profile", ""); put("minSeverity", 0); put("method", buildJsonObject { put("type", "email"); put("config", JsonObject(emptyMap())) }) } }, "Profil anlegen")
            }) {
                if (list.isEmpty()) Text("Keine Profile.", fontSize = TextSmall)
                list.forEachIndexed { i, pr ->
                    Row(Modifier.fillMaxWidth().background(stripe(i)).clickable { menu = pr }.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(pr["profile"].str, fontWeight = Bold)
                            Text("${methodNames[pr.at("method.type").str] ?: pr.at("method.type").str} · ab ${severities.firstOrNull { it.first.toLong() == pr["minSeverity"].long }?.second ?: pr["minSeverity"].str}", fontSize = TextSmall)
                        }
                        IconButton(onClick = { menu = pr }) { Icon(Icons.Default.Settings, "Aktionen", Modifier.size(20.dp)) }
                    }
                }
            }
        }
    }
    menu?.let { pr ->
        val name = pr["profile"].str
        AppDialog(
            onDismissRequest = { menu = null },
            title = { Text(name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { menu = null; edit = pr as? JsonObject }, Modifier.fillMaxWidth()) { Text("Bearbeiten") }
                    OutlinedButton(onClick = { menu = null; host.post("Test gesendet", "$base/srv/testNotificationProfile", pr.toString()) }, Modifier.fillMaxWidth()) { Text("Testnachricht senden") }
                    OutlinedButton(onClick = {
                        menu = null
                        confirm = Confirm("Profil löschen?", "„$name“ wird gelöscht.") {
                            host.run("Gelöscht", { api.deleteRaw("$base/srv/notificationProfiles/${encQuery(name)}"); "$name gelöscht." }, after = { refresh++ })
                        }
                    }, Modifier.fillMaxWidth()) { Text("Löschen", color = StatusBad) }
                }
            },
            confirmButton = { TextButton(onClick = { menu = null }) { Text("Schließen") } },
        )
    }
    edit?.let { pr -> NotificationEditor(base, host, pr, { edit = null }) { edit = null; refresh++ } }
    ConfirmDialog(confirm) { confirm = null }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NotificationEditor(base: String, host: ActionHost, initial: JsonObject, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var name by remember { mutableStateOf(initial["profile"].str) }
    var type by remember { mutableStateOf(initial.at("method.type").str.ifBlank { "email" }) }
    var severity by remember { mutableStateOf(initial["minSeverity"].long?.toInt() ?: 0) }
    var cfg by remember { mutableStateOf((initial.at("method.config") as? JsonObject) ?: JsonObject(emptyMap())) }
    val fields: List<Triple<String, String, Boolean>> = when (type) { // key, label, secret
        "email" -> listOf(Triple("smtpServer", "SMTP-Server", false), Triple("smtpPort", "SMTP-Port", false), Triple("smtpUsername", "SMTP-Benutzer", false),
            Triple("smtpPassword", "SMTP-Passwort", true), Triple("smtpIdentity", "SMTP-Identität (optional)", false), Triple("from", "Absender", false),
            Triple("to", "Empfänger", false), Triple("cc", "CC", false))
        "pushover" -> listOf(Triple("appToken", "App-Token", true), Triple("userKey", "Benutzer- oder Gruppenschlüssel", true))
        else -> listOf(Triple("endpoint", "URL", false), Triple("method", "HTTP-Methode (POST/PUT)", false), Triple("headers", "Header (eine pro Zeile: Name: Wert)", false))
    }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial["profile"].str.isBlank()) "Neues Benachrichtigungsprofil" else "Profil bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.filter { c -> c.isLetterOrDigit() || c in "-_." } }, Modifier.fillMaxWidth(), label = { Text("Profilname") }, singleLine = true,
                    enabled = initial["profile"].str.isBlank())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    methodNames.forEach { (k, l) -> FilterPill(type == k, { type = k }, l) }
                }
                Text("Ab Schweregrad")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    severities.forEach { (v, l) -> FilterPill(severity == v, { severity = v }, l) }
                }
                fields.forEach { (key, label, secret) ->
                    val cur = cfg[key]?.let { if (it is JsonPrimitive) it.content else it.toString() } ?: ""
                    if (secret) {
                        SecretField(cur, { v -> cfg = cfg.with(key, v.takeIf { it.isNotEmpty() }?.let { JsonPrimitive(it) }) }, label)
                    } else {
                        OutlinedTextField(cur, { v ->
                            cfg = cfg.with(key, when {
                                v.isEmpty() -> null
                                key == "smtpPort" -> v.filter(Char::isDigit).toIntOrNull()?.let { JsonPrimitive(it) }
                                else -> JsonPrimitive(v)
                            })
                        }, Modifier.fillMaxWidth(), label = { Text(label) }, singleLine = key != "headers")
                    }
                }
                Text("Format")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val f = cfg["format"].str.ifBlank { "txt" }
                    FilterPill(f == "txt", { cfg = cfg.with("format", JsonPrimitive("txt")) }, "Text")
                    FilterPill(f == "html", { cfg = cfg.with("format", JsonPrimitive("html")) }, "HTML")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val body = buildJsonObject {
                    put("profile", name); put("minSeverity", severity)
                    put("method", buildJsonObject { put("type", type); put("config", cfg) })
                }.toString()
                host.post("Profil gespeichert", "$base/srv/notificationProfiles", body, onSaved)
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
