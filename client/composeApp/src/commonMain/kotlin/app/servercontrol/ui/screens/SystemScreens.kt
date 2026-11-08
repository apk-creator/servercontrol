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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.servercontrol.Bold
import app.servercontrol.ButtonRow
import app.servercontrol.Section
import app.servercontrol.SmallButton
import app.servercontrol.data.*
import app.servercontrol.net.ApiClient
import app.servercontrol.ui.*
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

private fun enc(s: String) = s.replace("%", "%25").replace(" ", "%20").replace("&", "%26").replace("?", "%3F").replace("#", "%23").replace("/", "%2F")

// ======================= Overview =======================

@Composable
fun OverviewScreen(api: ApiClient, goTo: (Section) -> Unit) {
    var ov by remember { mutableStateOf<Overview?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val host = rememberActionHost { api }
    LaunchedEffect(api) {
        while (true) {
            try { ov = api.get("/api/system/overview"); err = null } catch (e: Throwable) { err = e.message }
            delay(5_000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        err?.let { ErrorCard(it) }
        val o = ov ?: run { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        if (o.rebootRequired || o.failedUnits > 0) {
            SectionCard("Hinweise") {
                if (o.rebootRequired) Text("Neustart erforderlich (nach Updates).", color = StatusWarn)
                if (o.failedUnits > 0) Text(
                    "${o.failedUnits} fehlgeschlagene Dienste – anzeigen",
                    color = StatusBad, modifier = Modifier.clickable { goTo(Section.SERVICES) },
                )
            }
        }
        SectionCard("System") {
            KeyValue("Hostname", o.hostname)
            KeyValue("Betriebssystem", o.os)
            KeyValue("Kernel", o.kernel)
            KeyValue("CPU", "${o.cpuModel} (${o.cpuCores} Threads)")
            KeyValue("Laufzeit", formatDuration(o.uptimeSeconds))
            KeyValue("Last (1/5/15)", o.load.joinToString(" / "))
        }
        SectionCard("Auslastung") {
            UsageBar("CPU", (o.cpuPercent * 10).toLong(), 1000, "${(o.cpuPercent * 10).toInt() / 10.0} %")
            val used = o.memTotal - o.memAvailable
            UsageBar("Arbeitsspeicher", used, o.memTotal, "${formatBytes(used)} / ${formatBytes(o.memTotal)}")
            if (o.swapTotal > 0) {
                val su = o.swapTotal - o.swapFree
                UsageBar("Swap", su, o.swapTotal, "${formatBytes(su)} / ${formatBytes(o.swapTotal)}")
            }
        }
        if (o.temperatures.isNotEmpty()) SectionCard("Temperaturen") {
            o.temperatures.sortedBy { it.chip + it.label }.forEach { t ->
                Row {
                    Text("${t.chip} · ${t.label}", Modifier.weight(1f))
                    Text("${(t.celsius * 10).toInt() / 10.0} °C",
                        color = when { t.celsius >= 85 -> StatusBad; t.celsius >= 70 -> StatusWarn; else -> StatusOk })
                }
            }
        }
        SectionCard("Energie") {
            ButtonRow {
                SmallButton("Neustart", {
                    confirm = Confirm("Server neu starten?", "${o.hostname} wird in 5 Sekunden neu gestartet. Die Verbindung bricht ab.") {
                        host.post("Neustart", "/api/system/power/reboot")
                    }
                })
                SmallButton("Herunterfahren", {
                    confirm = Confirm("Server herunterfahren?", "${o.hostname} wird ausgeschaltet. Ein Einschalten aus der Ferne ist danach nicht möglich (außer per Wake-on-LAN).") {
                        host.post("Herunterfahren", "/api/system/power/poweroff")
                    }
                })
            }
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
    ConfirmDialog(confirm) { confirm = null }
    ActionResultDialog(host, api)
}

// ======================= Services =======================

@Composable
fun ServicesScreen(api: ApiClient) {
    var filter by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(0) } // 0 all, 1 active, 2 failed
    var selected by remember { mutableStateOf<String?>(null) }
    Loader(Unit, { api.get<List<ServiceUnit>>("/api/system/services") }) { units, reload ->
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(filter, { filter = it }, Modifier.weight(1f), label = { Text("Suchen") }, singleLine = true)
                Spacer(Modifier.width(8.dp))
                SmallButton("Neu laden", reload)
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Alle", "Aktiv", "Fehler").forEachIndexed { i, t ->
                    FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(t) })
                }
            }
            val shown = units.filter {
                (filter.isBlank() || it.unit.contains(filter, true) || it.description.contains(filter, true)) &&
                    when (mode) { 1 -> it.active == "active"; 2 -> it.active == "failed"; else -> true }
            }
            LazyColumn {
                items(shown, key = { it.unit }) { u ->
                    ListItem(
                        leadingContent = { StatusDot(when (u.active) { "active" -> StatusOk; "failed" -> StatusBad; else -> MaterialTheme.colorScheme.outline }) },
                        headlineContent = { Text(u.unit.removeSuffix(".service"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text("${u.description}\n${u.active}/${u.sub}" + (u.enabled?.let { " · $it" } ?: ""), maxLines = 2) },
                        modifier = Modifier.clickable { selected = u.unit },
                    )
                    HorizontalDivider()
                }
            }
        }
        selected?.let { ServiceDialog(api, it, onDismiss = { selected = null; reload() }) }
    }
}

@Composable
private fun ServiceDialog(api: ApiClient, unit: String, onDismiss: () -> Unit) {
    val host = rememberActionHost { api }
    var tick by remember { mutableStateOf(0) }
    var detail by remember { mutableStateOf<JsonElement?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    LaunchedEffect(unit, tick) { detail = runCatching { api.getJson("/api/system/services/${enc(unit)}") }.getOrNull() }
    val act = { a: String, label: String ->
        host.post("$label: $unit", "/api/system/services/${enc(unit)}/$a") { tick++ }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(unit) },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = detail["properties"]
                KeyValue("Zustand", "${p["ActiveState"].str} / ${p["SubState"].str}")
                KeyValue("Autostart", p["UnitFileState"].str)
                KeyValue("PID", p["MainPID"].str)
                p["MemoryCurrent"].long?.takeIf { it < Long.MAX_VALUE / 2 }?.let { KeyValue("Speicher", formatBytes(it)) }
                KeyValue("Neustarts", p["NRestarts"].str)
                ButtonRow {
                    SmallButton("Start", { act("start", "Start") })
                    SmallButton("Neustart", { act("restart", "Neustart") })
                    SmallButton("Stopp", { confirm = Confirm("$unit stoppen?", "Der Dienst wird angehalten.") { act("stop", "Stopp") } })
                    SmallButton("Neu laden", { act("reload", "Reload") })
                    SmallButton("Autostart an", { act("enable", "Aktivieren") })
                    SmallButton("Autostart aus", { confirm = Confirm("Autostart deaktivieren?", "$unit startet beim nächsten Boot nicht mehr.") { act("disable", "Deaktivieren") } })
                    if (p["ActiveState"].str == "failed") SmallButton("Fehler zurücksetzen", { act("reset-failed", "Zurücksetzen") })
                }
                MonoText(detail["status"].str)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
    ConfirmDialog(confirm) { confirm = null }
    ActionResultDialog(host, api)
}

// ======================= Containers =======================

@Composable
fun ContainersScreen(api: ApiClient) {
    val host = rememberActionHost { api }
    var logs by remember { mutableStateOf<Pair<String, String>?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    Loader(Unit, { api.getJson("/api/containers") }) { list, reload ->
        if (list.arr.isEmpty()) Text("Keine Container gefunden (Docker/Podman nicht installiert oder keine Container).", Modifier.padding(16.dp))
        LazyColumn {
            items(list.arr) { c ->
                val name = c["Names"].str
                val running = c["State"].str == "running"
                SectionCard(name) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StatusDot(if (running) StatusOk else StatusBad); Spacer(Modifier.width(8.dp))
                        Text(c["Status"].str)
                    }
                    Text(c["Image"].str, style = MaterialTheme.typography.bodySmall)
                    if (running) Text("CPU ${c["CPUPerc"].str} · RAM ${c["MemUsage"].str}", style = MaterialTheme.typography.bodySmall)
                    ButtonRow {
                        if (!running) SmallButton("Start", { host.post("Start $name", "/api/containers/${enc(name)}/start", after = reload) })
                        if (running) SmallButton("Neustart", { host.post("Neustart $name", "/api/containers/${enc(name)}/restart", after = reload) })
                        if (running) SmallButton("Stopp", { confirm = Confirm("$name stoppen?", "Der Container wird angehalten.") { host.post("Stopp $name", "/api/containers/${enc(name)}/stop", after = reload) } })
                        SmallButton("Logs", { host.run("Logs $name", { api.getJson("/api/containers/${enc(name)}/logs")["log"].str }) })
                    }
                }
            }
        }
    }
    logs?.let { (n, l) -> AlertDialog({ logs = null }, { TextButton({ logs = null }) { Text("OK") } }, title = { Text(n) }, text = { MonoText(l) }) }
    ConfirmDialog(confirm) { confirm = null }
    ActionResultDialog(host, api)
}

// ======================= Logs =======================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(api: ApiClient) {
    var unit by remember { mutableStateOf("") }
    var grep by remember { mutableStateOf("") }
    var prio by remember { mutableStateOf("") }
    var boot by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("lines=300") }
    val prios = listOf("" to "Alle", "3" to "Fehler", "4" to "Warnungen", "6" to "Info")
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(unit, { unit = it.trim() }, Modifier.weight(1f), label = { Text("Dienst (z. B. nginx)") }, singleLine = true)
                OutlinedTextField(grep, { grep = it }, Modifier.weight(1f), label = { Text("Suchtext") }, singleLine = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                prios.forEach { (v, l) -> FilterChip(prio == v, { prio = v }, label = { Text(l) }) }
                FilterChip(boot, { boot = !boot }, label = { Text("Seit Boot") })
                Spacer(Modifier.weight(1f))
                Button(onClick = {
                    query = buildString {
                        append("lines=500")
                        if (unit.isNotBlank()) append("&unit=" + enc(if (unit.contains('.')) unit else "$unit.service"))
                        if (prio.isNotBlank()) append("&priority=$prio")
                        if (grep.isNotBlank()) append("&grep=" + enc(grep))
                        if (boot) append("&boot=1")
                    }
                }) { Text("Anzeigen") }
            }
        }
        Loader(query, { api.get<List<LogEntry>>("/api/system/logs?$query") }) { entries, _ ->
            if (entries.isEmpty()) Text("Keine Einträge.", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), reverseLayout = true) {
                items(entries.reversed()) { e ->
                    val color = when (e.priority.toIntOrNull() ?: 6) { in 0..3 -> StatusBad; 4 -> StatusWarn; else -> MaterialTheme.colorScheme.onSurface }
                    Text(
                        "${formatTime(e.time)}  ${e.ident.ifBlank { e.unit }}: ${e.message}",
                        color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp,
                        modifier = Modifier.padding(vertical = 1.dp),
                    )
                }
            }
        }
    }
}

/** epoch millis → "dd.MM. HH:mm:ss" in UTC offset of the device (rough, no tz database needed) */
fun formatTime(ms: Long): String {
    val offset = currentUtcOffsetMs()
    val t = (ms + offset) / 1000
    val days = t / 86400
    val sec = t % 86400
    val (y, m, d) = civil(days)
    fun two(n: Long) = n.toString().padStart(2, '0')
    return "${two(d)}.${two(m)}.${y % 100} ${two(sec / 3600)}:${two((sec % 3600) / 60)}:${two(sec % 60)}"
}

private fun civil(z0: Long): Triple<Long, Long, Long> {
    val z = z0 + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    return Triple(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
}

expect fun currentUtcOffsetMs(): Long

// ======================= Storage =======================

@Composable
fun StorageScreen(api: ApiClient) {
    var smartDev by remember { mutableStateOf<String?>(null) }
    Loader(Unit, { api.getJson("/api/system/storage") }) { data, reload ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Row(Modifier.padding(horizontal = 12.dp)) { Spacer(Modifier.weight(1f)); SmallButton("Neu laden", reload) }
            SectionCard("Dateisysteme") {
                AppJson.decodeFromJsonElement<List<MountInfo>>(data["mounts"] ?: kotlinx.serialization.json.JsonArray(emptyList())).forEach { m ->
                    UsageBar("${m.mount}  (${m.fstype})", m.used, m.total, "${formatBytes(m.used)} / ${formatBytes(m.total)} · frei ${formatBytes(m.free)}")
                    Text(m.device, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.heightIn(min = 4.dp))
                }
            }
            SectionCard("Laufwerke") {
                data["blockdevices"]["blockdevices"].arr.filter { it["type"].str == "disk" && !it["name"].str.startsWith("zram") }.forEach { d ->
                    Column(Modifier.fillMaxWidth().clickable { smartDev = d["path"].str }.padding(vertical = 6.dp)) {
                        Text("${d["path"].str}  ·  ${formatBytes(d["size"].long)}", fontWeight = Bold)
                        Text(listOf(d["model"].str, d["tran"].str.uppercase(), if (d["rota"].bool == true) "HDD" else "SSD")
                            .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        d["children"].arr.forEach { c ->
                            Text("   └ ${c["name"].str}  ${formatBytes(c["size"].long)}  ${c["fstype"].str}  ${c["mountpoints"].arr.joinToString { it.str }}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                }
                Text("Tippe auf ein Laufwerk für SMART-Werte.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    smartDev?.let { SmartDialog(api, it) { smartDev = null } }
}

@Composable
private fun SmartDialog(api: ApiClient, dev: String, onDismiss: () -> Unit) {
    val host = rememberActionHost { api }
    var raw by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("SMART $dev") },
        text = {
            Loader(dev, { api.getJson("/api/system/smart?device=${enc(dev)}") }) { s, _ ->
                Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val passed = s["smart_status"]["passed"].bool
                    Text(
                        when (passed) { true -> "Gesundheit: OK"; false -> "Gesundheit: FEHLER"; null -> "Gesundheit: unbekannt" },
                        color = when (passed) { true -> StatusOk; false -> StatusBad; null -> StatusWarn }, fontWeight = Bold,
                    )
                    KeyValue("Modell", s["model_name"].str)
                    KeyValue("Seriennummer", s["serial_number"].str)
                    KeyValue("Temperatur", s["temperature"]["current"].str + " °C")
                    KeyValue("Betriebsstunden", s["power_on_time"]["hours"].str)
                    KeyValue("Einschaltzyklen", s["power_cycle_count"].str)
                    val nvme = s["nvme_smart_health_information_log"]
                    if (nvme != null) {
                        KeyValue("Abnutzung", nvme["percentage_used"].str + " %")
                        KeyValue("Geschrieben", formatBytes((nvme["data_units_written"].long ?: 0) * 512_000))
                        KeyValue("Medienfehler", nvme["media_errors"].str)
                    }
                    s["ata_smart_attributes"]["table"].arr
                        .filter { it["id"].long in setOf(5L, 9L, 187L, 194L, 197L, 198L, 199L, 241L) }
                        .forEach { a -> KeyValue("${a["id"].str} ${a["name"].str}", a["raw"]["string"].str) }
                    ButtonRow {
                        SmallButton("Kurztest", { host.post("SMART-Kurztest", "/api/system/smart/test?device=${enc(dev)}&type=short") })
                        SmallButton("Langtest", { host.post("SMART-Langtest", "/api/system/smart/test?device=${enc(dev)}&type=long") })
                        SmallButton(if (raw) "Rohdaten aus" else "Rohdaten", { raw = !raw })
                    }
                    if (raw) MonoText(s.toString())
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
    ActionResultDialog(host, api)
}

// ======================= Network =======================

@Composable
fun NetworkScreen(api: ApiClient) {
    var prev by remember { mutableStateOf<Pair<Long, Map<String, Pair<Long, Long>>>?>(null) }
    var data by remember { mutableStateOf<JsonElement?>(null) }
    var rates by remember { mutableStateOf<Map<String, Pair<Long, Long>>>(emptyMap()) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(api) {
        while (true) {
            try {
                val d = api.getJson("/api/system/network")
                val now = currentTimeMs()
                val counters = d["counters"].obj.mapValues { (_, v) -> (v["rxBytes"].long ?: 0) to (v["txBytes"].long ?: 0) }
                prev?.let { (t0, c0) ->
                    val dt = ((now - t0) / 1000.0).coerceAtLeast(0.5)
                    rates = counters.mapValues { (k, v) ->
                        val o = c0[k] ?: v
                        ((v.first - o.first) / dt).toLong() to ((v.second - o.second) / dt).toLong()
                    }
                }
                prev = now to counters
                data = d; err = null
            } catch (e: Throwable) { err = e.message }
            delay(3_000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        err?.let { ErrorCard(it) }
        val d = data ?: return@Column
        d["interfaces"].arr.forEach { i ->
            val name = i["ifname"].str
            SectionCard(name) {
                KeyValue("Status", i["operstate"].str)
                KeyValue("MAC", i["address"].str)
                i["addr_info"].arr.forEach { a -> KeyValue(if (a["family"].str == "inet6") "IPv6" else "IPv4", "${a["local"].str}/${a["prefixlen"].str}") }
                rates[name]?.let { (rx, tx) -> KeyValue("Durchsatz", "↓ ${formatBytes(rx)}/s   ↑ ${formatBytes(tx)}/s") }
                d["counters"][name]?.let { c -> KeyValue("Gesamt", "↓ ${formatBytes(c["rxBytes"].long)}   ↑ ${formatBytes(c["txBytes"].long)}") }
            }
        }
        val wg = d["wireguard"].str
        if (wg.isNotBlank()) SectionCard("WireGuard (Server)") {
            wg.lines().filter { it.isNotBlank() }.forEach { l ->
                val f = l.split('\t')
                if (f.size >= 8) {
                    val hs = f[5].toLongOrNull() ?: 0
                    Text("${f[0]} · Peer ${f[1].take(8)}… · ${f[3]}", fontWeight = Bold)
                    Text("Endpoint ${f[2]} · letzter Handshake ${if (hs == 0L) "nie" else formatTime(hs * 1000)} · ↓ ${formatBytes(f[6].toLongOrNull())} ↑ ${formatBytes(f[7].toLongOrNull())}",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

expect fun currentTimeMs(): Long

// ======================= Processes =======================

@Composable
fun ProcessesScreen(api: ApiClient) {
    var sort by remember { mutableStateOf("cpu") }
    var tick by remember { mutableStateOf(0) }
    var kill by remember { mutableStateOf<ProcessInfo?>(null) }
    val host = rememberActionHost { api }
    LaunchedEffect(sort) { while (true) { delay(5_000); tick++ } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(sort == "cpu", { sort = "cpu" }, label = { Text("nach CPU") })
            FilterChip(sort == "mem", { sort = "mem" }, label = { Text("nach RAM") })
        }
        Loader(sort to tick, { api.get<List<ProcessInfo>>("/api/system/processes?sort=$sort") }) { list, _ ->
            LazyColumn {
                items(list, key = { it.pid }) { p ->
                    ListItem(
                        headlineContent = { Text("${p.comm}  (${p.pid})", maxLines = 1) },
                        supportingContent = { Text(p.args, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 11.sp) },
                        trailingContent = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${p.cpu} % CPU"); Text(formatBytes(p.rssKb * 1024), style = MaterialTheme.typography.bodySmall)
                                Text(p.user, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        modifier = Modifier.clickable { kill = p },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    kill?.let { p ->
        AlertDialog(
            onDismissRequest = { kill = null },
            title = { Text("${p.comm} (${p.pid}) beenden?") },
            text = { Text(p.args) },
            confirmButton = {
                Row {
                    TextButton({ host.post("SIGTERM ${p.pid}", "/api/system/processes/kill", """{"pid":${p.pid},"signal":"TERM"}"""); kill = null }) { Text("Beenden") }
                    TextButton({ host.post("SIGKILL ${p.pid}", "/api/system/processes/kill", """{"pid":${p.pid},"signal":"KILL"}"""); kill = null }) { Text("Erzwingen") }
                }
            },
            dismissButton = { TextButton({ kill = null }) { Text("Abbrechen") } },
        )
    }
    ActionResultDialog(host, api)
}

// ======================= Updates =======================

@Composable
fun UpdatesScreen(api: ApiClient) {
    val host = rememberActionHost { api }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    Loader(host.result == null, { api.get<UpdatesInfo>("/api/system/updates") }) { u, reload ->
        Column(Modifier.fillMaxSize()) {
            SectionCard {
                Text(if (u.packages.isEmpty()) "System ist aktuell." else "${u.packages.size} Updates verfügbar (${u.packages.count { it.Security }} Sicherheit)", fontWeight = Bold)
                if (u.rebootRequired) Text("Neustart erforderlich.", color = StatusWarn)
                ButtonRow {
                    SmallButton("Paketlisten aktualisieren", { host.post("apt update", "/api/system/updates/refresh") })
                    SmallButton("Neu laden", reload)
                    if (u.packages.isNotEmpty()) {
                        SmallButton("Installieren", { confirm = Confirm("Updates installieren?", "apt-get upgrade für ${u.packages.size} Pakete.") { host.post("Upgrade", "/api/system/updates/upgrade") } })
                        SmallButton("Full-Upgrade", { confirm = Confirm("Full-Upgrade ausführen?", "apt-get full-upgrade kann auch Pakete entfernen oder neu installieren.") { host.post("Full-Upgrade", "/api/system/updates/upgrade?mode=full") } })
                    }
                    if (u.rebootRequired) SmallButton("Neustart", { confirm = Confirm("Server neu starten?", "Neustart in 5 Sekunden.") { host.post("Neustart", "/api/system/power/reboot") } })
                }
            }
            LazyColumn {
                items(u.packages) { p ->
                    ListItem(
                        headlineContent = { Text(p.Name, color = if (p.Security) StatusBad else MaterialTheme.colorScheme.onSurface) },
                        supportingContent = { Text("${p.OldVersion} → ${p.NewVersion}\n${p.Origin}", fontSize = 12.sp) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    ConfirmDialog(confirm) { confirm = null }
    ActionResultDialog(host, api)
}

// ======================= Accounts =======================

@Composable
fun AccountsScreen(api: ApiClient) {
    Loader(Unit, { api.getJson("/api/system/accounts") }) { d, _ ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionCard("Benutzerkonten") {
                d["accounts"].arr.forEach { a ->
                    Text("${a["name"].str}  (UID ${a["uid"].str})", fontWeight = Bold)
                    Text("${a["gecos"].str} · ${a["home"].str} · ${a["shell"].str}", style = MaterialTheme.typography.bodySmall)
                }
            }
            SectionCard("Letzte Anmeldungen") { MonoText(d["lastLogins"].str) }
        }
    }
}

// ======================= Jobs =======================

@Composable
fun JobsScreen(api: ApiClient) {
    var open by remember { mutableStateOf<JobInfo?>(null) }
    Loader(open == null, { api.get<List<JobInfo>>("/api/jobs").sortedByDescending { it.started } }) { jobs, reload ->
        Column {
            Row(Modifier.padding(horizontal = 12.dp)) { Text("Aufgaben der letzten 24 h", Modifier.weight(1f).padding(top = 8.dp)); SmallButton("Neu laden", reload) }
            if (jobs.isEmpty()) Text("Keine Aufgaben.", Modifier.padding(16.dp))
            LazyColumn {
                items(jobs, key = { it.id }) { j ->
                    ListItem(
                        leadingContent = { StatusDot(when { j.finished == null -> StatusWarn; j.exitCode == 0 -> StatusOk; else -> StatusBad }) },
                        headlineContent = { Text(j.title) },
                        supportingContent = { Text(j.started.take(19).replace('T', ' ') + if (j.finished == null) " · läuft" else " · Exit ${j.exitCode}") },
                        modifier = Modifier.clickable { open = j },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
    open?.let { JobDialog(api, it.id, it.title) { open = null } }
}

// ======================= Shell (opt-in on the agent) =======================

@Composable
fun ShellScreen(api: ApiClient) {
    var cmd by remember { mutableStateOf("") }
    var background by remember { mutableStateOf(false) }
    val host = rememberActionHost { api }
    val history = remember { mutableStateOf(listOf<Pair<String, CmdResult>>()) }
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Befehle laufen als root auf dem Server. Nur aktiv, wenn allowShell in der Agent-Konfiguration gesetzt ist.",
            style = MaterialTheme.typography.bodySmall, color = StatusWarn)
        OutlinedTextField(cmd, { cmd = it }, Modifier.fillMaxWidth(), label = { Text("Befehl") },
            textStyle = LocalTextStyleMono())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(background, { background = it }); Text("Im Hintergrund (lange Befehle)")
            Spacer(Modifier.weight(1f))
            Button(enabled = cmd.isNotBlank(), onClick = {
                val c = cmd
                val body = """{"command":${jsonString(c)},"background":$background}"""
                if (background) host.post(c, "/api/system/shell", body)
                else host.run(c, {
                    val r = AppJson.decodeFromString<CmdResult>(api.postRaw("/api/system/shell", body))
                    history.value = listOf(c to r) + history.value.take(19)
                    (r.stdout + r.stderr).ifBlank { "(keine Ausgabe, Exit ${r.exitCode})" }
                })
            }) { Text("Ausführen") }
        }
        LazyColumn {
            items(history.value) { (c, r) ->
                Text("$ $c   (Exit ${r.exitCode})", fontFamily = FontFamily.Monospace, fontWeight = Bold, modifier = Modifier.clickable { cmd = c })
                MonoText((r.stdout + r.stderr).take(4000))
                HorizontalDivider()
            }
        }
    }
    ActionResultDialog(host, api)
}

@Composable
private fun LocalTextStyleMono() = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
