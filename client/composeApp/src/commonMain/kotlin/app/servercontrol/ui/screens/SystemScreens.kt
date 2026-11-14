package app.servercontrol.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.ui.graphics.Color
import kotlin.math.roundToInt
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

private fun enc(s: String) = s.replace("%", "%25").replace("+", "%2B").replace(" ", "%20").replace("&", "%26").replace("?", "%3F").replace("#", "%23").replace("/", "%2F")

// ======================= Overview =======================

private fun two(n: Long) = n.toString().padStart(2, '0')

/** epoch millis → "HH:mm" in the device's time zone */
fun formatClock(ms: Long): String {
    val t = ((ms + currentUtcOffsetMs()) / 1000).mod(86400L)
    return "${two(t / 3600)}:${two((t % 3600) / 60)}"
}

private fun c(v: Double) = "${v.roundToInt()}"
private fun tempColor(max: Double) = when { max >= 85 -> StatusBad; max >= 70 -> StatusWarn; else -> StatusOk }
private fun tempLine(t: TempStats) = "min ${c(t.min)} · Ø ${c(t.avg)} · max ${c(t.max)} °C"

@Composable
fun OverviewScreen(api: ApiClient, goTo: (Section) -> Unit) {
    var ov by remember { mutableStateOf<Overview?>(null) }
    var hist by remember { mutableStateOf<MetricHistory?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var power by remember { mutableStateOf<String?>(null) } // "reboot" / "poweroff"
    var popup by remember { mutableStateOf<String?>(null) } // "cores" / "board" / "hdd"
    val host = rememberActionHost { api }
    LaunchedEffect(api) {
        while (true) {
            try { ov = api.get("/api/system/overview"); err = null } catch (e: Throwable) { err = e.message }
            delay(5_000)
        }
    }
    LaunchedEffect(api) {
        while (true) {
            runCatching { hist = api.get("/api/system/history") }
            delay(30_000)
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // restart / shut down: two big fields at the very top
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PowerTile("Neustart", Icons.Default.RestartAlt, Modifier.weight(1f)) { power = "reboot" }
            PowerTile("Herunterfahren", Icons.Default.PowerSettingsNew, Modifier.weight(1f)) { power = "poweroff" }
        }
        err?.let { ErrorCard(it) }
        val o = ov ?: run { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
        o.scheduledPower?.takeIf { it.at > 0 }?.let { sp ->
            SectionCard(collapsible = false) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${if (sp.mode == "poweroff") "Herunterfahren" else "Neustart"} geplant um ${formatClock(sp.at)}", Modifier.weight(1f), color = StatusWarn, fontWeight = Bold)
                    SmallButton("Abbrechen", { host.post("Abgebrochen", "/api/system/power/cancel") })
                }
            }
        }
        if (o.rebootRequired || o.failedUnits > 0) {
            SectionCard("Hinweise") {
                var h = 0
                if (o.rebootRequired) KeyValue("Neustart", "erforderlich (nach Updates)", h++, StatusWarn)
                if (o.failedUnits > 0) KeyValue("Dienste", "${o.failedUnits} fehlgeschlagen – anzeigen", h++, StatusBad) { goTo(Section.SERVICES) }
            }
        }
        SectionCard("System") {
            var i = 0
            KeyValue("Hostname", o.hostname, i++)
            KeyValue("Betriebssystem", o.os, i++)
            KeyValue("Kernel", o.kernel, i++)
            KeyValue("CPU", "${o.cpuModel} (${o.cpuCores} Threads)", i++)
            KeyValue("Laufzeit", formatDuration(o.uptimeSeconds), i++)
            if (o.tempBoard.count > 0) {
                KeyValue("Mainboard", tempLine(o.tempBoard), i++, tempColor(o.tempBoard.max)) { popup = "board" }
            }
            if (o.hddTemps.isNotEmpty()) {
                val v = when {
                    o.tempDisks.count == 0 -> "alle im Standby"
                    else -> tempLine(o.tempDisks) + if (o.hddStandby > 0) "  (${o.hddStandby} im Standby)" else ""
                }
                KeyValue("HDDs", v, i++, if (o.tempDisks.count > 0) tempColor(o.tempDisks.max) else Color.Unspecified) { popup = "hdd" }
            }
        }
        SectionCard("Auslastung") {
            var n = 0
            Column(Modifier.clickable { popup = "cores" }) {
                UsageBar("CPU  ›", (o.cpuPercent * 10).toLong(), 1000, "${(o.cpuPercent * 10).roundToInt() / 10.0} %", n++)
            }
            val used = o.memTotal - o.memAvailable
            UsageBar("Arbeitsspeicher", used, o.memTotal, "${formatBytes(used)} / ${formatBytes(o.memTotal)}", n++)
            if (o.swapTotal > 0) {
                val su = o.swapTotal - o.swapFree
                UsageBar("Swap", su, o.swapTotal, "${formatBytes(su)} / ${formatBytes(o.swapTotal)}", n++)
            }
            UsageBar("Festplatten-I/O", (o.disk.busy * 10).toLong(), 1000,
                "${o.disk.busy.roundToInt()} % · ↓ ${formatBytes(o.disk.read.toLong())}/s ↑ ${formatBytes(o.disk.write.toLong())}/s", n++)
        }
        if (o.filesystems.isNotEmpty()) SectionCard("Festplatten") {
            o.filesystems.forEachIndexed { i, m ->
                UsageBar(m.mount, m.used, m.total, "${formatBytes(m.used)} / ${formatBytes(m.total)}", i)
            }
        }
        hist?.let { h ->
            val pts = h.points
            val cap = 3600 / h.intervalSeconds.coerceAtLeast(1)
            val strong = MaterialTheme.colorScheme.onSurface
            val weak = MaterialTheme.colorScheme.outline
            SectionCard("Verlauf (1 Stunde)") {
                ChartBlock("CPU", "${o.cpuPercent.roundToInt()} %", listOf(ChartSeries(pts.map { it.cpu }, strong, fill = true)), cap, 100.0, 0)
                ChartBlock("Arbeitsspeicher", "${pts.lastOrNull()?.mem?.roundToInt() ?: 0} %", listOf(ChartSeries(pts.map { it.mem }, strong, fill = true)), cap, 100.0, 1)
                ChartBlock("Festplatten  (lesen ━ / schreiben ━ grau)", "↓ ${formatBytes(o.disk.read.toLong())}/s  ↑ ${formatBytes(o.disk.write.toLong())}/s",
                    listOf(ChartSeries(pts.map { it.diskRead }, strong), ChartSeries(pts.map { it.diskWrite }, weak)), cap, index = 2)
                ChartBlock("Netzwerk  (empfangen ━ / senden ━ grau)", "↓ ${formatBytes(o.net.rx.toLong())}/s  ↑ ${formatBytes(o.net.tx.toLong())}/s",
                    listOf(ChartSeries(pts.map { it.netRx }, strong), ChartSeries(pts.map { it.netTx }, weak)), cap, index = 3)
            }
        }
        Spacer(Modifier.heightIn(min = 24.dp))
    }
    val o = ov
    when (popup) {
        "cores" -> if (o != null) AppDialog(
            onDismissRequest = { popup = null },
            title = { Text("CPU-Kerne") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${o.cpuModel} · gesamt ${o.cpuPercent.roundToInt()} %", fontSize = TextSmall)
                    if (o.cores.isEmpty()) Text("Noch keine Messwerte – gleich noch einmal öffnen.")
                    o.cores.forEachIndexed { i, v ->
                        UsageBar("Kern ${i + 1}", (v * 10).toLong(), 1000, "${v.roundToInt()} %", i)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { popup = null }) { Text("Schließen") } },
        )
        "board" -> if (o != null) AppDialog(
            onDismissRequest = { popup = null },
            title = { Text("Temperaturen Mainboard") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    o.temperatures.filter { it.chip != "drivetemp" && it.chip != "nvme" }.sortedBy { it.chip + it.label }.forEachIndexed { i, t ->
                        KeyValue("${t.chip} · ${t.label}", "${(t.celsius * 10).roundToInt() / 10.0} °C", i, tempColor(t.celsius))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { popup = null }) { Text("Schließen") } },
        )
        "hdd" -> if (o != null) AppDialog(
            onDismissRequest = { popup = null },
            title = { Text("Temperaturen HDDs") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Schlafende Platten werden nicht geweckt; für sie gilt der letzte gemessene Wert.", fontSize = TextSmall)
                    Spacer(Modifier.heightIn(min = 6.dp))
                    o.hddTemps.forEachIndexed { i, t ->
                        KeyValue("${t.device.removePrefix("/dev/")} · ${t.model}",
                            (if (t.celsius > 0) "${c(t.celsius)} °C" else "–") + if (t.standby) "  (Standby)" else "",
                            i, if (t.celsius > 0) tempColor(t.celsius) else Color.Unspecified)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { popup = null }) { Text("Schließen") } },
        )
    }
    power?.let { act ->
        val label = if (act == "reboot") "Neustart" else "Herunterfahren"
        AppDialog(
            onDismissRequest = { power = null },
            title = { Text(if (act == "reboot") "Server neu starten?" else "Server herunterfahren?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (act == "reboot") "${o?.hostname ?: "Der Server"} startet neu. Die Verbindung bricht kurz ab."
                        else "${o?.hostname ?: "Der Server"} wird ausgeschaltet. Einschalten aus der Ferne geht danach nur per Wake-on-LAN.",
                    )
                    listOf(0 to "Sofort", 1 to "In 1 Minute", 5 to "In 5 Minuten").forEach { (min, text) ->
                        Button(onClick = {
                            power = null
                            host.post(label, "/api/system/power/$act?delay=$min")
                        }, modifier = Modifier.fillMaxWidth()) { Text(text) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { power = null }) { Text("Abbrechen") } },
        )
    }
    ActionResultDialog(host, api)
}

@Composable
private fun PowerTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.heightIn(min = 76.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, Modifier.size(28.dp))
            Spacer(Modifier.heightIn(min = 6.dp))
            Text(label, fontWeight = Bold)
        }
    }
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
                SearchField(filter, { filter = it }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(4.dp))
                RefreshButton(reload)
            }
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Alle", "Aktiv", "Fehler").forEachIndexed { i, t ->
                    FilterPill(mode == i, { mode = i }, t)
                }
            }
            val shown = units.filter {
                (filter.isBlank() || it.unit.contains(filter, true) || it.description.contains(filter, true)) &&
                    when (mode) { 1 -> it.active == "active"; 2 -> it.active == "failed"; else -> true }
            }
            LazyColumn {
                itemsIndexed(shown, key = { _, u -> u.unit }) { i, u ->
                    StripedItem(
                        i,
                        leading = { StatusDot(when (u.active) { "active" -> StatusOk; "failed" -> StatusBad; else -> MaterialTheme.colorScheme.outline }) },
                        headline = { Text(u.unit.removeSuffix(".service"), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supporting = { Text("${u.description}\n${u.active}/${u.sub}" + (u.enabled?.let { " · $it" } ?: ""), maxLines = 2) },
                        onClick = { selected = u.unit },
                    )
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
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text(unit) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val p = detail["properties"]
                KeyValues(
                    "Zustand" to "${p["ActiveState"].str} / ${p["SubState"].str}",
                    "Autostart" to p["UnitFileState"].str,
                    "PID" to p["MainPID"].str,
                    p["MemoryCurrent"].long?.takeIf { it < Long.MAX_VALUE / 2 }?.let { "Speicher" to formatBytes(it) },
                    "Neustarts" to p["NRestarts"].str,
                )
                ButtonRow {
                    SmallButton("Start", { act("start", "Start") })
                    SmallButton("Neustart", { act("restart", "Neustart") })
                    SmallButton("Stopp", { confirm = Confirm("$unit stoppen?", "Der Dienst wird angehalten.") { act("stop", "Stopp") } })
                    SmallButton("Konfiguration neu laden", { act("reload", "Reload") })
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
        ListHeader("${list.arr.size} Container") { RefreshButton(reload) }
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
                    KeyValues("Image" to c["Image"].str, if (running) "Last" to "CPU ${c["CPUPerc"].str} · RAM ${c["MemUsage"].str}" else null)
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
    logs?.let { (n, l) -> AppDialog({ logs = null }, { TextButton({ logs = null }) { Text("OK") } }, title = { Text(n) }, text = { MonoText(l) }) }
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
    var tick by remember { mutableStateOf(0) }
    fun build() = buildString {
        append("lines=500")
        val u = unit.trim()
        // "kopia" finds kopia-data1.service, kopia-sys.service … (glob)
        if (u.isNotBlank()) append("&unit=" + enc(if (u.contains('.') || u.contains('*')) u else "$u*"))
        if (prio.isNotBlank()) append("&priority=$prio")
        if (grep.isNotBlank()) append("&grep=" + enc(grep.trim()))
        if (boot) append("&boot=1")
    }
    var query by remember { mutableStateOf("lines=500") }
    // filters apply by themselves (text after a short pause)
    LaunchedEffect(unit, grep, prio, boot) {
        delay(450)
        query = build()
    }
    val prios = listOf("" to "Alle", "3" to "Fehler", "4" to "Warnungen")
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SearchField(unit, { unit = it }, "Dienst", Modifier.weight(1f))
            SearchField(grep, { grep = it }, "Suchtext", Modifier.weight(1f))
        }
        Row(Modifier.padding(start = 12.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            prios.forEach { (v, l) -> FilterPill(prio == v, { prio = v }, l) }
            FilterPill(boot, { boot = !boot }, "Seit Boot")
            Spacer(Modifier.weight(1f))
            RefreshButton { tick++ }
        }
        Loader(query to tick, { api.get<List<LogEntry>>("/api/system/logs?$query") }) { entries, _ ->
            if (entries.isEmpty()) Text("Keine Einträge.", Modifier.padding(16.dp))
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp), reverseLayout = true) {
                itemsIndexed(entries.reversed()) { i, e ->
                    val color = when (e.priority.toIntOrNull() ?: 6) { in 0..3 -> StatusBad; 4 -> StatusWarn; else -> MaterialTheme.colorScheme.onSurface }
                    Text(
                        "${formatTime(e.time)}  ${e.ident.ifBlank { e.unit }}: ${e.message}",
                        color = color, fontFamily = FontFamily.Monospace, fontSize = TextSmall, lineHeight = LineSmall,
                        modifier = Modifier.fillMaxWidth().background(stripe(i)).padding(horizontal = 4.dp, vertical = 2.dp),
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
            SectionCard("Dateisysteme", actions = { RefreshButton(reload) }) {
                AppJson.decodeFromJsonElement<List<MountInfo>>(data["mounts"] ?: kotlinx.serialization.json.JsonArray(emptyList())).forEachIndexed { i, m ->
                    UsageBar("${m.mount}  (${m.fstype}) · ${m.device}", m.used, m.total, "${formatBytes(m.used)} / ${formatBytes(m.total)} · frei ${formatBytes(m.free)}", i)
                }
            }
            SectionCard("Laufwerke") {
                data["blockdevices"]["blockdevices"].arr.filter { it["type"].str == "disk" && !it["name"].str.startsWith("zram") }.forEachIndexed { i, d ->
                    val ds = data["diskStates"][d["path"].str]
                    Column(Modifier.fillMaxWidth().background(stripe(i)).clickable { smartDev = d["path"].str }.padding(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${d["path"].str}  ·  ${formatBytes(d["size"].long)}", Modifier.weight(1f), fontWeight = Bold)
                            if (ds != null) {
                                val standby = ds["standby"].bool == true
                                Text(if (standby) "Standby" else "aktiv", fontSize = TextSmall,
                                    color = if (standby) MaterialTheme.colorScheme.onSurfaceVariant else StatusOk)
                                Spacer(Modifier.width(10.dp))
                                val passed = ds["smartPassed"].bool
                                Text(when (passed) { true -> "SMART OK"; false -> "SMART FEHLER"; null -> "SMART ?" }, fontSize = TextSmall, fontWeight = Bold,
                                    color = when (passed) { true -> StatusOk; false -> StatusBad; null -> StatusWarn })
                            }
                        }
                        Text(listOf(d["model"].str, d["tran"].str.uppercase(), if (d["rota"].bool == true) "HDD" else "SSD",
                            ds["celsius"].str.toDoubleOrNull()?.takeIf { it > 0 }?.let { "${it.roundToInt()} °C" } ?: "")
                            .filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        d["children"].arr.forEach { c ->
                            Text("   └ ${c["name"].str}  ${formatBytes(c["size"].long)}  ${c["fstype"].str}  ${c["mountpoints"].arr.joinToString { it.str }}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Text("Tippe auf ein Laufwerk für SMART-Werte. Platten im Standby werden nicht geweckt; für sie gilt der letzte bekannte SMART-Status.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    smartDev?.let { SmartDialog(api, it) { smartDev = null } }
}

@Composable
private fun SmartDialog(api: ApiClient, dev: String, onDismiss: () -> Unit) {
    val host = rememberActionHost { api }
    var raw by remember { mutableStateOf(false) }
    var wake by remember { mutableStateOf(false) }
    AppDialog(
        onDismissRequest = onDismiss,
        title = { Text("SMART $dev") },
        text = {
            Loader(dev to wake, { api.getJson("/api/system/smart?device=${enc(dev)}" + if (wake) "&wake=1" else "", 60_000) }) { s, _ ->
                if (s["standby"].bool == true) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Die Platte ist im Standby. Eine SMART-Abfrage würde sie aufwecken.")
                        SmallButton("Trotzdem abfragen", { wake = true })
                    }
                    return@Loader
                }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val passed = s["smart_status"]["passed"].bool
                    Text(
                        when (passed) { true -> "Gesundheit: OK"; false -> "Gesundheit: FEHLER"; null -> "Gesundheit: unbekannt" },
                        color = when (passed) { true -> StatusOk; false -> StatusBad; null -> StatusWarn }, fontWeight = Bold,
                    )
                    val nvme = s["nvme_smart_health_information_log"]
                    val rows = listOfNotNull(
                        "Modell" to s["model_name"].str,
                        "Seriennummer" to s["serial_number"].str,
                        "Temperatur" to s["temperature"]["current"].str + " °C",
                        "Betriebsstunden" to s["power_on_time"]["hours"].str,
                        "Einschaltzyklen" to s["power_cycle_count"].str,
                        nvme?.let { "Abnutzung" to it["percentage_used"].str + " %" },
                        nvme?.let { "Geschrieben" to formatBytes((it["data_units_written"].long ?: 0) * 512_000) },
                        nvme?.let { "Medienfehler" to it["media_errors"].str },
                    ) + s["ata_smart_attributes"]["table"].arr
                        .filter { it["id"].long in setOf(5L, 9L, 187L, 193L, 194L, 197L, 198L, 199L, 241L) }
                        .map { a -> "${a["id"].str} ${a["name"].str}" to a["raw"]["string"].str }
                    rows.forEachIndexed { i, (k, v) -> KeyValue(k, v, i) }
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
                val rows = listOf("Status" to i["operstate"].str, "MAC" to i["address"].str) +
                    i["addr_info"].arr.map { a -> (if (a["family"].str == "inet6") "IPv6" else "IPv4") to "${a["local"].str}/${a["prefixlen"].str}" } +
                    listOfNotNull(
                        rates[name]?.let { (rx, tx) -> "Durchsatz" to "↓ ${formatBytes(rx)}/s   ↑ ${formatBytes(tx)}/s" },
                        d["counters"][name]?.let { c -> "Gesamt" to "↓ ${formatBytes(c["rxBytes"].long)}   ↑ ${formatBytes(c["txBytes"].long)}" },
                    )
                rows.forEachIndexed { n, (k, v) -> KeyValue(k, v, n) }
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
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterPill(sort == "cpu", { sort = "cpu" }, "nach CPU")
            FilterPill(sort == "mem", { sort = "mem" }, "nach RAM")
            Spacer(Modifier.weight(1f))
            RefreshButton { tick++ }
        }
        Loader(sort to tick, { api.get<List<ProcessInfo>>("/api/system/processes?sort=$sort") }) { list, _ ->
            LazyColumn {
                itemsIndexed(list, key = { _, p -> p.pid }) { i, p ->
                    StripedItem(
                        i,
                        headline = { Text("${p.comm}  (${p.pid})", maxLines = 1) },
                        supporting = { Text(p.args, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = TextSmall) },
                        trailing = {
                            Column(horizontalAlignment = Alignment.End) {
                                Text("${p.cpu} % CPU"); Text(formatBytes(p.rssKb * 1024), style = MaterialTheme.typography.bodySmall)
                                Text(p.user, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = { kill = p },
                    )
                }
            }
        }
    }
    kill?.let { p ->
        AppDialog(
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

// ======================= Accounts =======================

@Composable
fun AccountsScreen(api: ApiClient) {
    Loader(Unit, { api.getJson("/api/system/accounts") }) { d, reload ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            SectionCard("Benutzerkonten", actions = { RefreshButton(reload) }) {
                d["accounts"].arr.forEachIndexed { i, a ->
                    Column(Modifier.fillMaxWidth().background(stripe(i)).padding(4.dp)) {
                        Text("${a["name"].str}  (UID ${a["uid"].str})", fontWeight = Bold)
                        Text("${a["gecos"].str} · ${a["home"].str} · ${a["shell"].str}", style = MaterialTheme.typography.bodySmall)
                    }
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
            ListHeader("Länger laufende Aktionen, die aus der App auf dem Server gestartet wurden (z. B. apt, Kopia, Nextcloud), mit Ausgabe. " +
                "Sie bleiben 24 Stunden sichtbar; ein Neustart des Agenten leert die Liste.") { RefreshButton(reload) }
            if (jobs.isEmpty()) Text("Keine Aufgaben.", Modifier.padding(16.dp))
            LazyColumn {
                itemsIndexed(jobs, key = { _, j -> j.id }) { i, j ->
                    StripedItem(
                        i,
                        leading = { StatusDot(when { j.finished == null -> StatusWarn; j.exitCode == 0 -> StatusOk; else -> StatusBad }) },
                        headline = { Text(j.title) },
                        supporting = { Text(j.started.take(19).replace('T', ' ') + if (j.finished == null) " · läuft" else " · Exit ${j.exitCode}") },
                        onClick = { open = j },
                    )
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
